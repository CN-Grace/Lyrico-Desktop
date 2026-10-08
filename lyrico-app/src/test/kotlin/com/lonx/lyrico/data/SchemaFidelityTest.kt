package com.lonx.lyrico.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the strongest promise the ported database makes: its schema is identical to the Android one,
 * so a database file written by the Android app (schema version 21) opens here with no migration.
 *
 * The check compares Room's own generated schema JSON, because that is the artifact Room validates a
 * real database against. If anyone changes an entity, this fails and names the table that drifted —
 * before a user's library does.
 */
class SchemaFidelityTest {

    @Test
    fun `desktop schema v1 is identical to the android schema v21`() {
        val desktop = readSchema(SCHEMA_DIR_PROPERTY, "1.json")
        val android = readSchema(ANDROID_SCHEMA_DIR_PROPERTY, "21.json")

        val desktopTables = tablesOf(desktop)
        val androidTables = tablesOf(android)

        assertEquals(
            androidTables.keys,
            desktopTables.keys,
            "the desktop database must declare exactly the tables the Android schema declares",
        )
        for (table in androidTables.keys) {
            assertEquals(androidTables[table], desktopTables[table], "table `$table` drifted")
        }
        assertEquals(
            android["identityHash"]!!.jsonPrimitive.content,
            desktop["identityHash"]!!.jsonPrimitive.content,
            "Room identity hash differs, so an Android database would not be accepted as-is",
        )
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun readSchema(directoryProperty: String, fileName: String): Map<String, JsonElement> {
        val file = File(
            System.getProperty(directoryProperty),
            "com.lonx.lyrico.data.LyricoDatabase/$fileName",
        )
        assertTrue(
            file.isFile,
            "missing schema file ${file.path} — run `./gradlew :lyrico-app:kspKotlin` to generate it",
        )
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    /**
     * The structural part of each entity: everything Room compares when it decides whether an
     * existing database file matches the schema, keyed by table name.
     */
    private fun tablesOf(database: Map<String, JsonElement>): Map<String, JsonElement> =
        database["entities"]!!
            .jsonArray
            .associate { entity ->
                val fields = entity.jsonObject
                fields["tableName"]!!.jsonPrimitive.content to entity
            }

    private companion object {
        const val SCHEMA_DIR_PROPERTY = "lyrico.schema.dir"
        const val ANDROID_SCHEMA_DIR_PROPERTY = "lyrico.android.schema.dir"
    }
}
