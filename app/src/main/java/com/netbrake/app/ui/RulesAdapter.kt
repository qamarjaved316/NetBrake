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

    fun submitStats(s: Stats?) {
        stats = s
        notifyDataSetChanged()
    }

    class VH(val b: ItemRuleBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (rule, entry) = items[position]
        val ctx = holder.b.root.context
        holder.b.appName.text = entry?.label ?: rule.pkg
        entry?.icon?.let { holder.b.appIcon.setImageDrawable(it) }
        holder.b.ruleSummary.text = summarize(rule)

        // 实时速率（运行中且有流量时显示）
        var rateText: String? = null
        if (rule.netMode == NetMode.BLOCK) {
            rateText = ctx.getString(R.string.block_badge)
        } else {
            val st = stats?.stat(uidOf(ctx, rule.pkg))
            if (st != null && (st.rxRate > 0 || st.txRate > 0)) {
                rateText = ctx.getString(R.string.stats_fmt, fmt(st.rxRate), fmt(st.txRate))
            }
        }
        if (rateText != null) {
            holder.b.ruleRate.visibility = View.VISIBLE
            holder.b.ruleRate.text = rateText
        } else {
            holder.b.ruleRate.visibility = View.GONE
        }

        holder.b.ruleSwitch.setOnCheckedChangeListener(null)
        holder.b.ruleSwitch.isChecked = rule.enabled
        holder.b.ruleSwitch.setOnCheckedChangeListener { _, checked ->
            RuleRepo.setEnabled(rule.pkg, checked)
            VpnBrakeService.applyRules(ctx)
        }
        holder.b.root.setOnClickListener { onClick(rule) }
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
