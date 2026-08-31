package gift.onnuri.online.probe;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    private final OnlineSearchService svc;
    private final RateLimiter limiter;

    /** 빈 이름으로 한도를 고른다(ChatConfig 관례 — chat/report/adminLogin/onlineProbe). */
    public OnlineSearchController(OnlineSearchService svc, RateLimiter onlineProbeRateLimiter) {
        this.svc = svc;
        this.limiter = onlineProbeRateLimiter;
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
