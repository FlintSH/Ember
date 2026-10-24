package dev.fl1nt.ember.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [UploadEntity::class, SyncSourceEntity::class, LedgerEntity::class, ShortLinkEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class EmberDatabase : RoomDatabase() {
    abstract fun uploads(): UploadDao

    abstract fun sources(): SyncSourceDao

    abstract fun ledger(): LedgerDao

    abstract fun shortLinks(): ShortLinkDao

    companion object {
        fun create(context: Context): EmberDatabase =
            Room.databaseBuilder(context, EmberDatabase::class.java, "ember.db").build()
    }
}
