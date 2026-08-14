package com.restaurant.iptv.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.restaurant.iptv.data.entity.ChannelEntity
import com.restaurant.iptv.data.entity.HiddenGroupEntity
import com.restaurant.iptv.data.entity.ProviderEntity

@Dao
interface IptvDao {

    // --- Providers ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(p: ProviderEntity): Long

    @Query("SELECT * FROM providers ORDER BY id ASC")
    suspend fun getProviders(): List<ProviderEntity>

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun getProvider(id: Long): ProviderEntity?

    @Query("SELECT * FROM providers WHERE active = 1 ORDER BY id ASC LIMIT 1")
    suspend fun getActiveProvider(): ProviderEntity?

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun deleteProvider(id: Long)

    @Query("UPDATE providers SET channelCount = :count, expiresAt = :expiresAt, maxConnections = :maxConn, lastUpdated = :ts, lastError = :err WHERE id = :id")
    suspend fun updateProviderStatus(id: Long, count: Int, expiresAt: Long?, maxConn: Int?, ts: Long, err: String?)

    // --- Channels ---
    @Query("DELETE FROM channels WHERE providerId = :providerId")
    suspend fun clearChannels(providerId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Transaction
    suspend fun replaceChannels(providerId: Long, channels: List<ChannelEntity>) {
        clearChannels(providerId)
        insertChannels(channels)
    }

    @Query("SELECT * FROM channels WHERE providerId = :providerId ORDER BY sortIndex ASC, number ASC, name ASC")
    suspend fun getChannels(providerId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getChannel(id: Long): ChannelEntity?

    @Query(
        """
        SELECT c.* FROM channels c
        WHERE c.providerId = :providerId
          AND c.groupTitle NOT IN (SELECT groupTitle FROM hidden_groups WHERE providerId = :providerId)
        ORDER BY c.sortIndex ASC, c.number ASC, c.name ASC
        """
    )
    suspend fun getVisibleChannels(providerId: Long): List<ChannelEntity>

    @Query("SELECT DISTINCT groupTitle FROM channels WHERE providerId = :providerId ORDER BY groupTitle ASC")
    suspend fun getGroups(providerId: Long): List<String>

    // --- Hidden groups ---
    @Query("SELECT * FROM hidden_groups WHERE providerId = :providerId")
    suspend fun getHiddenGroups(providerId: Long): List<HiddenGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hideGroup(row: HiddenGroupEntity)

    @Query("DELETE FROM hidden_groups WHERE providerId = :providerId AND groupTitle = :groupTitle")
    suspend fun unhideGroup(providerId: Long, groupTitle: String)
}
