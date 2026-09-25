package com.example.management_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsStatusTest {

    @Test
    fun `eșecul se afișează până e închis`() {
        assertFalse(SmsStatus.shouldReport(failureAt = 0L, dismissedAt = 0L))
        assertTrue(SmsStatus.shouldReport(failureAt = 1000L, dismissedAt = 0L))
        assertFalse(SmsStatus.shouldReport(failureAt = 1000L, dismissedAt = 2000L))
        // Un eșec nou după închidere apare din nou.
        assertTrue(SmsStatus.shouldReport(failureAt = 3000L, dismissedAt = 2000L))
    }

    @Test
    fun `codurile de eroare ale sistemului au explicații clare`() {
        // Valorile SmsManager.RESULT_ERROR_* din API-ul Android.
        assertTrue(SmsStatus.describe(1).contains("SIM"))
        assertTrue(SmsStatus.describe(2).contains("avion"))
        assertTrue(SmsStatus.describe(4).contains("semnal"))
        assertEquals("Trimiterea a eșuat (cod 99).", SmsStatus.describe(99))
    }

    @Test
    fun `mesajul implicit cu diacritice nu încape într-un singur SMS`() {
        // Cauza bug-ului: un SMS cu diacritice (UCS-2) are maximum 70 de
        // caractere, iar sendTextMessage() cu text mai lung eșua silențios.
        // SmsSender împarte acum mereu mesajul în segmente.
        val msg = "EXPIRAT: Alertă: Programare test. Va expira la 24.09.2026 19:30. " +
            "Te rugăm să iei măsurile necesare."
        val hasNonGsm = msg.any { it in "ăâîșțĂÂÎȘȚ" }
        assertTrue(hasNonGsm)
        assertTrue(msg.length > 70)
    }
}
