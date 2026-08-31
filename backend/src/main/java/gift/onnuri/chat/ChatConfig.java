package gift.onnuri.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** 챗봇 빈 구성 — 한도는 application.yml(app.chat.*)에서 조정한다. */
@Configuration
public class ChatConfig {

    @Bean
    public RateLimiter reportRateLimiter(
            @Value("${app.report.rate-per-minute:2}") int perMinute,
            @Value("${app.report.rate-per-day:10}") int perDay) {
        return new RateLimiter(perMinute, perDay, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    /** 관리자 로그인(POST /api/admin/login) — 무차별 대입 방지라 제보보다도 타이트하게. */
    @Bean
    public RateLimiter adminLoginRateLimiter(
            @Value("${app.admin.rate-per-minute:5}") int perMinute,
            @Value("${app.admin.rate-per-day:30}") int perDay) {
        return new RateLimiter(perMinute, perDay, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    /**
     * 온라인 실시간 조회 — 이용자 IP 단위(ADR-17).
     * 캐시가 전량 적중하면 아웃바운드가 없으므로 이 한도를 소비하지 않는다.
     */
    @Bean
    public RateLimiter onlineProbeRateLimiter(
            @Value("${app.online.probe.rate-per-minute:5}") int perMinute,
            @Value("${app.online.probe.rate-per-day:100}") int perDay) {
        return new RateLimiter(perMinute, perDay, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    /**
     * 같은 RateLimiter 를 몰 단위로 한 번 더 쓴다 — 키가 IP 가 아니라 platformId 다.
     * 이용자가 몰려도 상대 사이트가 받는 부담은 여기서 잘린다(야간 배치가 "하루 3~4곳"으로
     * 억제한 근거와 같은 취지, ADR-16).
     */
    @Bean
    public RateLimiter onlineTargetRateLimiter(
            @Value("${app.online.probe.per-target-per-minute:6}") int perMinute,
            @Value("${app.online.probe.per-target-per-day:600}") int perDay) {
        return new RateLimiter(perMinute, perDay, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    @Bean
    public RateLimiter chatRateLimiter(
            @Value("${app.chat.rate-per-minute:10}") int perMinute,
            @Value("${app.chat.rate-per-day:200}") int perDay) {
        return new RateLimiter(perMinute, perDay, Clock.system(ZoneId.of("Asia/Seoul")));
    }
}
