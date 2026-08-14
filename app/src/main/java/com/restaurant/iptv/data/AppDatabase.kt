package com.restaurant.iptv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.restaurant.iptv.data.entity.ChannelEntity
import com.restaurant.iptv.data.entity.HiddenGroupEntity
import com.restaurant.iptv.data.entity.ProviderEntity

@Database(
    entities = [ProviderEntity::class, ChannelEntity::class, HiddenGroupEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): IptvDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "restaurant_iptv.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
