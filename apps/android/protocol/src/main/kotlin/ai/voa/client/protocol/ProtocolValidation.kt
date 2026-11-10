package ai.voa.client.protocol

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

// SPEC 3.1 공통 제약 검증기 (원문 값을 예외 메시지에 포함하지 않는다)
internal object ProtocolValidation {
    private val ID_REGEX = Regex("^[A-Za-z0-9_.:-]+$")

    fun requireValidId(value: String, fieldName: String) {
        if (value.isEmpty() || value.length > 128 || !ID_REGEX.matches(value)) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "invalid id format for field '$fieldName'",
            )
        }
    }

    fun requireNonBlank(value: String, fieldName: String) {
        if (value.isBlank()) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "blank value is not allowed for field '$fieldName'",
            )
        }
    }

    fun requireMaxLength(value: String, maxLength: Int, fieldName: String) {
        if (value.length > maxLength) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "length exceeds $maxLength for field '$fieldName'",
            )
        }
    }

    fun requireValidScore(value: Double, fieldName: String) {
        if (!value.isFinite() || value < 0.0 || value > 1.0) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "score out of [0.0, 1.0] range for field '$fieldName'",
            )
        }
    }

    fun requireValidBBox(bbox: List<Double>, fieldName: String) {
        if (bbox.size != 4 || bbox.any { !it.isFinite() }) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "bbox must contain exactly 4 finite numbers for field '$fieldName'",
            )
        }
        val (x1, y1, x2, y2) = bbox
        val validX = x1 >= 0.0 && x1 < x2 && x2 <= 1.0
        val validY = y1 >= 0.0 && y1 < y2 && y2 <= 1.0
        if (!validX || !validY) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "bbox bounds violated for field '$fieldName'",
            )
        }
    }

    fun requireUtcTimestamp(value: String, fieldName: String) {
        val parsed = try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "invalid ISO-8601 timestamp for field '$fieldName'",
            )
        }
        if (parsed.offset != ZoneOffset.UTC) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "timestamp must be UTC (offset +00:00 or Z) for field '$fieldName'",
            )
        }
    }
}

