package gift.onnuri.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * rate limit 버킷 키(2026-08-18, F-6). X-Forwarded-For의 <b>첫</b> 값을 쓰면 클라이언트가
 * 헤더를 위조해 매 요청 다른 버킷으로 들어갈 수 있다 — 한도가 정직한 클라이언트에게만 걸린다.
 *
 * 프로덕션은 Caddy 1홉(deploy/Caddyfile: reverse_proxy app:8080)이고 Caddy가 실제 IP를
 * 리스트 <b>끝</b>에 덧붙이므로, 마지막 값만이 TCP 유래라 위조 불가다.
 */
class ClientIpTest {

    private MockHttpServletRequest req(String remoteAddr, String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(remoteAddr);
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @Test
    void XFF가_없으면_remoteAddr을_쓴다() {
        assertEquals("10.0.0.1", ClientIp.of(req("10.0.0.1", null)));
    }

    @Test
    void XFF가_한_값이면_그_값이_실제_클라이언트다() {
        // 클라이언트가 XFF를 안 보낸 정상 요청 — Caddy가 붙인 실제 IP 하나만 남는다.
        assertEquals("198.51.100.7", ClientIp.of(req("172.18.0.4", "198.51.100.7")));
    }

    @Test
    void XFF가_여러_값이면_마지막_값을_쓴다() {
        // 앞부분은 공격자가 위조한 값, 마지막이 Caddy가 본 실제 IP.
        assertEquals("198.51.100.7",
                ClientIp.of(req("172.18.0.4", "203.0.113.1, 203.0.113.2, 198.51.100.7")));
    }

    @Test
    void 위조된_앞값이_달라도_마지막_값이_같으면_같은_키다() {
        String a = ClientIp.of(req("172.18.0.4", "203.0.113.1, 198.51.100.7"));
        String b = ClientIp.of(req("172.18.0.4", "8.8.8.8, 198.51.100.7"));
        assertEquals(a, b, "같은 클라이언트는 XFF 위조와 무관하게 한 버킷");
    }

    @Test
    void 공백과_빈_요소를_건너뛴다() {
        assertEquals("198.51.100.7", ClientIp.of(req("172.18.0.4", " 203.0.113.1 ,  198.51.100.7  ")));
        assertEquals("198.51.100.7", ClientIp.of(req("172.18.0.4", "203.0.113.1, 198.51.100.7, ")));
    }

    @Test
    void XFF가_비었거나_공백뿐이면_remoteAddr로_돌아간다() {
        assertEquals("10.0.0.1", ClientIp.of(req("10.0.0.1", "")));
        assertEquals("10.0.0.1", ClientIp.of(req("10.0.0.1", "   ")));
        assertEquals("10.0.0.1", ClientIp.of(req("10.0.0.1", " , , ")));
    }
}
