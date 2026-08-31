package gift.onnuri.online.probe;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import gift.onnuri.chat.RateLimiter;
import gift.onnuri.web.ClientIp;

/**
 * 실시간 조회 엔드포인트.
 *
 * OnlineController(목록)와 분리한다 — 캐시·부하 성격이 완전히 달라 한 서비스에 섞으면
 * 목록 장애가 검색 장애가 된다.
 *
 * GET 은 운영 curl 용, POST 는 프론트용(검색 조건을 주소창에 노출하지 않는 ADR-13 관례).
 */
@RestController
@RequestMapping("/api/online")
public class OnlineSearchController {

    private static final Logger log = LoggerFactory.getLogger(OnlineSearchController.class);

    private final OnlineSearchService svc;
    private final SelfTestService selfTest;
    private final RateLimiter limiter;
    private final String adminKey;

    /** 빈 이름으로 한도를 고른다(ChatConfig 관례 — chat/report/adminLogin/onlineProbe). */
    public OnlineSearchController(OnlineSearchService svc, SelfTestService selfTest,
                                  RateLimiter onlineProbeRateLimiter,
                                  @Value("${app.admin.key:}") String adminKey) {
        this.svc = svc;
        this.selfTest = selfTest;
        this.limiter = onlineProbeRateLimiter;
        this.adminKey = adminKey;
    }

    /**
     * 판정 규칙 카나리아 — 야간 배치(단계 E)가 하루 한 번 부른다.
     *
     * 키는 헤더로 받는다. 계획서는 ?key= 였으나 배치는 cron 로그·프로세스 목록에 명령줄이
     * 그대로 남아 키가 새어 나간다. 기존 관리자 경로(ReportController)도 X-Admin-Key 헤더다.
     *
     * 응답은 200 고정이다 — failed 건수가 본문에 있고, HTTP 코드로 실패를 알리면
     * 배치의 fail-open 이 "서버 장애"와 "규칙 깨짐"을 구분하지 못한다.
     */
    @GetMapping("/search/selftest")
    public ResponseEntity<?> selftest(
            @RequestHeader(name = "X-Admin-Key", required = false) String key,
            HttpServletRequest http) {
        if (adminKey == null || adminKey.isBlank() || !constantEquals(adminKey, key)) {
            log.warn("카나리아 인증 실패 — ip={}", ClientIp.of(http));   // 시도값은 남기지 않는다
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(java.util.Map.of("error", "관리자 키가 필요합니다."));
        }
        if (!selfTest.tryLock()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(java.util.Map.of("error", "카나리아가 이미 실행 중입니다."));
        }
        try {
            return ResponseEntity.ok(selfTest.run());
        } finally {
            selfTest.unlock();
        }
    }

    /** 길이가 달라도 조기 반환하지 않도록 상수시간 비교(AdminController 와 같은 방식). */
    private static boolean constantEquals(String configured, String given) {
        if (given == null) return false;
        return MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                given.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/search")
    public ResponseEntity<?> post(@RequestBody(required = false) ProbeRequest body,
                                  HttpServletRequest http) {
        return run(body == null ? null : body.q(), http);
    }

    @GetMapping("/search")
    public ResponseEntity<?> get(@RequestParam(name = "q", required = false) String q,
                                 HttpServletRequest http) {
        return run(q, http);
    }

    private ResponseEntity<?> run(String raw, HttpServletRequest http) {
        ProbeQuery q = ProbeQuery.of(raw);
        if (!q.searchable()) {
            // 조용히 빈 결과를 주지 않는다 — 왜 조회하지 않았는지 말한다.
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "검색어는 " + ProbeQuery.MIN_LEN + "~" + ProbeQuery.MAX_LEN
                            + "자여야 하고 문자나 숫자를 포함해야 합니다."));
        }
        // 캐시가 적중하면 상대 사이트로 나가는 요청이 없으므로 한도를 소비하지 않는다.
        OnlineSearchResult cached = svc.cached(q);
        if (cached != null) return ResponseEntity.ok(cached);

        if (!limiter.tryAcquire(ClientIp.of(http))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(java.util.Map.of(
                    "error", "실시간 확인 요청이 많습니다. 잠시 후 다시 시도해 주세요."));
        }
        return ResponseEntity.ok(svc.searchCached(q));
    }
}
