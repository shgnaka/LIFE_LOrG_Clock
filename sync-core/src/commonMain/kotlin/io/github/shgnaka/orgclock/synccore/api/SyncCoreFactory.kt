package io.github.shgnaka.orgclock.synccore.api

import io.github.shgnaka.orgclock.synccore.engine.InMemorySyncCore

object SyncCoreFactory {
    fun createInMemory(
        clock: SyncClock,
        transport: SyncTransport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
        random: SyncRandom = SyncRandom { 0.0 },
        topicPolicy: TopicPolicy = TopicPolicy { _, _, _ -> TopicAuthorization.Allowed },
        trustedPeerResolver: TrustedPeerResolver = TrustedPeerResolver { null },
    ): SyncCore = InMemorySyncCore(
        clock = clock,
        transport = transport,
        random = random,
        topicPolicy = topicPolicy,
        trustedPeerResolver = trustedPeerResolver,
    )

    fun createPersistent(
        store: SyncStore,
        clock: SyncClock,
        transport: SyncTransport = SyncTransport { _, _ -> DispatchOutcome.Accepted },
        random: SyncRandom = SyncRandom { 0.0 },
        topicPolicy: TopicPolicy = TopicPolicy { _, _, _ -> TopicAuthorization.Allowed },
        trustedPeerResolver: TrustedPeerResolver = TrustedPeerResolver { null },
    ): SyncCore = InMemorySyncCore(
        clock = clock,
        transport = transport,
        random = random,
        topicPolicy = topicPolicy,
        trustedPeerResolver = trustedPeerResolver,
        durableStore = store,
    )
}

