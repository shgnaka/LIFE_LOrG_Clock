package com.example.orgclock.sync

import android.content.Context
import com.example.orgclock.data.ClockRepository
import com.example.orgclock.domain.ClockService
import com.example.orgclock.time.ClockEnvironment
import io.github.shgnaka.synccore.api.CommandState
import io.github.shgnaka.synccore.api.DomainCommandExecutor
import io.github.shgnaka.synccore.api.ResultStatus
import io.github.shgnaka.synccore.api.SyncResult
import io.github.shgnaka.synccore.engine.DispatchOutcome
import io.github.shgnaka.synccore.engine.EngineStore
import io.github.shgnaka.synccore.engine.MessageDispatcher
import io.github.shgnaka.synccore.engine.OutgoingCommandRecord
import io.github.shgnaka.synccore.engine.SyncCoreEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

class SynccoreEngineClientFactory : SyncCoreClientFactory {
    override fun create(
        appContext: Context,
        repository: ClockRepository,
        clockService: ClockService,
        clockEnvironment: ClockEnvironment,
    ): OrgSyncCoreClient {
        val commandIdStore = SharedPreferencesCommandIdStore(
            appContext.getSharedPreferences("orgclock_sync", Context.MODE_PRIVATE),
        )
        val appCommandExecutor = DefaultClockCommandExecutor(
            repository = repository,
            clockService = clockService,
            commandIdStore = commandIdStore,
            clockEnvironment = clockEnvironment,
        )
        val domainExecutor = OrgClockDomainCommandExecutor(appCommandExecutor)
        val store = InMemoryEngineStore()
        val engine = SyncCoreEngine(
            store = store,
            dispatcher = LocalLoopbackDispatcher(domainExecutor),
            ioDispatcher = Dispatchers.Default,
            flushIntervalMs = 1_000L,
        )
        return EngineBackedOrgSyncCoreClient(engine = engine)
    }
}

private class OrgClockDomainCommandExecutor(
    private val appExecutor: ClockCommandExecutor,
) : DomainCommandExecutor {
    override suspend fun execute(topic: String, payloadJson: String): SyncResult {
        val result = appExecutor.execute(payloadJson)
        return SyncResult(
            commandId = result.commandId,
            status = when (result.status) {
                ClockResultStatus.Applied -> ResultStatus.APPLIED
                ClockResultStatus.Failed -> ResultStatus.FAILED
                ClockResultStatus.Duplicate -> ResultStatus.DUPLICATE
                ClockResultStatus.Rejected -> ResultStatus.REJECTED
            },
            errorCode = result.errorCode?.name,
            errorMessage = result.errorMessage,
            appliedAtEpochMs = result.appliedAt.toEpochMilliseconds(),
        )
    }
}

private class LocalLoopbackDispatcher(
    private val domainExecutor: DomainCommandExecutor,
) : MessageDispatcher {
    override suspend fun dispatch(command: io.github.shgnaka.synccore.api.SyncCommand): DispatchOutcome {
        return runCatching {
            val result = domainExecutor.execute(command.topic, command.payloadJson)
            DispatchOutcome.Accepted(result = result)
        }.getOrElse { error ->
            DispatchOutcome.Rejected(
                errorCode = io.github.shgnaka.synccore.api.DeliveryErrorCode.PROTOCOL_ERROR,
                errorMessage = error.message ?: "domain execution failed",
            )
        }
    }
}

