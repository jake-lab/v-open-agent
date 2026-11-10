package ai.voa.client.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class ModelRef(
    val id: String,
    val revision: String,
) {
    init {
        ProtocolValidation.requireNonBlank(id, "model.id")
        ProtocolValidation.requireNonBlank(revision, "model.revision")
    }
}

@Serializable
data class Detection(
    val label: String,
    @SerialName("class_id")
    val classId: Int? = null,
    val bbox: List<Double>,
    val score: Double,
) {
    init {
        ProtocolValidation.requireNonBlank(label, "detections.label")
        ProtocolValidation.requireValidBBox(bbox, "detections.bbox")
        ProtocolValidation.requireValidScore(score, "detections.score")
    }
}

@Serializable
data class Track(
    @SerialName("track_id")
    val trackId: String,
    val label: String,
    val bbox: List<Double>,
    val score: Double,
    val quality: TrackQuality,
    @SerialName("motion_class")
    val motionClass: MotionClass,
    @SerialName("age_frames")
    val ageFrames: Int,
) {
    init {
        ProtocolValidation.requireValidId(trackId, "tracks.track_id")
        ProtocolValidation.requireNonBlank(label, "tracks.label")
        ProtocolValidation.requireValidBBox(bbox, "tracks.bbox")
        ProtocolValidation.requireValidScore(score, "tracks.score")
        if (ageFrames < 1) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "tracks.age_frames must be >= 1",
            )
        }
    }
}

// SPEC 3.3 SoundEventPayload.events 의 (label, score) 2-원소 JSON 튜플 표현
@Serializable(with = SoundEventEntrySerializer::class)
data class SoundEventEntry(
    val label: String,
    val score: Double,
) {
    init {
        ProtocolValidation.requireNonBlank(label, "events.label")
        ProtocolValidation.requireValidScore(score, "events.score")
    }
}

internal object SoundEventEntrySerializer : KSerializer<SoundEventEntry> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("SoundEventEntry")

    override fun deserialize(decoder: Decoder): SoundEventEntry {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("SoundEventEntry supports JSON only")
        val array = jsonDecoder.decodeJsonElement() as? JsonArray
            ?: throw SerializationException("sound event entry must be a 2-element JSON array")
        if (array.size != 2) {
            throw SerializationException("sound event entry must contain exactly 2 elements")
        }
        val labelPrimitive = array[0] as? JsonPrimitive
            ?: throw SerializationException("sound event label must be a string")
        val scorePrimitive = array[1] as? JsonPrimitive
            ?: throw SerializationException("sound event score must be a number")
        if (!labelPrimitive.isString) {
            throw SerializationException("sound event label must be a string")
        }
        val score = scorePrimitive.doubleOrNull
            ?: throw SerializationException("sound event score must be a valid number")
        return SoundEventEntry(
            label = labelPrimitive.jsonPrimitive.content,
            score = score,
        )
    }

    override fun serialize(encoder: Encoder, value: SoundEventEntry) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("SoundEventEntry supports JSON only")
        jsonEncoder.encodeJsonElement(
            JsonArray(
                listOf(
                    JsonPrimitive(value.label),
                    JsonPrimitive(value.score),
                ),
            ),
        )
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Payload {
    val sourceKind: SourceKind
}

@Serializable
@SerialName("object_detection")
data class DetectionPayload(
    @SerialName("frame_id")
    val frameId: String,
    val detections: List<Detection>,
) : Payload {
    override val sourceKind: SourceKind get() = SourceKind.OBJECT_DETECTION

    init {
        ProtocolValidation.requireValidId(frameId, "payload.frame_id")
    }
}

@Serializable
@SerialName("track_state")
data class TrackPayload(
    @SerialName("frame_id")
    val frameId: String,
    val tracks: List<Track>,
) : Payload {
    override val sourceKind: SourceKind get() = SourceKind.TRACK_STATE

    init {
        ProtocolValidation.requireValidId(frameId, "payload.frame_id")
    }
}

@Serializable
@SerialName("sound_event")
data class SoundEventPayload(
    @SerialName("chunk_id")
    val chunkId: String,
    @SerialName("window_start_ns")
    val windowStartNs: Long,
    @SerialName("window_end_ns")
    val windowEndNs: Long,
    val events: List<SoundEventEntry>,
) : Payload {
    override val sourceKind: SourceKind get() = SourceKind.SOUND_EVENT

    init {
        ProtocolValidation.requireValidId(chunkId, "payload.chunk_id")
        if (windowStartNs >= windowEndNs) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "payload.window_start_ns must be < window_end_ns",
            )
        }
    }
}

