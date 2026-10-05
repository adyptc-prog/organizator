package com.example.management_app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LicenseShareTest {

    private lateinit var context: Context
    private val fixtures = JSONObject(
        javaClass.classLoader!!.getResource("license_fixtures.json")!!.readText()
    )
    private val partnerB = "+40722000111"
    private val phoneC = "0733000222"

    private fun license(name: String) = fixtures.getJSONObject(name).toString()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val decoder: (String) -> ByteArray = { Base64.getMimeDecoder().decode(it) }
        LicenseStore.verifierOverride =
            LicenseVerifier(decoder(fixtures.getString("publicKeyDerB64")), decoder)
        context.getSharedPreferences(LicenseStore.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(LicenseStore.KEY_BUSINESS_ID, "organizator-1700000000000")
            .commit()
    }

    @After
    fun tearDown() {
        LicenseStore.verifierOverride = null
    }

    @Test
    fun `licenta importata se trimite primului partener si ramane legata de el`() {
        assertTrue(LicenseStore.importLicense(context, license("timed")).isActive)

        val (first, payload) = LicenseStore.shareTo(context, partnerB)
        assertEquals(ShareDecision.SEND, first)
        assertNotNull(payload)
        // Același număr, alt format — tot partenerul legat.
        assertEquals(ShareDecision.SEND, LicenseStore.shareTo(context, "0722 000 111").first)
        // Al treilea telefon — refuzat.
        val (third, none) = LicenseStore.shareTo(context, phoneC)
        assertEquals(ShareDecision.OTHER_PARTNER, third)
        assertNull(none)
        assertEquals("***111", LicenseStore.shareInfo(context)["sharedWith"])
    }

    @Test
    fun `licenta reinnoita poate fi trimisa altui partener`() {
        LicenseStore.importLicense(context, license("timed"))
        LicenseStore.shareTo(context, partnerB)
        // Licență nouă (alt licenseId) — legătura veche nu se mai aplică.
        assertTrue(LicenseStore.importLicense(context, license("timedLater")).isActive)
        assertEquals(ShareDecision.SEND, LicenseStore.shareTo(context, phoneC).first)
    }

    @Test
    fun `telefonul care a primit licenta de la partener nu o mai trimite`() {
        // Telefon nou, cu alt cod de instalare: preia licența partenerului.
        context.getSharedPreferences(LicenseStore.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(LicenseStore.KEY_BUSINESS_ID, "organizator-2").commit()
        assertEquals(AdoptionDecision.ADOPT, LicenseStore.adoptFromPartner(context, license("timed")))
        assertTrue(LicenseStore.check(context).isActive)

        assertEquals(ShareDecision.NOT_OWNER, LicenseStore.shareTo(context, phoneC).first)
        assertEquals(true, LicenseStore.shareInfo(context)["fromPartner"])
    }

    @Test
    fun `fara licenta activa nu se trimite nimic`() {
        assertEquals(ShareDecision.NO_LICENSE, LicenseStore.shareTo(context, partnerB).first)
    }

    @Test
    fun `provenienta si partenerul legat trec prin backup`() {
        LicenseStore.importLicense(context, license("timed"))
        LicenseStore.shareTo(context, partnerB)
        val identity = LicenseStore.identity(context)
        assertEquals(LicenseStore.SOURCE_FILE, identity.licenseSource)
        assertEquals("40722000111", identity.sharePartner)

        val encoded = BackupFormat.encode(emptyMap<String, Any>(), identity, 0L)
        assertEquals(identity, BackupFormat.decode(encoded).identity)
    }
}
