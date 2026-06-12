package com.example.orgclock.template

sealed interface TemplateSyncOutcome {
    data object AlreadyCurrent : TemplateSyncOutcome
    data class Downloaded(val revisionId: String) : TemplateSyncOutcome
    data class Uploaded(val revisionId: String) : TemplateSyncOutcome
    data class Conflict(
        val localRevisionId: String?,
        val remoteRevisionId: String?,
    ) : TemplateSyncOutcome
}

class TemplateSharingService(
    private val localStore: SharedTemplateStore,
    private val transport: TemplateSyncTransport,
    private val localPeerId: String,
    private val remotePeerId: String,
) {
    suspend fun synchronize(): Result<TemplateSyncOutcome> = runCatching {
        val local = localStore.readRevision().getOrThrow()
        val remote = transport.fetchTemplate(
            TemplateFetchRequest(localPeerId, remotePeerId),
        ).revision

        when {
            local == null && remote == null -> TemplateSyncOutcome.AlreadyCurrent
            local == null && remote != null -> {
                when (localStore.writeRevision(remote, expectedCurrentRevisionId = null).getOrThrow()) {
                    is TemplatePushResult.Accepted -> TemplateSyncOutcome.Downloaded(remote.revisionId)
                    is TemplatePushResult.Conflict -> TemplateSyncOutcome.Conflict(null, remote.revisionId)
                }
            }
            local != null && remote == null -> {
                when (
                    transport.pushTemplate(
                        TemplatePushRequest(localPeerId, remotePeerId, local, expectedCurrentRevisionId = null),
                    )
                ) {
                    is TemplatePushResult.Accepted -> TemplateSyncOutcome.Uploaded(local.revisionId)
                    is TemplatePushResult.Conflict -> TemplateSyncOutcome.Conflict(local.revisionId, null)
                }
            }
            local!!.revisionId == remote!!.revisionId -> TemplateSyncOutcome.AlreadyCurrent
            local.parentRevisionId == remote.revisionId -> {
                when (
                    transport.pushTemplate(
                        TemplatePushRequest(
                            localPeerId,
                            remotePeerId,
                            local,
                            expectedCurrentRevisionId = remote.revisionId,
                        ),
                    )
                ) {
                    is TemplatePushResult.Accepted -> TemplateSyncOutcome.Uploaded(local.revisionId)
                    is TemplatePushResult.Conflict -> TemplateSyncOutcome.Conflict(local.revisionId, remote.revisionId)
                }
            }
            remote.parentRevisionId == local.revisionId -> {
                when (
                    localStore.writeRevision(
                        remote,
                        expectedCurrentRevisionId = local.revisionId,
                    ).getOrThrow()
                ) {
                    is TemplatePushResult.Accepted -> TemplateSyncOutcome.Downloaded(remote.revisionId)
                    is TemplatePushResult.Conflict -> TemplateSyncOutcome.Conflict(local.revisionId, remote.revisionId)
                }
            }
            else -> TemplateSyncOutcome.Conflict(local.revisionId, remote.revisionId)
        }
    }
}
