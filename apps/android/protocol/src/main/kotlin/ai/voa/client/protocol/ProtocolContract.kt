package ai.voa.client.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

// 서버와 공유하는 스키마 버전 및 안전한 JSON 직렬화/파싱 진입점 (SPEC 3, 7)
object ProtocolContract {
    const val SCHEMA_VERSION: String = "0.1.0"

    val json: Json = Json {
        // 알 수 없는 필드는 엄격히 거부한다 (SPEC 7.2 extra="forbid")
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    private val knownServerMessageTypes = setOf(
        "auth_ok",
        "admission",
        "decision",
        "alert",
        "notice",
        "heartbeat",
        "error",
    )

    private val knownClientMessageTypes = setOf(
        "auth",
        "resume",
        "observation",
        "ack",
    )

    fun decodeObservationDraft(rawJson: String): ObservationDraft =
        decodeSafely(fieldContext = "ObservationDraft") {
            json.decodeFromString<ObservationDraft>(rawJson)
        }

    fun decodeRiskDecision(rawJson: String): RiskDecision =
        decodeSafely(fieldContext = "RiskDecision") {
            json.decodeFromString<RiskDecision>(rawJson)
        }

    fun decodeAlert(rawJson: String): Alert =
        decodeSafely(fieldContext = "Alert") {
            json.decodeFromString<Alert>(rawJson)
        }

    fun decodeServerMessage(rawJson: String): ServerMessage {
        val messageType = extractMessageType(rawJson)
        if (messageType !in knownServerMessageTypes) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_UNSUPPORTED_MESSAGE_TYPE,
                reason = "unsupported server message type",
            )
        }
        return decodeSafely(fieldContext = "ServerMessage") {
            json.decodeFromString<ServerMessage>(rawJson)
        }
    }

    fun decodeClientMessage(rawJson: String): ClientMessage {
        val messageType = extractMessageType(rawJson)
        if (messageType !in knownClientMessageTypes) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_UNSUPPORTED_MESSAGE_TYPE,
                reason = "unsupported client message type",
            )
        }
        return decodeSafely(fieldContext = "ClientMessage") {
            json.decodeFromString<ClientMessage>(rawJson)
        }
    }

    private fun extractMessageType(rawJson: String): String {
        val root = try {
            json.parseToJsonElement(rawJson)
        } catch (_: SerializationException) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "malformed JSON frame",
            )
        }
        val obj = root as? JsonObject ?: throw ProtocolParseException(
            errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
            reason = "JSON frame root must be an object",
        )
        val typeElement = obj["type"] as? JsonPrimitive ?: throw ProtocolParseException(
            errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
            reason = "missing or non-primitive 'type' discriminator",
        )
        if (!typeElement.isString) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "'type' discriminator must be a string",
            )
        }
        return typeElement.jsonPrimitive.content
    }

    private inline fun <T> decodeSafely(fieldContext: String, block: () -> T): T {
        try {
            return block()
        } catch (e: ProtocolParseException) {
            throw e
        } catch (_: SerializationException) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "schema validation failed for $fieldContext",
            )
        } catch (_: IllegalArgumentException) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "contract invariant failed for $fieldContext",
            )
        }
    }
}

@Serializable
enum class SourceKind {
    @SerialName("object_detection")
    OBJECT_DETECTION,

    @SerialName("track_state")
    TRACK_STATE,

    @SerialName("sound_event")
    SOUND_EVENT,

    @SerialName("asr_transcript")
    ASR_TRANSCRIPT,

    @SerialName("ocr_text")
    OCR_TEXT,

    @SerialName("health")
    HEALTH,
}

@Serializable
enum class ClockQuality {
    @SerialName("synced")
    SYNCED,

    @SerialName("estimated")
    ESTIMATED,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
enum class RiskState {
    @SerialName("clear")
    CLEAR,

    @SerialName("candidate")
    CANDIDATE,

    @SerialName("warning")
    WARNING,

    @SerialName("unknown")
    UNKNOWN,

    @SerialName("resolved")
    RESOLVED,
}

@Serializable
enum class Verdict {
    @SerialName("met")
    MET,

