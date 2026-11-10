package ai.voa.client.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

// REST 세션 및 ACK API 모델 (SPEC 7.1, 7.3)
@Serializable
data class SessionCapabilities(
    val inputs: List<InputCap>,
    val outputs: List<OutputCap>,
)

@Serializable
data class CreateSessionRequest(
    @SerialName("device_id")
    val deviceId: String,
    val secret: String? = null,
    val capabilities: SessionCapabilities,
) {
    init {
        ProtocolValidation.requireValidId(deviceId, "device_id")
    }

    override fun toString(): String =
        "CreateSessionRequest(deviceId=$deviceId, secret=<redacted>, capabilities=$capabilities)"
}

@Serializable
data class CreateSessionResponse(
    @SerialName("session_id")
    val sessionId: String,
    val token: String,
    @SerialName("token_expires_at_utc")
    val tokenExpiresAtUtc: String,
    @SerialName("resume_token")
    val resumeToken: String,
    @SerialName("resume_token_expires_at_utc")
    val resumeTokenExpiresAtUtc: String,
    @SerialName("heartbeat_ms")
    val heartbeatMs: Int,
    @SerialName("watchdog_ms")
    val watchdogMs: Int,
) {
    init {
        ProtocolValidation.requireValidId(sessionId, "session_id")
        ProtocolValidation.requireNonBlank(token, "token")
        ProtocolValidation.requireUtcTimestamp(tokenExpiresAtUtc, "token_expires_at_utc")
        ProtocolValidation.requireNonBlank(resumeToken, "resume_token")
        ProtocolValidation.requireUtcTimestamp(resumeTokenExpiresAtUtc, "resume_token_expires_at_utc")
        if (heartbeatMs <= 0 || watchdogMs < 2 * heartbeatMs) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "invalid heartbeat_ms or watchdog_ms bounds",
            )
        }
    }

    override fun toString(): String =
        "CreateSessionResponse(sessionId=$sessionId, token=<redacted>, tokenExpiresAtUtc=$tokenExpiresAtUtc, resumeToken=<redacted>, resumeTokenExpiresAtUtc=$resumeTokenExpiresAtUtc, heartbeatMs=$heartbeatMs, watchdogMs=$watchdogMs)"
}

@Serializable
data class ResumeSessionRequest(
    @SerialName("resume_token")
    val resumeToken: String,
) {
    init {
        ProtocolValidation.requireNonBlank(resumeToken, "resume_token")
    }

    override fun toString(): String = "ResumeSessionRequest(resumeToken=<redacted>)"
}

@Serializable
data class ResumeSessionResponse(
    @SerialName("session_id")
    val sessionId: String? = null,
    val token: String,
    @SerialName("token_expires_at_utc")
    val tokenExpiresAtUtc: String,
    @SerialName("resume_token")
    val resumeToken: String,
    @SerialName("resume_token_expires_at_utc")
    val resumeTokenExpiresAtUtc: String,
) {
    init {
        if (sessionId != null) {
            ProtocolValidation.requireValidId(sessionId, "session_id")
        }
        ProtocolValidation.requireNonBlank(token, "token")
        ProtocolValidation.requireUtcTimestamp(tokenExpiresAtUtc, "token_expires_at_utc")
        ProtocolValidation.requireNonBlank(resumeToken, "resume_token")
        ProtocolValidation.requireUtcTimestamp(resumeTokenExpiresAtUtc, "resume_token_expires_at_utc")
    }

    override fun toString(): String =
        "ResumeSessionResponse(sessionId=$sessionId, token=<redacted>, tokenExpiresAtUtc=$tokenExpiresAtUtc, resumeToken=<redacted>, resumeTokenExpiresAtUtc=$resumeTokenExpiresAtUtc)"
}

@Serializable
data class AckAlertRequest(
    val status: AckStatus,
)

@Serializable
data class ApiErrorResponse(
    val code: String,
) {
    init {
        ProtocolValidation.requireNonBlank(code, "code")
    }
}

