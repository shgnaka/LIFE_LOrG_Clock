import org.gradle.api.GradleException

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.test") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}

private data class SyncCoreAcceptanceRow(
    val id: String,
    val requirement: String,
    val operation: String,
    val oracle: String,
)

private fun parseSyncCoreAcceptanceRows(text: String): List<SyncCoreAcceptanceRow> {
    val testIdPattern = Regex("""^(ARC|ENG|ING|SEC|DB|IDN|INT|QUA|INS)-\d+$""")
    return text.lineSequence().mapNotNull { line ->
        if (!line.startsWith("|")) {
            return@mapNotNull null
        }
        val columns = line.trim().trim('|').split('|').map { it.trim() }
        if (columns.size < 4 || !testIdPattern.matches(columns[0])) {
            return@mapNotNull null
        }
        SyncCoreAcceptanceRow(
            id = columns[0],
            requirement = columns[1],
            operation = columns[2],
            oracle = columns[3],
        )
    }.toList()
}

private fun requireUniqueSyncCoreAcceptanceIds(rows: List<SyncCoreAcceptanceRow>, label: String) {
    val duplicates = rows.groupBy { it.id }.filterValues { it.size != 1 }
    if (duplicates.isNotEmpty()) {
        val details = duplicates.entries.joinToString(", ") { "${it.key} x${it.value.size}" }
        throw GradleException("$label contains duplicate test IDs: $details")
    }
}

private fun numberedSyncCoreRequirementIds(text: String): Set<String> =
    Regex("""(?m)^### ((?:AR|FR|SR|DR|OR|CR|QR)-\d+)""")
        .findAll(text)
        .map { it.groupValues[1] }
        .toSet()

private fun mappedSyncCoreRequirementIds(rows: List<SyncCoreAcceptanceRow>): Set<String> =
    rows.asSequence()
        .flatMap { row ->
            Regex("""(?:AR|FR|SR|DR|OR|CR|QR)-\d+""").findAll(row.requirement).map { it.value }
        }
        .toSet()

private val syncCoreExternalScanTargets = listOf(
    "settings.gradle.kts",
    "build.gradle.kts",
    "app/build.gradle.kts",
    "app/src/main",
    "desktopApp/src/main",
    "sync-core/src",
    "shared/src",
    "security-loop/modules/sync-core-transport-lan",
    "docs/project-overview.md",
    "docs/synccore-integration/overview.md",
    "docs/synccore-integration/contract.md",
    "docs/synccore-integration/execution-plan-m1.md",
)

private fun syncCoreForbiddenMarker(vararg parts: String): String = parts.joinToString(separator = "")

private val forbiddenExternalSyncCorePatterns = listOf(
    syncCoreForbiddenMarker("SYNC", "_CORE", "_DIR"),
    syncCoreForbiddenMarker("synccore", ".", "dir"),
    syncCoreForbiddenMarker("app/src/", "synccore"),
    syncCoreForbiddenMarker("lanonly-p2p-", "cmdsync-core"),
    syncCoreForbiddenMarker("io.github.shgnaka.", "synccore"),
    syncCoreForbiddenMarker("sync-core", "-api"),
    syncCoreForbiddenMarker("sync-core", "-engine"),
    syncCoreForbiddenMarker("sync-core", "-android"),
    syncCoreForbiddenMarker("Synccore", "EngineClientFactory"),
).map { marker -> Regex(Regex.escape(marker)) to marker }

private val androidKeyStorageRequiredMarkers = listOf(
    "AndroidKeystoreSyncCoreTransportCredentialStore",
    "AndroidKeystoreSyncCoreEnvelopeSigner",
    "AndroidKeystoreSyncCoreTlsIdentityStore",
    "AndroidSyncCoreTlsIdentity",
    "AndroidKeyStore",
    "AES/GCM/NoPadding",
    "SSLServerSocketFactory",
    "makeSecure",
    ".setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)",
    "assertNull(secretKey.encoded)",
    "assertNull(entry.privateKey.encoded)",
    "verifyEs256",
)

private val desktopKeyStorageRequiredMarkers = listOf(
    "DesktopSyncIdentity",
    "DesktopTlsIdentity",
    "DesktopEncryptedSyncCoreTransportCredentialStore",
    "PBKDF2WithHmacSHA256",
    "AES/GCM/NoPadding",
    "SIGNING_ENCRYPTED_PRIVATE_KEY_FILE",
    "setOwnerOnlyPermissions",
    "storePasswordForRoot",
    "LEGACY_STORE_PASSWORD",
    "PKCS12",
    "tlsIdentityStoreUsesRootDerivedPasswordAndOwnerOnlyWhenSupported",
)

private val syncCoreSecretRedactionRequiredMarkers = listOf(
    "IngressRedactionPolicy",
    "safeDetail",
    "malformedEnvelopeErrorDetailIsRedacted",
    "coreRejectedErrorDetailIsRedacted",
    "rejectedResponseDoesNotExposeUnsanitizedCoreDetail",
    "retryLaterResponseDoesNotExposeUnsanitizedCoreDetail",
    "SECRET-",
    "assertFalse(response.body.contains(secret))",
)

private val syncCoreDomainTimestampBoundaryRequiredMarkers = listOf(
    "SEC-15",
    "DomainTimestampBoundaryTest.oldPayloadFreshEnvelope",
    "requested_at",
    "1900-01-01T00:00:00Z",
    "sentAtEpochMs",
    "IngressOutcome.Accepted",
    "upper-layer validator owns domain rejection",
)

private val syncCoreDataRetentionRequiredMarkers = listOf(
    "RetentionPolicy",
    "retentionPolicyDefaultsMatchDr01Baseline",
    "retentionCleanupPrunesExpiredDeliveryEventsOnStart",
    "retentionCleanupSaveFailureDoesNotBlockStartAndRollsBackSnapshot",
    "retentionCleanupPrunesExpiredTerminalOutgoingAndIncomingOnStart",
    "retentionCleanupCapsOldestEligibleTerminalOutgoingAndIncomingOnStart",
    "peerRevocationRejectsActiveRowsDisablesRoutesAndRetainsHistory",
    "maxTerminalOutgoingRecords: Int = 20_000",
    "terminalOutgoingTtlMs: Long = 7L",
    "terminalIncomingTtlMs: Long = 7L",
)

private val syncCoreCommandResultRoutingRequiredMarkers = listOf(
    "INT-04",
    "INT-11",
    "CommandResultRoundTripTest.restartBetween",
    "AdapterExpirationTest.commandAndResult",
    "reportResultResolvesRouteAfterClientAndCoreRecreation",
    "persistentStoreRestoresIncomingRouteAcrossCoreInstances",
    "reportResultQueuesClockResultToOriginalPeerBeforeAckingIncoming",
    "receiveAcceptedEnvelopeSchedulesCommandDrainAndAcksInbox",
    "assertEquals(nowEpochMs + COMMAND_EXPIRATION_MS, outgoing.expiresAtEpochMs)",
    "assertEquals(nowEpochMs + RESULT_EXPIRATION_MS, outgoing.expiresAtEpochMs)",
    "assertEquals(NOW_EPOCH_MS + RESULT_EXPIRATION_MS, outgoing.expiresAtEpochMs)",
    "RESULT_ROUTE_NOT_FOUND",
)

