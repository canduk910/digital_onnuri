package gift.onnuri.online.probe;

import com.sun.net.httpserver.HttpServer;
import gift.onnuri.chat.RateLimiter;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 리다이렉트가 **다른 호스트**에 닿으면 그 응답을 쓰지 않는다 — 배선까지 시험한다.
 *
 * `sameHost` 단위 테스트(ProbeFetcherHostTest)는 판단만 본다. 그 판단이 실제 조회
 * 경로에 **연결돼 있는가**는 다른 질문이라, 진짜 HTTP 서버 둘을 세워 확인한다.
 * `127.0.0.1` 과 `localhost` 는 같은 기계를 가리키지만 **호스트 문자열이 다르므로**
 * 외부 망 없이 교차 리다이렉트를 재현할 수 있다.
 *
 * 왜 robots 까지 보는가: 2026-09-05 현대이지웰이 점검에 들어가며
 * `www.onnuri-sijang.com/robots.txt` 를 다른 도메인의 안내 페이지로 302 보냈고,
 * 우리는 그 **HTML 을 robots.txt 로 파싱해** `allowed: true` 로 보고하고 있었다.
 * 마침 실제 규칙과 결과가 같아 눈에 띄지 않았을 뿐, ADR-21 이 "감시 대상이 조용히
 * 다른 사이트가 되는 것"이라 적은 바로 그 상태다. 판정이 우연히 맞는 것은 맞는 것이 아니다.
 */
class ProbeFetcherRedirectTest {

    private static ProbeFetcher fetcher() {
        // 한도는 넉넉히, 타임아웃은 짧게. 로컬 서버라 왕복이 순간이다.
        return new ProbeFetcher(new RateLimiter(1000, 10000, java.time.Clock.systemUTC()),
                                true, 3000, 8, 1_000_000);
    }

    /** 본문을 그대로 주는 서버 하나와, 그 서버로 넘기는 서버 하나를 세운다. */
    private record Pair(HttpServer from, HttpServer to, int fromPort, int toPort) implements AutoCloseable {
        public void close() { from.stop(0); to.stop(0); }
    }

    private static Pair servers(String body) throws Exception {
        HttpServer to = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int toPort = to.getAddress().getPort();
        to.createContext("/", ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        to.start();

        HttpServer from = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int fromPort = from.getAddress().getPort();
        from.createContext("/", ex -> {
            // 같은 기계지만 **호스트 문자열이 다른** 곳으로 넘긴다.
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + toPort + "/moved");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        from.start();
        return new Pair(from, to, fromPort, toPort);
    }

    @Test
    void 다른_호스트로_넘어간_조회는_본문을_쓰지_않는다() throws Exception {
        // 점검 안내 페이지를 흉내 낸다 — 200 이고 충분히 길다(길이 가드를 통과한다).
        String maintenance = "<html><body><h1>서비스 일시중단 안내</h1>"
                + "<p>시스템 점검 중입니다. </p>".repeat(40) + "</body></html>";
        try (Pair p = servers(maintenance)) {
            // localhost → 127.0.0.1 로 넘어간다(문자열이 다르다).
            ProbeTarget t = new ProbeTarget("test-mall",
                    "http://localhost:" + p.fromPort() + "/search?q={q}",
                    StandardCharsets.UTF_8, ProbeTarget.Scope.ONNURI_SCOPE,
                    java.util.List.of(), java.util.List.of(), false, 5, null, 0,
                    "김치", 3000, java.time.LocalDate.of(2026, 9, 5), java.time.LocalDate.of(2026, 9, 5));
            ProbeOutcome o = fetcher().fetch(t, ProbeQuery.of("김치"));
            assertFalse(o.fetched(), "다른 곳에서 온 본문을 받아 버렸다");
            assertEquals(ProbeOutcome.REDIRECTED, o.reason());
        }
    }

    @Test
    void 같은_호스트_안의_리다이렉트는_그대로_쓴다() throws Exception {
        // 경로만 바뀌는 리다이렉트는 흔하다. 이것까지 막으면 가드가 아니라 고장이다.
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = s.getAddress().getPort();
        String body = "<html><body>" + "<p>상품 목록</p>".repeat(60) + "</body></html>";
        s.createContext("/search", ex -> {
            ex.getResponseHeaders().add("Location", "/result");
            ex.sendResponseHeaders(302, -1); ex.close();
        });
        s.createContext("/result", ex -> {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b); ex.close();
        });
        s.start();
        try {
            ProbeTarget t = new ProbeTarget("test-mall",
                    "http://127.0.0.1:" + port + "/search?q={q}",
                    StandardCharsets.UTF_8, ProbeTarget.Scope.ONNURI_SCOPE,
                    java.util.List.of(), java.util.List.of(), false, 5, null, 0,
                    "김치", 3000, java.time.LocalDate.of(2026, 9, 5), java.time.LocalDate.of(2026, 9, 5));
            ProbeOutcome o = fetcher().fetch(t, ProbeQuery.of("김치"));
            assertTrue(o.fetched(), "같은 호스트 안의 경로 이동까지 막아 버렸다: " + o.reason());
            assertTrue(o.html().contains("상품 목록"));
        } finally { s.stop(0); }
    }

    /**
     * robots 는 `https` 를 고정으로 만들어 로컬 http 서버로는 잴 수 없다. 그래서
     * **판단부**(`robotsBodyOrThrow`)를 직접 부른다 — `fetchRobots` 가 쓰는 바로 그 코드다.
     */
    @Test
    void robots가_다른_호스트에서_오면_관측_실패로_올린다() {
        java.net.URI asked = java.net.URI.create("https://www.onnuri-sijang.com/robots.txt");

        // 이지웰 실측: 점검 안내 **HTML** 이 다른 도메인에서 200 으로 돌아온다.
        String html = "<!DOCTYPE html><html><head><title>서비스 일시중단 안내</title></head></html>";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ProbeFetcher.robotsBodyOrThrow(
                        asked, 200, java.net.URI.create("https://withus.ezwel.com/maintenance/index.html"), html),
                "다른 곳에서 온 HTML 을 robots.txt 로 읽어 버렸다");
        assertTrue(e.getMessage().contains("other host"), e.getMessage());

        // 같은 호스트면 그대로 쓴다(경로가 바뀌는 것은 흔하다).
        assertEquals("User-agent: *\nAllow: /",
                ProbeFetcher.robotsBodyOrThrow(asked, 200,
                        java.net.URI.create("https://www.onnuri-sijang.com/robots.txt?v=2"),
                        "User-agent: *\nAllow: /"));
    }

