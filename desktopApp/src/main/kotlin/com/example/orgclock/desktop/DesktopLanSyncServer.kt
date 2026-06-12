package com.example.orgclock.desktop

import com.example.orgclock.sync.AppendClockEventResult
import com.example.orgclock.sync.ClockEventFetchResponse
import com.example.orgclock.sync.ClockEventPushResponse
import com.example.orgclock.sync.ClockEventStore
import com.example.orgclock.sync.ClockEventTransportAckResult
import com.example.orgclock.sync.ClockEventTransportJsonCodec
import com.example.orgclock.sync.PeerTrustRecord
import com.example.orgclock.sync.RemoteClockEventApplier
import com.example.orgclock.sync.SyncPairingExchangeJsonCodec
import com.example.orgclock.sync.SyncPairingExchangeResponse
import com.example.orgclock.sync.SyncTransportCredentialCodec
import com.example.orgclock.template.SharedTemplateStore
import com.example.orgclock.template.TemplateFetchResponse
import com.example.orgclock.template.TemplateSharingJsonCodec
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.runBlocking

open class DesktopLanSyncServer(
    private val port: Int = DesktopSyncIdentity.DEFAULT_PORT,
    private val localDeviceId: () -> String,
    private val eventStore: () -> ClockEventStore?,
    private val trustStore: () -> PeerTrustStore?,
    private val tlsIdentity: () -> DesktopTlsIdentity,
    private val pairingManager: DesktopPairingManager,
    private val remoteEventApplier: RemoteClockEventApplier,
    private val templateStore: () -> SharedTemplateStore?,
) : AutoCloseable {
    private var server: HttpsServer? = null
    private var executor: ExecutorService? = null

    open fun start() {
        if (server != null) return
        val executor = Executors.newFixedThreadPool(4) { Thread(it, "org-clock-lan-sync").apply { isDaemon = true } }
        this.executor = executor
        try {
            server = HttpsServer.create(InetSocketAddress("0.0.0.0", port), 0).apply {
            httpsConfigurator = HttpsConfigurator(tlsIdentity().sslContext)
            this.executor = executor
            createContext("/v1/health") { exchange ->
                if (exchange.requestMethod != "GET") exchange.respond(405, "method not allowed")
                else exchange.respond(200, "ok", "text/plain; charset=utf-8")
            }
            createContext("/v1/pair") { exchange -> handlePost(exchange) { body ->
                val request = SyncPairingExchangeJsonCodec.decodeRequest(body)
                val credential = pairingManager.exchange(
                    request,
                    trustStore() ?: error("org root is not open"),
                    tlsIdentity().certificateSha256,
                )
                SyncPairingExchangeJsonCodec.encodeResponse(SyncPairingExchangeResponse(credential))
            } }
            createContext("/v1/events/fetch") { exchange -> handleAuthorized(exchange) { body, peer ->
                val request = ClockEventTransportJsonCodec.decodeFetchRequest(body)
                requireSource(peer, request.sourcePeerId)
                requireTarget(request.targetPeerId)
                val scanned = runBlocking {
                    (eventStore() ?: error("org root is not open")).listSince(request.sinceCursor, request.batchLimit * 4)
                }
                val events = scanned.filter { it.event.deviceId == localDeviceId() }.take(request.batchLimit)
                ClockEventTransportJsonCodec.encodeFetchResponse(
                    ClockEventFetchResponse(
                        sourcePeerId = localDeviceId(),
                        targetPeerId = request.sourcePeerId,
                        events = events,
                        hasMore = scanned.size == request.batchLimit * 4 || events.size == request.batchLimit,
                    ),
                )
            } }
            createContext("/v1/events/push") { exchange -> handleAuthorized(exchange) { body, peer ->
                val request = ClockEventTransportJsonCodec.decodePushRequest(body)
                requireSource(peer, request.sourcePeerId)
                requireTarget(request.targetPeerId)
                require(request.events.all { it.event.deviceId == peer.deviceId }) { "event device does not match paired device" }
                val store = eventStore() ?: error("org root is not open")
                val duplicates = mutableListOf<String>()
                runBlocking {
                    request.events.forEach { stored ->
                        if (store.contains(stored.event.eventId)) {
                            duplicates += stored.event.eventId
                        } else {
                            remoteEventApplier.apply(stored.event).getOrThrow()
                            if (store.append(stored.event) is AppendClockEventResult.Duplicate) {
                                duplicates += stored.event.eventId
                            }
                        }
                    }
                }
                ClockEventTransportJsonCodec.encodePushResponse(
                    ClockEventPushResponse(sourcePeerId = localDeviceId(), targetPeerId = request.sourcePeerId, acceptedCursor = request.events.lastOrNull()?.cursor, duplicateEventIds = duplicates),
                )
            } }
            createContext("/v1/events/ack") { exchange -> handleAuthorized(exchange) { body, peer ->
                val ack = ClockEventTransportJsonCodec.decodeAck(body)
                requireSource(peer, ack.sourcePeerId)
                requireTarget(ack.targetPeerId)
                ClockEventTransportJsonCodec.encodeAckResult(ClockEventTransportAckResult.Accepted)
            } }
            createContext("/v1/template/fetch") { exchange -> handleAuthorized(exchange) { body, peer ->
                require(peer.role == com.example.orgclock.sync.PeerTrustRole.Full) { "viewer peers cannot access templates" }
                val request = TemplateSharingJsonCodec.decodeFetchRequest(body)
                requireSource(peer, request.sourcePeerId)
                requireTarget(request.targetPeerId)
                val revision = runBlocking {
                    (templateStore() ?: error("template store is unavailable")).readRevision().getOrThrow()
                }
                TemplateSharingJsonCodec.encodeFetchResponse(
                    TemplateFetchResponse(localDeviceId(), request.sourcePeerId, revision),
                )
            } }
            createContext("/v1/template/push") { exchange -> handleAuthorized(exchange) { body, peer ->
                require(peer.role == com.example.orgclock.sync.PeerTrustRole.Full) { "viewer peers cannot update templates" }
                val request = TemplateSharingJsonCodec.decodePushRequest(body)
                requireSource(peer, request.sourcePeerId)
                requireTarget(request.targetPeerId)
                val result = runBlocking {
                    (templateStore() ?: error("template store is unavailable"))
                        .writeRevision(request.revision, request.expectedCurrentRevisionId)
                        .getOrThrow()
                }
                TemplateSharingJsonCodec.encodePushResult(result)
            } }
                start()
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override open fun close() {
        server?.stop(0)
        server = null
        executor?.shutdownNow()
        executor = null
    }

    private fun handlePost(exchange: HttpExchange, handler: (String) -> String) {
        if (exchange.requestMethod != "POST") return exchange.respond(405, "method not allowed")
        runCatching { handler(exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }) }
            .onSuccess { exchange.respond(200, it) }.onFailure { exchange.respond(400, it.message ?: "invalid request") }
    }

    private fun handleAuthorized(exchange: HttpExchange, handler: (String, PeerTrustRecord) -> String) {
        if (exchange.requestMethod != "POST") return exchange.respond(405, "method not allowed")
        val supplied = exchange.requestHeaders.getFirst(DesktopLanSyncTransport.PAIRING_HEADER).orEmpty()
        val peer = trustStore()?.listTrustRecords()?.firstOrNull { record ->
            record.isActive && SyncTransportCredentialCodec.decode(record.publicKeyBase64).getOrNull()?.pairingSecret?.let { secureEquals(supplied, it) } == true
        } ?: return exchange.respond(401, "invalid pairing credential")
        runCatching { handler(exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }, peer) }
            .onSuccess { exchange.respond(200, it) }.onFailure { exchange.respond(400, it.message ?: "invalid sync request") }
    }

    private fun requireSource(peer: PeerTrustRecord, sourcePeerId: String) = require(sourcePeerId == peer.deviceId) { "source peer mismatch" }
    private fun requireTarget(targetPeerId: String?) = require(targetPeerId == localDeviceId()) { "target peer mismatch" }
    private fun secureEquals(left: String, right: String) = MessageDigest.isEqual(left.encodeToByteArray(), right.encodeToByteArray())
    private fun HttpExchange.respond(status: Int, body: String, type: String = "application/json; charset=utf-8") {
        val bytes = body.encodeToByteArray(); responseHeaders.set("Content-Type", type); sendResponseHeaders(status, bytes.size.toLong()); responseBody.use { it.write(bytes) }
    }
}
