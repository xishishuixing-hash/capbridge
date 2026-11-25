package io.github.xishishuixing.capbridge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Status board and the two authorisations this app needs. Both are granted from
 * the system settings pages: no ADB, no root, one tap each.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 72, 56, 72)
        }

        box.addView(title("CapBridge"))
        box.addView(body("没有 ADB、没有 root 时的能力层。所有接口只绑 127.0.0.1。"))

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 40, 0, 32)
        }
        box.addView(status)

        box.addView(button("① 开启无障碍") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        box.addView(button("② 开启通知访问") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        })
        box.addView(button("③ 启动本机桥") {
            startService(Intent(this, BridgeService::class.java))
            refresh()
        })
        box.addView(button("④ 停止本机桥") {
            stopService(Intent(this, BridgeService::class.java))
            refresh()
        })
        box.addView(button("刷新状态") { refresh() })

        val scroll = ScrollView(this).apply { addView(box) }
        setContentView(scroll)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 24f
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, 16)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.parseColor("#9AA0A6"))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun refresh() {
        val a11y = accessibilityEnabled()
        val notif = notifEnabled()
        status.text = buildString {
            appendLine("无障碍服务   ${if (a11y) "已开启 ✅" else "未开启 ❌"}")
            appendLine("通知访问     ${if (notif) "已开启 ✅" else "未开启 ❌"}")
            appendLine("本机桥       ${if (BridgeService.listening) "127.0.0.1:${BridgeService.PORT} 在线 ✅" else "未启动 ❌"}")
            appendLine()
            appendLine("通道就绪后，agent 可以直接:")
            appendLine("  GET  /screen        读当前界面")
            appendLine("  POST /tap {\"x\":1,\"y\":2}")
            appendLine("  POST /swipe {...}")
            appendLine("  POST /text {\"text\":\"...\"}")
            appendLine("  POST /key  {\"action\":\"back\"}")
            appendLine("  GET  /notifications")
            appendLine("  GET  /probe         能力矩阵")
        }
    }

    private fun accessibilityEnabled(): Boolean {
        val expected = "$packageName/${CapService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun notifEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return enabled.contains(packageName)
    }
}
