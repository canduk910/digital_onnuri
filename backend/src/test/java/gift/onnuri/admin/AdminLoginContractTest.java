package gift.onnuri.admin;

import gift.onnuri.chat.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 관리자 로그인 계약(2026-08-18) — admin-report.html이 {"key": …}를 그대로 소비한다.
 * 키 이름·상태코드가 바뀌면 관리자 페이지가 조용히 진입 불가가 된다.
 *
 * 스프링 컨텍스트 없이 컨트롤러를 직접 조립한다(이 저장소 테스트는 DB 없이 도는 것이 규약).
 */
class AdminLoginContractTest {

    private static final String KEY = "abcdef0123456789";
    private static final String PW = "온누리비번2026";

    private AdminController controller(String password, String key, RateLimiter limiter) {
        return new AdminController(limiter, key, password);
    }

    private RateLimiter openLimiter() {
        return new RateLimiter(1000, 1000, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    private MockHttpServletRequest req(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(ip);
        return r;
    }

    /** 프록시 뒤 요청 — 프로덕션 Caddy는 실제 IP를 XFF 리스트 끝에 덧붙인다. */
    private MockHttpServletRequest proxied(String xff) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("172.18.0.4");   // 컨테이너 네트워크의 Caddy — 모든 요청이 동일
        r.addHeader("X-Forwarded-For", xff);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> res) {
        return (Map<String, Object>) res.getBody();
    }

    @Test
    void 정상_비밀번호는_200과_관리자_키를_돌려준다() {
        ResponseEntity<?> res = controller(PW, KEY, openLimiter())
                .login(Map.of("password", PW), req("1.2.3.4"));

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(KEY, body(res).get("key"), "프론트가 읽는 키 이름은 \"key\"");
    }

    @Test
    void 틀린_비밀번호는_403이고_키를_흘리지_않는다() {
        ResponseEntity<?> res = controller(PW, KEY, openLimiter())
                .login(Map.of("password", "wrong"), req("1.2.3.4"));

        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        assertFalse(String.valueOf(res.getBody()).contains(KEY), "실패 응답에 키가 담기면 안 된다");
    }

    @Test
    void 비밀번호_미설정이면_기능_비활성_403() {
        assertEquals(HttpStatus.FORBIDDEN,
                controller("", KEY, openLimiter()).login(Map.of("password", ""), req("1.2.3.4")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller(null, KEY, openLimiter()).login(Map.of("password", "아무거나"), req("1.2.3.4")).getStatusCode());
    }

    @Test
    void 관리자_키_미설정이면_비번이_맞아도_403() {
        assertEquals(HttpStatus.FORBIDDEN,
                controller(PW, "", openLimiter()).login(Map.of("password", PW), req("1.2.3.4")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                controller(PW, null, openLimiter()).login(Map.of("password", PW), req("1.2.3.4")).getStatusCode());
    }

    @Test
    void 무차별_대입은_분당_한도에서_429로_막힌다() {
        RateLimiter limiter = new RateLimiter(5, 30, Clock.system(ZoneId.of("Asia/Seoul")));
        AdminController c = controller(PW, KEY, limiter);

        for (int i = 0; i < 5; i++) {
            assertEquals(HttpStatus.FORBIDDEN,
                    c.login(Map.of("password", "guess" + i), req("9.9.9.9")).getStatusCode(),
                    "한도 안에서는 통상 실패(403)");
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                c.login(Map.of("password", PW), req("9.9.9.9")).getStatusCode(),
                "한도 초과는 정답 비번이어도 429");
        assertEquals(HttpStatus.OK,
                c.login(Map.of("password", PW), req("9.9.9.8")).getStatusCode(),
                "다른 IP는 영향 없음");
    }

    @Test
    void XFF_첫값을_매번_바꿔도_한도를_우회하지_못한다() {
        // F-6 회귀: 첫 값을 쓰던 구현에서는 매 요청이 다른 버킷이라 429가 영원히 안 났다.
        RateLimiter limiter = new RateLimiter(5, 30, Clock.system(ZoneId.of("Asia/Seoul")));
        AdminController c = controller(PW, KEY, limiter);

        for (int i = 0; i < 5; i++) {
            assertEquals(HttpStatus.FORBIDDEN,
                    c.login(Map.of("password", "guess" + i),
                            proxied("203.0.113." + i + ", 198.51.100.7")).getStatusCode());
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                c.login(Map.of("password", PW), proxied("8.8.8.8, 198.51.100.7")).getStatusCode(),
                "위조한 앞값이 매번 달라도 마지막 값(실제 IP)이 같으면 한 버킷 — 6회째는 429");
    }

    @Test
    void 프록시_뒤에서도_실제_클라이언트가_다르면_버킷이_갈린다() {
        RateLimiter limiter = new RateLimiter(5, 30, Clock.system(ZoneId.of("Asia/Seoul")));
        AdminController c = controller(PW, KEY, limiter);

        for (int i = 0; i < 5; i++) {
            c.login(Map.of("password", "guess" + i), proxied("198.51.100.7"));
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                c.login(Map.of("password", PW), proxied("198.51.100.7")).getStatusCode());
        assertEquals(HttpStatus.OK,
                c.login(Map.of("password", PW), proxied("198.51.100.8")).getStatusCode(),
                "다른 실제 클라이언트는 앞선 IP의 한도에 걸리지 않는다");
    }

    @Test
    void 본문이_없거나_password가_비어도_500이_아니라_403() {
        AdminController c = controller(PW, KEY, openLimiter());
        assertEquals(HttpStatus.FORBIDDEN, c.login(null, req("1.2.3.4")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, c.login(Map.of(), req("1.2.3.4")).getStatusCode());
    }

    @Test
    void 비밀번호_대조는_설정값이_비면_무조건_거절한다() {
        assertFalse(AdminController.authenticate("", "anything"));
        assertFalse(AdminController.authenticate(null, "anything"));
        assertFalse(AdminController.authenticate("   ", "   "));
        assertFalse(AdminController.authenticate("secret", null));
        assertFalse(AdminController.authenticate("secret", "secre"));   // 접두 일치도 거절
        assertFalse(AdminController.authenticate("secret", "secretx"));
        assertTrue(AdminController.authenticate("secret", "secret"));
        assertTrue(AdminController.authenticate("한글 비번", "한글 비번"));
    }
}
