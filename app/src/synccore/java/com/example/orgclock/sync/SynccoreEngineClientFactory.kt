package com.example.orgclock.sync

import android.content.Context
import com.example.orgclock.data.ClockRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.notification.NotificationPrefs
import com.example.orgclock.time.ClockEnvironment
import io.github.shgnaka.synccore.engine.DispatchOutcome
import io.github.shgnaka.synccore.engine.EngineStore
import io.github.shgnaka.synccore.engine.MessageDispatcher
import io.github.shgnaka.synccore.engine.SyncCoreEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.logging.Logger

class SynccoreEngineClientFactory : SyncCoreClientFactory {
    override fun create(
        appContext: Context,
        repository: ClockRepository,
        clockService: ClockService,
        clockEnvironment: ClockEnvironment,
    ): OrgSyncCoreClient {
        val sharedPrefs = appContext.getSharedPreferences(NotificationPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val database = SyncQueueDatabaseFactory.create(appContext)
        val store: EngineStore = RoomEngineStore(database.syncQueueDao())
        val runtimePrefs = SharedPreferencesSyncRuntimePrefs(
            sharedPrefs,
        )
        val deviceIdProvider = SharedPreferencesDeviceIdProvider(
            sharedPrefs,
        )
        val peerTrustStore = SharedPreferencesPeerTrustStore(sharedPrefs)
        val engine = SyncCoreEngine(
            store = store,
            dispatcher = GatewayDispatcher(
                HttpSyncTransportGateway(
                    localDeviceIdProvider = { deviceIdProvider.getOrCreate() },
                    resultEnvelopeSigner = AndroidKeystoreResultEnvelopeSigner(
                        keyAliasProvider = { runtimePrefs.resultSigningKeyAlias() },
                    ),
                ),
            ),
            ioDispatcher = Dispatchers.Default,
            flushIntervalMs = 1_000L,
        )
        return EngineBackedOrgSyncCoreClient(
            engine = engine,
            incomingCommandSource = HttpIncomingCommandSource(
                allowedSkewSeconds = runtimePrefs.inboundClockSkewSeconds(),
                maxRequestsPerMinute = runtimePrefs.inboundMaxRequestsPerMinute(),
                incomingEnvelopeVerifier = Ed25519IncomingEnvelopeVerifier(peerTrustStore),
                replayRegistry = PersistentReplayRegistry(database.syncQueueDao()),
            ),
        )
    }
}
private class GatewayDispatcher(
    private val gateway: SyncTransportGateway,
) : MessageDispatcher {
    override suspend fun dispatch(command: io.github.shgnaka.synccore.api.SyncCommand): DispatchOutcome {
        return when (val result = gateway.dispatch(command)) {
            is TransportDispatchResult.Accepted -> DispatchOutcome.Accepted(result.result)
            is TransportDispatchResult.RetryableFailure -> DispatchOutcome.RetryableFailure(
                errorCode = result.errorCode,
                errorMessage = result.errorMessage,
            )
            is TransportDispatchResult.Rejected -> DispatchOutcome.Rejected(
                errorCode = result.errorCode,
                errorMessage = result.errorMessage,
            )
        }
    }
}

private class EngineBackedOrgSyncCoreClient(
    private val engine: SyncCoreEngine,
    private val incomingCommandSource: IncomingCommandSource,
    private val json: Json = Json,
) : OrgSyncCoreClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val deliveryStates = ArrayDeque<SyncDeliveryState>()
    private val incomingCommands = IncomingCommandBuffer(MAX_INCOMING_COMMAND_HISTORY)
    private val commandRouting = linkedMapOf<String, String>()
    private var started = false
    private var collectorJob: Job? = null

