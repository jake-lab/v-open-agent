package ai.voa.client.testing

import ai.voa.client.protocol.ClientMessage
import ai.voa.client.protocol.ProtocolContract
import ai.voa.client.protocol.ServerMessage
import kotlinx.serialization.encodeToString

// 외부 서버 없이 프로토콜과 세션 계층을 검증하기 위한 결정론적 가짜 전송 채널 (AND-01)
class FakeTransport {
    private val _outboundFrames = mutableListOf<String>()
    private val _inboundMessages = mutableListOf<ServerMessage>()
    var closeCode: Int? = null
        private set

    val outboundFrames: List<String> get() = _outboundFrames.toList()
    val inboundMessages: List<ServerMessage> get() = _inboundMessages.toList()

    fun sendClientMessage(message: ClientMessage) {
        check(closeCode == null) { "cannot send on a closed transport" }
        val encoded = ProtocolContract.json.encodeToString(message)
        // 송신 메시지가 계약 스키마를 준수하는지 즉시 재검증한다
        ProtocolContract.decodeClientMessage(encoded)
        _outboundFrames.add(encoded)
    }

    fun receiveRawServerFrame(rawJson: String): ServerMessage {
        check(closeCode == null) { "cannot receive on a closed transport" }
        val decoded = ProtocolContract.decodeServerMessage(rawJson)
        _inboundMessages.add(decoded)
        return decoded
    }

    fun close(code: Int) {
        closeCode = code
    }
}

