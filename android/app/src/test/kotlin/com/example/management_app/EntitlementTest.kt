package com.example.management_app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntitlementTest {

    private lateinit var context: Context
    private val day = 24L * 60L * 60L * 1000L
    private val sent = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SmsSender.testSink = { phone, message -> synchronized(sent) { sent.add(phone to message) } }
        flutter().edit()
            .putString("flutter.management_boards",
                JSONArray().put(JSONObject().put("id", "b1").put("name", "Salon")).toString())
            .putBoolean("flutter.booking_enabled_b1", true)
            .putLong("flutter.work_start_b1", 0L)
            .putLong("flutter.work_end_b1", 24L * 60 - 1)
            .commit()
    }

    @After
    fun tearDown() {
        SmsSender.testSink = null
    }

    private fun flutter() =
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    // Exact formatul scris de Dart: DateTime.now().toIso8601String().
    private fun trialStartedDaysAgo(days: Long) {
        flutter().edit()
            .putString("flutter.trial_start_date", LocalDateTime.now().minusDays(days).toString())
            .commit()
    }

    @Test
    fun `calculul trial-ului`() {
        assertTrue(Entitlement.isTrialActive(null, 0L))
        assertTrue(Entitlement.isTrialActive(0L, 29 * day))
        assertFalse(Entitlement.isTrialActive(0L, 30 * day))
        assertEquals(30, Entitlement.trialDaysLeft(null, 0L))
        assertEquals(10, Entitlement.trialDaysLeft(0L, 20 * day))
        assertEquals(0, Entitlement.trialDaysLeft(0L, 40 * day))
    }

    @Test
    fun `data scrisa de Dart e citita corect`() {
        val ms = Entitlement.parseDartLocal("2026-10-06T00:30:12.345678")
        assertTrue(ms != null)
        assertEquals(null, Entitlement.parseDartLocal("nu e data"))
    }

    @Test
    fun `in trial botul raspunde`() {
        trialStartedDaysAgo(5)
        ClientBookingReceiver().handleMessage(context, "+40712345678", "liber")
        assertEquals(1, sent.size)
    }

    @Test
    fun `dupa trial, fara licenta, botul tace`() {
        trialStartedDaysAgo(31)
        ClientBookingReceiver().handleMessage(context, "+40712345678", "liber")
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `ceasul dat inapoi nu prelungeste trial-ul`() {
        trialStartedDaysAgo(10)
        // Telefonul a „văzut” deja o dată cu 25 de zile în viitor (apoi ceasul
        // a fost dat înapoi): timpul efectiv rămâne acela → 35 de zile de trial.
        context.getSharedPreferences(LicenseStore.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("last_license_check", System.currentTimeMillis() + 25 * day)
            .commit()
        assertFalse(Entitlement.isActive(context))
    }

    private fun fireSmsAlarm(): List<Pair<String, String>> {
        flutter().edit()
            .putString("flutter.sms_alarm_120", JSONObject()
                .put("phone", "0712345678").put("message", "Reminder").toString())
            .commit()
        context.sendBroadcast(Intent(context, SmsAlarmReceiver::class.java).putExtra("sms_alarm_id", 120))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(500) // receiverul trimite pe un fir separat
        return synchronized(sent) { sent.toList() }
    }

    @Test
    fun `reminderul SMS pleaca in trial`() {
        trialStartedDaysAgo(5)
        assertEquals(listOf("0712345678" to "Reminder"), fireSmsAlarm())
    }

    @Test
    fun `reminderul SMS nu pleaca dupa trial fara licenta`() {
        trialStartedDaysAgo(31)
        assertTrue(fireSmsAlarm().isEmpty())
    }
}
