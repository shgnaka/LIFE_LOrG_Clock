package com.example.orgclock.sync

import kotlinx.datetime.Instant

const val CLOCK_COMMAND_SCHEMA_V1 = "clock.command.v1"
const val CLOCK_RESULT_SCHEMA_V1 = "clock.result.v1"

data class ClockCommandTarget(
    val fileName: String,
    val headingPath: String,
)

enum class ClockCommandKind(val wireValue: String) {
    Start("clock.start"),
    Stop("clock.stop"),
    Cancel("clock.cancel"),
    ;

    companion object {
        fun fromWireValue(raw: String): ClockCommandKind? = entries.firstOrNull { it.wireValue == raw }
    }
}

data class ClockCommandPayload(
    val schema: String,
    val commandId: String,
    val kind: ClockCommandKind,
    val target: ClockCommandTarget,
    val requestedAt: Instant,
    val fromDeviceId: String,
    val requestId: String?,
)

enum class ClockResultStatus(val wireValue: String) {
    Applied("applied"),
    Failed("failed"),
    Duplicate("duplicate"),
    Rejected("rejected"),
    ;
}

enum class ClockErrorCode {
    TARGET_FILE_NOT_FOUND,
    TARGET_HEADING_NOT_FOUND,
    INVALID_HEADING_LEVEL,
    ALREADY_RUNNING,
    NO_OPEN_CLOCK,
    CONFLICT_RETRY_EXHAUSTED,
    SAVE_ROUND_TRIP_MISMATCH,
    IO_FAILURE,
    VALIDATION_FAILED,
    DUPLICATE_COMMAND,
}

data class ClockResultPayload(
    val schema: String = CLOCK_RESULT_SCHEMA_V1,
    val commandId: String,
    val status: ClockResultStatus,
    val errorCode: ClockErrorCode? = null,
    val errorMessage: String? = null,
    val appliedAt: Instant,
    val byDeviceId: String,
)
