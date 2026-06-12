package com.example.orgclock.template

import kotlinx.datetime.Instant

const val TEMPLATE_SHARING_SCHEMA_V1 = "orgclock.template.v1"

data class SharedTemplateRevision(
    val schema: String = TEMPLATE_SHARING_SCHEMA_V1,
    val revisionId: String,
    val parentRevisionId: String?,
    val content: String,
    val contentHash: String,
    val updatedAt: Instant,
    val updatedByDeviceId: String,
) {
    init {
        require(schema == TEMPLATE_SHARING_SCHEMA_V1) { "Unsupported template schema: $schema" }
        require(revisionId.isNotBlank()) { "Template revision ID cannot be blank." }
        require(contentHash.isNotBlank()) { "Template content hash cannot be blank." }
        require(updatedByDeviceId.isNotBlank()) { "Template updater device ID cannot be blank." }
    }
}

data class TemplateFetchRequest(
    val sourcePeerId: String,
    val targetPeerId: String,
)

data class TemplateFetchResponse(
    val sourcePeerId: String,
    val targetPeerId: String,
    val revision: SharedTemplateRevision?,
)

data class TemplatePushRequest(
    val sourcePeerId: String,
    val targetPeerId: String,
    val revision: SharedTemplateRevision,
    val expectedCurrentRevisionId: String?,
)

sealed interface TemplatePushResult {
    data class Accepted(val revisionId: String) : TemplatePushResult
    data class Conflict(
        val currentRevisionId: String?,
        val incomingRevisionId: String,
    ) : TemplatePushResult
}

interface TemplateSyncTransport {
    suspend fun fetchTemplate(request: TemplateFetchRequest): TemplateFetchResponse
    suspend fun pushTemplate(request: TemplatePushRequest): TemplatePushResult
}

interface SharedTemplateStore {
    suspend fun readRevision(): Result<SharedTemplateRevision?>

    suspend fun writeRevision(
        revision: SharedTemplateRevision,
        expectedCurrentRevisionId: String?,
    ): Result<TemplatePushResult>
}
