package io.github.shgnaka.orgclock.synccore

import io.github.shgnaka.orgclock.synccore.api.ClaimIncomingOutcome
import io.github.shgnaka.orgclock.synccore.api.IncomingReceipt
import io.github.shgnaka.orgclock.synccore.api.SyncCore
import kotlin.test.fail

suspend fun SyncCore.claimedIncoming(limit: Int = 100): List<IncomingReceipt> =
    when (val outcome = claimIncoming(limit)) {
        is ClaimIncomingOutcome.Claimed -> outcome.receipts
        is ClaimIncomingOutcome.Failed -> fail("claimIncoming failed: ${outcome.error.code}")
    }
