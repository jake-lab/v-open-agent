package ai.voa.client.protocol

// 원문 입력이나 민감 토큰을 노출하지 않는 프로토콜 파싱 예외 (SPEC 7.2, 05-android-client-plan.md 9절)
class ProtocolParseException(
    val errorCode: String,
    val reason: String,
) : IllegalArgumentException("$errorCode: $reason") {
    companion object {
        const val CODE_REJECTED_SCHEMA: String = "rejected_schema"
        const val CODE_UNSUPPORTED_MESSAGE_TYPE: String = "unsupported_message_type"
        const val CODE_PROTOCOL_ERROR: String = "protocol_error"
    }
}

