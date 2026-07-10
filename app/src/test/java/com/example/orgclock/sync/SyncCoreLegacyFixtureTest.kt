package com.example.orgclock.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoreLegacyFixtureTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun commandAndResultFixtures_preserveV1DomainContract() {
        val command = json.parseToJsonElement(resource("clock-command-payload.json")).jsonObject
        val result = json.parseToJsonElement(resource("clock-result-payload.json")).jsonObject

        assertEquals(CLOCK_COMMAND_SCHEMA_V1, command.string("schema"))
        assertEquals("cmd-fixture-001", command.string("command_id"))
        assertEquals("clock.start", command.string("kind"))
        assertEquals("2026-03-01.org", command.objectValue("target").string("file_name"))
        assertEquals("Work/Project A", command.objectValue("target").string("heading_path"))
        assertEquals("clock.result.v1", result.string("schema"))
        assertEquals(command.string("command_id"), result.string("command_id"))
        assertEquals("applied", result.string("status"))
    }

    @Test
    fun envelopeFixture_preservesLegacyFieldNamesAndCanonicalInput() {
        val envelope = json.parseToJsonElement(resource("command-envelope.json")).jsonObject
        val canonical = listOf(
            envelope.string("schemaVersion"),
            envelope.string("messageType"),
            envelope.string("messageId"),
            envelope.string("senderDeviceId"),
            envelope.string("sentAtEpochMs"),
            envelope.string("nonce"),
            envelope.string("payloadJson"),
        ).joinToString("\n")

        assertEquals(1, envelope["schemaVersion"]?.jsonPrimitive?.content?.toInt())
        assertEquals("COMMAND", envelope.string("messageType"))
        assertEquals("device-a", envelope.string("senderDeviceId"))
        assertEquals(resource("command-envelope.canonical.txt").trimEnd(), canonical)
        assertTrue(envelope.string("signatureBase64").isNotBlank())
    }

    @Test
    fun schemaFixtures_freezeAdditiveRoomMigrationFromV1ToV2() {
        val v1 = resource("schema-v1.sql")
        val migration = resource("migration-1-2.sql")
        val v2 = resource("schema-v2.sql")

        val v1Tables = tableNames(v1)
        val v2Tables = tableNames(v2)

        assertEquals(
            setOf("sync_outgoing_queue", "sync_processed_results", "sync_delivery_events"),
            v1Tables,
        )
        assertEquals(v1Tables + "sync_incoming_replay", v2Tables)
        assertTrue(migration.contains("PRIMARY KEY(`senderDeviceId`, `commandId`)"))
        assertTrue(migration.contains("index_sync_incoming_replay_registeredAtEpochMs"))
        assertFalse(migration.contains("DROP TABLE", ignoreCase = true))
        assertFalse(migration.contains("DELETE FROM", ignoreCase = true))
    }

    private fun resource(name: String): String {
        val path = "synccore/legacy-v1/$name"
        return requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "Missing fixture resource: $path"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun JsonObject.string(name: String): String =
        requireNotNull(this[name]) { "Missing field: $name" }.jsonPrimitive.content

    private fun JsonObject.objectValue(name: String): JsonObject =
        requireNotNull(this[name]) { "Missing object: $name" }.jsonObject

    private fun tableNames(sql: String): Set<String> {
        val pattern = Regex("""CREATE TABLE IF NOT EXISTS `([^`]+)`""")
        return pattern.findAll(sql).map { it.groupValues[1] }.toSet()
    }
}