@Serializable
@SerialName("asr_transcript")
data class AsrPayload(
    @SerialName("utterance_id")
    val utteranceId: String,
    val revision: Int,
    @SerialName("is_final")
    val isFinal: Boolean,
    val text: String,
    @SerialName("speaker_role")
    val speakerRole: SpeakerRole,
    @SerialName("authentication_basis")
    val authenticationBasis: AuthenticationBasis,
) : Payload {
    override val sourceKind: SourceKind get() = SourceKind.ASR_TRANSCRIPT

    init {
        ProtocolValidation.requireValidId(utteranceId, "payload.utterance_id")
        if (revision < 0) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "payload.revision must be >= 0",
            )
        }
        ProtocolValidation.requireMaxLength(text, 500, "payload.text")
    }

    // 비신뢰/음성 원문이 로그에 찍히지 않도록 toString에서 텍스트를 가린다
    override fun toString(): String =
        "AsrPayload(utteranceId=$utteranceId, revision=$revision, isFinal=$isFinal, speakerRole=$speakerRole, authenticationBasis=$authenticationBasis, text=<redacted>)"
}

@Serializable
@SerialName("ocr_text")
data class OcrPayload(
    @SerialName("frame_id")
    val frameId: String,
    val text: String,
    val bbox: List<Double>,
    val language: String,
    val score: Double,
) : Payload {
    override val sourceKind: SourceKind get() = SourceKind.OCR_TEXT

    init {
        ProtocolValidation.requireValidId(frameId, "payload.frame_id")
        ProtocolValidation.requireMaxLength(text, 500, "payload.text")
        ProtocolValidation.requireValidBBox(bbox, "payload.bbox")
        ProtocolValidation.requireNonBlank(language, "payload.language")
        ProtocolValidation.requireValidScore(score, "payload.score")
    }

    // 비신뢰 OCR 원문이 로그에 찍히지 않도록 toString에서 텍스트를 가린다
    override fun toString(): String =
        "OcrPayload(frameId=$frameId, language=$language, score=$score, text=<redacted>)"
}

@Serializable
@SerialName("health")
data class HealthPayload(
    val status: HealthStatus,
    @SerialName("detail_code")
    val detailCode: String,
) : Payload {
    override val sourceKind: SourceKind get() = SourceKind.HEALTH

    init {
        ProtocolValidation.requireNonBlank(detailCode, "payload.detail_code")
    }
}

// INV-05 결정론적 직렬화를 위해 quality_flags 집합을 사전순 정렬 리스트로 직렬화한다
internal object SortedQualityFlagsSerializer : KSerializer<Set<QualityFlag>> {
    private val delegate = ListSerializer(QualityFlag.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): Set<QualityFlag> =
        delegate.deserialize(decoder).toSet()

    override fun serialize(encoder: Encoder, value: Set<QualityFlag>) {
        val sorted = value.sortedBy { flag ->
            when (flag) {
                QualityFlag.CAMERA_MOTION_UNCOMPENSATED -> "camera_motion_uncompensated"
                QualityFlag.CLOCK_UNRELIABLE -> "clock_unreliable"
                QualityFlag.FRAME_DROPPED -> "frame_dropped"
                QualityFlag.LOW_LIGHT -> "low_light"
                QualityFlag.OCCLUDED -> "occluded"
            }
        }
        delegate.serialize(encoder, sorted)
    }
}

@Serializable
data class ObservationDraft(
    @SerialName("stream_id")
    val streamId: String,
    val seq: Int,
    @SerialName("capture_ts_monotonic_ns")
    val captureTsMonotonicNs: Long,
    @SerialName("clock_quality")
    val clockQuality: ClockQuality = ClockQuality.UNKNOWN,
    val model: ModelRef? = null,
    @Serializable(with = SortedQualityFlagsSerializer::class)
    @SerialName("quality_flags")
    val qualityFlags: Set<QualityFlag> = emptySet(),
    val payload: Payload,
) {
    init {
        ProtocolValidation.requireValidId(streamId, "stream_id")
        if (seq < 0) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "seq must be >= 0",
            )
        }
    }
}