private val syncCoreRetryContractRequiredMarkers = listOf(
    "ENG-07",
    "ENG-08",
    "ENG-09",
    "ENG-10",
    "DB-02",
    "RetryPolicyTest.jitter",
    "RetryPolicyTest.boundaryTime",
    "RetryPolicyTest.classification",
    "RetryPolicyTest.exhaustion",
    "RetryReopenTest.nextAttempt",
    "retryableFailureSchedulesBoundedBackoffWithJitter",
    "retryWaitIsNotDispatchedBeforeNextAttemptBoundary",
    "tenthRetryableAttemptFailsWithRetryExhausted",
    "persistentRetryWaitRestoresNextAttemptBoundary",
    "httpStatusClassificationMatchesRetryPolicy",
    "networkFailureClassificationMatchesRetryPolicy",
    "syncCoreHttpPostResultForStatus",
    "desktopSyncCoreHttpPostResultForStatus",
    "SocketTimeoutException",
    "CertificatePinMismatch",
    "NetworkUnreachable",
    "RateLimited",
    "RetryExhausted",
    "408 to SyncErrorCode.Timeout",
    "425 to SyncErrorCode.RemoteBusy",
    "429 to SyncErrorCode.RateLimited",
    "500 to SyncErrorCode.RemoteBusy",
    "400 to SyncErrorCode.ProtocolError",
    "401 to SyncErrorCode.PeerNotAuthorized",
    "413 to SyncErrorCode.PayloadTooLarge",
)

private val syncCoreCapacityContractRequiredMarkers = listOf(
    "ENG-29",
    "SEC-09",
    "DB-11",
    "CapacityTest.outgoingLimits",
    "InboxCapacityTest.full",
    "CapacityTransactionTest.concurrentWriters",
    "defaultOutgoingCapacityBoundariesRejectWithoutEvictingActiveRows",
    "defaultInboxCapacityBoundaryReturnsRetryLaterWithoutEvictingActiveRows",
    "concurrentOutgoingWritersCannotExceedTotalCapacity",
    "concurrentOutgoingWritersCannotExceedPerPeerCapacity",
    "concurrentIncomingWritersCannotExceedInboxCapacity",
    "maxPendingOutgoingTotal: Int = 2_000",
    "maxPendingOutgoingPerPeer: Int = 500",
    "maxUnprocessedIncoming: Int = 2_000",
    "capacityRetryAfterSeconds: Int = 60",
    "SyncErrorCode.QueueFull",
    "SyncErrorCode.InboxFull",
    "IngressOutcome.RetryLater",
    "assertEquals(2_000L, totalCore.metricsSnapshot().queueDepth)",
    "assertEquals(2_000L, core.metricsSnapshot().inboxUnprocessedDepth)",
)

private val syncCoreDeterminismForbiddenPatterns = listOf(
    Regex("""\bThread\.sleep\s*\(""") to "Thread.sleep",
    Regex("""\bSystem\.currentTimeMillis\s*\(""") to "System.currentTimeMillis",
    Regex("""\bClock\.System\b""") to "Clock.System",
    Regex("""\bkotlinx\.datetime\.Clock\b""") to "real clock import",
    Regex("""\bdelay\s*\(""") to "delay",
    Regex("""\b(java\.net\.)?(ServerSocket|Socket|DatagramSocket)\b""") to "real socket",
    Regex("""\bUUID\.randomUUID\s*\(""") to "UUID.randomUUID",
    Regex("""\bkotlin\.random\.Random\b""") to "kotlin.random.Random",
)

private val syncCoreCancellationConcurrencyRequiredMarkers = listOf(
    "QUA-03",
    "QUA-04",
    "ENG-30",
    "CancellationContractTest",
    "storeSaveCancellationRollsBackMutationAndPropagates",
    "transportCancellationPropagatesWithoutConvertingToFailure",
    "catch (error: CancellationException)",
    "ConcurrencyStressTest",
    "seededTenThousandOperationConcurrencyStress",
    "SEED_COUNT = 100",
    "WAVES_PER_SEED = 10",
    "OPERATIONS_PER_WAVE = 10",
)

private val syncCoreProtocolCompatibilityRequiredMarkers = listOf(
    "INT-01",
    "INT-02",
    "INT-03",
    "INT-09",
    "SEC-11",
    "SEC-12",
    "LegacyWireCompatibilityTest.commandFixture",
    "LegacyWireCompatibilityTest.resultFixture",
    "ProtocolEvolutionTest.optionalAndUnknownVersion",
    "UnsupportedProtocolTest",
    "CanonicalizationTest.fieldOrder",
    "EnvelopeVersionTest.legacyAndCurrent",
    "SyncCoreLegacyFixtureTest",
    "canonicalInputIsStableAcrossJsonFieldOrderAndWhitespace",
    "unknownOptionalFieldIsIgnoredButUnsupportedVersionIsRejected",
    "unsupportedTopicAndEnvelopeVersionAreRejectedWithoutStoppingReceiver",
    "command-envelope.canonical.txt",
    "clock-result-payload.json",
)

private val androidRoomMigrationRequiredMarkers = listOf(
    "DB-04",
    "DB-05",
    "DB-06",
    "RoomMigrationTest.v1ToCurrent",
    "RoomMigrationTest.v2ToCurrent",
    "RoomMigrationTest.failureIsNonDestructive",
    "roomMigrationFromV1ToCurrentPreservesLegacyRows",
    "roomMigrationFromV2ToCurrentPreservesLegacyRowsAndReplayRegistry",
    "roomMigrationFailureLeavesLegacyDatabaseReadable",
    "backupPreV3IfPresentCopiesDatabaseWalAndShm",
    "InternalSyncCoreDatabaseBackupManager.forContext(appContext).backupPreV3IfPresent()",
    "MIGRATION_1_2",
    "MIGRATION_2_3",
    "SyncErrorCode.MigrationFailed.name",
)

private val syncCoreAppIntegrationRequiredMarkers = listOf(
    "INT-05",
    "INT-06",
    "INT-08",
    "HostRuntimeIntegrationTest.modeTransitions",
    "FailureIsolationTest.databaseUnavailable",
    "SyncDisabledRegressionTest",
    "hostRuntimeIntegration_modeTransitionsDelegateToCoordinator",
    "failureIsolation_databaseUnavailableStillAllowsLocalMutation",
    "syncDisabledRegression_doesNotMutateLocalFileOrStartRuntime",
    "SyncRuntimeCoordinator",
    "RecordingSyncRuntimeCoordinator",
    "STORE_UNAVAILABLE: sync DB unavailable",
)

private val syncCoreClientContractRequiredMarkers = listOf(
    "INT-07",
    "CR-05",
    "OrgSyncCoreClientContractTest",
    "internalFactorySatisfiesOrgSyncCoreClientContract",
    "submitOutgoing",
    "observeIncomingCommands",
    "reportResult",
    "observeDeliveryState",
    "metricsSnapshot",
    "lifecycle methods",
    "InRepositoryOrgSyncCoreClient",
    "NoOpOrgSyncCoreClient",
)

private val syncCoreObservabilityContractRequiredMarkers = listOf(
    "ENG-28",
    "ENG-31",
    "ENG-32",
    "MetricsTest.mixedOutcomes",
    "ErrorContractTest.stableCodes",
    "ObservationContractTest",
    "typedModels",
    "DeliveryEvent",
    "SyncMetrics",
    "observeDeliveryEvents",
    "metricsSnapshot",
    "submittedTotal",
    "acceptedTotal",
    "rejectedTotal",
    "retryAttemptsTotal",
    "incomingRejectedTotal",
    "expiredLeaseRecoveryTotal",
    "persistenceErrorTotal",
    "lastSuccessfulDispatchByPeer",
    "FailingSaveStore",
    "StoreWriteFailed",
    "InvalidMessage",
    "PayloadTooLarge",
    "PeerNotTrusted",
    "CertificatePinMismatch",
    "ResultRouteNotFound",
    "InvalidStateTransition",
    "\"x\".repeat(512)",
    "\"x\".repeat(513)",
)

