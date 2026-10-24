package dev.fl1nt.ember.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.fl1nt.ember.api.Access
import dev.fl1nt.ember.api.Capabilities
import dev.fl1nt.ember.api.FlareCredential
import dev.fl1nt.ember.api.FlareOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.ProviderException

/** Why uploads are paused until someone reconnects. */
enum class AccountProblem {
    /** Flare answered 401. The token expired, was revoked, or lost a scope. */
    TOKEN_REJECTED,

    /** This phone can no longer decrypt the saved token, for example after its keys were reset. */
    TOKEN_UNREADABLE,
}

/** The connected Flare instance. The token itself is only available through [AccountStore.credential]. */
data class Account(
    val origin: FlareOrigin,
    val displayName: String,
    val credentialKind: FlareCredential.Kind,
    val capabilities: Capabilities,
    /** Flare's public address, learned from upload links. Short links use it. */
    val publicOrigin: FlareOrigin?,
    val connectedAt: Long,
    val checkedAt: Long,
    val problem: AccountProblem? = null,
) {
    val linkOrigin: FlareOrigin get() = publicOrigin ?: origin
}

/** Stores the single connected account. The token is encrypted with [SecretCipher]. */
class AccountStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val account: Flow<Account?> = dataStore.data.map { it.toAccount() }

    suspend fun current(): Account? = account.first()

    /**
     * Decrypts the token, or returns `null` when Ember isn't connected or can't read
     * it. An unreadable token is recorded as [AccountProblem.TOKEN_UNREADABLE], so
     * the app asks to reconnect instead of looking connected.
     */
    suspend fun credential(): FlareCredential? {
        val prefs = dataStore.data.first()
        val sealed = prefs[TOKEN] ?: return null
        val credential = try {
            FlareCredential.of(cipher.decrypt(sealed))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: ProviderException) {
            // The Keystore reports some failures unchecked.
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (credential == null) {
            reportProblem(AccountProblem.TOKEN_UNREADABLE)
        } else if (prefs[PROBLEM] == AccountProblem.TOKEN_UNREADABLE.name) {
            // A passing Keystore error shouldn't leave Ember asking to reconnect.
            clearProblem()
        }
        return credential
    }

    /** Records that uploads can't continue until someone reconnects. */
    suspend fun reportProblem(problem: AccountProblem) {
        dataStore.edit { prefs -> if (prefs[ORIGIN] != null) prefs[PROBLEM] = problem.name }
    }

    /** Clears a recorded problem once Flare accepts the token again. */
    suspend fun clearProblem() {
        // Most calls find nothing to clear, so skip the write.
        if (dataStore.data.first()[PROBLEM] == null) return
        dataStore.edit { it.remove(PROBLEM) }
    }

    suspend fun connect(origin: FlareOrigin, credential: FlareCredential, capabilities: Capabilities, displayName: String?) {
        val now = clock()
        val sealed = cipher.encrypt(credential.secret)
        dataStore.edit { prefs ->
            prefs.clear()
            prefs[ORIGIN] = origin.toString()
            prefs[NAME] = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: origin.displayName
            prefs[TOKEN] = sealed
            prefs[KIND] = credential.kind.name
            prefs[CAPABILITIES] = json.encodeToString(StoredCapabilities.serializer(), StoredCapabilities.of(capabilities))
            prefs[CONNECTED_AT] = now
            prefs[CHECKED_AT] = now
        }
    }

    suspend fun updateCapabilities(capabilities: Capabilities) {
        dataStore.edit { prefs ->
            if (prefs[ORIGIN] == null) return@edit
            prefs[CAPABILITIES] = json.encodeToString(StoredCapabilities.serializer(), StoredCapabilities.of(capabilities))
            prefs[CHECKED_AT] = clock()
        }
    }

    suspend fun rename(displayName: String) {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs -> if (prefs[ORIGIN] != null) prefs[NAME] = trimmed }
    }

    /** Remembers the origin Flare uses in its links, for building short link addresses. */
    suspend fun learnPublicOrigin(link: String) {
        val origin = FlareOrigin.parseOrNull(link) ?: return
        dataStore.edit { prefs ->
            if (prefs[ORIGIN] != null && prefs[PUBLIC_ORIGIN] != origin.toString()) prefs[PUBLIC_ORIGIN] = origin.toString()
        }
    }

    /** Forgets the account, but remembers its address so reconnecting is quick. */
    suspend fun disconnect() {
        dataStore.edit { prefs ->
            val last = prefs[ORIGIN]
            prefs.clear()
            last?.let { prefs[LAST_ORIGIN] = it }
        }
    }

    /** The address of the most recently disconnected account, if any. */
    suspend fun lastOrigin(): String? = dataStore.data.first()[LAST_ORIGIN]

    private fun Preferences.toAccount(): Account? {
        val origin = this[ORIGIN]?.let(FlareOrigin::parseOrNull) ?: return null
        if (this[TOKEN] == null) return null
        val capabilities = this[CAPABILITIES]?.let {
            runCatching { json.decodeFromString(StoredCapabilities.serializer(), it).toCapabilities() }.getOrNull()
        } ?: Capabilities.NONE
        return Account(
            origin = origin,
            displayName = this[NAME] ?: origin.displayName,
            credentialKind = this[KIND]?.let { runCatching { FlareCredential.Kind.valueOf(it) }.getOrNull() }
                ?: FlareCredential.Kind.NAMED_TOKEN,
            capabilities = capabilities,
            publicOrigin = this[PUBLIC_ORIGIN]?.let(FlareOrigin::parseOrNull),
            connectedAt = this[CONNECTED_AT] ?: 0,
            checkedAt = this[CHECKED_AT] ?: 0,
            problem = this[PROBLEM]?.let { runCatching { AccountProblem.valueOf(it) }.getOrNull() },
        )
    }

    @Serializable
    private data class StoredCapabilities(
        val uploadFiles: String,
        val readFiles: String,
        val readLinks: String,
        val manageLinks: String,
        val listFolders: String,
    ) {
        fun toCapabilities() = Capabilities(
            uploadFiles = access(uploadFiles),
            readFiles = access(readFiles),
            readLinks = access(readLinks),
            manageLinks = access(manageLinks),
            listFolders = access(listFolders),
        )

        private fun access(value: String) = runCatching { Access.valueOf(value) }.getOrDefault(Access.UNKNOWN)

        companion object {
            fun of(capabilities: Capabilities) = StoredCapabilities(
                uploadFiles = capabilities.uploadFiles.name,
                readFiles = capabilities.readFiles.name,
                readLinks = capabilities.readLinks.name,
                manageLinks = capabilities.manageLinks.name,
                listFolders = capabilities.listFolders.name,
            )
        }
    }

    private companion object {
        val ORIGIN = stringPreferencesKey("origin")
        val NAME = stringPreferencesKey("display_name")
        val TOKEN = stringPreferencesKey("token")
        val KIND = stringPreferencesKey("credential_kind")
        val CAPABILITIES = stringPreferencesKey("capabilities")
        val PUBLIC_ORIGIN = stringPreferencesKey("public_origin")
        val CONNECTED_AT = longPreferencesKey("connected_at")
        val CHECKED_AT = longPreferencesKey("checked_at")
        val LAST_ORIGIN = stringPreferencesKey("last_origin")
        val PROBLEM = stringPreferencesKey("problem")
        val json = Json { ignoreUnknownKeys = true }
    }
}
