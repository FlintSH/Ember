package dev.fl1nt.ember.data.account

import com.google.common.truth.Truth.assertThat
import dev.fl1nt.ember.FakeCipher
import dev.fl1nt.ember.api.Access
import dev.fl1nt.ember.api.Capabilities
import dev.fl1nt.ember.api.FlareCredential
import dev.fl1nt.ember.api.FlareOrigin
import dev.fl1nt.ember.data.settings.SettingsStore
import dev.fl1nt.ember.testDataStore
import dev.fl1nt.ember.ui.theme.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val capabilities = Capabilities(Access.GRANTED, Access.GRANTED, Access.NOT_IN_TOKEN, Access.DENIED_BY_ROLE, Access.UNKNOWN)

    @Test
    fun `stores the token encrypted and reads it back`() = runBlocking {
        val store = AccountStore(testDataStore(temp.root), FakeCipher(), clock = { 42 })
        assertThat(store.current()).isNull()

        store.connect(FlareOrigin.parse("files.example.com"), FlareCredential.of("flr_secret"), capabilities, displayName = null)
        val account = store.current()!!
        assertThat(account.displayName).isEqualTo("files.example.com")
        assertThat(account.credentialKind).isEqualTo(FlareCredential.Kind.NAMED_TOKEN)
        assertThat(account.capabilities).isEqualTo(capabilities)
        assertThat(account.connectedAt).isEqualTo(42)
        assertThat(store.credential()?.secret).isEqualTo("flr_secret")

        // The plaintext token never reaches the preferences file.
        val raw = temp.root.walk().filter { it.isFile }.map { it.readBytes().toString(Charsets.UTF_8) }.joinToString()
        assertThat(raw).doesNotContain("flr_secret")
    }

    @Test
    fun `learns the public origin from upload links and forgets everything on disconnect`() = runBlocking {
        val store = AccountStore(testDataStore(temp.root), FakeCipher())
        store.connect(FlareOrigin.parse("http://192.168.1.20:3000"), FlareCredential.of("account-token"), capabilities, displayName = "Home server")
        assertThat(store.current()!!.linkOrigin.toString()).isEqualTo("http://192.168.1.20:3000")

        store.learnPublicOrigin("https://files.example.com/alex/a.png")
        assertThat(store.current()!!.linkOrigin.toString()).isEqualTo("https://files.example.com")
        assertThat(store.current()!!.displayName).isEqualTo("Home server")
        assertThat(store.current()!!.credentialKind).isEqualTo(FlareCredential.Kind.ACCOUNT_TOKEN)

        store.disconnect()
        assertThat(store.current()).isNull()
        assertThat(store.credential()).isNull()
        // The address survives so replacing a revoked token doesn't mean retyping it.
        assertThat(store.lastOrigin()).isEqualTo("http://192.168.1.20:3000")
    }

    @Test
    fun `an undecryptable token reads as disconnected`() = runBlocking {
        val data = testDataStore(temp.root)
        AccountStore(data, FakeCipher()).connect(FlareOrigin.parse("files.example.com"), FlareCredential.of("flr_x"), capabilities, null)
        val otherDevice = object : SecretCipher {
            override fun encrypt(plaintext: String) = plaintext
            override fun decrypt(encoded: String): String = throw java.security.GeneralSecurityException("key gone")
        }
        assertThat(AccountStore(data, otherDevice).credential()).isNull()
    }

    @Test
    fun `settings default to Flare's look and round trip`() = runBlocking {
        val settings = SettingsStore(testDataStore(temp.root))
        val defaults = settings.current()
        assertThat(defaults.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(defaults.sync.wifiOnly).isTrue()
        assertThat(defaults.copyLinkAfterUpload).isTrue()

        settings.update { it.copy(themeMode = ThemeMode.LIGHT, pasteFileName = "  ", sync = it.sync.copy(intervalMinutes = 5)) }
        val updated = settings.current()
        assertThat(updated.themeMode).isEqualTo(ThemeMode.LIGHT)
        assertThat(updated.pasteFileName).isEqualTo("paste.txt")
        assertThat(updated.sync.intervalMinutes).isEqualTo(15)
    }
}
