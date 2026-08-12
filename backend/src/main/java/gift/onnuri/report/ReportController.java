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

    public ReportController(JdbcTemplate jdbc, RateLimiter reportRateLimiter) {
        this.jdbc = jdbc;
        this.limiter = reportRateLimiter;
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
