package com.example.management_app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Primitive comune de programare/anulare a alarmelor native (AlarmManager),
 * folosite atât din MainActivity (canalul Flutter "organizator/sms"), cât și
 * din AlarmRescheduler (repornire după reboot).
 */
object AlarmScheduler {

    private fun notifPendingIntentFor(context: Context, id: Int, flags: Int): PendingIntent? {
        val intent = Intent(context, NotifAlarmReceiver::class.java).apply {
            putExtra("notif_alarm_id", id)
        }
        val piFlags = flags or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, id + 50000, intent, piFlags)
    }

    fun scheduleNotifAlarm(context: Context, id: Int, triggerAtMs: Long) {
        val pi = notifPendingIntentFor(context, id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    fun cancelNotifAlarm(context: Context, id: Int) {
        val pi = notifPendingIntentFor(context, id, PendingIntent.FLAG_NO_CREATE) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi)
        pi.cancel()
    }

    private fun smsPendingIntentFor(context: Context, id: Int, flags: Int): PendingIntent? {
        val intent = Intent(context, SmsAlarmReceiver::class.java).apply {
            putExtra("sms_alarm_id", id)
        }
        val piFlags = flags or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, id, intent, piFlags)
    }

    fun scheduleSmsAlarm(context: Context, id: Int, triggerAtMs: Long) {
        val pi = smsPendingIntentFor(context, id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    fun cancelSmsAlarm(context: Context, id: Int) {
        val pi = smsPendingIntentFor(context, id, PendingIntent.FLAG_NO_CREATE) ?: return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi)
        pi.cancel()
    }
}