private val androidUnsignedEndpointPolicyRequiredMarkers = listOf(
    "SEC-14",
    "UnsignedEndpointTest.buildVariants",
    "legacyUnsignedIncomingCommandEndpointIsNotExposed",
    "MESSAGES_PATH = \"/v1/messages\"",
    "BuildConfig.DEBUG",
    "EXTRA_SYNC_COMMAND_PAYLOAD",
    "android:usesCleartextTraffic=\"false\"",
    "android:exported=\"false\"",
)

tasks.register("runDesktop") {
    group = "application"
    description = "Runs the Compose Desktop host."
    dependsOn(":desktopApp:run")
}
tasks.register("verifyDesktopCurrentOs") {
    group = "verification"
    description = "Compiles the desktop host for the current operating system."
    dependsOn(":desktopApp:compileKotlin")
}

tasks.register("verifyDesktopCompile") {
    group = "verification"
    description = "Alias for verifyDesktopCurrentOs."
    dependsOn("verifyDesktopCurrentOs")
}

tasks.register("packageDesktopCurrentOs") {
    group = "distribution"
    description = "Builds desktop distribution artifacts for the current operating system."
    dependsOn(":desktopApp:packageDistributionForCurrentOS")
}

tasks.register("packageDesktopLinux") {
    group = "distribution"
    description = "Alias for packageDesktopCurrentOs."
    dependsOn("packageDesktopCurrentOs")
}

tasks.register("verifySyncCoreTestTraceability") {
    group = "verification"
    description = "Verifies sync-core requirement/test traceability."

    inputs.files(
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val requirementsText = file("docs/synccore-integration/in-repository-requirements.md").readText()
        val testSpecText = file("docs/synccore-integration/in-repository-test-spec.md").readText()

        val requirementRows = parseSyncCoreAcceptanceRows(requirementsText)
        val testSpecRows = parseSyncCoreAcceptanceRows(testSpecText)
        requireUniqueSyncCoreAcceptanceIds(requirementRows, "Requirements catalog")
        requireUniqueSyncCoreAcceptanceIds(testSpecRows, "Test specification")

        val requirementIds = requirementRows.map { it.id }.toSet()
        val testSpecIds = testSpecRows.map { it.id }.toSet()
        val missingFromSpec = requirementIds.minus(testSpecIds)
        val orphanedInSpec = testSpecIds.minus(requirementIds)
        if (missingFromSpec.isNotEmpty()) {
            throw GradleException("Acceptance IDs missing from test specification: ${missingFromSpec.sorted().joinToString(", ")}")
        }
        if (orphanedInSpec.isNotEmpty()) {
            throw GradleException("Test specification IDs not declared by requirements: ${orphanedInSpec.sorted().joinToString(", ")}")
        }

        val blankDefinitions = testSpecRows.filter {
            it.requirement.isBlank() || it.operation.isBlank() || it.oracle.isBlank()
        }
        if (blankDefinitions.isNotEmpty()) {
            throw GradleException(
                "Test specification contains blank owner/operation/oracle fields: " +
                    blankDefinitions.joinToString(", ") { it.id },
            )
        }

        val numberedRequirements = numberedSyncCoreRequirementIds(requirementsText)
        val mappedRequirements = mappedSyncCoreRequirementIds(requirementRows)
        val unmappedRequirements = numberedRequirements.minus(mappedRequirements)
        if (unmappedRequirements.isNotEmpty()) {
            throw GradleException("Numbered requirements without an acceptance test: ${unmappedRequirements.sorted().joinToString(", ")}")
        }

        logger.lifecycle(
            "sync-core test traceability verified: " +
                "${numberedRequirements.size} requirements, ${requirementIds.size} acceptance tests",
        )
    }
}

