package io.github.shgnaka.orgclock.synccore.benchmark

import io.github.shgnaka.orgclock.synccore.api.DeliveryState
import io.github.shgnaka.orgclock.synccore.api.DispatchOutcome
import io.github.shgnaka.orgclock.synccore.api.MessageId
import io.github.shgnaka.orgclock.synccore.api.PeerId
import io.github.shgnaka.orgclock.synccore.api.PeerRole
import io.github.shgnaka.orgclock.synccore.api.StartOutcome
import io.github.shgnaka.orgclock.synccore.api.StoreHealth
import io.github.shgnaka.orgclock.synccore.api.SubmitOutcome
import io.github.shgnaka.orgclock.synccore.api.SyncClock
import io.github.shgnaka.orgclock.synccore.api.SyncCoreFactory
import io.github.shgnaka.orgclock.synccore.api.SyncMessage
import io.github.shgnaka.orgclock.synccore.api.SyncStore
import io.github.shgnaka.orgclock.synccore.api.SyncStoreLoadResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreMetrics
import io.github.shgnaka.orgclock.synccore.api.SyncStoreOutgoingRecord
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSaveResult
import io.github.shgnaka.orgclock.synccore.api.SyncStoreSnapshot
import io.github.shgnaka.orgclock.synccore.api.SyncTransport
import io.github.shgnaka.orgclock.synccore.api.Topic
import io.github.shgnaka.orgclock.synccore.api.TrustedPeer
import io.github.shgnaka.orgclock.synccore.security.EnvelopeCodec
import io.github.shgnaka.orgclock.synccore.security.EnvelopeDecodeResult
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.Locale
import kotlin.math.ceil
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val WARMUP_ITERATIONS = 20
private const val MEASURE_ITERATIONS = 100
private const val NOW_EPOCH_MS = 1_700_000_000_000L

fun main(args: Array<String>) {
    val reportFile = args.firstOrNull()?.let(::File)
        ?: File("build/reports/sync-core/benchmark.txt")
    val results = runBlocking {
        listOf(
            measureCase(
                name = "queue_due_100_from_2000",
                thresholdP95Ms = 100.0,
                prepareOperation = queueDue100From2000Operation(),
            ),
            measureCase(
                name = "envelope_validation_128k",
                thresholdP95Ms = 50.0,
                prepareOperation = envelopeValidation128kOperation(),
            ),
            measureCase(
                name = "submit_durable_write",
                thresholdP95Ms = 100.0,
                prepareOperation = submitDurableWriteOperation(),
            ),
        )
    }
    val passed = results.all { it.p95Ms <= it.thresholdP95Ms }
    reportFile.parentFile.mkdirs()
    reportFile.writeText(renderReport(results, passed))
    println("sync-core benchmark report written to ${reportFile.absolutePath}")
    if (!passed) {
        exitProcess(1)
    }
}

private suspend fun measureCase(
    name: String,
    thresholdP95Ms: Double,
    prepareOperation: suspend () -> suspend () -> Unit,
): BenchmarkResult {
    repeat(WARMUP_ITERATIONS) { prepareOperation()() }
    val durations = LongArray(MEASURE_ITERATIONS)
    repeat(MEASURE_ITERATIONS) { index ->
        val operation = prepareOperation()
        val started = System.nanoTime()
        operation()
        durations[index] = System.nanoTime() - started
    }
    durations.sort()
    return BenchmarkResult(
        name = name,
        thresholdP95Ms = thresholdP95Ms,
        p50Ms = durations.percentile(0.50).nanosToMillis(),
        p95Ms = durations.percentile(0.95).nanosToMillis(),
        maxMs = durations.last().nanosToMillis(),
    )
}

private fun queueDue100From2000Operation(): suspend () -> suspend () -> Unit {
    val snapshot = queueDue100From2000Snapshot()
    return {
        val core = SyncCoreFactory.createPersistent(
            store = BenchmarkStore(snapshot),
            clock = FixedClock(NOW_EPOCH_MS),
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
        )
        check(core.start() is StartOutcome.Started)
        val operation: suspend () -> Unit = {
            val summary = core.flushDue()
            check(summary.dispatched == 100) { "expected 100 dispatched, got ${summary.dispatched}" }
            check(summary.accepted == 100) { "expected 100 accepted, got ${summary.accepted}" }
        }
        operation
    }
}

private fun queueDue100From2000Snapshot(): SyncStoreSnapshot {
    val outgoing = mutableListOf<SyncStoreOutgoingRecord>()
    var sequence = 1L
    repeat(100) { peerIndex ->
        val peerId = PeerId("peer-$peerIndex")
        val due = message("due-$peerIndex", peerId, NOW_EPOCH_MS - 1_000L)
        outgoing += SyncStoreOutgoingRecord(
            sequence = sequence++,
            message = due,
            state = DeliveryState.Pending,
            attempt = 0,
            nextAttemptAtEpochMs = due.createdAtEpochMs,
            lastError = null,
            leaseUntilEpochMs = null,
        )
        repeat(19) { futureIndex ->
            val futureCreatedAt = NOW_EPOCH_MS + 60_000L + futureIndex
            val future = message("future-$peerIndex-$futureIndex", peerId, futureCreatedAt)
            outgoing += SyncStoreOutgoingRecord(
                sequence = sequence++,
                message = future,
                state = DeliveryState.Pending,
                attempt = 0,
                nextAttemptAtEpochMs = future.createdAtEpochMs,
                lastError = null,
                leaseUntilEpochMs = null,
            )
        }
    }
    return SyncStoreSnapshot(
        outgoing = outgoing,
        metrics = SyncStoreMetrics(submittedTotal = outgoing.size.toLong()),
        nextRecordSequence = sequence,
    )
}

