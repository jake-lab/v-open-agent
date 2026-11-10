package ai.voa.client.session

import ai.voa.client.protocol.AckMessage
import ai.voa.client.protocol.AckStatus
import ai.voa.client.protocol.AuthMessage
import ai.voa.client.protocol.AuthOkMessage
import ai.voa.client.protocol.ProtocolParseException
import ai.voa.client.testing.FakeMonotonicClock
import ai.voa.client.testing.FakeTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SessionStateTest {
    @Test
    fun `fake monotonic clock advances deterministically`() {
        val clock = FakeMonotonicClock(initialMs = 1_000L)
        val monotonicClock = MonotonicClock { clock.currentMs }

        assertEquals(1_000L, monotonicClock.nowMs())
        clock.advanceBy(2_500L)
        assertEquals(3_500L, monotonicClock.nowMs())
    }

    @Test
    fun `fake monotonic clock rejects negative delta`() {
        val clock = FakeMonotonicClock(initialMs = 1_000L)
        assertFailsWith<IllegalArgumentException> {
            clock.advanceBy(-1L)
        }
    }

    @Test
    fun `fake transport validates outbound and inbound frames without external server`() {
        val transport = FakeTransport()
        transport.sendClientMessage(AuthMessage(token = "tok_test_01"))
        transport.sendClientMessage(AckMessage(alertId = "alert_01", status = AckStatus.RECEIVED))
        assertEquals(2, transport.outboundFrames.size)

        val serverMsg = transport.receiveRawServerFrame(
            """{"type":"auth_ok","session_id":"sess_01","server_time_utc":"2026-11-09T00:00:00Z"}""",
        )
        assertIs<AuthOkMessage>(serverMsg)
        assertEquals(1, transport.inboundMessages.size)

        // 잘못된 서버 프레임 주입 시 프로토콜 예외 발생
        assertFailsWith<ProtocolParseException> {
            transport.receiveRawServerFrame("""{"type":"unknown_event"}""")
        }
    }
}
