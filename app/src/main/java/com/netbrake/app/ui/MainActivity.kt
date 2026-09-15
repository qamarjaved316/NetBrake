package com.netbrake.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.net.VpnService
import com.google.android.material.snackbar.Snackbar
import com.netbrake.app.R
import com.netbrake.app.data.AppList
import com.netbrake.app.data.Rule
import com.netbrake.app.data.RuleRepo
import com.netbrake.app.databinding.ActivityMainBinding
import com.netbrake.app.vpn.EngineHolder
import com.netbrake.app.vpn.VpnBrakeService

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: RulesAdapter

    private val pickApp = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val pkg = result.data?.getStringExtra(AppPickerActivity.EXTRA_PKG) ?: return@registerForActivityResult
            startActivity(
                Intent(this, RuleEditActivity::class.java)
                    .putExtra(RuleEditActivity.EXTRA_PKG, pkg)
            )
        }
    }

    /** 首次启用时的系统 VPN 授权弹窗 */
    private val vpnAuthorize = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val masterOn = pendingMasterOn
        pendingMasterOn = false
        if (result.resultCode == RESULT_OK) {
            if (masterOn) applyMasterOnRules()
            startVpnService()
        } else {
            // 拒绝授权：规则一个都没动过，只把总开关显示回未运行
            refreshStatus()
        }
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            if (VpnBrakeService.running || pendingVpnState != null) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    /** 正在按代码同步开关状态，此时不要把它当成用户操作。 */
    private var syncingSwitch = false

    /**
     * 用户刚拨动总开关、而服务状态还没跟上时的期望值。
     * 服务是异步起停的，没有这个值的话开关会先弹回原位再跳过去。
     */
    private var pendingVpnState: Boolean? = null

    private val uiPrefs by lazy { getSharedPreferences("netbrake_ui", Context.MODE_PRIVATE) }

    /**
     * 总开关关闭前各应用的启用状态，用于再次打开时原样恢复，
     * 否则「临时停一下再打开」会把用户特意排除的应用也打开，且不可逆。
     *
     * 必须落盘：关总开关会把 enabled=false 持久化，若这份备份只存在内存里，
     * 进程被杀后再打开总开关就会退化成「全都管起来」，把用户特意排除的应用
     * （含配了断网/限速的）静默纳入管控。
     */
    private var enabledBeforeMasterOff: List<String>?
        get() = uiPrefs.getStringSet(KEY_PREV_ENABLED, null)?.toList()
        set(value) {
            uiPrefs.edit().apply {
                if (value.isNullOrEmpty()) remove(KEY_PREV_ENABLED) else putStringSet(KEY_PREV_ENABLED, value.toSet())
            }.apply()
        }

    /** 用户点开总开关但还卡在系统授权弹窗：授权确认后才动规则。 */
    private var pendingMasterOn = false

    /** 服务始终没到达期望状态（如启动失败）时的兜底，防止开关一直停在假状态。 */
    private val clearPending = Runnable {
        pendingVpnState = null
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = RulesAdapter(
            onClick = { rule ->
                startActivity(
                    Intent(this, RuleEditActivity::class.java)
                        .putExtra(RuleEditActivity.EXTRA_PKG, rule.pkg)
                )
            },
            onToggle = { rule, checked -> onRuleToggled(rule, checked) }
        )
        b.rulesList.layoutManager = LinearLayoutManager(this)
        b.rulesList.adapter = adapter
        attachSwipeToDelete()

        b.addAppBtn.setOnClickListener {
            pickApp.launch(Intent(this, AppPickerActivity::class.java))
        }

        // 开关状态一律由 refreshStatus() 从服务状态推导；交给系统保存/恢复的话，
        // onRestoreInstanceState 里的 setChecked 会绕过 syncingSwitch 触发用户操作分支
        // （旋屏就可能重跑一遍 onMasterOn、甚至弹出 VPN 授权框）
        b.vpnSwitch.isSaveEnabled = false
        b.vpnSwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            if (checked) onMasterOn() else onMasterOff()
        }

        // Android 13+ 需要运行时通知权限，启动时就请求，避免 VPN 运行后通知不可见
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        RuleRepo.addListener(ruleListener)
    }

    // ------------------------------------------------------------------
    // 总开关与单应用开关的父子联动：
    //   任一子开关打开 → 总开关打开；子开关全部关闭 / 总开关关闭 → 全部关闭。
    // ------------------------------------------------------------------

    private fun onMasterOn() {
        if (RuleRepo.all().isEmpty()) {
            setVpnSwitchSilently(false)
            android.widget.Toast.makeText(
                this, R.string.toast_add_app_first, android.widget.Toast.LENGTH_SHORT
            ).show()
            // 直接引导去添加应用，省一步点击
            pickApp.launch(Intent(this, AppPickerActivity::class.java))
            return
        }
        // 先过系统授权再改规则：用户在授权弹窗点「取消」时，不能留下被改写过的开关状态
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            pendingMasterOn = true
            vpnAuthorize.launch(prepare)
            return
        }
        applyMasterOnRules()
        startVpnService()
    }

    /**
     * 总开关打开时恢复出可管控的应用集合：子开关全关的话 VPN 起来也是空跑。
     * 优先还原上次关总开关前的选择，避免把用户特意排除的应用也一并打开。
     */
    private fun applyMasterOnRules() {
        if (RuleRepo.enabledPkgs().isEmpty()) {
            val prev = enabledBeforeMasterOff?.filter { RuleRepo.byPkg(it) != null }
            if (prev.isNullOrEmpty()) RuleRepo.setAllEnabled(true) else RuleRepo.setEnabledOnly(prev)
        }
        enabledBeforeMasterOff = null
    }

    private fun onMasterOff() {
        RuleRepo.enabledPkgs().let { if (it.isNotEmpty()) enabledBeforeMasterOff = it }
        RuleRepo.setAllEnabled(false)
        stopVpnFlow()
    }

    private fun onRuleToggled(rule: Rule, enabled: Boolean) {
        RuleRepo.setEnabled(rule.pkg, enabled)
        // 用户明确打开某个子开关时才允许顺带拉起 VPN
        syncVpnWithRules(allowStart = enabled)
    }

    /**
     * 规则集合变化后把 VPN 状态对齐：一个启用项都不剩就停。
     *
     * @param allowStart 允许在 VPN 未运行时把它拉起来。只有用户明确打开开关时才为 true——
     *                   删除、撤销这类操作不该顺手启动 VPN，更不该盖掉用户刚关掉的总开关。
     */
    private fun syncVpnWithRules(allowStart: Boolean) {
        val anyEnabled = RuleRepo.enabledPkgs().isNotEmpty()
        // 服务起停是异步的，pending 窗口内 running 还是旧值。按用户刚表达的意图判断，
        // 否则会把 ACTION_APPLY 发给一个正在停止的服务，且与总开关的显示状态不一致
        val running = pendingVpnState ?: VpnBrakeService.running
        when {
            !anyEnabled && running -> stopVpnFlow()
            // 运行中：交给服务自己判断热更新还是重建
            running -> {
                VpnBrakeService.applyRules(this)
                schedulePoll()
            }
            anyEnabled && allowStart -> startVpnFlow()
            else -> refreshStatus()
        }
    }

    /** 服务起停是异步的，稍后再回来对一次 UI。 */
    private fun schedulePoll() {
        handler.removeCallbacks(refresher)
        handler.postDelayed(refresher, 500)
        refreshStatus()
    }

    private fun startVpnFlow() {
        // 首次使用需要系统 VPN 授权，授权回来后再真正启动
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnAuthorize.launch(prepare)
            return
        }
        startVpnService()
    }

    private fun startVpnService() {
        markPending(true)
        VpnBrakeService.start(this)
        schedulePoll()
    }

    private fun stopVpnFlow() {
        markPending(false)
        VpnBrakeService.stop(this)
        schedulePoll()
    }

    private fun markPending(desired: Boolean) {
        pendingVpnState = desired
        handler.removeCallbacks(clearPending)
        handler.postDelayed(clearPending, 3000)
    }

    /** 按状态同步总开关，不触发用户操作分支。 */
    private fun setVpnSwitchSilently(checked: Boolean) {
        if (b.vpnSwitch.isChecked == checked) return
        syncingSwitch = true
        b.vpnSwitch.isChecked = checked
        syncingSwitch = false
    }

    /**
     * 左滑删除：只平移条目上层的 itemForeground，底层删除区保持不动，
     * 视觉上像是把删除按钮「擦」出来。滑过阈值即删除，并给一次撤销机会。
     */
    private fun attachSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            private val ui = ItemTouchHelper.Callback.getDefaultUIUtil()

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            // 默认阈值太灵敏，轻轻一带就删；这里要求滑过半个条目或明确的甩动
            override fun getSwipeThreshold(vh: RecyclerView.ViewHolder): Float = 0.5f

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 3f

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
                if (vh is RulesAdapter.VH) ui.onSelected(vh.b.itemForeground)
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                if (vh is RulesAdapter.VH) ui.clearView(vh.b.itemForeground)
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                if (vh is RulesAdapter.VH) {
                    ui.onDraw(c, rv, vh.b.itemForeground, dX, dY, actionState, isCurrentlyActive)
                }
            }

            override fun onChildDrawOver(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder?,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                if (vh is RulesAdapter.VH) {
                    ui.onDrawOver(c, rv, vh.b.itemForeground, dX, dY, actionState, isCurrentlyActive)
                }
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val rule = adapter.ruleAt(vh.bindingAdapterPosition)
                // 位置已失效（列表刚被别处刷新），整体重绘让条目归位，不做删除
                if (rule == null) refreshRules() else deleteRule(rule)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(b.rulesList)
    }

    private fun deleteRule(rule: Rule) {
        val wasRunning = pendingVpnState ?: VpnBrakeService.running
        RuleRepo.remove(rule.pkg)          // 触发 ruleListener → refreshRules
        syncVpnWithRules(allowStart = false)
        val label = AppList.entryOf(this, rule.pkg)?.label ?: rule.pkg
        Snackbar.make(b.root, getString(R.string.rule_deleted, label), Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                RuleRepo.upsert(rule)
                // 撤销要回到删除前的状态：删掉最后一条启用规则会顺带停掉 VPN，
                // 只恢复规则而不重新拉起，会留下「子开关开着、总开关却是关的」
                syncVpnWithRules(allowStart = wasRunning)
            }
            .show()
    }

    private val ruleListener: () -> Unit = {
        runOnUiThread { refreshRules() }
    }

    override fun onDestroy() {
        super.onDestroy()
        RuleRepo.removeListener(ruleListener)
        handler.removeCallbacks(refresher)
        handler.removeCallbacks(clearPending)
    }

    override fun onResume() {
        super.onResume()
        refreshRules()
        refreshStatus()
        handler.removeCallbacks(refresher)
        // 条件要和 refresher 自身的续跑条件一致：VPN 授权弹窗返回时 onActivityResult
        // 先于 onResume 执行，这里只看 running 会把刚排上的那次轮询清掉，导致速率不刷新
        if (VpnBrakeService.running || pendingVpnState != null) {
            handler.postDelayed(refresher, 1000)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun refreshRules() {
        val rules = RuleRepo.all()
        adapter.submit(rules.map { r -> r to AppList.entryOf(this, r.pkg) })
        b.emptyHint.visibility = if (rules.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun refreshStatus() {
        val running = VpnBrakeService.running
        // 服务已经到达期望状态，撤掉「等待中」的显示
        if (pendingVpnState == running) {
            pendingVpnState = null
            handler.removeCallbacks(clearPending)
        }
        setVpnSwitchSilently(pendingVpnState ?: running)
        if (running) {
            val count = RuleRepo.enabledPkgs().size
            b.statusText.text = getString(R.string.status_running, count)
            val e = EngineHolder.engine
            // 列表里的每应用速率同样由这里驱动；不喂 stats 的话每行速率永远不显示
            adapter.submitStats(e?.stats)
            if (e != null) {
                val rx = e.stats.totalRxRate()
                val tx = e.stats.totalTxRate()
                b.rateText.text = getString(R.string.stats_fmt, RulesAdapter.fmt(rx), RulesAdapter.fmt(tx)) +
                        "  ·  ${e.activeSessionCount()} 会话"
            }
        } else {
            b.statusText.text = getString(R.string.status_stopped)
            b.rateText.text = ""
            adapter.submitStats(null)
        }
    }

    companion object {
        private const val KEY_PREV_ENABLED = "enabled_before_master_off"
    }
}
