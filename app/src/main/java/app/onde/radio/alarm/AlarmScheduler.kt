package app.onde.radio.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.onde.radio.OndeApp
import app.onde.radio.data.AlarmSettings
import app.onde.radio.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/** Programme le prochain réveil radio dans le système (visible dans la barre d'état comme un réveil). */
object AlarmScheduler {

    fun schedule(context: Context, alarm: AlarmSettings) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val fire = firePendingIntent(context)
        manager.cancel(fire)
        if (!alarm.enabled || alarm.station == null || alarm.days.isEmpty()) return

        val triggerAt = nextTrigger(alarm) ?: return
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (canExact) {
            val show = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), fire)
        } else {
            // Sans autorisation d'alarme exacte : déclenchement à quelques minutes près.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, fire)
        }
    }

    /** Prochaine occurrence (dans les 8 jours) à l'heure et un jour choisis, ou null. */
    fun nextTrigger(alarm: AlarmSettings, now: Long = System.currentTimeMillis()): Long? {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (cal.timeInMillis > now && cal.get(Calendar.DAY_OF_WEEK) in alarm.days) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    private fun firePendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** L'heure du réveil est arrivée : on lance la station, puis on programme le suivant. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as OndeApp
        val alarm = app.library.data.value.alarm
        AlarmScheduler.schedule(app, alarm)
        val station = alarm.station?.takeIf { alarm.enabled } ?: return
        app.engine.playForAlarm(station)
        // La connexion à la radio prend quelques secondes : on demande à Android de garder l'appli
        // en vie jusqu'à ce que le son démarre (le service de lecture prend ensuite le relais).
        val pending = goAsync()
        app.appScope.launch {
            try {
                withTimeoutOrNull(8_000) { app.engine.state.first { it.isPlaying } }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Après un redémarrage du téléphone, les réveils programmés sont perdus : on le reprogramme. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) return
        val app = context.applicationContext as OndeApp
        AlarmScheduler.schedule(app, app.library.data.value.alarm)
    }
}