tasks.register("generateSyncCoreAcceptanceAuditReport") {
    group = "verification"
    description = "Generates an acceptance-ID to implementation-evidence audit report for in-repository sync-core."

    val reportFile = layout.buildDirectory.file("reports/sync-core/acceptance-audit.md")
    inputs.files(
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
        fileTree("sync-core/src") { include("**/*.kt") },
        fileTree("app/src/main") { include("**/*.kt", "**/*.xml") },
        fileTree("app/src/test") { include("**/*.kt", "**/*.java", "**/*.json", "**/*.sql", "**/*.txt") },
        fileTree("app/src/androidTest") { include("**/*.kt", "**/*.java") },
        fileTree("desktopApp/src/main") { include("**/*.kt") },
        fileTree("desktopApp/src/test") { include("**/*.kt") },
        fileTree("scripts") { include("**/*") },
        fileTree("security-loop") { include("**/*") },
        "build.gradle.kts",
    )
    outputs.file(reportFile)

    doLast {
        val requirementsText = file("docs/synccore-integration/in-repository-requirements.md").readText()
        val testSpecText = file("docs/synccore-integration/in-repository-test-spec.md").readText()
        val rows = parseSyncCoreAcceptanceRows(testSpecText).sortedBy { it.id }
        val sourceFiles = inputs.files.files
            .filter { it.exists() && it.isFile && !it.invariantSeparatorsPath.contains("docs/synccore-integration") }
        val sourceText = sourceFiles.joinToString(System.lineSeparator()) { it.readText() }

        fun ownerToken(owner: String): String = owner.trim('`').substringBefore(' ')
        fun ownerClass(owner: String): String = ownerToken(owner).substringBefore('.')
        fun ownerMethod(owner: String): String? = ownerToken(owner).substringAfter('.', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
        val evidenceAliases = mapOf(
            "QueueReopenTest.allFields" to listOf("persistentStoreRestoresOutgoingAcrossCoreInstances"),
            "RetryReopenTest.nextAttempt" to listOf("persistentRetryWaitRestoresNextAttemptBoundary"),
            "InboxReopenTest.messageAndRoute" to listOf("persistentStoreRestoresIncomingAcrossCoreInstances", "persistentStoreRestoresIncomingRouteAcrossCoreInstances"),
            "RetentionTest.boundariesAndCaps" to listOf("retentionCleanupPrunesExpiredTerminalOutgoingAndIncomingOnStart", "retentionCleanupCapsOldestEligibleTerminalOutgoingAndIncomingOnStart"),
            "RetentionTest.activeRows" to listOf("retentionCleanupPrunesExpiredTerminalOutgoingAndIncomingOnStart"),
            "RetentionTest.cleanupFailure" to listOf("retentionCleanupSaveFailureDoesNotBlockStartAndRollsBackSnapshot"),
            "PeerRevocationTest.pendingRows" to listOf("peerRevocationRejectsActiveRowsDisablesRoutesAndRetainsHistory"),
            "SubmitContractTest.validMessage" to listOf("submitStoresPendingMessage"),
            "SubmitContractTest.sameIdSameContent" to listOf("submitSameMessageIsIdempotent"),
            "SubmitContractTest.sameIdDifferentContent" to listOf("submitSameIdWithDifferentContentIsConflict"),
            "SubmitValidationTest.invalidInputs" to listOf("invalidPayloadJsonIsRejectedByMessageContract"),
            "DispatchStateTest.accepted" to listOf("flushAcceptedMessageTransitionsToAcked"),
            "DispatchStateTest.rejected" to listOf("flushRejectedMessageTransitionsToRejected"),
            "ExpirationTest.expiredBeforeDispatch" to listOf("expiredMessageIsNotDispatched"),
            "ExpirationTest.expiresInFlight" to listOf("retryableResponseAfterExpirationBecomesExpiredWithoutRetry"),
            "OrderingTest.samePeerFifo" to listOf("samePeerMessagesDoNotOvertakeRetryWaitHead"),
            "OrderingTest.crossPeerConcurrency" to listOf("differentPeersDispatchWithGlobalInFlightLimitFour"),
            "DispatchLeaseTest.expiredLeaseRecovery" to listOf("expiredDispatchLeaseIsRecoveredAndResentAcrossCoreInstances"),
            "DispatchLeaseTest.liveLeaseRecovery" to listOf("liveDispatchLeaseIsNotRecoveredBeforeBoundary"),
            "ManualRetryTest.failedMessage" to listOf("manualRetryResetsFailedMessageAttempt"),
            "CancellationTest.byState" to listOf("cancelPendingRetryWaitDispatchingAndTerminalStates"),
            "StoreFailureTest.atomicity" to listOf("persistentSaveFailureReturnsTypedSubmitFailure", "flushRollsBackAcceptedOutcomeWhenResultPersistFails"),
            "ObserverIsolationTest.slowAndFailing" to listOf("slowAndFailingDeliveryObserversDoNotStopDispatch"),
            "IncomingLeaseTest.expiredProcessingLease" to listOf("claimedIncomingIsReclaimedAfterProcessingLeaseExpires"),
            "ConcurrencyTest.cancellationAndRaces" to listOf("seededTenThousandOperationConcurrencyStress"),
            "IngressDurabilityTest.commitBeforeSuccess" to listOf("receiveVerifiedStoresAndClaimsIncomingMessage"),
            "IngressDurabilityTest.restartBeforeAck" to listOf("sharedStoreRestoresUnprocessedIncomingAcrossCoreInstances"),
            "IngressDedupTest.sameContent" to listOf("duplicateIncomingSameContentIsAcceptedButNotRepublished"),
            "IngressDedupTest.conflictingContent" to listOf("duplicateIncomingDifferentContentIsReplayConflict"),
            "IncomingAckTest.outcomes" to listOf("incomingAckProcessedRejectedAndRetryLaterControlsReclaim"),
            "ResultRouteTest.restart" to listOf("reportResultResolvesRouteAfterClientAndCoreRecreation"),
            "ResultRouteTest.missing" to listOf("reportResultForMissingRouteReturnsRejectedWithoutOutgoing"),
            "TopicPolicyTest.beforePersistence" to listOf("topicPolicyRejectsIncomingBeforePersistence", "topicPolicyAllowsIncomingBeforePersistence"),
            "EnvelopeSignatureTest.valid" to listOf("validEs256SignatureIsAccepted", "trustedSignedEnvelopeIsAcceptedIntoInbox"),
            "EnvelopeSignatureTest.unknownAndRevoked" to listOf("unknownPeerIsRejectedWithoutPersistence", "revokedPeerIsRejectedWithoutPersistence"),
            "EnvelopeSignatureTest.tampering" to listOf("tamperedSignedFieldIsRejected", "invalidSignatureIsRejectedWithoutPersistence"),
            "EnvelopeTimestampTest.boundaries" to listOf("timestampBoundaryIsInclusive"),
            "EndpointPolicyTest.cleartext" to listOf("cleartextEndpointRejectedBeforeExchangeInvocation"),
            "TlsPinningTest.certificatePins" to listOf("certificatePinMatchAllowsExchange", "certificatePinMismatchIsTerminalAndSkipsExchange"),
            "IngressSizeLimitTest.boundaries" to listOf("encodedBodySizeBoundaryIsEnforcedBeforePersistence", "decodedPayloadSizeBoundaryIsEnforcedBeforePersistence"),
            "RateLimitTest.window" to listOf("rateLimitRejectsOneHundredTwentyFirstRequestAndResetsNextWindow"),
            "SecretRedactionTest.allSinks" to listOf("malformedEnvelopeErrorDetailIsRedacted", "coreRejectedErrorDetailIsRedacted"),
            "PeerAuthorizationTest.viewerMutation" to listOf("viewerMutationTopicIsRejectedBeforePersistence"),
        )

        val exactMatches = rows.filter { row ->
            val owner = ownerToken(row.requirement)
            owner.startsWith("verify") || owner.startsWith("inspect") || owner == "syncCoreBenchmark" || sourceText.contains(owner)
        }
        val classAndMethodMatches = rows.minus(exactMatches.toSet()).filter { row ->
            val clazz = ownerClass(row.requirement)
            val method = ownerMethod(row.requirement)
            sourceText.contains(clazz) && (method == null || sourceText.contains(method))
        }
        val aliasMatches = rows.minus(exactMatches.toSet()).minus(classAndMethodMatches.toSet()).filter { row ->
            evidenceAliases[ownerToken(row.requirement)]?.all { marker -> sourceText.contains(marker) } == true
        }
        val idMarkerMatches = rows.minus(exactMatches.toSet()).minus(classAndMethodMatches.toSet()).minus(aliasMatches.toSet()).filter { row ->
            sourceText.contains(row.id)
        }
        val unresolved = rows.minus(exactMatches.toSet()).minus(classAndMethodMatches.toSet()).minus(aliasMatches.toSet()).minus(idMarkerMatches.toSet())

        val report = buildString {
            appendLine("# sync-core acceptance audit")
            appendLine()
            appendLine("acceptance_ids=${rows.size}")
            appendLine("exact_owner_evidence=${exactMatches.size}")
            appendLine("class_and_method_evidence=${classAndMethodMatches.size}")
            appendLine("alias_evidence=${aliasMatches.size}")
            appendLine("id_marker_only_evidence=${idMarkerMatches.size}")
            appendLine("unresolved_owner_evidence=${unresolved.size}")
            appendLine()
            appendLine("## Unresolved Owner Evidence")
            appendLine()
            if (unresolved.isEmpty()) {
                appendLine("None.")
            } else {
                appendLine("| ID | Spec owner | Requirement | Oracle |")
                appendLine("|---|---|---|---|")
                unresolved.forEach { row ->
                    appendLine("| ${row.id} | `${ownerToken(row.requirement)}` | ${row.operation} | ${row.oracle} |")
                }
            }
            appendLine()
            appendLine("## Exact Owner Evidence")
            appendLine()
            appendLine("| ID | Spec owner |")
            appendLine("|---|---|")
            exactMatches.forEach { row ->
                appendLine("| ${row.id} | `${ownerToken(row.requirement)}` |")
            }
            appendLine()
            appendLine("## Class And Method Evidence")
            appendLine()
            appendLine("| ID | Spec owner |")
            appendLine("|---|---|")
            classAndMethodMatches.forEach { row ->
                appendLine("| ${row.id} | `${ownerToken(row.requirement)}` |")
            }
            appendLine()
            appendLine("## Alias Evidence")
            appendLine()
            appendLine("| ID | Spec owner | Evidence aliases |")
            appendLine("|---|---|---|")
            aliasMatches.forEach { row ->
                val aliases = evidenceAliases.getValue(ownerToken(row.requirement)).joinToString("`, `")
                appendLine("| ${row.id} | `${ownerToken(row.requirement)}` | `$aliases` |")
            }
            appendLine()
            appendLine("## ID Marker Only Evidence")
            appendLine()
            appendLine("| ID | Spec owner |")
            appendLine("|---|---|")
            idMarkerMatches.forEach { row ->
                appendLine("| ${row.id} | `${ownerToken(row.requirement)}` |")
            }
            appendLine()
            appendLine("Requirements document bytes=${requirementsText.length}")
        }

        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(report)
        }
        logger.lifecycle("sync-core acceptance audit report written to ${reportFile.get().asFile.relativeTo(rootDir).invariantSeparatorsPath}")
    }
}

