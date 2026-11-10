package io.github.fggtf24cyber.yaytsacloud

import android.app.Activity
import android.content.Intent
import android.net.Uri
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Вход через Google в обычном браузере — как в приложении для Windows (GoogleLogin.cs): внутри WebView Google вход не пускает.
 * Открываем страницу сайта #app-login/p<порт>/<state>. После выбора аккаунта она уводит браузер на
 * http://127.0.0.1:<порт>/cb#… — данные во #фрагменте в сеть не уходят, страница /cb отдаёт их нам POST-запросом.
 */
object GoogleLogin {
    private var current: ServerSocket? = null

    fun start(act: Activity, site: String, done: (idToken: String, accessToken: String) -> Unit) {
        try { current?.close() } catch (_: Exception) {} // прошлый незаконченный вход
        val state = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10 * 60_000 }
        current = server
        thread(isDaemon = true) {
            try {
                while (true) {
                    val form = server.accept().use { handle(it, state) } ?: continue
                    act.runOnUiThread { done(form.getValue("id"), form["at"] ?: "") }
                    break
                }
            } catch (_: Exception) { // десять минут прошло или начали новый вход
            } finally { try { server.close() } catch (_: Exception) {} }
        }
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$site#app-login/p${server.localPort}/$state"))) }
        catch (_: Exception) { server.close() }
    }

    private val PAGE = """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>Яйца Cloud</title>
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0B0B0B;color:#F0F0F0;font:16px system-ui,sans-serif;text-align:center;padding:0 24px;box-sizing:border-box}
[hidden]{display:none!important}a{display:inline-block;margin-top:16px;padding:12px 22px;border-radius:12px;background:#3B82F6;color:#fff;text-decoration:none}</style>
<div><p id=t>Передаём вход в приложение…</p><a id=back href="intent://login#Intent;scheme=yaytsacloud;package=io.github.fggtf24cyber.yaytsacloud;end" hidden>Вернуться в приложение</a></div>
<script>
fetch('/done', { method: 'POST', body: location.hash.slice(1) })
  .then(r => { t.textContent = r.ok ? 'Готово, вы вошли.' : 'Ссылка устарела. Нажмите «Продолжить с Google» в приложении ещё раз.'; history.replaceState(null, '', '/cb'); if (r.ok) { back.hidden = false; location.href = back.href; } })
  .catch(() => { t.textContent = 'Приложение закрыто. Откройте Яйца Cloud и войдите ещё раз.'; });
</script>"""

    private fun handle(s: Socket, state: String): Map<String, String>? {
        s.soTimeout = 5000
        val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
        val first = input.readLine() ?: return null
        var length = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", true)) length = line.substring(15).trim().toIntOrNull() ?: 0
        }
        val out = s.getOutputStream()
        if (first.startsWith("GET /cb ")) { reply(out, 200, "text/html; charset=utf-8", PAGE); return null }
        if (!first.startsWith("POST /done ") || length <= 0 || length > 20000) { reply(out, 404, "text/plain", "not found"); return null }
        // тело — URLSearchParams, только ASCII: символов столько же, сколько байт
        val buf = CharArray(length); var read = 0
        while (read < length) { val n = input.read(buf, read, length - read); if (n < 0) break; read += n }
        val form = String(buf, 0, read).split('&').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()
        if (form["state"] != state || form["id"].isNullOrEmpty()) { reply(out, 403, "text/plain", "bad state"); return null }
        reply(out, 200, "text/plain", "ok")
        return form
    }

    private fun reply(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        out.write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes); out.flush()
    }
}
