package com.example.orgclock.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSyncCommandPublisherTest {
    @Test
    fun buildClockCommandPayloadJson_usesProvidedFileNameAndHeadingPath() {
        val payload = buildClockCommandPayloadJson(
            commandId = "cmd-1",
            kind = ClockCommandKind.Start,
            target = ClockPublishTarget(
                fileName = "2026-03-01.org",
                headingPath = "Work/Project A",
            ),
            deviceId = "device-a",
        )

        val json = Json.parseToJsonElement(payload).jsonObject
        val target = json.getValue("target").jsonObject

        assertEquals("clock.command.v1", json.getValue("schema").jsonPrimitive.content)
        assertEquals("clock.start", json.getValue("kind").jsonPrimitive.content)
        assertEquals("2026-03-01.org", target.getValue("file_name").jsonPrimitive.content)
        assertEquals("Work/Project A", target.getValue("heading_path").jsonPrimitive.content)
        assertEquals("device-a", json.getValue("from_device_id").jsonPrimitive.content)
        assertEquals("cmd-1", json.getValue("request_id").jsonPrimitive.content)
    }
}
