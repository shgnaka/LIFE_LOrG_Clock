package com.example.orgclock.sync

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCommandValidationEngineTest {
    @Test
    fun messagesPath_rejectsDuplicateByReplayRegistry() = runBlocking {
        val replay = RecordingReplayRegistry()
        val engine = IncomingCommandValidationEngine(
            allowedSkewSeconds = 300,
            replayRegistry = replay,
        )
        val verifier = fixedVerifier(commandId = "cmd-1", sender = "device-a")

        val first = engine.extractClockCommandPayload("/v1/messages", "{}", verifier)
        val second = engine.extractClockCommandPayload("/v1/messages", "{}", verifier)

        assertTrue(first.isSuccess)
        assertFalse(second.isSuccess)
    }

    @Test
    fun incomingCommandPath_skipsReplayRegistry() = runBlocking {
        val replay = object : ReplayRegistryPort {
            override suspend fun register(senderDeviceId: String, commandId: String): Boolean = false
        }
        val engine = IncomingCommandValidationEngine(
            allowedSkewSeconds = 300,
            replayRegistry = replay,
        )

        val first = engine.extractClockCommandPayload("/v1/incoming-command", payload(commandId = "cmd-1"), fixedVerifier())
        val second = engine.extractClockCommandPayload("/v1/incoming-command", payload(commandId = "cmd-1"), fixedVerifier())

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
    }

    @Test
    fun messagesPath_rejectsWhenSenderMismatch() = runBlocking {
        val replay = RecordingReplayRegistry()
        val engine = IncomingCommandValidationEngine(
            allowedSkewSeconds = 300,
            replayRegistry = replay,
        )
        val verifier = object : IncomingEnvelopeVerifierPort {
            override fun verifyCommandEnvelope(rawEnvelopeJson: String): Result<IncomingCommandPayloadContext> {
                return Result.success(
                    IncomingCommandPayloadContext(
                        payloadJson = payload(commandId = "cmd-1", fromDevice = "device-a"),
                        verifiedPeerId = "peer-a",
                        signatureKeyId = "sig-1",
                        expectedSenderDeviceId = "device-b",
                        verificationMethod = "envelope-signature+schema+sender+timestamp+replay",
                    ),
                )
            }
        }

        val result = engine.extractClockCommandPayload("/v1/messages", "{}", verifier)

        assertFalse(result.isSuccess)
    }

    @Test
    fun messagesPath_setsVerificationMetadataFromEnvelopeContext() = runBlocking {
        val replay = RecordingReplayRegistry()
        val engine = IncomingCommandValidationEngine(
            allowedSkewSeconds = 300,
            replayRegistry = replay,
        )
        val verifier = fixedVerifier(commandId = "cmd-9", sender = "device-z", peer = "peer-z", signatureKeyId = "sig-z")

        val result = engine.extractClockCommandPayload("/v1/messages", "{}", verifier)

        assertTrue(result.isSuccess)
        val verified = result.getOrThrow()
        assertTrue(verified.verificationMethod!!.contains("envelope-signature"))
        assertTrue(verified.verifiedPeerId == "peer-z")
        assertTrue(verified.signatureKeyId == "sig-z")
        assertTrue(verified.replayCheckPassed)
    }

    private fun fixedVerifier(
        commandId: String = "cmd-1",
        sender: String = "device-a",
        peer: String = "peer-a",
        signatureKeyId: String = "sig-1",
    ): IncomingEnvelopeVerifierPort {
        return object : IncomingEnvelopeVerifierPort {
            override fun verifyCommandEnvelope(rawEnvelopeJson: String): Result<IncomingCommandPayloadContext> {
                return Result.success(
                    IncomingCommandPayloadContext(
                        payloadJson = payload(commandId = commandId, fromDevice = sender),
                        verifiedPeerId = peer,
                        signatureKeyId = signatureKeyId,
                        expectedSenderDeviceId = sender,
                        verificationMethod = "envelope-signature+schema+sender+timestamp+replay",
                    ),
                )
            }
        }
    }

    private fun payload(commandId: String, fromDevice: String = "device-a"): String {
        val requestedAt = Clock.System.now().toString()
        return """
            {
              "schema": "clock.command.v1",
              "command_id": "$commandId",
              "kind": "clock.start",
              "target": {
                "file_name": "2026-03-01.org",
                "heading_path": "Work/Project A"
              },
              "requested_at": "$requestedAt",
              "from_device_id": "$fromDevice"
            }
        """.trimIndent()
    }
}

private class RecordingReplayRegistry : ReplayRegistryPort {
    private val seen = linkedSetOf<String>()

    override suspend fun register(senderDeviceId: String, commandId: String): Boolean {
        val key = "$senderDeviceId::$commandId"
        if (seen.contains(key)) return false
        seen.add(key)
        return true
    }
}
