package com.example.management_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class SmsAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra("sms_alarm_id", -1)
        if (alarmId < 0) return

        // goAsync() extinde limita de timp la 60s și eliberează main thread-ul
        val pending = goAsync()

        Thread {
            try {
                // Citim datele din SharedPreferences Flutter
                val prefs   = context.getSharedPreferences(
                    "FlutterSharedPreferences", Context.MODE_PRIVATE
                )
                val dataStr = prefs.getString("flutter.sms_alarm_$alarmId", null)
                    ?: return@Thread

                val json    = JSONObject(dataStr)
                val phone   = json.optString("phone")
                val message = json.optString("message")

                if (phone.isBlank() || message.isBlank()) return@Thread

                // Împărțit în segmente: un text cu diacritice peste 70 de
                // caractere nu pleacă altfel (sendTextMessage eșua silențios).
                SmsSender.send(context, phone, message)

                // Nu ștergem cheia din SharedPreferences din receiver:
                // evităm accesul concurent cu Flutter care poate provoca crash
                // Cheia va fi curățată de Dart la próxima reprogramare / ștergere
            } catch (_: Exception) {
                // Erorile sunt silențioase pentru a nu crash-ui procesul
            } finally {
                pending.finish()
            }
        }.start()
    }
}