    @Test
    void robots_상태코드_갈래는_그대로다() {
        java.net.URI u = java.net.URI.create("https://www.ongong.kr/robots.txt");
        // 404·410 = 금지가 없다. 오류가 아니라 빈 규칙이다.
        assertEquals("", ProbeFetcher.robotsBodyOrThrow(u, 404, u, null));
        assertEquals("", ProbeFetcher.robotsBodyOrThrow(u, 410, u, null));
        // 401·403 은 RFC 가 허용해도 **막는다는 신호**라 '파일 없음'으로 보지 않는다(ADR-21).
        assertThrows(IllegalStateException.class, () -> ProbeFetcher.robotsBodyOrThrow(u, 403, u, ""));
        assertThrows(IllegalStateException.class, () -> ProbeFetcher.robotsBodyOrThrow(u, 500, u, ""));
    }

    /**
     * 관측 실패 사유가 **무슨 일인지 말해야** 한다. 클래스 이름만 적으면 배치 로그가
     * `관측 실패: hyundai-ezwel-onnuri — IllegalStateException` 한 줄이 되어
     * 아침에 그 줄을 보는 사람에게 아무것도 알려 주지 못한다(2026-09-05 실제로 겪었다).
     */
    /**
     * **배선까지 본다.** 헬퍼만 직접 부르면 호출부를 `getClass().getSimpleName()` 로
     * 되돌려도 테스트가 통과한다 — 실제로 그렇게 만들었다가 변조 실험에서 걸렸다.
     * 조회를 실패시키는 수집기를 넣고 리포트에 사유가 실제로 실리는지 본다.
     */
    @Test
    void 리포트에_실패_사유가_실제로_실린다() {
        ProbeFetcher broken = new ProbeFetcher(
                new RateLimiter(1000, 10000, java.time.Clock.systemUTC()), true, 3000, 8, 1_000_000) {
            @Override public String fetchRobots(String host) {
                throw new IllegalStateException("robots redirect to other host: withus.ezwel.com");
            }
        };
        var checks = new SelfTestService(broken, true).robots();
        assertFalse(checks.isEmpty(), "조회 대상이 하나도 없다");
        checks.forEach(c -> {
            assertNotNull(c.error(), c.platformId() + " 를 못 읽었는데 사유가 비었다");
            assertTrue(c.error().contains("other host"),
                    "리포트에 사유가 실리지 않았다(호출부가 클래스 이름만 쓰고 있다): " + c.error());
            assertFalse(c.allowed(), "못 읽은 것을 허용으로 적었다");
        });
    }

    @Test
    void 관측_실패_사유가_무슨_일인지_말한다() {
        String d = SelfTestService.describe(
                new IllegalStateException("robots redirect to other host: withus.ezwel.com"));
        assertTrue(d.contains("IllegalStateException"), d);
        assertTrue(d.contains("other host"), "사유가 사라졌다: " + d);

        // 메시지가 없으면 클래스 이름만.
        assertEquals("RuntimeException", SelfTestService.describe(new RuntimeException()));

        // 길면 자른다 — 리포트가 스택으로 부풀면 안 된다.
        String longMsg = "x".repeat(500);
        assertTrue(SelfTestService.describe(new IllegalStateException(longMsg)).length() <= 145);
    }
}
