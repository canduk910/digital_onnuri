package gift.onnuri.report;

import gift.onnuri.chat.RateLimiter;
import gift.onnuri.merchant.dto.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 버그 제보 게시판(2026-08-11). 익명 제출 — 개인정보 미수집(닉네임은 선택 자유입력).
 * 남용 방지: IP당 rate limit(reportRateLimiter). 렌더는 프론트가 텍스트로만(esc) 처리.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final JdbcTemplate jdbc;
    private final RateLimiter limiter;
    private final String adminKey;

    public ReportController(JdbcTemplate jdbc, RateLimiter reportRateLimiter,
                            @org.springframework.beans.factory.annotation.Value("${app.admin.key:}") String adminKey) {
        this.jdbc = jdbc;
        this.limiter = reportRateLimiter;
        this.adminKey = adminKey;
    }

    /** 처리상태 화이트리스트 — report.html 배지와 1:1(그 외 값은 배지 스타일이 없다). */
    static boolean isValidStatus(String s) {
        return "접수".equals(s) || "반영".equals(s);
    }

    /** 관리자 키 검증 — 서버 .env(APP_ADMIN_KEY)에만 존재. 미설정이면 기능 자체 비활성(403). */
    static boolean authorized(String configuredKey, String providedKey) {
        return configuredKey != null && !configuredKey.isBlank() && configuredKey.equals(providedKey);
    }

    /** 처리상태 변경(운영자 전용) — admin-report.html이 X-Admin-Key와 함께 호출한다. */
    @PostMapping("/{id}/status")
    public ResponseEntity<?> setStatus(@PathVariable long id,
                                       @RequestBody Map<String, String> body,
                                       @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        if (!authorized(adminKey, key)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "관리자 키가 올바르지 않습니다."));
        }
        String status = body.get("status");
        if (!isValidStatus(status)) {
            return ResponseEntity.badRequest().body(Map.of("message", "상태는 '접수' 또는 '반영'만 가능합니다."));
        }
        int n = jdbc.update("UPDATE report SET status = ? WHERE id = ?", status, id);
        if (n == 0) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "해당 제보가 없습니다."));
        return ResponseEntity.ok(Map.of("ok", true, "id", id, "status", status));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody ReportCreate in, HttpServletRequest http) {
        if (!limiter.tryAcquire(clientIp(http))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "잠시 후 다시 시도해 주세요."));
        }
        String title = clean(in.title(), 120);
        String content = clean(in.content(), 2000);
        if (title == null || content == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "제목과 내용을 입력해 주세요."));
        }
        jdbc.update("INSERT INTO report (title, content, page, nickname) VALUES (?, ?, ?, ?)",
                title, content, clean(in.page(), 40), clean(in.nickname(), 40));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping
    public PageResult<ReportView> list(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        int sz = Math.min(Math.max(size, 1), 50);
        int off = Math.max(page, 0) * sz;
        List<ReportView> items = jdbc.query(
                "SELECT id, title, content, page, nickname, status, created_at FROM report "
                        + "ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> new ReportView(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        FMT.format(rs.getTimestamp(7).toInstant())),
                sz, off);
        Long total = jdbc.queryForObject("SELECT count(*) FROM report", Long.class);
        return new PageResult<>(items, total == null ? 0 : total, Math.max(page, 0), sz);
    }

    private static String clean(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.substring(0, Math.min(t.length(), max));
    }

    private String clientIp(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : http.getRemoteAddr();
    }
}
