package io.github.fggtf24cyber.yaytsacloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Уведомления о лайках, ответах, подписках и сообщениях.
 * - Приложение живо (на экране или недавно свёрнуто): сайт сам присылает notify, оно приходит сразу.
 * - Приложение выгружено: фоновая проверка базы. ponytail: Android разрешает её раз в ~15 минут,
 *   поэтому уведомление может опоздать до 15 минут; мгновенно — только через сервер рассылки (Firebase Cloud Messaging).
 */
object Notifier {
    private const val CHANNEL = "yaytsa"
    private const val WORK = "yaytsa-check"
    const val API_KEY = "AIzaSyB5N_ynBpx9cD8cyy4da_HFMHVc-qZ_YyU" // открытый ключ сайта (app.js)
    const val PROJECT = "yaytsa-cloud"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("yaytsa", Context.MODE_PRIVATE)

    /** с этого момента ищем новое */
    fun touch(ctx: Context) = prefs(ctx).edit().putLong("since", System.currentTimeMillis()).apply()

    /** вошёл — включаем фоновую проверку, вышел — выключаем и забываем вход */
    fun setAuth(ctx: Context, uid: String, refresh: String) {
        val wm = WorkManager.getInstance(ctx)
        if (uid.isEmpty() || refresh.isEmpty()) {
            prefs(ctx).edit().remove("uid").remove("refresh").apply()
            wm.cancelUniqueWork(WORK)
            return
        }
        prefs(ctx).edit().putString("uid", uid).putString("refresh", refresh).apply()
        if (!prefs(ctx).contains("since")) touch(ctx)
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    fun show(ctx: Context, key: String, title: String, body: String, href: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Сообщения и ответы", NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_HREF, href)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFFF4E00.toInt())
            .setContentTitle(title.ifBlank { "Яйца Cloud" })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(key.hashCode(), n) } catch (_: SecurityException) {} // уведомления запрещены
    }

    // То же, что noteTitle и noteHref в app.js сайта.
    fun noteTitle(type: String, name: String, appId: String) = when (type) {
        "like" -> "Лайк от $name"
        "follow" -> "$name подписался(-ась) на вас"
        "everyone" -> "$name отметил(а) всех в посте"
        "mention" -> "Упоминание от $name"
        "mod" -> if (appId.isNotEmpty()) "Админ одобрил вашу программу" else "Админ отклонил вашу программу"
        else -> "Ответ от $name"
    }

    fun noteHref(type: String, from: String, appId: String, commentId: String) = when (type) {
        "follow" -> "#uid/$from"
        "treply" -> "#post/$appId/$commentId"
        "everyone" -> "#post/$appId"
        "mention" -> if (commentId.isNotEmpty()) "#post/$appId/$commentId" else "#post/$appId"
        "mod" -> if (appId.isNotEmpty()) "#app/$appId" else "#publish"
        else -> "#app/$appId/$commentId"
    }
}

/** Фоновая проверка: входим по сохранённому refreshToken и читаем базу через REST, как это делает сайт. */
class CheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        if (MainActivity.visible) return Result.success()
        val ctx = applicationContext
        val p = Notifier.prefs(ctx)
        val uid = p.getString("uid", null) ?: return Result.success()
        val refresh = p.getString("refresh", null) ?: return Result.success()
        val since = p.getLong("since", System.currentTimeMillis())
        val started = System.currentTimeMillis()

        val token = try { idToken(refresh) } catch (e: AuthGone) {
            Notifier.setAuth(ctx, "", ""); return Result.success() // вышли из аккаунта или сменили пароль
        } catch (e: Exception) { return Result.retry() }

        try {
            var shown = 0
            for (d in query(token, "notifications", "to", "EQUAL", uid)) {
                val f = d.optJSONObject("fields") ?: continue
                if (bool(f, "read") || time(f, "createdAt") <= since) continue
                val type = str(f, "type"); val appId = str(f, "appId")
                if (shown++ < 5) Notifier.show(ctx, d.getString("name"), Notifier.noteTitle(type, str(f, "fromName"), appId), str(f, "text"),
                    Notifier.noteHref(type, str(f, "from"), appId, str(f, "commentId")))
            }
            for (d in query(token, "chats", "members", "ARRAY_CONTAINS", uid)) {
                val f = d.optJSONObject("fields") ?: continue
                val at = time(f, "lastAt")
                val seen = f.optJSONObject("seen")?.optJSONObject("mapValue")?.optJSONObject("fields")?.let { time(it, uid) } ?: 0
                val muted = f.optJSONObject("muted")?.optJSONObject("mapValue")?.optJSONObject("fields")?.let { bool(it, uid) } ?: false
                if (str(f, "lastFrom") == uid || at <= since || at <= seen || muted) continue
                val id = d.getString("name").substringAfterLast('/')
                val group = bool(f, "group")
                Notifier.show(ctx, "chat/$id", if (group) str(f, "name") else "Новое сообщение", str(f, "lastText"),
                    if (group) "#group/$id" else "#inbox")
            }
        } catch (e: Exception) { return Result.retry() }
        p.edit().putLong("since", started).apply()
        return Result.success()
    }

    private class AuthGone : Exception()

    /** refreshToken → свежий idToken (живёт час) */
    private fun idToken(refresh: String): String {
        val c = open("https://securetoken.googleapis.com/v1/token?key=${Notifier.API_KEY}")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write("grant_type=refresh_token&refresh_token=${URLEncoder.encode(refresh, "UTF-8")}".toByteArray()) }
        if (c.responseCode == 400) throw AuthGone()
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        return j.getString("id_token")
    }

    /** документы коллекции по одному условию (то же, что сайт читает своим onSnapshot) */
    private fun query(token: String, collection: String, field: String, op: String, value: String): List<JSONObject> {
        val c = open("https://firestore.googleapis.com/v1/projects/${Notifier.PROJECT}/databases/(default)/documents:runQuery")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        val q = JSONObject().put("structuredQuery", JSONObject()
            .put("from", JSONArray().put(JSONObject().put("collectionId", collection)))
            .put("where", JSONObject().put("fieldFilter", JSONObject()
                .put("field", JSONObject().put("fieldPath", field)).put("op", op)
                .put("value", JSONObject().put("stringValue", value)))))
        c.outputStream.use { it.write(q.toString().toByteArray()) }
        if (c.responseCode != 200) throw java.io.IOException("firestore ${c.responseCode}")
        val rows = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until rows.length()).mapNotNull { rows.getJSONObject(it).optJSONObject("document") }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 15_000
        // на случай, если ключ сайта ограничен адресом сайта
        setRequestProperty("Referer", MainActivity.LIVE)
    }

    private fun str(f: JSONObject, k: String) = f.optJSONObject(k)?.optString("stringValue") ?: ""
    private fun bool(f: JSONObject, k: String) = f.optJSONObject(k)?.optBoolean("booleanValue") ?: false
    private fun time(f: JSONObject, k: String) =
        f.optJSONObject(k)?.optString("timestampValue")?.takeIf { it.isNotEmpty() }?.let { Instant.parse(it).toEpochMilli() } ?: 0L
}
