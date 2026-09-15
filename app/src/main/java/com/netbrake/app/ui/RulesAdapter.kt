package com.netbrake.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.netbrake.app.R
import com.netbrake.app.core.Stats
import com.netbrake.app.data.AppEntry
import com.netbrake.app.data.NetMode
import com.netbrake.app.data.Rule
import com.netbrake.app.data.RuleRepo
import com.netbrake.app.databinding.ItemRuleBinding
import com.netbrake.app.vpn.VpnBrakeService

class RulesAdapter(
    private val onClick: (Rule) -> Unit
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
        val ctx = holder.b.root.context
        holder.b.appName.text = entry?.label ?: rule.pkg
        entry?.icon?.let { holder.b.appIcon.setImageDrawable(it) }
        holder.b.ruleSummary.text = summarize(rule)
        bindRate(holder, rule)

        holder.b.ruleSwitch.setOnCheckedChangeListener(null)
        holder.b.ruleSwitch.isChecked = rule.enabled
        holder.b.ruleSwitch.setOnCheckedChangeListener { _, checked ->
            RuleRepo.setEnabled(rule.pkg, checked)
            VpnBrakeService.applyRules(ctx)
        }
        holder.b.root.setOnClickListener { onClick(rule) }
    }

    /** 实时速率（运行中且有流量时显示）；断网规则固定显示徽标。 */
    private fun bindRate(holder: VH, rule: Rule) {
        val ctx = holder.b.root.context
        val rateText: String? = if (rule.netMode == NetMode.BLOCK) {
            ctx.getString(R.string.block_badge)
        } else {
            val st = stats?.stat(uidOf(ctx, rule.pkg))
            if (st != null && (st.rxRate > 0 || st.txRate > 0)) {
                ctx.getString(R.string.stats_fmt, fmt(st.rxRate), fmt(st.txRate))
            } else null
        }
        if (rateText != null) {
            holder.b.ruleRate.visibility = View.VISIBLE
            holder.b.ruleRate.text = rateText
        } else {
            holder.b.ruleRate.visibility = View.GONE
        }
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

        fun summarize(rule: Rule): String {
            val parts = ArrayList<String>()
            parts.add(if (rule.downLimitKBps <= 0) "↓不限" else "↓${fmtKBps(rule.downLimitKBps)}")
            parts.add(if (rule.upLimitKBps <= 0) "↑不限" else "↑${fmtKBps(rule.upLimitKBps)}")
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

        fun fmtKBps(kbps: Int): String =
            if (kbps >= 1024) String.format("%.1fMB/s", kbps / 1024.0) else "${kbps}KB/s"

        fun fmt(bytesPerSec: Long): String = when {
            bytesPerSec >= 1024 * 1024 -> String.format("%.1fMB/s", bytesPerSec / 1048576.0)
            bytesPerSec >= 1024 -> String.format("%.0fKB/s", bytesPerSec / 1024.0)
            else -> "${bytesPerSec}B/s"
        }
    }
}
