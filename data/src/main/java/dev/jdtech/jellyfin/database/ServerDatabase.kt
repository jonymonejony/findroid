package dev.jdtech.jellyfin.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.DeleteTable
import androidx.room.RenameColumn
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import dev.jdtech.jellyfin.models.FindroidEpisodeDto
import dev.jdtech.jellyfin.models.FindroidMediaStreamDto
import dev.jdtech.jellyfin.models.FindroidMovieDto
import dev.jdtech.jellyfin.models.FindroidSeasonDto
import dev.jdtech.jellyfin.models.FindroidSegmentDto
import dev.jdtech.jellyfin.models.FindroidShowDto
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.models.FindroidTrickplayInfoDto
import dev.jdtech.jellyfin.models.FindroidUserDataDto
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.User

@Database(
    entities = [
        Server::class,
        ServerAddress::class,
        User::class,
        FindroidMovieDto::class,
        FindroidShowDto::class,
        FindroidSeasonDto::class,
        FindroidEpisodeDto::class,
        FindroidSourceDto::class,
        FindroidMediaStreamDto::class,
        FindroidUserDataDto::class,
        FindroidTrickplayInfoDto::class,
        FindroidSegmentDto::class,
    ],
    version = 8,
    autoMigrations = [
        // FIX: Attached the spec here where 'address' actually disappears (1 -> 2)
        AutoMigration(from = 1, to = 2, spec = ServerDatabase.SchemaChange1to2::class),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = ServerDatabase.TrickplayMigration::class),
        AutoMigration(from = 5, to = 6, spec = ServerDatabase.IntrosMigration::class),

        // The 'segments' table schema changes happen here (6 -> 7)
        AutoMigration(from = 6, to = 7, spec = ServerDatabase.SchemaChange6to7::class),
        AutoMigration(from = 7, to = 8),
    ],
)
@TypeConverters(Converters::class)
abstract class ServerDatabase : RoomDatabase() {
    abstract fun getServerDatabaseDao(): ServerDatabaseDao

    @DeleteTable(tableName = "trickPlayManifests")
    class TrickplayMigration : AutoMigrationSpec

    @DeleteTable(tableName = "intros")
    class IntrosMigration : AutoMigrationSpec

    // FIX: New spec specifically for the 1 -> 2 transition
    @DeleteColumn.Entries(
        DeleteColumn(tableName = "servers", columnName = "address")
    )
    class SchemaChange1to2 : AutoMigrationSpec

    // FIX: Removed the 'servers.address' deletion.
    // This spec now ONLY handles the 'segments' table changes for 6 -> 7.
    @DeleteColumn.Entries(
        DeleteColumn(tableName = "segments", columnName = "showAt"),
        DeleteColumn(tableName = "segments", columnName = "hideAt")
    )
    @RenameColumn.Entries(
        RenameColumn(tableName = "segments", fromColumnName = "startTime", toColumnName = "startTicks"),
        RenameColumn(tableName = "segments", fromColumnName = "endTime", toColumnName = "endTicks")
    )
    class SchemaChange6to7 : AutoMigrationSpec
}