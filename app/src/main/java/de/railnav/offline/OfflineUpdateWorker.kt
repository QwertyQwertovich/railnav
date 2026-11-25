package de.railnav.offline

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.railnav.data.Providers
import de.railnav.store.Settings
import java.util.concurrent.TimeUnit

/** Weekly refresh of the offline timetable (Wi-Fi, battery not low). */
class OfflineUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val feeds = Settings(applicationContext).feeds
        if (feeds.isEmpty()) return Result.success()
        return try {
            Providers.offline(applicationContext).importFeeds(feeds) { _, _ -> }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        } catch (e: OutOfMemoryError) {
            Result.failure()
        }
    }

    companion object {
        private const val NAME = "offline-update"
        fun schedule(ctx: Context, on: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<OfflineUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
