package com.chief.anansi.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [AnansiDatabase.MIGRATION_3_4] against the committed v4 export
 * schema (spec: Verification): the migration's CREATE TABLE statement must
 * equal the plugin_storage createSql the Room compiler generates, so a
 * fresh v4 install and a migrated v3 database have identical shapes.
 * Module-relative read (unit tests run in the app module directory); the
 * DAO surface is compiler-checked and carries no Robolectric test by repo
 * convention.
 */
class PluginStorageMigrationTest {

    @Test
    fun migrationSqlMatchesCommittedSchema() {
        val schema = File("schemas/com.chief.anansi.data.AnansiDatabase/4.json")
        val root = Json.parseToJsonElement(schema.readText()).jsonObject
        val database = root.getValue("database").jsonObject
        assertEquals("4", database.getValue("version").jsonPrimitive.content)
        val createSql = database.getValue("entities").jsonArray
            .map { it.jsonObject }
            .first { it.getValue("tableName").jsonPrimitive.content == "plugin_storage" }
            .getValue("createSql").jsonPrimitive.content
        assertEquals(
            normalize(AnansiDatabase.PLUGIN_STORAGE_CREATE_SQL),
            normalize(createSql),
        )
    }

    private fun normalize(sql: String): String =
        sql.replace("\${TABLE_NAME}", "plugin_storage").replace("IF NOT EXISTS ", "")
}
