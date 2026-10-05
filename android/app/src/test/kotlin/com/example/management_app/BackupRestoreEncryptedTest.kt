package com.example.management_app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric nu are Android Keystore — „sigilarea” e simulată.
private object FakeBox : SecretBox {
    override fun seal(plain: ByteArray) = "sealed:" + String(plain, Charsets.UTF_8)
    override fun open(sealed: String) = sealed.removePrefix("sealed:").toByteArray(Charsets.UTF_8)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreEncryptedTest {

    private lateinit var context: Context
    private val password = "parola-backup"

    private fun flutter() =
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    // Backup criptat făcut cu datele „Ana” — apoi datele curente devin „Ion”.
    private lateinit var encrypted: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BackupPassword.box = FakeBox
        flutter().edit().putString("flutter.management_items_b1", "Ana").commit()
        encrypted = BackupCrypto.encrypt(BackupManager.snapshot(context, 1000L), password, 1000L, iterations = 1000)
        flutter().edit().putString("flutter.management_items_b1", "Ion").commit()
    }

    @After
    fun tearDown() {
        BackupPassword.box = KeystoreSecretBox
    }

    private fun items() = flutter().getString("flutter.management_items_b1", null)

    @Test
    fun `pe acelasi telefon parola salvata deschide backup-ul`() {
        BackupPassword.set(context, password)
        BackupManager.restoreContent(context, encrypted, keepSyncPartners = true)
        assertEquals("Ana", items())
    }

    @Test
    fun `fara parola salvata restaurarea cere parola si nu atinge datele`() {
        val e = assertThrows(BackupManager.PasswordException::class.java) {
            BackupManager.restoreContent(context, encrypted, keepSyncPartners = true)
        }
        assertEquals(BackupManager.PASSWORD_REQUIRED, e.code)
        assertEquals("Ion", items())
    }

    @Test
    fun `parola gresita e raportata si datele raman neatinse`() {
        val e = assertThrows(BackupManager.PasswordException::class.java) {
            BackupManager.restoreContent(context, encrypted, keepSyncPartners = true, password = "alta-parola")
        }
        assertEquals(BackupManager.PASSWORD_WRONG, e.code)
        assertEquals("Ion", items())
    }

    @Test
    fun `pe un telefon nou parola data devine parola de backup`() {
        assertNull(BackupPassword.get(context))
        BackupManager.restoreContent(context, encrypted, keepSyncPartners = true, password = password)
        assertEquals("Ana", items())
        assertEquals(password, BackupPassword.get(context))
    }

    @Test
    fun `backup-ul vechi necriptat se restaureaza in continuare`() {
        flutter().edit().putString("flutter.management_items_b1", "Vechi").commit()
        val plain = BackupManager.snapshot(context, 1000L)
        flutter().edit().putString("flutter.management_items_b1", "Ion").commit()
        BackupManager.restoreContent(context, plain, keepSyncPartners = true)
        assertEquals("Vechi", items())
    }

    @Test
    fun `statusul spune daca parola e setata`() {
        assertEquals(false, BackupManager.status(context)["hasPassword"])
        BackupPassword.set(context, password)
        assertTrue(BackupManager.status(context)["hasPassword"] as Boolean)
    }

    @Test
    fun `parola prea scurta nu e acceptata`() {
        assertThrows(IllegalArgumentException::class.java) { BackupPassword.set(context, "scurta") }
        assertNull(BackupPassword.get(context))
    }
}