tasks.register("verifyExternalReferencesRemoved") {
    group = "verification"
    description = "Verifies that production sync-core no longer references the external implementation."

    inputs.files(syncCoreExternalScanTargets.map { file(it) }.filter { it.exists() })

    doLast {
        val violations = mutableListOf<String>()
        syncCoreExternalScanTargets.forEach { relativeTarget ->
            val target = file(relativeTarget)
            if (!target.exists()) {
                return@forEach
            }
            val files = if (target.isDirectory) {
                fileTree(target) { include("**/*") }.files.filter { it.isFile }
            } else {
                listOf(target)
            }
            files.forEach { sourceFile ->
                val text = sourceFile.readText()
                forbiddenExternalSyncCorePatterns.forEach { (pattern, marker) ->
                    if (pattern.containsMatchIn(text)) {
                        violations += "${sourceFile.relativeTo(rootDir).invariantSeparatorsPath} contains forbidden external sync-core marker: $marker"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(violations.sorted().joinToString(System.lineSeparator()))
        }
        logger.lifecycle("sync-core external dependency removal verified")
    }
}

tasks.register("verifySyncCoreRepositoryOwnership") {
    group = "verification"
    description = "Verifies the in-repository sync-core is owned and built from this checkout."

    dependsOn(
        "verifyExternalReferencesRemoved",
        "verifySyncCoreArchitecture",
        "verifySyncCoreTestTraceability",
        "verifySyncCoreCommandResultRouting",
        "verifySyncCoreRetryContract",
        "verifySyncCoreCapacityContract",
        "verifySyncCoreTestDeterminism",
        "verifySyncCoreCancellationAndConcurrency",
        "verifySyncCoreProtocolCompatibility",
        "inspectAndroidRoomMigration",
        "verifySyncCoreAppIntegration",
        "verifySyncCoreClientContract",
        "verifySyncCoreObservabilityContract",
        "verifySyncCoreDataRetention",
        ":sync-core:allTests",
        ":app:compileDebugKotlin",
        ":desktopApp:compileKotlin",
    )
}

tasks.register("verifyNoExternalSyncCoreBuild") {
    group = "verification"
    description = "Runs the no-external sync-core build gate used for Gate 5 cutover."

    dependsOn(
        "verifySyncCoreRepositoryOwnership",
        ":app:testDebugUnitTest",
        ":desktopApp:test",
        ":app:compileDebugAndroidTestKotlin",
    )
}

tasks.register("inspectAndroidRoomMigration") {
    group = "verification"
    description = "Inspects Android Room sync-core migrations and optionally runs connected migration proof."

    dependsOn(
        ":app:testDebugUnitTest",
        ":app:compileDebugAndroidTestKotlin",
    )
    if (providers.gradleProperty("syncCoreAndroidConnected").orNull == "true") {
        dependsOn(":app:connectedDebugAndroidTest")
    }
    inputs.files(
        "app/src/main/java/com/example/orgclock/sync/InRepositorySyncCoreClientFactory.kt",
        "app/src/main/java/com/example/orgclock/sync/InternalSyncCoreDatabaseBackup.kt",
        "app/src/main/java/com/example/orgclock/sync/InternalSyncCoreRoomStore.kt",
        "app/src/test/java/com/example/orgclock/sync/RoomMigrationPlannerTest.kt",
        "app/src/test/java/com/example/orgclock/sync/InternalSyncCoreDatabaseBackupManagerTest.kt",
        "app/src/androidTest/java/com/example/orgclock/sync/AndroidSyncCorePlatformAdapterInstrumentedTest.kt",
        "app/src/test/resources/synccore/legacy-v1/schema-v1.sql",
        "app/src/test/resources/synccore/legacy-v1/schema-v2.sql",
        "app/src/test/resources/synccore/legacy-v1/migration-1-2.sql",
        "docs/synccore-integration/in-repository-storage-migration.md",
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = androidRoomMigrationRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        val forbidden = listOf("fallbackToDestructiveMigration", "fallbackToDestructiveMigrationOnDowngrade")
            .filter { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("Android Room sync-core migration inspection markers missing: ${missing.joinToString(", ")}")
        }
        if (forbidden.isNotEmpty()) {
            throw GradleException("Android Room sync-core migration contains destructive fallback markers: ${forbidden.joinToString(", ")}")
        }
        if (providers.gradleProperty("syncCoreAndroidConnected").orNull != "true") {
            logger.lifecycle(
                "Android Room migration static/unit inspection verified; run with " +
                    "-PsyncCoreAndroidConnected=true and a device/emulator for instrumented proof",
            )
        } else {
            logger.lifecycle("Android Room migration static, unit, and connected inspection verified")
        }
    }
}

tasks.register("inspectAndroidKeyStorage") {
    group = "verification"
    description = "Inspects Android sync-core key storage sources and optionally runs connected inspection tests."

    dependsOn(":app:compileDebugAndroidTestKotlin")
    if (providers.gradleProperty("syncCoreAndroidConnected").orNull == "true") {
        dependsOn(":app:connectedDebugAndroidTest")
    }
    inputs.files(
        "app/src/main/java/com/example/orgclock/sync/AndroidSyncCoreIdentityAdapter.kt",
        "app/src/main/java/com/example/orgclock/sync/AndroidSyncCoreIngressServer.kt",
        "app/src/main/java/com/example/orgclock/sync/AndroidSyncCoreTlsIdentity.kt",
        "app/src/main/java/com/example/orgclock/sync/AndroidSyncCoreTransport.kt",
        "app/src/androidTest/java/com/example/orgclock/sync/AndroidSyncCorePlatformAdapterInstrumentedTest.kt",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = androidKeyStorageRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("Android sync-core key storage inspection markers missing: ${missing.joinToString(", ")}")
        }
        if (providers.gradleProperty("syncCoreAndroidConnected").orNull != "true") {
            logger.lifecycle(
                "Android key storage static inspection verified; run with " +
                    "-PsyncCoreAndroidConnected=true and a device/emulator for instrumented proof",
            )
        } else {
            logger.lifecycle("Android key storage static and connected inspection verified")
        }
    }
}

tasks.register("inspectAndroidUnsignedEndpointPolicy") {
    group = "verification"
    description = "Inspects Android release/debug unsigned sync endpoint exposure policy."

    dependsOn(":app:testDebugUnitTest")
    inputs.files(
        "app/src/main/AndroidManifest.xml",
        "app/src/main/java/com/example/orgclock/MainActivity.kt",
        "app/src/main/java/com/example/orgclock/sync/AndroidSyncCoreIngressServer.kt",
        "app/src/test/java/com/example/orgclock/sync/AndroidSyncCoreIngressServerTest.kt",
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = androidUnsignedEndpointPolicyRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        val productionText = listOf(
            "app/src/main/AndroidManifest.xml",
            "app/src/main/java/com/example/orgclock/MainActivity.kt",
            "app/src/main/java/com/example/orgclock/sync/AndroidSyncCoreIngressServer.kt",
        ).map { file(it) }
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val forbidden = listOf(
            "/v1/incoming-command",
            "android:usesCleartextTraffic=\"true\"",
            "fallbackToDestructiveMigration",
        ).filter { marker -> productionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("Android unsigned endpoint policy markers missing: ${missing.joinToString(", ")}")
        }
        if (forbidden.isNotEmpty()) {
            throw GradleException("Android unsigned endpoint policy contains forbidden markers: ${forbidden.joinToString(", ")}")
        }
        logger.lifecycle("Android unsigned endpoint policy static inspection and tests verified")
    }
}

tasks.register("inspectDesktopKeyStorage") {
    group = "verification"
    description = "Inspects Desktop sync-core key storage sources and runs desktop key-storage tests."

    dependsOn(":desktopApp:test")
    inputs.files(
        "desktopApp/src/main/kotlin/com/example/orgclock/desktop/DesktopSyncIdentity.kt",
        "desktopApp/src/main/kotlin/com/example/orgclock/desktop/DesktopTlsIdentity.kt",
        "desktopApp/src/main/kotlin/com/example/orgclock/desktop/DesktopSyncCoreCredentialStore.kt",
        "desktopApp/src/test/kotlin/com/example/orgclock/desktop/DesktopSyncIdentityTest.kt",
        "desktopApp/src/test/kotlin/com/example/orgclock/desktop/DesktopSyncCoreCredentialStoreTest.kt",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = desktopKeyStorageRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("Desktop sync-core key storage inspection markers missing: ${missing.joinToString(", ")}")
        }
        logger.lifecycle("Desktop key storage static inspection and tests verified")
    }
}

tasks.register("inspectSecretRedaction") {
    group = "verification"
    description = "Inspects sync-core secret redaction sources and runs ingress redaction tests."

    dependsOn(
        ":sync-core:allTests",
        ":app:testDebugUnitTest",
        ":desktopApp:test",
    )
    inputs.files(
        "sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/security/RawIngressReceiver.kt",
        "sync-core/src/commonTest/kotlin/io/github/shgnaka/orgclock/synccore/security/RawIngressReceiverTest.kt",
        "app/src/main/java/com/example/orgclock/sync/AndroidSyncCoreIngressServer.kt",
        "app/src/test/java/com/example/orgclock/sync/AndroidSyncCoreIngressServerTest.kt",
        "desktopApp/src/main/kotlin/com/example/orgclock/desktop/DesktopSyncCoreIngress.kt",
        "desktopApp/src/test/kotlin/com/example/orgclock/desktop/DesktopSyncCoreIngressTest.kt",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = syncCoreSecretRedactionRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("sync-core secret redaction inspection markers missing: ${missing.joinToString(", ")}")
        }
        logger.lifecycle("sync-core secret redaction static inspection and tests verified")
    }
}

