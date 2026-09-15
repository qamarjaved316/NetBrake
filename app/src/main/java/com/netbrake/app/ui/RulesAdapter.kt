package com.netbrake.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.netbrake.app.R
import com.netbrake.app.core.Stats
import com.netbrake.app.data.AppEntry
import com.netbrake.app.data.NetMode
import com.netbrake.app.data.Rule
import com.netbrake.app.databinding.ItemRuleBinding

/**
 * @param onToggle 单个规则开关。写库与 VPN 联动都交给 Activity 处理，
 *                 因为「打开某个应用就要顺带打开总开关」可能需要走系统 VPN 授权。
 */
class RulesAdapter(
    private val onClick: (Rule) -> Unit,
    private val onToggle: (Rule, Boolean) -> Unit
) : RecyclerView.Adapter<RulesAdapter.VH>() {

    private var items: List<Pair<Rule, AppEntry?>> = emptyList()
    private var stats: Stats? = null
    private val uidCache = HashMap<String, Int>()

    fun submit(list: List<Pair<Rule, AppEntry?>>) {
        items = list
        notifyDataSetChanged()
    }

    /**
     * 刷新实时速率数据源。由 MainActivity 的秒级轮询驱动。
     *
     * 只下发 [PAYLOAD_RATE] 局部刷新而非 notifyDataSetChanged：后者每秒重建整行，
     * 会重设图标与开关监听，滚动时还会打断手势。
     */
    fun submitStats(s: Stats?) {
        stats = s
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, PAYLOAD_RATE)
    }

    /** 供左滑删除回调定位被滑动的规则。 */
    fun ruleAt(position: Int): Rule? = items.getOrNull(position)?.first

    class VH(val b: ItemRuleBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_RATE }) {
            bindRate(holder, items[position].first)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (rule, entry) = items[position]
        // 复用的 holder 可能残留上一次左滑的位移（删除后立刻重绑），这里归位
        holder.b.itemForeground.translationX = 0f
        holder.b.appName.text = entry?.label ?: rule.pkg
        entry?.icon?.let { holder.b.appIcon.setImageDrawable(it) }
        holder.b.ruleSummary.text = summarize(rule)
        bindRate(holder, rule)

        holder.b.ruleSwitch.setOnCheckedChangeListener(null)
        holder.b.ruleSwitch.isChecked = rule.enabled
        holder.b.ruleSwitch.setOnCheckedChangeListener { _, checked -> onToggle(rule, checked) }
        holder.b.itemForeground.setOnClickListener { onClick(rule) }
    }

    /**
     * 速率行固定显示「实时速率 | 限速」两个值（↓12KB/s|300KB/s  ↑3KB/s|不限），
     * 这样没跑流量时也能一眼看到限速配置。未运行（无 stats）时实时值显示占位符。
     * 断网规则没有速率可言，固定显示徽标。
     */
    private fun bindRate(holder: VH, rule: Rule) {
        val ctx = holder.b.root.context
        if (rule.netMode == NetMode.BLOCK) {
            holder.b.ruleRate.text = ctx.getString(R.string.block_badge)
            return
        }
        val st = stats?.stat(uidOf(ctx, rule.pkg))
        val idle = ctx.getString(R.string.rate_idle)
        holder.b.ruleRate.text = ctx.getString(
            R.string.rate_pair_fmt,
            if (st == null) idle else fmt(st.rxRate), limitText(rule.downLimitKBps),
            if (st == null) idle else fmt(st.txRate), limitText(rule.upLimitKBps)
        )
    }

    private fun uidOf(ctx: Context, pkg: String): Int =
        uidCache.getOrPut(pkg) {
            try {
                ctx.packageManager.getPackageUid(pkg, 0)
            } catch (_: Exception) {
                -1
            }
        }

    companion object {
        /** 局部刷新标记：只重绘速率一行，不动图标与开关。 */
        private const val PAYLOAD_RATE = "rate"

        /** 摘要行只讲网络与丢包；限速值放到速率行与实时速率并排显示。 */
        fun summarize(rule: Rule): String {
            val parts = ArrayList<String>()
            if (rule.downLossPct > 0 || rule.upLossPct > 0) {
                parts.add("丢${rule.downLossPct}%/${rule.upLossPct}%")
            }
            parts.add(
                when (rule.netMode) {
                    NetMode.WIFI -> "仅WiFi"
                    NetMode.CELLULAR -> "仅流量"
                    NetMode.BLOCK -> "断网"
                    else -> "默认网络"
                }
            )
            return parts.joinToString(" · ")
        }

        /** 限速展示值，0 视为不限。 */
        fun limitText(kbps: Int): String = if (kbps <= 0) "不限" else fmtKBps(kbps)

        fun fmtKBps(kbps: Int): String =
            if (kbps >= 1024) String.format("%.1fMB/s", kbps / 1024.0) else "${kbps}KB/s"

        fun fmt(bytesPerSec: Long): String = when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1fMB/s", bytesPerSec / 1048576.0)
            bytesPerSec >= 1024 -> String.format("%.0fKB/s", bytesPerSec / 1024.0)
            else -> "${bytesPerSec}B/s"
        }
    }
}
