package io.github.fggtf24cyber.yaytsacloud

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Обновление без Google Play, как в «Свиноферме»: на сайте лежит android.json с номером последней версии
 * и ссылкой на APK в GitHub Releases. Новее установленной — предлагаем скачать и открываем системную установку.
 */
object Updater {
    private const val INFO = MainActivity.LIVE + "android.json"

    fun check(act: Activity) = thread(isDaemon = true) {
        val j = try {
            val c = URL("$INFO?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            JSONObject(c.inputStream.bufferedReader().use { it.readText() }).also { c.disconnect() }
        } catch (_: Exception) { return@thread } // без сети молча работаем дальше
        val code = j.optLong("versionCode"); val url = j.optString("url")
        val mine = PackageInfoCompat.getLongVersionCode(act.packageManager.getPackageInfo(act.packageName, 0))
        if (code <= mine || !url.startsWith("https://")) return@thread
        act.runOnUiThread {
            if (act.isFinishing) return@runOnUiThread
            AlertDialog.Builder(act)
                .setTitle("Вышла версия ${j.optString("versionName")}")
                .setMessage(j.optString("notes").ifEmpty { "Обновить приложение?" })
                .setPositiveButton("Обновить") { _, _ -> download(act, url, code) }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    private fun download(act: Activity, url: String, code: Long) {
        Toast.makeText(act, "Скачиваем обновление…", Toast.LENGTH_SHORT).show()
        thread(isDaemon = true) {
            try {
                val dir = File(act.cacheDir, "apk").apply { mkdirs() }
                val f = File(dir, "yaytsa-cloud-$code.apk")
                if (!f.exists()) {
                    dir.listFiles()?.forEach { it.delete() }
                    val part = File(dir, "download.part")
                    // HttpURLConnection сам идёт по перенаправлению GitHub (https → https)
                    URL(url).openStream().use { i -> part.outputStream().use { i.copyTo(it) } }
                    part.renameTo(f)
                }
                val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", f)
                act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                act.runOnUiThread { Toast.makeText(act, "Не получилось скачать обновление", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
