package com.netbrake.app.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.netbrake.app.data.AppList
import com.netbrake.app.data.AppEntry
import com.netbrake.app.databinding.ActivityAppPickerBinding
import com.netbrake.app.databinding.ItemAppBinding
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AppPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
    }

    private lateinit var b: ActivityAppPickerBinding
    private var allApps: List<AppEntry> = emptyList()
    private var displayList: List<AppEntry> = emptyList()
    private var showSystem = false
    private var query = ""

    /** 图标缓存与后台解码线程：列表先以纯文本秒出，图标随后逐个补齐。 */
    private val iconCache = LruCache<String, Drawable>(192)
    private val iconExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val adapter = object : RecyclerView.Adapter<AppVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            AppVH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = displayList.size

        override fun onBindViewHolder(holder: AppVH, position: Int) {
            val e = displayList[position]
            holder.b.appName.text = e.label
            holder.b.appPkg.text = e.pkg

            // 图标：命中缓存直接显示；否则清空并异步解码，用 tag 防止复用错位
            val iv = holder.b.appIcon
            iv.setImageDrawable(iconCache.get(e.pkg))
            iv.tag = e.pkg
            iconExecutor.execute {
                val d = AppList.iconOf(this@AppPickerActivity, e.pkg)
                if (d != null) iconCache.put(e.pkg, d)
                mainHandler.post {
                    if (iv.tag == e.pkg && d != null) iv.setImageDrawable(d)
                }
            }

            holder.b.root.setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PKG, e.pkg))
                finish()
            }
        }
    }

    class AppVH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.appList.layoutManager = LinearLayoutManager(this)
        b.appList.adapter = adapter

        b.toolbar.setNavigationOnClickListener { finish() }

        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                refilter()
            }
        })

        b.showSystem.setOnCheckedChangeListener { _, checked ->
            showSystem = checked
            refilter()
        }

        // 两阶段加载：先取纯文本列表（通常 <300ms），图标随后按需异步补齐
        b.loadingBox.visibility = View.VISIBLE
        Thread {
            val apps = AppList.queryLite(this, true)
            runOnUiThread {
                allApps = apps
                refilter()
                b.loadingBox.visibility = View.GONE
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        iconExecutor.shutdownNow()
    }

    private fun refilter() {
        var list = allApps
        if (!showSystem) list = list.filter { !it.isSystem }
        if (query.isNotEmpty()) {
            val q = query.lowercase()
            list = list.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        }
        displayList = list
        adapter.notifyDataSetChanged()
    }
}