    @SerialName("absent")
    ABSENT,

    @SerialName("indeterminate")
    INDETERMINATE,
}

@Serializable
enum class Severity {
    @SerialName("notice")
    NOTICE,

    @SerialName("attention")
    ATTENTION,
}

@Serializable
enum class TextKind {
    @SerialName("approved_template")
    APPROVED_TEMPLATE,

    @SerialName("validated_explanation")
    VALIDATED_EXPLANATION,
}

@Serializable
enum class DeliveryState {
    @SerialName("queued")
    QUEUED,

    @SerialName("sent")
    SENT,

    @SerialName("received")
    RECEIVED,

    @SerialName("played")
    PLAYED,

    @SerialName("expired")
    EXPIRED,

    @SerialName("unknown_delivery")
    UNKNOWN_DELIVERY,
}

@Serializable
enum class TrackQuality {
    @SerialName("good")
    GOOD,

    @SerialName("degraded")
    DEGRADED,

    @SerialName("lost")
    LOST,
}

@Serializable
enum class MotionClass {
    @SerialName("static")
    STATIC,

    @SerialName("moving")
    MOVING,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
enum class HealthStatus {
    @SerialName("ok")
    OK,

    @SerialName("degraded")
    DEGRADED,

    @SerialName("down")
    DOWN,
}

@Serializable
enum class QualityFlag {
    @SerialName("occluded")
    OCCLUDED,

    @SerialName("camera_motion_uncompensated")
    CAMERA_MOTION_UNCOMPENSATED,

    @SerialName("low_light")
    LOW_LIGHT,

    @SerialName("frame_dropped")
    FRAME_DROPPED,

    @SerialName("clock_unreliable")
    CLOCK_UNRELIABLE,
}

@Serializable
enum class SpeakerRole {
    @SerialName("authenticated_user")
    AUTHENTICATED_USER,

    @SerialName("untrusted_environment")
    UNTRUSTED_ENVIRONMENT,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
enum class AuthenticationBasis {
    @SerialName("explicit_push_to_talk")
    EXPLICIT_PUSH_TO_TALK,

    @SerialName("none")
    NONE,
}

@Serializable
enum class AdmissionStatus {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("rejected_session_mismatch")
    REJECTED_SESSION_MISMATCH,

    @SerialName("duplicate")
    DUPLICATE,

    @SerialName("out_of_order")
    OUT_OF_ORDER,

    @SerialName("stale_on_arrival")
    STALE_ON_ARRIVAL,

    @SerialName("config_error")
    CONFIG_ERROR,
}

@Serializable
enum class ReasonCode {
    @SerialName("condition_observed")
    CONDITION_OBSERVED,

    @SerialName("temporal_confirmation_passed")
    TEMPORAL_CONFIRMATION_PASSED,

    @SerialName("condition_absent_confirmed")
    CONDITION_ABSENT_CONFIRMED,

    @SerialName("episode_closed")
    EPISODE_CLOSED,

    @SerialName("input_stale")
    INPUT_STALE,

    @SerialName("quality_blocked")
    QUALITY_BLOCKED,

    @SerialName("track_lost")
    TRACK_LOST,

    @SerialName("sensor_down")
    SENSOR_DOWN,

    @SerialName("fresh_evidence_returned")
    FRESH_EVIDENCE_RETURNED,
}

@Serializable
enum class InputCap {
    @SerialName("camera_0")
    CAMERA_0,

    @SerialName("mic_0")
    MIC_0,

    @SerialName("imu_0")
    IMU_0,

    @SerialName("ptt_0")
    PTT_0,
}

@Serializable
enum class OutputCap {
    @SerialName("hud")
    HUD,

    @SerialName("speaker")
    SPEAKER,

    @SerialName("haptic")
    HAPTIC,
}

@Serializable
enum class AckStatus {
    @SerialName("received")
    RECEIVED,

    @SerialName("played")
    PLAYED,
}

@Serializable
enum class NoticeKind {
    @SerialName("degraded")
    DEGRADED,
}