private fun envelopeValidation128kOperation(): suspend () -> suspend () -> Unit {
    val fixture = EnvelopeFixture.create()
    val codec = EnvelopeCodec(clock = FixedClock(fixture.sentAtEpochMs))
    return {
        val operation: suspend () -> Unit = {
            val decoded = codec.decodeAndVerifyCurrent(fixture.rawJson, fixture.trustedPeer)
            check(decoded is EnvelopeDecodeResult.Accepted) { "expected accepted envelope, got $decoded" }
        }
        operation
    }
}

private fun submitDurableWriteOperation(): suspend () -> suspend () -> Unit {
    var counter = 0
    return {
        val clock = FixedClock(NOW_EPOCH_MS)
        val core = SyncCoreFactory.createPersistent(
            store = BenchmarkStore(),
            clock = clock,
            transport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
        )
        check(core.start() is StartOutcome.Started)
        val id = "durable-${counter++}"
        val preparedMessage = message(id, PeerId("peer-durable"), NOW_EPOCH_MS)
        val operation: suspend () -> Unit = {
            check(core.submit(preparedMessage) == SubmitOutcome.Submitted)
        }
        operation
    }
}

private fun message(id: String, peerId: PeerId, createdAtEpochMs: Long): SyncMessage = SyncMessage(
    messageId = MessageId(id),
    topic = Topic("clock.command.v1"),
    payloadJson = """{"op":"start","id":"$id"}""",
    targetPeerId = peerId,
    createdAtEpochMs = createdAtEpochMs,
    expiresAtEpochMs = createdAtEpochMs + 86_400_000L,
)

private class FixedClock(private val now: Long) : SyncClock {
    override fun nowEpochMs(): Long = now
}

private class BenchmarkStore(initialSnapshot: SyncStoreSnapshot = SyncStoreSnapshot()) : SyncStore {
    private var snapshot = initialSnapshot.deepCopy()

    override suspend fun load(): SyncStoreLoadResult = SyncStoreLoadResult.Loaded(snapshot.deepCopy())

    override suspend fun save(snapshot: SyncStoreSnapshot): SyncStoreSaveResult {
        this.snapshot = snapshot.deepCopy()
        return SyncStoreSaveResult.Saved
    }

    override suspend fun healthCheck(): StoreHealth = StoreHealth.Healthy
}

private fun SyncStoreSnapshot.deepCopy(): SyncStoreSnapshot = copy(
    outgoing = outgoing.toList(),
    incoming = incoming.toList(),
    deliveryEvents = deliveryEvents.toList(),
    metrics = metrics.copy(
        lastSuccessfulDispatchByPeer = metrics.lastSuccessfulDispatchByPeer.toMap(),
    ),
)

private data class EnvelopeFixture(
    val rawJson: String,
    val trustedPeer: TrustedPeer,
    val sentAtEpochMs: Long,
) {
    companion object {
        fun create(): EnvelopeFixture {
            val keyPair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            }.generateKeyPair()
            val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
            val sentAtEpochMs = NOW_EPOCH_MS
            val payloadJson = Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject { put("data", "x".repeat(96 * 1024 - 32)) },
            )
            val payloadSha256 = sha256Hex(payloadJson)
            val canonicalInput = canonicalInput(
                schemaVersion = 1,
                alg = "ES256",
                envelopeId = "env-benchmark",
                messageId = "cmd-benchmark",
                senderPeerId = "peer-benchmark",
                targetPeerId = "peer-local",
                topic = "clock.command.v1",
                sentAtEpochMs = sentAtEpochMs,
                nonce = "nonce-benchmark",
                payloadSha256 = payloadSha256,
            )
            val signatureBase64 = sign(keyPair, canonicalInput)
            val rawJson = buildEnvelopeJson(
                payloadJson = payloadJson,
                payloadSha256 = payloadSha256,
                signatureBase64 = signatureBase64,
                sentAtEpochMs = sentAtEpochMs,
            )
            return EnvelopeFixture(
                rawJson = rawJson,
                trustedPeer = TrustedPeer(
                    peerId = PeerId("peer-benchmark"),
                    deviceId = "device-benchmark",
                    displayName = "Benchmark",
                    signingPublicKeyBase64 = publicKeyBase64,
                    role = PeerRole.Full,
                    endpoint = null,
                    active = true,
                    signingAlg = "ES256",
                ),
                sentAtEpochMs = sentAtEpochMs,
            )
        }

        private fun buildEnvelopeJson(
            payloadJson: String,
            payloadSha256: String,
            signatureBase64: String,
            sentAtEpochMs: Long,
        ): String {
            fun encode(paddingLength: Int): String = Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    put("schemaVersion", 1)
                    put("alg", "ES256")
                    put("envelopeId", "env-benchmark")
                    put("messageId", "cmd-benchmark")
                    put("senderPeerId", "peer-benchmark")
                    put("targetPeerId", "peer-local")
                    put("topic", "clock.command.v1")
                    put("payloadJson", payloadJson)
                    put("sentAtEpochMs", sentAtEpochMs)
                    put("nonce", "nonce-benchmark")
                    put("payloadSha256", payloadSha256)
                    put("signatureBase64", signatureBase64)
                    put("padding", "p".repeat(paddingLength.coerceAtLeast(0)))
                },
            )
            val targetBytes = 128 * 1024
            var padding = 0
            var encoded = encode(padding)
            padding = (targetBytes - encoded.encodeToByteArray().size).coerceAtLeast(0)
            encoded = encode(padding)
            while (encoded.encodeToByteArray().size > targetBytes && padding > 0) {
                padding -= 1
                encoded = encode(padding)
            }
            while (encoded.encodeToByteArray().size < targetBytes) {
                padding += 1
                encoded = encode(padding)
            }
            return encoded
        }
    }
}

