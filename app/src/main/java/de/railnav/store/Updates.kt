package de.railnav.store

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.railnav.BuildConfig
import de.railnav.data.Net
import de.railnav.notify.TripNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class UpdateInfo(val version: String, val notes: String, val apkUrl: String)

/** Checks GitHub releases for a newer APK and installs it via the system package installer. */
object Updates {
    private const val API = "https://api.github.com/repos/QwertyQwertovich/railnav/releases/latest"
    const val LATEST_APK = "https://github.com/QwertyQwertovich/railnav/releases/latest/download/RailNav.apk"

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }
    fun newer(remote: String, local: String = currentVersion): Boolean {
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Latest release if it is newer than the installed app, else null. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            val o = Net.json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            val tag = (o["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return@withContext null
            if (!newer(tag)) return@withContext null
            val apk = o["assets"]?.jsonArray?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".apk") == true }
                ?.let { (it["browser_download_url"] as? JsonPrimitive)?.contentOrNull } ?: LATEST_APK
            UpdateInfo(tag.removePrefix("v"), (o["body"] as? JsonPrimitive)?.contentOrNull.orEmpty(), apk)
        }
    }

    /**
     * Downloads the APK with DownloadManager and opens the system installer when done.
     * [progress] gets 0..1 while downloading. Returns an error text or null on success.
     */
    suspend fun downloadAndInstall(ctx: Context, info: UpdateInfo, progress: (Float?) -> Unit): String? {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val name = "RailNav-${info.version}.apk"
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("RailNav ${info.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE))
        while (true) {
            delay(500)
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) return "Загрузка отменена"
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                progress(if (total > 0) done.toFloat() / total else null)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val uri = dm.getUriForDownloadedFile(id) ?: return "Файл не найден"
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                        return null
                    }
                    DownloadManager.STATUS_FAILED -> return "Не удалось скачать обновление"
                }
            }
        }
    }

    fun scheduleDaily(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork("update-check"); return }
        wm.enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

/** Daily background check: posts a notification when a new version is out. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val s = Settings(applicationContext)
        if (!s.autoUpdateCheck) return Result.success()
        val info = runCatching { Updates.check() }.getOrNull() ?: return Result.success()
        if (s.notifiedVersion == info.version) return Result.success()
        s.notifiedVersion = info.version
        val open = android.app.PendingIntent.getActivity(applicationContext, 7,
            Intent(applicationContext, de.railnav.ui.MainActivity::class.java).putExtra("update", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        TripNotifications.post(applicationContext, 7007, TripNotifications.CH_ALERT, "Доступна RailNav ${info.version}",
            "Нажмите, чтобы обновить приложение.", open)
        return Result.success()
    }
}
