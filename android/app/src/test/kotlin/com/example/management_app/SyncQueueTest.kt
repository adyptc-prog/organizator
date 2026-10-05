package com.example.management_app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncQueueTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun ids(json: String): List<String> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    private fun msgs(json: String): List<String> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("msg") }
    }

    @Test
    fun `mesajul sosit in timpul procesarii nu se pierde`() {
        SmsSyncReceiver.enqueue(context, "b1", "ORG:A:{\"s\":\"a\"}")
        val read = SmsSyncReceiver.snapshot(context)

        // Un SMS nou sosește cât timp Flutter încă procesează ce a citit.
        SmsSyncReceiver.enqueue(context, "b1", "ORG:D:b")
        SmsSyncReceiver.acknowledge(context, ids(read))

        assertEquals(listOf("ORG:D:b"), msgs(SmsSyncReceiver.snapshot(context)))
    }

    @Test
    fun `fiecare intrare are un id unic`() {
        SmsSyncReceiver.enqueue(context, "b1", "ORG:D:x")
        SmsSyncReceiver.enqueue(context, "b1", "ORG:D:x")
        val all = ids(SmsSyncReceiver.snapshot(context))
        assertEquals(2, all.toSet().size)
    }

    @Test
    fun `intrarile vechi fara id primesc unul stabil si pot fi confirmate`() {
        context.getSharedPreferences(SmsSyncReceiver.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(SmsSyncReceiver.QUEUE_KEY, """["ORG:D:x",{"board":"b2","msg":"ORG:D:y"}]""")
            .commit()

        val first = SmsSyncReceiver.snapshot(context)
        assertEquals(listOf("ORG:D:x", "ORG:D:y"), msgs(first))
        // Același id la citiri repetate — altfel confirmarea n-ar găsi intrarea.
        assertEquals(ids(first), ids(SmsSyncReceiver.snapshot(context)))

        SmsSyncReceiver.acknowledge(context, ids(first))
        assertEquals("[]", SmsSyncReceiver.snapshot(context))
    }

    @Test
    fun `coada corupta nu blocheaza mesajele noi`() {
        context.getSharedPreferences(SmsSyncReceiver.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(SmsSyncReceiver.QUEUE_KEY, "{corupt")
            .commit()

        SmsSyncReceiver.enqueue(context, "b1", "ORG:D:z")
        assertEquals(listOf("ORG:D:z"), msgs(SmsSyncReceiver.snapshot(context)))
    }

    @Test
    fun `confirmarea unor id-uri necunoscute nu sterge nimic`() {
        SmsSyncReceiver.enqueue(context, "b1", "ORG:D:z")
        SmsSyncReceiver.acknowledge(context, listOf("necunoscut"))
        assertTrue(msgs(SmsSyncReceiver.snapshot(context)).contains("ORG:D:z"))
    }
}
