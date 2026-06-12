package com.example.orgclock.template

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object TemplateSharingJsonCodec {
    fun encodeFetchRequest(value: TemplateFetchRequest): String =
        json.encodeToString(FetchRequestWire.serializer(), FetchRequestWire(value.sourcePeerId, value.targetPeerId))

    fun decodeFetchRequest(raw: String): TemplateFetchRequest =
        json.decodeFromString(FetchRequestWire.serializer(), raw).let {
            TemplateFetchRequest(it.sourcePeerId, it.targetPeerId)
        }

    fun encodeFetchResponse(value: TemplateFetchResponse): String =
        json.encodeToString(
            FetchResponseWire.serializer(),
            FetchResponseWire(value.sourcePeerId, value.targetPeerId, value.revision?.toWire()),
        )

    fun decodeFetchResponse(raw: String): TemplateFetchResponse =
        json.decodeFromString(FetchResponseWire.serializer(), raw).let {
            TemplateFetchResponse(it.sourcePeerId, it.targetPeerId, it.revision?.toModel())
        }

    fun encodePushRequest(value: TemplatePushRequest): String =
        json.encodeToString(
            PushRequestWire.serializer(),
            PushRequestWire(
                value.sourcePeerId,
                value.targetPeerId,
                value.revision.toWire(),
                value.expectedCurrentRevisionId,
            ),
        )

    fun decodePushRequest(raw: String): TemplatePushRequest =
        json.decodeFromString(PushRequestWire.serializer(), raw).let {
            TemplatePushRequest(
                it.sourcePeerId,
                it.targetPeerId,
                it.revision.toModel(),
                it.expectedCurrentRevisionId,
            )
        }

    fun encodePushResult(value: TemplatePushResult): String =
        json.encodeToString(
            PushResultWire.serializer(),
            when (value) {
                is TemplatePushResult.Accepted -> PushResultWire(true, value.revisionId)
                is TemplatePushResult.Conflict -> PushResultWire(
                    accepted = false,
                    revisionId = value.incomingRevisionId,
                    currentRevisionId = value.currentRevisionId,
                )
            },
        )

    fun decodePushResult(raw: String): TemplatePushResult =
        json.decodeFromString(PushResultWire.serializer(), raw).let {
            if (it.accepted) {
                TemplatePushResult.Accepted(it.revisionId)
            } else {
                TemplatePushResult.Conflict(it.currentRevisionId, it.revisionId)
            }
        }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}

@Serializable
private data class FetchRequestWire(val sourcePeerId: String, val targetPeerId: String)

@Serializable
private data class FetchResponseWire(
    val sourcePeerId: String,
    val targetPeerId: String,
    val revision: RevisionWire? = null,
)

@Serializable
private data class PushRequestWire(
    val sourcePeerId: String,
    val targetPeerId: String,
    val revision: RevisionWire,
    val expectedCurrentRevisionId: String? = null,
)

@Serializable
private data class PushResultWire(
    val accepted: Boolean,
    val revisionId: String,
    val currentRevisionId: String? = null,
)

@Serializable
private data class RevisionWire(
    val schema: String,
    val revisionId: String,
    val parentRevisionId: String? = null,
    val content: String,
    val contentHash: String,
    val updatedAt: String,
    val updatedByDeviceId: String,
)

private fun SharedTemplateRevision.toWire() = RevisionWire(
    schema,
    revisionId,
    parentRevisionId,
    content,
    contentHash,
    updatedAt.toString(),
    updatedByDeviceId,
)

private fun RevisionWire.toModel() = SharedTemplateRevision(
    schema,
    revisionId,
    parentRevisionId,
    content,
    contentHash,
    Instant.parse(updatedAt),
    updatedByDeviceId,
)
