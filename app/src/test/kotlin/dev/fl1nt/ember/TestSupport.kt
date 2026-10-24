package dev.fl1nt.ember

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.fl1nt.ember.data.account.SecretCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.security.GeneralSecurityException
import java.util.UUID

/** A plain Application, so tests don't start Ember's real container, Keystore, or WorkManager. */
class TestApp : Application()

/** Reversible stand-in for the Android Keystore, which Robolectric doesn't provide. */
class FakeCipher : SecretCipher {
    override fun encrypt(plaintext: String) = "sealed:" + plaintext.reversed()

    override fun decrypt(encoded: String): String {
        if (!encoded.startsWith("sealed:")) throw GeneralSecurityException("not sealed by FakeCipher")
        return encoded.removePrefix("sealed:").reversed()
    }
}

fun testDataStore(directory: File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        File(directory, "test-${UUID.randomUUID()}.preferences_pb")
    }

/** A real 1x1 PNG, so Flare-style type checks see an image. */
val PNG_BYTES: ByteArray = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, 0x90.toByte(), 0x77, 0x53, 0xDE.toByte(),
)