    override suspend fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        engine.start()
        incomingCommandSource.start { payload ->
            if (incomingCommands.add(payload)) {
                logger.fine("sync.incoming.queue_overflow dropped_oldest=true")
            }
        }
        collectorJob?.cancel()
        collectorJob = scope.launch {
            engine.observeDeliveryState().collectLatest { event ->
                synchronized(lock) {
                    if (deliveryStates.size >= MAX_DELIVERY_STATE_HISTORY) deliveryStates.removeFirst()
                    deliveryStates.addLast(
                        SyncDeliveryState(
                            commandId = event.commandId,
                            state = event.state.name,
                            detail = event.detail,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun stop() {
        synchronized(lock) {
            started = false
        }
        collectorJob?.cancel()
        collectorJob = null
        incomingCommandSource.stop()
        engine.stop()
    }

    override suspend fun flushNow() {
        engine.flushNow()
    }

    override suspend fun submitOutgoing(command: OutgoingClockCommand): SubmitResult {
        return runCatching {
            engine.submit(
                io.github.shgnaka.synccore.api.SyncCommand(
                    commandId = command.commandId,
                    topic = CLOCK_COMMAND_SCHEMA_V1,
                    payloadJson = command.payloadJson,
                    targetPeerId = command.targetPeerId,
                    createdAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                ),
            ).fold(
                onSuccess = { SubmitResult.Submitted },
                onFailure = { SubmitResult.Failed(it.message ?: "submit failed") },
            )
        }.getOrElse { SubmitResult.Failed(it.message ?: "submit failed") }
    }

    override suspend fun observeIncomingCommands(): List<VerifiedIncomingCommand> = incomingCommands.drainAll()
        .also { drained ->
            synchronized(lock) {
                for (command in drained) {
                    val route = command.verifiedPeerId ?: command.peerId
                    if (!route.isNullOrBlank()) {
                        commandRouting[command.commandId] = route
                    }
                }
                trimRoutingHistory()
            }
        }

    override suspend fun reportResult(result: ClockResultPayload) {
        val route = synchronized(lock) {
            commandRouting[result.commandId]
        } ?: run {
            logger.fine("sync.result.route_missing commandId=${result.commandId}")
            return
        }
        engine.submit(
            io.github.shgnaka.synccore.api.SyncCommand(
                commandId = "result-${result.commandId}",
                topic = CLOCK_RESULT_SCHEMA_V1,
                payloadJson = json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    buildResultPayload(result),
                ),
                targetPeerId = route,
                createdAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    override suspend fun observeDeliveryState(): List<SyncDeliveryState> {
        return synchronized(lock) { deliveryStates.toList() }
    }

    override suspend fun metricsSnapshot(): SyncMetricsSnapshot {
        val snapshot = engine.metricsSnapshot()
        return SyncMetricsSnapshot(
            commandsSubmittedTotal = snapshot.commandsSubmittedTotal,
            commandsAppliedTotal = snapshot.commandsAppliedTotal,
            retryAttemptsTotal = snapshot.retryAttemptsTotal,
            queueDepth = snapshot.queueDepth,
        )
    }

    private companion object {
        private val logger: Logger = Logger.getLogger(EngineBackedOrgSyncCoreClient::class.java.name)
        const val MAX_DELIVERY_STATE_HISTORY = 100
        const val MAX_INCOMING_COMMAND_HISTORY = 500
        const val MAX_COMMAND_ROUTING_HISTORY = 2_000
    }

    private fun buildResultPayload(result: ClockResultPayload) = buildJsonObject {
        put("schema", result.schema)
        put("command_id", result.commandId)
        put("status", result.status.wireValue)
        result.errorCode?.let { put("error_code", it.name) }
        result.errorMessage?.let { put("error_message", it) }
        put("applied_at", result.appliedAt.toString())
        put("by_device_id", result.byDeviceId)
    }

    private fun trimRoutingHistory() {
        while (commandRouting.size > MAX_COMMAND_ROUTING_HISTORY) {
            val first = commandRouting.keys.firstOrNull() ?: break
            commandRouting.remove(first)
        }
    }
}
