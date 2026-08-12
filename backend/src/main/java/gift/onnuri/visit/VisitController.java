package gift.onnuri.visit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 방문자 카운트(2026-08-11). 개인정보 미저장 — 일자별 정수 카운트만.
 * POST = 세션 첫 진입 시 1 증가(클라이언트 sessionStorage가 중복 방지), GET = 조회만.
 */
@RestController
@RequestMapping("/api/visit")
public class VisitController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;

    public VisitController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping
    public VisitStats hit() {
        LocalDate today = LocalDate.now(KST);
        jdbc.update("INSERT INTO visit_daily (day, count) VALUES (?, 1) "
                + "ON CONFLICT (day) DO UPDATE SET count = visit_daily.count + 1", today);
        return stats();
    }

    @GetMapping
    public VisitStats stats() {
        LocalDate today = LocalDate.now(KST);
        Long t = null;
        try {
            t = jdbc.queryForObject("SELECT count FROM visit_daily WHERE day = ?", Long.class, today);
        } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
        }
        Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(count), 0) FROM visit_daily", Long.class);
        return new VisitStats(t == null ? 0 : t, sum == null ? 0 : sum);
    }
}
