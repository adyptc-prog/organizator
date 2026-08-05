package com.example.management_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Rearmează alarmele native (SMS + notificări) după repornirea telefonului
 * sau după actualizarea aplicației, când AlarmManager își pierde toate
 * alarmele programate anterior.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" ->
                AlarmRescheduler.rescheduleAll(context)
        }
    }
}
