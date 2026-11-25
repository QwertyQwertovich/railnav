package de.railnav.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.railnav.R
import de.railnav.data.Providers
import de.railnav.model.Leg
import de.railnav.model.TripPlan
import de.railnav.store.Settings
import de.railnav.store.TripStore
import de.railnav.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class TripEvent(val at: Long, val key: String, val title: String, val text: String)

object TripNotifications {
    const val CH_EVENTS = "trip_events"
    const val CH_LIVE = "trip_live"
    const val CH_ALERT = "trip_alerts"
    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    fun t(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hm)

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "Отправление и прибытие", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Текущая поездка", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Опоздания и пересадки", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun plat(p: String?) = p?.let { ", пл. $it" } ?: ""
    private fun legName(l: Leg) = l.line + (l.direction?.let { " → $it" } ?: "")

    /** All upcoming notification events of a plan. */
    fun events(p: TripPlan, s: Settings): List<TripEvent> {
        val legs = p.segments.flatMap { it.journey.transitLegs }
        val out = ArrayList<TripEvent>()
        legs.forEachIndexed { i, l ->
            val next = legs.getOrNull(i + 1)
            val depText = buildString {
                append("${t(l.dep)} ${l.from.name}${plat(l.from.platform)}")
                if (l.from.depDelayMin > 0) append(" (+${l.from.depDelayMin})")
                if (l.from.platformChanged) append(" — смена платформы!")
                append("\nПрибытие ${t(l.arr)} ${l.to.name}${plat(l.to.platform)}")
            }
            out += TripEvent(l.dep - s.notifyBeforeDepMin * 60_000L, "dep$i", "Отправление: ${legName(l)}", depText)
            val arrText = buildString {
                append("Прибытие ${t(l.arr)}${plat(l.to.platform)}")
                if (l.to.arrDelayMin > 0) append(" (+${l.to.arrDelayMin})")
                if (next != null) {
                    val slack = (next.dep - l.arr) / 60000
                    if (next.from.name == l.to.name) {
                        append("\nПересадка: ${legName(next)} в ${t(next.dep)}${plat(next.from.platform)} — $slack мин")
                    } else {
                        append("\nДалее: ${next.from.name}, ${legName(next)} в ${t(next.dep)}${plat(next.from.platform)}")
                    }
                } else append("\nКонечная точка поездки")
            }
            out += TripEvent(l.arr - s.notifyBeforeArrMin * 60_000L, "arr$i", "Скоро ${l.to.name}", arrText)
        }
        return out
    }

    private fun alarmIntent(ctx: Context, planId: String, key: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra("plan", planId).putExtra("key", key)
            .setData(android.net.Uri.parse("railnav://alarm/$planId/$key"))
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun schedule(ctx: Context, p: TripPlan) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        cancel(ctx, p)
        if (!p.notify) return
        val now = System.currentTimeMillis()
        for (e in events(p, Settings(ctx))) {
            if (e.at < now - 60_000) continue
            val pi = alarmIntent(ctx, p.id, e.key)
            val at = maxOf(e.at, now + 2000)
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
        ensureWorker(ctx)
    }

    fun cancel(ctx: Context, p: TripPlan) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        // cancel generously (keys are dep/arr + index)
        for (i in 0 until 40) for (k in listOf("dep", "arr")) am.cancel(alarmIntent(ctx, p.id, "$k$i"))
        NotificationManagerCompat.from(ctx).cancel(p.id.hashCode())
    }

