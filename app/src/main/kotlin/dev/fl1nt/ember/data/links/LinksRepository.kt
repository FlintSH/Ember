package dev.fl1nt.ember.data.links

import dev.fl1nt.ember.api.FlareException
import dev.fl1nt.ember.api.ShortLink
import dev.fl1nt.ember.data.FlareConnection
import dev.fl1nt.ember.data.account.AccountStore
import dev.fl1nt.ember.data.db.EmberDatabase
import dev.fl1nt.ember.data.db.ShortLinkEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Short links. Ember keeps the ones it creates locally, and replaces that list
 * with the account's full list when the token can read links.
 */
class LinksRepository(
    private val db: EmberDatabase,
    private val connection: FlareConnection,
    private val accounts: AccountStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observe(): Flow<List<ShortLinkEntity>> = db.shortLinks().observeAll()

    /** @throws FlareException when Flare refuses or can't be reached. */
    suspend fun create(target: String): ShortLinkEntity {
        val client = connection.client() ?: throw FlareException.Unauthorized()
        val link = client.createShortLink(target)
        val entity = link.toEntity()
        db.shortLinks().upsert(entity)
        return entity
    }

    /**
     * Loads the account's links. Returns false when the token can't list them.
     * @throws FlareException when a token that could list them is now refused, or Flare can't be reached.
     */
    suspend fun refresh(): Boolean {
        val account = accounts.current() ?: return false
        if (account.capabilities.readLinks.isDenied) return false
        val client = connection.client() ?: throw FlareException.Unauthorized()
        val links = try {
            client.listShortLinks()
        } catch (refused: FlareException.Unauthorized) {
            // Expected without Read short links. With it, the token itself was refused.
            if (account.capabilities.readLinks.isGranted) throw refused
            return false
        }
        val entities = links.map { it.toEntity() }
        db.shortLinks().replaceAll(entities)
        return true
    }

    suspend fun delete(id: String) {
        val client = connection.client() ?: throw FlareException.Unauthorized()
        client.deleteShortLink(id)
        db.shortLinks().delete(id)
    }

    private suspend fun ShortLink.toEntity(): ShortLinkEntity {
        val origin = accounts.current()?.linkOrigin
        val created = runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(clock())
        return ShortLinkEntity(
            id = id,
            shortCode = shortCode,
            targetUrl = targetUrl,
            shortUrl = "${origin ?: ""}/u/$shortCode",
            clicks = clicks,
            createdAt = created,
        )
    }
}