// WebSocket C->S 메시지 (SPEC 7.2)
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ClientMessage

@Serializable
@SerialName("auth")
data class AuthMessage(
    val token: String,
) : ClientMessage {
    init {
        ProtocolValidation.requireNonBlank(token, "token")
    }

    override fun toString(): String = "AuthMessage(token=<redacted>)"
}

@Serializable
@SerialName("resume")
data class ResumeMessage(
    @SerialName("last_alert_seq")
    val lastAlertSeq: Int,
) : ClientMessage {
    init {
        if (lastAlertSeq < 0) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "last_alert_seq must be >= 0",
            )
        }
    }
}

@Serializable
@SerialName("observation")
data class ObservationMessage(
    val observation: ObservationDraft,
) : ClientMessage

@Serializable
@SerialName("ack")
data class AckMessage(
    @SerialName("alert_id")
    val alertId: String,
    val status: AckStatus,
) : ClientMessage {
    init {
        ProtocolValidation.requireValidId(alertId, "alert_id")
    }
}

// WebSocket S->C 메시지 (SPEC 7.2)
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ServerMessage

@Serializable
@SerialName("auth_ok")
data class AuthOkMessage(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("server_time_utc")
    val serverTimeUtc: String,
) : ServerMessage {
    init {
        ProtocolValidation.requireValidId(sessionId, "session_id")
        ProtocolValidation.requireUtcTimestamp(serverTimeUtc, "server_time_utc")
    }
}

@Serializable
@SerialName("admission")
data class AdmissionMessage(
    val seq: Int,
    @SerialName("stream_id")
    val streamId: String,
    val status: AdmissionStatus,
) : ServerMessage {
    init {
        if (seq < 0) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "seq must be >= 0",
            )
        }
        ProtocolValidation.requireValidId(streamId, "stream_id")
        if (status == AdmissionStatus.ACCEPTED) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "admission message is sent only for non-accepted statuses",
            )
        }
    }
}

@Serializable
@SerialName("decision")
data class DecisionMessage(
    val decision: RiskDecision,
) : ServerMessage

@Serializable
@SerialName("alert")
data class AlertMessage(
    @SerialName("alert_seq")
    val alertSeq: Int,
    val alert: Alert,
    @SerialName("risk_event_id")
    val riskEventId: String,
    @SerialName("scenario_id")
    val scenarioId: String,
) : ServerMessage {
    init {
        if (alertSeq < 1) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "alert_seq must be >= 1",
            )
        }
        ProtocolValidation.requireValidId(riskEventId, "risk_event_id")
        ProtocolValidation.requireValidId(scenarioId, "scenario_id")
    }
}

@Serializable
@SerialName("notice")
data class NoticeMessage(
    @SerialName("notice_id")
    val noticeId: String,
    val kind: NoticeKind,
    @SerialName("scenario_id")
    val scenarioId: String,
    val text: String,
) : ServerMessage {
    init {
        ProtocolValidation.requireValidId(noticeId, "notice_id")
        ProtocolValidation.requireValidId(scenarioId, "scenario_id")
        ProtocolValidation.requireNonBlank(text, "text")
    }

    override fun toString(): String =
        "NoticeMessage(noticeId=$noticeId, kind=$kind, scenarioId=$scenarioId)"
}

@Serializable
@SerialName("heartbeat")
data class HeartbeatMessage(
    @SerialName("server_time_utc")
    val serverTimeUtc: String,
    val states: Map<String, RiskState>,
) : ServerMessage {
    init {
        ProtocolValidation.requireUtcTimestamp(serverTimeUtc, "server_time_utc")
        states.keys.forEach { ProtocolValidation.requireValidId(it, "states.scenario_id") }
    }
}

@Serializable
@SerialName("error")
data class ErrorMessage(
    val code: String,
    val message: String,
) : ServerMessage {
    init {
        ProtocolValidation.requireNonBlank(code, "code")
        ProtocolValidation.requireNonBlank(message, "message")
    }
}