tasks.register("verifySyncCoreSecurity") {
    group = "verification"
    description = "Runs sync-core security inspection gates for key storage and secret redaction."

    dependsOn(
        "inspectAndroidKeyStorage",
        "inspectDesktopKeyStorage",
        "inspectSecretRedaction",
        "inspectSyncCoreDomainTimestampBoundary",
        "inspectAndroidUnsignedEndpointPolicy",
    )
    if (providers.gradleProperty("syncCoreSecurityLoop").orNull == "true") {
        dependsOn("verifySyncCoreSecurityLoop")
    }
}

tasks.register("inspectSyncCoreDomainTimestampBoundary") {
    group = "verification"
    description = "Inspects SEC-15 domain timestamp boundary behavior for sync-core ingress."

    dependsOn(":sync-core:allTests")
    inputs.files(
        "sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/security/EnvelopeCodec.kt",
        "sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/security/RawIngressReceiver.kt",
        "sync-core/src/commonTest/kotlin/io/github/shgnaka/orgclock/synccore/security/DomainTimestampBoundaryTest.kt",
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = syncCoreDomainTimestampBoundaryRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("sync-core domain timestamp boundary markers missing: ${missing.joinToString(", ")}")
        }
        logger.lifecycle("sync-core domain timestamp boundary static inspection and tests verified")
    }
}

tasks.register("verifySyncCoreDataRetention") {
    group = "verification"
    description = "Verifies sync-core data retention and peer revocation behavior."

    dependsOn(":sync-core:allTests")
    inputs.files(
        "sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/engine/InMemorySyncCore.kt",
        "sync-core/src/commonTest/kotlin/io/github/shgnaka/orgclock/synccore/engine/PersistentSyncCoreTest.kt",
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = syncCoreDataRetentionRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("sync-core data retention inspection markers missing: ${missing.joinToString(", ")}")
        }
        logger.lifecycle("sync-core data retention static inspection and tests verified")
    }
}

