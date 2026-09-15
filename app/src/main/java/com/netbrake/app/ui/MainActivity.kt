package com.netbrake.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import android.net.VpnService
import com.netbrake.app.R
import com.netbrake.app.data.AppList
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
        if (result.resultCode == RESULT_OK) {
            VpnBrakeService.start(this)
            handler.postDelayed(refresher, 500)
            refreshStatus()
        } else {
            b.vpnSwitch.isChecked = false
        }
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            if (VpnBrakeService.running) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = RulesAdapter { rule ->
            startActivity(
                Intent(this, RuleEditActivity::class.java)
                    .putExtra(RuleEditActivity.EXTRA_PKG, rule.pkg)
            )
        }
        b.rulesList.layoutManager = LinearLayoutManager(this)
        b.rulesList.adapter = adapter

        b.addAppBtn.setOnClickListener {
            pickApp.launch(Intent(this, AppPickerActivity::class.java))
        }

        b.vpnSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (RuleRepo.enabledPkgs().isEmpty()) {
                    b.vpnSwitch.isChecked = false
                    android.widget.Toast.makeText(
                        this, R.string.toast_add_app_first, android.widget.Toast.LENGTH_SHORT
                    ).show()
                    // 直接引导去添加应用，省一步点击
                    pickApp.launch(Intent(this, AppPickerActivity::class.java))
                    return@setOnCheckedChangeListener
                }
                // 首次使用需要系统 VPN 授权
                val prepare = VpnService.prepare(this)
                if (prepare != null) {
                    vpnAuthorize.launch(prepare)
                } else {
                    VpnBrakeService.start(this)
                    handler.postDelayed(refresher, 500)
                }
            } else {
                VpnBrakeService.stop(this)
            }
            refreshStatus()
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

    private val ruleListener: () -> Unit = {
        runOnUiThread { refreshRules() }
    }

    override fun onDestroy() {
        super.onDestroy()
        RuleRepo.removeListener(ruleListener)
        handler.removeCallbacks(refresher)
    }

    override fun onResume() {
        super.onResume()
        refreshRules()
        refreshStatus()
        handler.removeCallbacks(refresher)
        if (VpnBrakeService.running) handler.postDelayed(refresher, 1000)
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
        b.vpnSwitch.isChecked = running
        if (running) {
            val count = RuleRepo.enabledPkgs().size
            b.statusText.text = getString(R.string.status_running, count)
            val e = EngineHolder.engine
            if (e != null) {
                val rx = e.stats.totalRxRate()
                val tx = e.stats.totalTxRate()
                b.rateText.text = getString(R.string.stats_fmt, RulesAdapter.fmt(rx), RulesAdapter.fmt(tx)) +
                        "  ·  ${e.activeSessionCount()} 会话"
            }
        } else {
            b.statusText.text = getString(R.string.status_stopped)
            b.rateText.text = ""
        }
    }
}
