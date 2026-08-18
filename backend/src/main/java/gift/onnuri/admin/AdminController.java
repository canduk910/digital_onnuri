package gift.onnuri.admin;

import gift.onnuri.chat.RateLimiter;
import gift.onnuri.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * 관리자 로그인(2026-08-18). 기억 가능한 비밀번호로 48자리 APP_ADMIN_KEY를 받아온다 —
 * admin-report.html이 받은 키를 sessionStorage에 두고 X-Admin-Key로 쓴다(ReportController).
 *
 * 비밀번호·키는 서버 .env에만 존재하고, 둘 중 하나라도 비면 로그인 자체가 비활성(403)이다.
 * 무차별 대입 방지로 IP당 분 5회·일 30회(adminLoginRateLimiter). 비밀번호는 로그에 남기지 않는다.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final RateLimiter limiter;
    private final String adminKey;
    private final String adminPassword;

    public AdminController(RateLimiter adminLoginRateLimiter,
                           @Value("${app.admin.key:}") String adminKey,
                           @Value("${app.admin.password:}") String adminPassword) {
        this.limiter = adminLoginRateLimiter;
        this.adminKey = adminKey;
        this.adminPassword = adminPassword;
    }

    /**
     * 비밀번호 대조. 미설정(빈 값)이면 무조건 거절 — 기능 비활성.
     * 길이가 달라도 조기 반환하지 않도록 MessageDigest.isEqual로 상수시간 비교한다.
     */
    static boolean authenticate(String configured, String provided) {
        if (configured == null || configured.isBlank() || provided == null) return false;
        return MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    /** 로그인 — 성공 시 {"key": …}. 실패는 이유를 구분하지 않는다(비번 오답·미설정·키 미설정 모두 403). */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody(required = false) Map<String, String> body,
                                   HttpServletRequest http) {
        String ip = ClientIp.of(http);
        if (!limiter.tryAcquire(ip)) {
            // 무차별 대입 흔적을 운영자가 볼 수 있게 남긴다 — IP만, 시도값은 절대 남기지 않는다.
            log.warn("관리자 로그인 한도 초과 — ip={}", ip);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "시도가 너무 잦습니다. 잠시 후 다시 시도해 주세요."));
        }
        String password = body == null ? null : body.get("password");
        if (!authenticate(adminPassword, password) || adminKey == null || adminKey.isBlank()) {
            log.warn("관리자 로그인 실패 — ip={}", ip);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "비밀번호가 올바르지 않습니다."));
        }
        // 성공도 남긴다 — 실패만 있으면 대입 시도가 끝내 뚫렸는지를 로그로 알 수 없다.
        log.info("관리자 로그인 성공 — ip={}", ip);
        return ResponseEntity.ok(Map.of("key", adminKey));
    }
}