    fun ensureWorker(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RealtimeWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("realtime", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun openPlanIntent(ctx: Context, planId: String, alternatives: Boolean = false): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("plan", planId).putExtra("alternatives", alternatives)
            .setData(android.net.Uri.parse("railnav://open/$planId/$alternatives"))
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    fun post(ctx: Context, id: Int, channel: String, title: String, text: String, open: PendingIntent, ongoing: Boolean = false,
             action: Pair<String, PendingIntent>? = null) {
        if (!canPost(ctx)) return
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_train)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setAutoCancel(!ongoing)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        action?.let { b.addAction(0, it.first, it.second) }
        NotificationManagerCompat.from(ctx).notify(id, b.build())
    }

    /** Ongoing "where am I in the trip" notification. */
    fun postLive(ctx: Context, p: TripPlan) {
        val now = System.currentTimeMillis()
        if (now < p.dep - 3600_000L || now > p.arr + 600_000L) {
            NotificationManagerCompat.from(ctx).cancel(p.id.hashCode()); return
        }
        val legs = p.segments.flatMap { it.journey.transitLegs }
        val cur = legs.firstOrNull { now in it.dep..it.arr }
        val next = legs.firstOrNull { it.dep > now }
        val text = buildString {
            if (cur != null) append("Сейчас: ${legName(cur)}, прибытие ${t(cur.arr)} ${cur.to.name}${plat(cur.to.platform)}\n")
            if (next != null) append("Далее: ${legName(next)} в ${t(next.dep)} от ${next.from.name}${plat(next.from.platform)}")
        }.trim().ifEmpty { "Прибытие ${t(p.arr)}" }
        post(ctx, p.id.hashCode(), CH_LIVE, p.title, text, openPlanIntent(ctx, p.id), ongoing = true)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val planId = intent.getStringExtra("plan") ?: return
        val key = intent.getStringExtra("key") ?: return
        val p = TripStore(ctx).get(planId) ?: return
        if (!p.notify) return
        val e = TripNotifications.events(p, Settings(ctx)).firstOrNull { it.key == key } ?: return
        TripNotifications.post(ctx, (planId + key).hashCode(), TripNotifications.CH_EVENTS, e.title, e.text,
            TripNotifications.openPlanIntent(ctx, planId))
        TripNotifications.postLive(ctx, p)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        TripStore(ctx).all().filter { it.notify && it.arr > System.currentTimeMillis() }
            .forEach { TripNotifications.schedule(ctx, it) }
    }
}

/** Refreshes active saved trips with live data and warns about broken connections. */
class RealtimeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = TripStore(applicationContext)
        val settings = Settings(applicationContext)
        val now = System.currentTimeMillis()
        val active = store.all().filter { it.notify && now > it.dep - 3 * 3600_000L && now < it.arr + 1800_000L }
        for (p in active) {
            val provider = Providers.get(applicationContext, p.segments.first().journey.provider)
            if (!provider.supportsRealtime) { TripNotifications.postLive(applicationContext, p); continue }
            val before = p.segments.flatMap { s -> s.journey.brokenTransfers(settings.minTransferMin).map { s.journey.id + it } }.toSet()
            val updated = p.copy(segments = p.segments.map { s ->
                s.copy(journey = s.journey.copy(legs = s.journey.legs.map { l ->
                    if (l.isWalk || l.arr < now - 600_000) l
                    else runCatching { provider.refreshLeg(l) }.getOrNull() ?: l
                }))
            })
            store.save(updated)
            TripNotifications.schedule(applicationContext, updated)
            TripNotifications.postLive(applicationContext, updated)

            val broken = updated.segments.flatMap { s -> s.journey.brokenTransfers(settings.minTransferMin).map { s.journey.id + it } }.toSet()
            val stayBroken = (0 until updated.segments.size - 1).any { updated.stayMin(it) < 0 }
            if ((broken - before).isNotEmpty() || stayBroken) {
                TripNotifications.post(
                    applicationContext, (p.id + "alert").hashCode(), TripNotifications.CH_ALERT,
                    "Пересадка под угрозой", "Из-за опоздания можно не успеть на пересадку в поездке «${p.title}». Нажмите, чтобы найти альтернативы.",
                    TripNotifications.openPlanIntent(applicationContext, p.id, alternatives = true),
                )
            }
        }
        return Result.success()
    }
}