tasks.register("verifySyncCoreTestDeterminism") {
    group = "verification"
    description = "Verifies sync-core common tests avoid real time/network APIs and repeat deterministically."

    val runCountProvider = providers.gradleProperty("syncCoreDeterminismRuns")
        .map { value ->
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: throw GradleException("syncCoreDeterminismRuns must be a positive integer")
        }
        .orElse(20)
    val reportDirectory = layout.buildDirectory.dir("reports/sync-core/determinism")

    inputs.files(
        fileTree("sync-core/src/commonMain/kotlin") { include("**/*.kt") },
        fileTree("sync-core/src/commonTest/kotlin") { include("**/*.kt") },
    )
    inputs.property("syncCoreDeterminismRuns", runCountProvider)
    outputs.dir(reportDirectory)
    outputs.upToDateWhen { false }

    doLast {
        val violations = mutableListOf<String>()
        inputs.files.files
            .filter { it.isFile && it.extension == "kt" }
            .forEach { sourceFile ->
                val relativePath = sourceFile.relativeTo(rootDir).invariantSeparatorsPath
                val text = sourceFile.readText()
                syncCoreDeterminismForbiddenPatterns.forEach { (pattern, description) ->
                    if (pattern.containsMatchIn(text)) {
                        violations += "$relativePath uses forbidden nondeterministic API: $description"
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(violations.sorted().joinToString(System.lineSeparator()))
        }

        val runCount = runCountProvider.get()
        val reportDir = reportDirectory.get().asFile
        reportDir.mkdirs()
        val fingerprints = mutableListOf<String>()
        val gradleCommand = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("cmd", "/c", "gradlew.bat")
        } else {
            listOf("./gradlew")
        }

        repeat(runCount) { index ->
            exec {
                commandLine(gradleCommand + listOf("--quiet", ":sync-core:jvmTest", "--rerun-tasks"))
            }
            val resultFiles = fileTree("sync-core/build/test-results/jvmTest") {
                include("TEST-*.xml")
            }.files.sortedBy { it.name }
            if (resultFiles.isEmpty()) {
                throw GradleException("sync-core determinism run ${index + 1} produced no JVM test result XML")
            }
            val fingerprint = resultFiles.joinToString(System.lineSeparator()) { resultFile ->
                val testSuite = Regex("""<testsuite\b[^>]*>""").find(resultFile.readText())?.value
                    ?: throw GradleException("${resultFile.relativeTo(rootDir).invariantSeparatorsPath} has no testsuite element")
                fun attr(name: String): String = Regex("""\b$name="([^"]*)"""")
                    .find(testSuite)
                    ?.groupValues
                    ?.get(1)
                    ?: throw GradleException("${resultFile.name} missing $name attribute")
                val failures = attr("failures")
                val errors = attr("errors")
                if (failures != "0" || errors != "0") {
                    throw GradleException("${resultFile.name} reported failures=$failures errors=$errors")
                }
                "${attr("name")}:tests=${attr("tests")}:skipped=${attr("skipped")}:failures=$failures:errors=$errors"
            }
            fingerprints += fingerprint
            reportDir.resolve("run-${index + 1}.txt").writeText(fingerprint)
        }

        val distinctFingerprints = fingerprints.distinct()
        if (distinctFingerprints.size != 1) {
            throw GradleException("sync-core JVM common test outcomes were not deterministic across $runCount runs")
        }
        reportDir.resolve("summary.txt").writeText(
            "sync-core determinism verified: $runCount JVM common-test runs, " +
                "no forbidden real time/network APIs" + System.lineSeparator(),
        )
        logger.lifecycle("sync-core test determinism verified: $runCount JVM common-test runs")
    }
}

tasks.register("verifySyncCoreSecurityLoop") {
    group = "verification"
    description = "Runs the sync-core transport LAN security-loop module."

    val iterations = providers.gradleProperty("syncCoreSecurityLoopIterations").orElse("3")
    val skipGates = providers.gradleProperty("syncCoreSecurityLoopSkipGates").orNull == "true"
    val outputDirectory = layout.buildDirectory.dir("reports/security-loop/sync-core-transport-lan")
    inputs.files(
        "security-loop/run.py",
        "security-loop/run.ps1",
        "security-loop/modules/sync-core-transport-lan/manifest.json",
        "security-loop/modules/sync-core-transport-lan/attackers/static_v1.sh",
        "security-loop/modules/sync-core-transport-lan/defenders/static_v1.sh",
    )
    outputs.dir(outputDirectory)
    outputs.upToDateWhen { false }

    doLast {
        val args = mutableListOf(
            "-File",
            file("security-loop/run.ps1").absolutePath,
            "--module",
            "sync-core-transport-lan",
            "--iterations",
            iterations.get(),
            "--out",
            outputDirectory.get().asFile.absolutePath,
            "--fail-on-high",
        )
        if (skipGates) {
            args += "--skip-gates"
        }
        exec {
            commandLine(listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass") + args)
        }
        logger.lifecycle(
            "sync-core security-loop report written to " +
                outputDirectory.get().asFile.relativeTo(rootDir).invariantSeparatorsPath,
        )
    }
}

tasks.register("verifySyncCoreProtocolCompatibility") {
    group = "verification"
    description = "Verifies sync-core legacy fixture, protocol evolution, canonicalization, and unsupported-protocol coverage."

    dependsOn(
        ":sync-core:allTests",
        ":app:testDebugUnitTest",
    )
    inputs.files(
        "sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/security/EnvelopeCodec.kt",
        "sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/security/RawIngressReceiver.kt",
        "sync-core/src/commonTest/kotlin/io/github/shgnaka/orgclock/synccore/security/EnvelopeCodecTest.kt",
        "sync-core/src/commonTest/kotlin/io/github/shgnaka/orgclock/synccore/security/RawIngressReceiverTest.kt",
        "app/src/test/java/com/example/orgclock/sync/SyncCoreLegacyFixtureTest.kt",
        "app/src/test/resources/synccore/legacy-v1/clock-command-payload.json",
        "app/src/test/resources/synccore/legacy-v1/clock-result-payload.json",
        "app/src/test/resources/synccore/legacy-v1/command-envelope.json",
        "app/src/test/resources/synccore/legacy-v1/command-envelope.canonical.txt",
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = syncCoreProtocolCompatibilityRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("sync-core protocol compatibility inspection markers missing: ${missing.joinToString(", ")}")
        }
        logger.lifecycle("sync-core protocol compatibility static inspection and tests verified")
    }
}

tasks.register("verifySyncCoreAppIntegration") {
    group = "verification"
    description = "Verifies sync-core app runtime mode, failure isolation, and disabled-regression coverage."

    dependsOn(":app:testDebugUnitTest")
    inputs.files(
        "app/src/main/java/com/example/orgclock/sync/SyncIntegrationService.kt",
        "app/src/main/java/com/example/orgclock/sync/SyncRuntimeManager.kt",
        "app/src/main/java/com/example/orgclock/sync/SyncRuntimeMode.kt",
        "app/src/test/java/com/example/orgclock/sync/DefaultClockCommandExecutorTest.kt",
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = syncCoreAppIntegrationRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("sync-core app integration inspection markers missing: ${missing.joinToString(", ")}")
        }
        logger.lifecycle("sync-core app integration static inspection and tests verified")
    }
}

tasks.register("verifySyncCoreClientContract") {
    group = "verification"
    description = "Verifies the OrgSyncCoreClient app-boundary contract for the internal sync-core provider."

    dependsOn(":app:testDebugUnitTest")
    inputs.files(
        "app/src/main/java/com/example/orgclock/sync/SyncCoreClient.kt",
        "app/src/main/java/com/example/orgclock/sync/InRepositorySyncCoreClientFactory.kt",
        "app/src/test/java/com/example/orgclock/sync/OrgSyncCoreClientContractTest.kt",
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = syncCoreClientContractRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("sync-core client contract inspection markers missing: ${missing.joinToString(", ")}")
        }
        logger.lifecycle("sync-core client contract static inspection and tests verified")
    }
}

tasks.register("verifySyncCoreObservabilityContract") {
    group = "verification"
    description = "Verifies sync-core metrics and stable typed error-code contract coverage."

    dependsOn(":sync-core:allTests")
    inputs.files(
        "sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/api/SyncCoreApi.kt",
        "sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/engine/InMemorySyncCore.kt",
        "sync-core/src/commonTest/kotlin/io/github/shgnaka/orgclock/synccore/engine/MetricsAndErrorContractTest.kt",
        "sync-core/src/commonTest/kotlin/io/github/shgnaka/orgclock/synccore/engine/ObservationContractTest.kt",
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = syncCoreObservabilityContractRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("sync-core observability/error contract inspection markers missing: ${missing.joinToString(", ")}")
        }
        logger.lifecycle("sync-core observability and error-code contract static inspection and tests verified")
    }
}

tasks.register("verifySyncCoreRetryContract") {
    group = "verification"
    description = "Verifies sync-core retry backoff, classification, exhaustion, and reopen coverage."

    dependsOn(
        ":sync-core:allTests",
        ":app:testDebugUnitTest",
        ":desktopApp:test",
    )
    inputs.files(
        "sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/engine/InMemorySyncCore.kt",
        "sync-core/src/commonTest/kotlin/io/github/shgnaka/orgclock/synccore/engine/InMemorySyncCoreTest.kt",
        "sync-core/src/commonTest/kotlin/io/github/shgnaka/orgclock/synccore/engine/PersistentSyncCoreTest.kt",
        "app/src/main/java/com/example/orgclock/sync/AndroidSyncCoreTransport.kt",
        "app/src/test/java/com/example/orgclock/sync/AndroidSyncCoreTransportTest.kt",
        "desktopApp/src/main/kotlin/com/example/orgclock/desktop/DesktopSyncCoreTransport.kt",
        "desktopApp/src/test/kotlin/com/example/orgclock/desktop/DesktopSyncCoreTransportTest.kt",
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = syncCoreRetryContractRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("sync-core retry contract inspection markers missing: ${missing.joinToString(", ")}")
        }
        logger.lifecycle("sync-core retry contract static inspection and tests verified")
    }
}

