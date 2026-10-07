package com.example.management_app

import android.content.Context
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsSyncReceiverTest {

    private lateinit var context: Context
    private val receiver = SmsSyncReceiver()
    private val partner = "+40722000111"
    private val codeB1 = "AAAA2222"
    private val codeB2 = "BBBB3333"
    private val msg = """ORG:A:{"s":"abc","n":"Ion","c":"2026-10-06T10:00"}"""
    private val sent = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val boards = JSONArray()
            .put(JSONObject().put("id", "b1").put("name", "Tabel 1"))
            .put(JSONObject().put("id", "b2").put("name", "Tabel 2"))
        // Același partener pe două tabele, cu coduri diferite.
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
            .putString("flutter.management_boards", boards.toString())
            .putString("flutter.sync_partner_phone_b1", "0722000111")
            .putString("flutter.sync_secret_b1", codeB1)
            .putString("flutter.sync_partner_phone_b2", "0722 000 111")
            .putString("flutter.sync_secret_b2", codeB2)
            .commit()
        SmsSender.testSink = { phone, message -> sent.add(phone to message) }
    }

    @After
    fun tearDown() {
        SmsSender.testSink = null
    }

    private fun queue(): List<JSONObject> {
        val arr = JSONArray(SmsSyncReceiver.snapshot(context))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    @Test
    fun `mesajul nesemnat de la partener e ignorat`() {
        receiver.handleSms(context, partner, msg)
        assertTrue(queue().isEmpty())
    }

    @Test
    fun `mesajul semnat ajunge in tabelul al carui cod il valideaza`() {
        receiver.handleSms(context, partner, SyncAuth.sign(codeB2, msg))
        val q = queue()
        assertEquals(1, q.size)
        assertEquals("b2", q[0].getString("board"))
        assertEquals(msg, q[0].getString("msg"))
        assertEquals(SmsSyncReceiver.ORIGIN_PARTNER, q[0].getString("origin"))
    }

    @Test
    fun `semnatura corecta de la alt numar e ignorata`() {
        receiver.handleSms(context, "+40799999999", SyncAuth.sign(codeB1, msg))
        assertTrue(queue().isEmpty())
    }

    @Test
    fun `mesajul modificat pe drum e ignorat`() {
        val signed = SyncAuth.sign(codeB1, msg).replace("Ion", "Eve")
        receiver.handleSms(context, partner, signed)
        assertTrue(queue().isEmpty())
    }

    @Test
    fun `trimiterea semneaza cu codul tabelului`() {
        assertTrue(SmsSyncReceiver.sendSigned(context, "b1", msg))
        assertEquals("0722000111", sent.single().first)
        assertEquals(msg, SyncAuth.verify(codeB1, sent.single().second))
    }

    @Test
    fun `fara cod de imperechere nu se trimite nimic`() {
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE).edit()
            .remove("flutter.sync_secret_b1").commit()
        assertFalse(SmsSyncReceiver.sendSigned(context, "b1", msg))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `serviciile primite de la partener se scriu imediat pentru bot`() {
        val v = """ORG:V:{"d":45,"s":[{"n":"Gel","m":90},{"n":"Ojă","m":30}]}"""
        receiver.handleSms(context, partner, SyncAuth.sign(codeB2, v))
        assertEquals(
            listOf(ServiceInfo("Gel", 90), ServiceInfo("Ojă", 30)),
            ServicesStore.load(context, "b2"),
        )
        assertEquals(45, BookingSettings.loadSettings(context, "b2").durationMin)
        assertTrue(ServicesStore.load(context, "b1").isEmpty())
        // Și ajunge în coadă, pentru cache-ul din Flutter.
        assertEquals(v, queue().single().getString("msg"))
    }

    @Test
    fun `lista de servicii invalida e ignorata`() {
        receiver.handleSms(context, partner, SyncAuth.sign(codeB1, """ORG:V:{"d":0}"""))
        receiver.handleSms(context, partner, SyncAuth.sign(codeB1, "ORG:V:nu-e-json"))
        assertTrue(ServicesStore.load(context, "b1").isEmpty())
        assertTrue(queue().isEmpty())
    }

    @Test
    fun `serviciile cu durata invalida sunt sarite`() {
        val v = """ORG:V:{"d":30,"s":[{"n":"","m":30},{"n":"Prea lung","m":9999},{"n":"Ok","m":60}]}"""
        receiver.handleSms(context, partner, SyncAuth.sign(codeB1, v))
        assertEquals(listOf(ServiceInfo("Ok", 60)), ServicesStore.load(context, "b1"))
    }
}