private fun canonicalInput(
    schemaVersion: Int,
    alg: String,
    envelopeId: String,
    messageId: String,
    senderPeerId: String,
    targetPeerId: String,
    topic: String,
    sentAtEpochMs: Long,
    nonce: String,
    payloadSha256: String,
): String = buildString {
    append("schemaVersion=").append(schemaVersion).append('\n')
    append("alg=").append(alg).append('\n')
    append("envelopeId=").append(envelopeId).append('\n')
    append("messageId=").append(messageId).append('\n')
    append("senderPeerId=").append(senderPeerId).append('\n')
    append("targetPeerId=").append(targetPeerId).append('\n')
    append("topic=").append(topic).append('\n')
    append("sentAtEpochMs=").append(sentAtEpochMs).append('\n')
    append("nonce=").append(nonce).append('\n')
    append("payloadSha256=").append(payloadSha256)
}

private fun sign(keyPair: KeyPair, canonicalInput: String): String {
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(keyPair.private)
    signer.update(canonicalInput.toByteArray(Charsets.UTF_8))
    return Base64.getEncoder().encodeToString(ecdsaDerToJoseRaw(signer.sign()))
}

private fun ecdsaDerToJoseRaw(der: ByteArray, partSize: Int = 32): ByteArray {
    require(der.size >= 8 && der[0] == 0x30.toByte()) { "Invalid ECDSA DER signature." }
    var index = 2
    require(der[index] == 0x02.toByte()) { "Invalid ECDSA DER integer." }
    val rLength = der[index + 1].toInt() and 0xff
    val r = der.copyOfRange(index + 2, index + 2 + rLength)
    index += 2 + rLength
    require(der[index] == 0x02.toByte()) { "Invalid ECDSA DER integer." }
    val sLength = der[index + 1].toInt() and 0xff
    val s = der.copyOfRange(index + 2, index + 2 + sLength)
    return unsignedFixed(r, partSize) + unsignedFixed(s, partSize)
}

private fun unsignedFixed(value: ByteArray, size: Int): ByteArray {
    val stripped = value.dropWhile { it == 0.toByte() }.toByteArray()
    require(stripped.size <= size) { "ECDSA integer is too large." }
    return ByteArray(size - stripped.size) + stripped
}

private fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private data class BenchmarkResult(
    val name: String,
    val thresholdP95Ms: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val maxMs: Double,
)

private fun LongArray.percentile(percentile: Double): Long {
    val index = ceil((size * percentile)).toInt().coerceIn(1, size) - 1
    return this[index]
}

private fun Long.nanosToMillis(): Double = this / 1_000_000.0

private fun renderReport(results: List<BenchmarkResult>, passed: Boolean): String = buildString {
    appendLine("sync-core benchmark gate")
    appendLine("generatedAt=${Instant.now()}")
    appendLine("status=${if (passed) "passed" else "failed"}")
    appendLine("warmupIterations=$WARMUP_ITERATIONS")
    appendLine("measureIterations=$MEASURE_ITERATIONS")
    appendLine("javaVersion=${System.getProperty("java.version")}")
    appendLine("os=${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
    appendLine()
    appendLine("| case | p50_ms | p95_ms | max_ms | threshold_p95_ms | status |")
    appendLine("|---|---:|---:|---:|---:|---|")
    results.forEach { result ->
        appendLine(
            "| ${result.name} | ${result.p50Ms.format()} | ${result.p95Ms.format()} | " +
                "${result.maxMs.format()} | ${result.thresholdP95Ms.format()} | " +
                "${if (result.p95Ms <= result.thresholdP95Ms) "passed" else "failed"} |",
        )
    }
}

private fun Double.format(): String = String.format(Locale.US, "%.3f", this)