tasks.register("verifySyncCoreCapacityContract") {
    group = "verification"
    description = "Verifies sync-core outgoing/inbox capacity limits and concurrent writer boundaries."

    dependsOn(":sync-core:allTests")
    inputs.files(
        "sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/engine/InMemorySyncCore.kt",
        "sync-core/src/commonTest/kotlin/io/github/shgnaka/orgclock/synccore/engine/InMemorySyncCoreTest.kt",
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = syncCoreCapacityContractRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("sync-core capacity contract inspection markers missing: ${missing.joinToString(", ")}")
        }
        logger.lifecycle("sync-core capacity contract static inspection and tests verified")
    }
}

tasks.register("verifySyncCoreCancellationAndConcurrency") {
    group = "verification"
    description = "Verifies sync-core cancellation propagation and deterministic concurrency stress coverage."

    dependsOn(":sync-core:allTests")
    inputs.files(
        "sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/engine/InMemorySyncCore.kt",
        "sync-core/src/commonTest/kotlin/io/github/shgnaka/orgclock/synccore/engine/CancellationContractTest.kt",
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = syncCoreCancellationConcurrencyRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("sync-core cancellation/concurrency inspection markers missing: ${missing.joinToString(", ")}")
        }
        logger.lifecycle("sync-core cancellation and concurrency static inspection and tests verified")
    }
}

tasks.register("verifySyncCoreCommandResultRouting") {
    group = "verification"
    description = "Verifies sync-core command/result routing, restart recovery, and adapter expiration coverage."

    dependsOn(
        ":sync-core:allTests",
        ":app:testDebugUnitTest",
        ":desktopApp:test",
    )
    inputs.files(
        "app/src/main/java/com/example/orgclock/sync/InRepositorySyncCoreClientFactory.kt",
        "app/src/test/java/com/example/orgclock/sync/InRepositoryOrgSyncCoreClientTest.kt",
        "desktopApp/src/main/kotlin/com/example/orgclock/desktop/DesktopSyncCoreRuntime.kt",
        "desktopApp/src/test/kotlin/com/example/orgclock/desktop/DesktopSyncCoreRuntimeTest.kt",
        "sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/engine/InMemorySyncCore.kt",
        "sync-core/src/commonTest/kotlin/io/github/shgnaka/orgclock/synccore/engine/PersistentSyncCoreTest.kt",
        "docs/synccore-integration/in-repository-requirements.md",
        "docs/synccore-integration/in-repository-test-spec.md",
    )

    doLast {
        val inspectionText = inputs.files.files
            .filter { it.exists() }
            .joinToString(System.lineSeparator()) { it.readText() }
        val missing = syncCoreCommandResultRoutingRequiredMarkers.filterNot { marker -> inspectionText.contains(marker) }
        if (missing.isNotEmpty()) {
            throw GradleException("sync-core command/result routing inspection markers missing: ${missing.joinToString(", ")}")
        }
        logger.lifecycle("sync-core command/result routing static inspection and tests verified")
    }
}

tasks.register("verifySyncCoreArchitecture") {
    group = "verification"
    description = "Verifies sync-core module ownership, dependency direction, and public API boundary."

    dependsOn(":sync-core:compileKotlinJvm", ":sync-core:compileDebugKotlinAndroid")
    inputs.files(fileTree("sync-core/src") { include("**/*.kt") }, "sync-core/build.gradle.kts")

    doLast {
        val syncCoreProject = project(":sync-core")
        val dependencyViolations = syncCoreProject.configurations
            .flatMap { it.dependencies }
            .mapNotNull { dependency ->
                val group = dependency.group.orEmpty()
                val name = dependency.name
                val coordinate = listOf(group, name).filter { it.isNotBlank() }.joinToString(":")
                when {
                    coordinate.contains("androidx.room", ignoreCase = true) -> coordinate
                    coordinate.contains("com.example.orgclock", ignoreCase = true) -> coordinate
                    name in setOf("app", "desktopApp", "shared") -> coordinate.ifBlank { name }
                    else -> null
                }
            }
            .distinct()
        if (dependencyViolations.isNotEmpty()) {
            throw GradleException(
                ":sync-core has forbidden host/platform dependencies: " +
                    dependencyViolations.sorted().joinToString(", "),
            )
        }

        val buildText = file("sync-core/build.gradle.kts").readText()
        val forbiddenBuildMarkers = listOf(
            "project(\":app\")",
            "project(\":desktopApp\")",
            "project(\":shared\")",
            "androidx.room",
            "room-runtime",
            "room-ktx",
        )
        val buildViolations = forbiddenBuildMarkers.filter { buildText.contains(it) }
        if (buildViolations.isNotEmpty()) {
            throw GradleException("sync-core build contains forbidden markers: ${buildViolations.joinToString(", ")}")
        }

        val sourceFiles = fileTree("sync-core/src") { include("**/*.kt") }.files
        val sourceViolations = mutableListOf<String>()
        val forbiddenSourcePatterns = listOf(
            Regex("""(?m)^\s*import\s+android\.""") to "Android SDK import",
            Regex("""(?m)^\s*import\s+androidx\.""") to "AndroidX import",
            Regex("""\bandroidx\.room\b""") to "Room reference",
            Regex("""\bcom\.example\.orgclock\b""") to "host app/shared package reference",
        )
        val publicDeclarationPattern = Regex(
            """(?m)^(?!(?:internal|private)\b)(?:@\w+\s+)?(?:data\s+|sealed\s+|fun\s+|value\s+|enum\s+)*""" +
                """(?:class|interface|object|fun|val|var)\b""",
        )
        sourceFiles.forEach { sourceFile ->
            val relativePath = sourceFile.relativeTo(rootDir).invariantSeparatorsPath
            val text = sourceFile.readText()
            forbiddenSourcePatterns.forEach { (pattern, description) ->
                if (pattern.containsMatchIn(text)) {
                    sourceViolations += "$relativePath contains $description"
                }
            }
            val isMainSource = relativePath.startsWith("sync-core/src/commonMain/kotlin/") ||
                relativePath.startsWith("sync-core/src/jvmMain/kotlin/") ||
                relativePath.startsWith("sync-core/src/androidMain/kotlin/")
            val isApiMainSource = relativePath.startsWith("sync-core/src/commonMain/kotlin/") &&
                "/api/" in relativePath
            if (isMainSource && !isApiMainSource && publicDeclarationPattern.containsMatchIn(text)) {
                sourceViolations += "$relativePath contains a public top-level declaration outside commonMain api"
            }
        }
        if (sourceViolations.isNotEmpty()) {
            throw GradleException(sourceViolations.sorted().joinToString(System.lineSeparator()))
        }

        logger.lifecycle("sync-core architecture verified")
    }
}

tasks.register("syncCoreBenchmark") {
    group = "verification"
    description = "Runs the sync-core benchmark gate and writes the aggregate report."

    dependsOn(":sync-core:syncCoreBenchmark")
    if (providers.gradleProperty("syncCoreBenchmark.connected").orNull == "true") {
        dependsOn(":benchmark:connectedBenchmarkAndroidTest")
    }

    val sourceReport = project(":sync-core")
        .layout
        .buildDirectory
        .file("reports/sync-core/benchmark.txt")
    val reportFile = layout.buildDirectory.file("reports/sync-core/benchmark.txt")
    inputs.file(sourceReport)
    outputs.file(reportFile)

    doLast {
        val sourceReportFile = sourceReport.get().asFile
        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            sourceReportFile.readText() +
                System.lineSeparator() +
                "connectedBenchmark=${providers.gradleProperty("syncCoreBenchmark.connected").orNull == "true"}" +
                System.lineSeparator(),
        )
        logger.lifecycle("sync-core benchmark report written to ${report.relativeTo(rootDir).invariantSeparatorsPath}")
    }
}