private class EngineBackedOrgSyncCoreClient(
    private val engine: SyncCoreEngine,
) : OrgSyncCoreClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val deliveryStates = ArrayDeque<SyncDeliveryState>()
    private var started = false
    private var collectorJob: Job? = null

    override suspend fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        engine.start()
        collectorJob?.cancel()
        collectorJob = scope.launch {
            engine.observeDeliveryState().collectLatest { event ->
                synchronized(lock) {
                    if (deliveryStates.size >= 100) deliveryStates.removeFirst()
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
        engine.stop()
    }

    override suspend fun flushNow() {
        engine.flushNow()
    }

    override suspend fun observeIncomingCommands(): List<String> = emptyList()

    override suspend fun reportResult(result: ClockResultPayload) {
        // The local loopback dispatcher already reports result via SyncCoreEngine.
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
}

private class InMemoryEngineStore : EngineStore {
    private val outgoing = LinkedHashMap<String, MutableOutgoingRecord>()
    private val processed = LinkedHashMap<String, SyncResult>()
    private val events = mutableListOf<io.github.shgnaka.synccore.api.DeliveryEvent>()

    override suspend fun healthCheck(): Result<Unit> = Result.success(Unit)

    override suspend fun insertOutgoing(command: io.github.shgnaka.synccore.api.SyncCommand): Result<Unit> {
        if (outgoing.containsKey(command.commandId)) {
            return Result.failure(IllegalStateException("Command already exists: ${command.commandId}"))
        }
        outgoing[command.commandId] = MutableOutgoingRecord(
            command = command,
            state = CommandState.PENDING,
            retryCount = 0,
            nextRetryAtEpochMs = command.createdAtEpochMs,
            lastUpdatedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
        )
        return Result.success(Unit)
    }

    override suspend fun markState(commandId: String, state: CommandState): Result<Unit> {
        val row = outgoing[commandId] ?: return Result.failure(IllegalStateException("Unknown command: $commandId"))
        row.state = state
        row.lastUpdatedAtEpochMs = Clock.System.now().toEpochMilliseconds()
        return Result.success(Unit)
    }

    override suspend fun recordResult(result: SyncResult): Result<Unit> {
        processed[result.commandId] = result
        return Result.success(Unit)
    }

    override suspend fun isProcessed(commandId: String): Boolean = processed.containsKey(commandId)

    override suspend fun listPendingCommands(limit: Int): List<io.github.shgnaka.synccore.api.SyncCommand> {
        return outgoing.values
            .asSequence()
            .filter { it.state == CommandState.PENDING }
            .take(limit)
            .map { it.command }
            .toList()
    }

    override suspend fun listDueCommands(nowEpochMs: Long, limit: Int): List<OutgoingCommandRecord> {
        return outgoing.values
            .asSequence()
            .filter {
                it.state == CommandState.PENDING &&
                    it.nextRetryAtEpochMs <= nowEpochMs &&
                    !processed.containsKey(it.command.commandId)
            }
            .sortedBy { it.nextRetryAtEpochMs }
            .take(limit)
            .map { it.toImmutable() }
            .toList()
    }

    override suspend fun updateRetry(
        commandId: String,
        retryCount: Int,
        nextRetryAtEpochMs: Long,
        errorCode: io.github.shgnaka.synccore.api.DeliveryErrorCode?,
        errorMessage: String?,
    ): Result<Unit> {
        val row = outgoing[commandId] ?: return Result.failure(IllegalStateException("Unknown command: $commandId"))
        row.retryCount = retryCount
        row.nextRetryAtEpochMs = nextRetryAtEpochMs
        row.lastUpdatedAtEpochMs = Clock.System.now().toEpochMilliseconds()
        return Result.success(Unit)
    }

    override suspend fun appendDeliveryEvent(event: io.github.shgnaka.synccore.api.DeliveryEvent): Result<Unit> {
        events += event
        return Result.success(Unit)
    }

    override suspend fun getQueueDepth(): Long {
        return outgoing.values.count {
            it.state == CommandState.PENDING || it.state == CommandState.SENT || it.state == CommandState.ACKED
        }.toLong()
    }
}

private data class MutableOutgoingRecord(
    val command: io.github.shgnaka.synccore.api.SyncCommand,
    var state: CommandState,
    var retryCount: Int,
    var nextRetryAtEpochMs: Long,
    var lastUpdatedAtEpochMs: Long,
) {
    fun toImmutable(): OutgoingCommandRecord {
        return OutgoingCommandRecord(
            command = command,
            state = state,
            retryCount = retryCount,
            nextRetryAtEpochMs = nextRetryAtEpochMs,
        )
    }
}
