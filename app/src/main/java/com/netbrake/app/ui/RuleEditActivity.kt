package com.netbrake.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.netbrake.app.R
import com.netbrake.app.data.AppList
import com.netbrake.app.data.NetMode
import com.netbrake.app.data.Rule
import com.netbrake.app.data.RuleRepo
import com.netbrake.app.databinding.ActivityRuleEditBinding
import com.netbrake.app.vpn.VpnBrakeService

class RuleEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
    }

    private lateinit var b: ActivityRuleEditBinding
    private lateinit var pkg: String
    private var netMode = NetMode.FOLLOW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRuleEditBinding.inflate(layoutInflater)
        setContentView(b.root)

        pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        val entry = AppList.entryOf(this, pkg)
        b.appName.text = entry?.label ?: pkg
        entry?.icon?.let { b.appIcon.setImageDrawable(it) }
        b.toolbar.setNavigationOnClickListener { finish() }

        val rule = RuleRepo.byPkg(pkg)

        // --- 网络 ---
        fun setNet(mode: Int) {
            netMode = mode
            val id = when (mode) {
                NetMode.WIFI -> R.id.netWifi
                NetMode.CELLULAR -> R.id.netCell
                NetMode.BLOCK -> R.id.netBlock
                else -> R.id.netDefault
            }
            b.netGroup.check(id)
        }
        setNet(rule?.netMode ?: NetMode.FOLLOW)
        b.netGroup.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId: Int, isChecked: Boolean ->
            if (!isChecked) return@addOnButtonCheckedListener
            netMode = when (checkedId) {
                R.id.netWifi -> NetMode.WIFI
                R.id.netCell -> NetMode.CELLULAR
                R.id.netBlock -> NetMode.BLOCK
                else -> NetMode.FOLLOW
            }
        }

        // --- 限速 ---
        b.downLimitInput.setText((rule?.downLimitKBps ?: 0).toString())
        b.upLimitInput.setText((rule?.upLimitKBps ?: 0).toString())

        // --- 丢包 ---
        b.downLossSlider.value = (rule?.downLossPct ?: 0).toFloat()
        b.upLossSlider.value = (rule?.upLossPct ?: 0).toFloat()
        b.downLossSlider.setLabelFormatter { "${it.toInt()}%" }
        b.upLossSlider.setLabelFormatter { "${it.toInt()}%" }

        // --- 预设 ---
        fun applyPreset(down: Int, up: Int, downLoss: Int, upLoss: Int) {
            b.downLimitInput.setText(down.toString())
            b.upLimitInput.setText(up.toString())
            b.downLossSlider.value = downLoss.toFloat()
            b.upLossSlider.value = upLoss.toFloat()
        }
        b.presetFree.setOnClickListener { applyPreset(0, 0, 0, 0) }
        b.preset2g.setOnClickListener { applyPreset(30, 15, 1, 1) }
        b.preset3g.setOnClickListener { applyPreset(300, 100, 0, 0) }
        b.presetWeak.setOnClickListener { applyPreset(128, 64, 5, 5) }
        b.presetBad.setOnClickListener { applyPreset(32, 16, 15, 15) }

        // --- 保存 / 删除 ---
        b.saveBtn.setOnClickListener { save() }
        b.deleteBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.delete_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    RuleRepo.remove(pkg)
                    VpnBrakeService.applyRules(this)
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun save() {
        val down = parseLimit(b.downLimitInput.text?.toString())
        val up = parseLimit(b.upLimitInput.text?.toString())
        val downLoss = b.downLossSlider.value.toInt().coerceIn(0, 100)
        val upLoss = b.upLossSlider.value.toInt().coerceIn(0, 100)

        RuleRepo.upsert(
            Rule(
                pkg = pkg,
                enabled = RuleRepo.byPkg(pkg)?.enabled ?: true,
                downLimitKBps = down,
                upLimitKBps = up,
                downLossPct = downLoss,
                upLossPct = upLoss,
                netMode = netMode
            )
        )
        VpnBrakeService.applyRules(this)
        Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun parseLimit(s: String?): Int =
        s?.trim()?.toIntOrNull()?.coerceIn(0, 100 * 1024) ?: 0
}
