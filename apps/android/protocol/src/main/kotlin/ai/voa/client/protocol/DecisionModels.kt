package ai.voa.client.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Alert(
    @SerialName("alert_id")
    val alertId: String,
    val text: String,
    @SerialName("text_kind")
    val textKind: TextKind,
    @SerialName("delivery_state")
    val deliveryState: DeliveryState,
    @SerialName("valid_until_utc")
    val validUntilUtc: String? = null,
) {
    init {
        ProtocolValidation.requireValidId(alertId, "alert_id")
        ProtocolValidation.requireNonBlank(text, "text")
        if (validUntilUtc != null) {
            ProtocolValidation.requireUtcTimestamp(validUntilUtc, "valid_until_utc")
        }
    }

    // 경고 문구가 로그에 남지 않도록 toString에서 텍스트를 가린다 (05-android-client-plan.md 7절)
    override fun toString(): String =
        "Alert(alertId=$alertId, textKind=$textKind, deliveryState=$deliveryState, validUntilUtc=$validUntilUtc)"
}

@Serializable
data class RiskDecision(
    @SerialName("schema_version")
    val schemaVersion: String,
    @SerialName("risk_event_id")
    val riskEventId: String,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("scenario_id")
    val scenarioId: String,
    @SerialName("policy_version")
    val policyVersion: String,
    val state: RiskState,
    @SerialName("previous_state")
    val previousState: RiskState? = null,
    val severity: Severity? = null,
    @SerialName("evidence_ids")
    val evidenceIds: List<String>,
    @SerialName("reason_codes")
    val reasonCodes: List<ReasonCode>,
    @SerialName("model_versions")
    val modelVersions: Map<String, String> = emptyMap(),
    @SerialName("decided_at_utc")
    val decidedAtUtc: String,
    val alert: Alert? = null,
    @SerialName("fixture_note")
    val fixtureNote: String? = null,
) {
    init {
        if (schemaVersion != ProtocolContract.SCHEMA_VERSION) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "unsupported schema_version",
            )
        }
        ProtocolValidation.requireValidId(riskEventId, "risk_event_id")
        ProtocolValidation.requireValidId(sessionId, "session_id")
        ProtocolValidation.requireValidId(deviceId, "device_id")
        ProtocolValidation.requireValidId(scenarioId, "scenario_id")
        ProtocolValidation.requireNonBlank(policyVersion, "policy_version")
        evidenceIds.forEach { ProtocolValidation.requireValidId(it, "evidence_ids") }
        ProtocolValidation.requireUtcTimestamp(decidedAtUtc, "decided_at_utc")

        // 검증기 1: state == warning <=> alert != null
        val isWarning = state == RiskState.WARNING
        if (isWarning != (alert != null)) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "state is warning if and only if alert is present",
            )
        }
        // 검증기 2: state == warning => severity != null && evidence_ids.size >= 1
        if (isWarning && (severity == null || evidenceIds.isEmpty())) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "warning requires severity and at least one evidence id",
            )
        }
        // 검증기 3: reason_codes는 1개 이상
        if (reasonCodes.isEmpty()) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "reason_codes must not be empty",
            )
        }
        // 검증기 4: RiskDecision 내부 alert는 생성 시점 불변 기록이므로 queued여야 함
        if (alert != null && alert.deliveryState != DeliveryState.QUEUED) {
            throw ProtocolParseException(
                errorCode = ProtocolParseException.CODE_REJECTED_SCHEMA,
                reason = "an alert embedded in a decision must be queued",
            )
        }
    }
}

