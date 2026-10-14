package dev.fonebrew.spike.fb1

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** A table whose column goes through a `@TypeConverter` (Map<String,String> <-> TEXT), like core's metadata columns. */
@Entity(tableName = "converter_probe")
data class ConverterProbeEntity(
    @PrimaryKey val id: String,
    val metadata: Map<String, String>,
)

@Dao
interface ConverterProbeDao {
    @Insert
    suspend fun insert(row: ConverterProbeEntity)

    @Upsert
    suspend fun upsert(row: ConverterProbeEntity)

    @Query("SELECT * FROM converter_probe WHERE id = :id")
    suspend fun get(id: String): ConverterProbeEntity?
}
