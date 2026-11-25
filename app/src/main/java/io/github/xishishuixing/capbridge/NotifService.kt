package io.github.xishishuixing.capbridge

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads notifications without ADB. Keeps a small ring of the latest ones so a
 * caller can see app events that never appear on screen.
 */
class NotifService : NotificationListenerService() {

    companion object {
        @Volatile
        var connected: Boolean = false
            private set
        private const val LIMIT = 50
        private val ring = ArrayDeque<JSONObject>()

        fun snapshot(): JSONArray {
            val out = JSONArray()
            synchronized(ring) { ring.forEach { out.put(it) } }
            return out
        }
    }

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val extras = n.notification.extras
        val entry = JSONObject()
            .put("pkg", n.packageName ?: "")
            .put("title", extras.getCharSequence("android.title")?.toString() ?: "")
            .put("text", extras.getCharSequence("android.text")?.toString() ?: "")
            .put("at", n.postTime)
        synchronized(ring) {
            ring.addLast(entry)
            while (ring.size > LIMIT) ring.removeFirst()
        }
    }
}
