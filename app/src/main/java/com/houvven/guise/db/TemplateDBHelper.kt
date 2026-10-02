package com.houvven.guise.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.houvven.guise.ContextAmbient

@Database(entities = [Template::class, BundledTemplateState::class], version = 5)
abstract class TemplateDBHelper : RoomDatabase() {

    abstract fun templateDao(): TemplateDao

    companion object {
        private val db: TemplateDBHelper by lazy {
            Room.databaseBuilder(
                ContextAmbient.current,
                TemplateDBHelper::class.java,
                "template.db"
            )
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }

        val templateDao by lazy { db.templateDao() }

        fun runInTransaction(block: () -> Unit) {
            db.runInTransaction { block() }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `BundledTemplateState` (
                        `seedId` TEXT NOT NULL,
                        `templateId` TEXT NOT NULL,
                        `installedVersion` INTEGER NOT NULL,
                        `installedFingerprint` TEXT NOT NULL,
                        `deleted` INTEGER NOT NULL,
                        `managed` INTEGER NOT NULL,
                        PRIMARY KEY(`seedId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `Template` ADD COLUMN `groupName` TEXT")
            }
        }

    }
}
