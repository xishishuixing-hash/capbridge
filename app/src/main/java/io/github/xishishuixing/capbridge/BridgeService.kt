package io.github.xishishuixing.capbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Loopback HTTP face over the accessibility and notification channels.
 * Bound to 127.0.0.1 only: nothing on the LAN can reach it.
 */
class BridgeService : Service() {

    companion object {
        const val PORT = 8789
        @Volatile
        var listening: Boolean = false
            private set
    }

    private var server: ServerSocket? = null
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        startServer()
    }

    private fun buildNotification(): Notification {
        val channelId = "capbridge"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "CapBridge", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("CapBridge 在线")
            .setContentText("127.0.0.1:$PORT")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()
    }

    private fun startServer() {
        if (listening) return
        try {
            val socket = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
            server = socket
            listening = true
            worker = Thread {
                while (listening) {
                    val client = try {
                        socket.accept()
                    } catch (e: Exception) {
                        break
                    }
                    Thread { serve(client) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }
            worker?.start()
        } catch (e: Exception) {
            listening = false
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use { c ->
                val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                val query = parts[1].substringAfter('?', "")

                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("content-length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else ""

                val (code, payload) = route(method, path, query, body)
                respond(c.getOutputStream(), code, payload)
            }
        } catch (e: Exception) {
            // a dropped connection is not an error worth reporting
        }
    }

    private fun route(method: String, path: String, query: String, body: String): Pair<Int, String> {
        val svc = CapService.instance
        return when (path) {
            "/probe" -> 200 to probe(svc).toString()

            "/screen" -> {
                if (svc == null) return 503 to err("accessibility service is not connected")
                val depth = query.substringAfter("depth=", "6").substringBefore('&').toIntOrNull() ?: 6
                200 to svc.screen(depth).toString()
            }

            "/notifications" -> 200 to JSONObject()
                .put("ok", true)
                .put("connected", NotifService.connected)
                .put("items", NotifService.snapshot())
                .toString()

            "/tap" -> {
                if (svc == null) return 503 to err("accessibility service is not connected")
                val j = JSONObject(body)
                val ok = svc.tap(j.optDouble("x", 0.0).toFloat(), j.optDouble("y", 0.0).toFloat())
                200 to JSONObject().put("ok", ok).toString()
            }

            "/swipe" -> {
                if (svc == null) return 503 to err("accessibility service is not connected")
                val j = JSONObject(body)
                val ok = svc.swipe(
                    j.optDouble("x1", 0.0).toFloat(), j.optDouble("y1", 0.0).toFloat(),
                    j.optDouble("x2", 0.0).toFloat(), j.optDouble("y2", 0.0).toFloat(),
                    j.optLong("ms", 300L)
                )
                200 to JSONObject().put("ok", ok).toString()
            }

            "/text" -> {
                if (svc == null) return 503 to err("accessibility service is not connected")
                val j = JSONObject(body)
                200 to svc.typeText(j.optString("text", "")).toString()
            }

            "/key" -> {
                if (svc == null) return 503 to err("accessibility service is not connected")
                val j = JSONObject(body)
                val ok = svc.globalAction(j.optString("action", ""))
                200 to JSONObject().put("ok", ok).toString()
            }

            else -> 404 to err("no route $path")
        }
    }

    private fun err(message: String) = JSONObject().put("ok", false).put("error", message).toString()

    private fun probe(svc: CapService?): JSONObject {
        val capabilities = JSONArray()
        capabilities.put(JSONObject().put("id", "screen").put("available", svc != null).put("via", "accessibility"))
        capabilities.put(JSONObject().put("id", "tap").put("available", svc != null).put("via", "accessibility-gesture"))
        capabilities.put(JSONObject().put("id", "type").put("available", svc != null).put("via", "accessibility-set-text"))
        capabilities.put(JSONObject().put("id", "notifications").put("available", NotifService.connected).put("via", "notification-listener"))
        return JSONObject()
            .put("ok", true)
            .put("port", PORT)
            .put("capabilities", capabilities)
            .put("blind", JSONArray(listOf("other app private data", "process memory", "system partition")))
    }

    private fun respond(out: OutputStream, code: Int, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code ${if (code == 200) "OK" else "ERROR"}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    override fun onDestroy() {
        listening = false
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
        super.onDestroy()
    }
}
