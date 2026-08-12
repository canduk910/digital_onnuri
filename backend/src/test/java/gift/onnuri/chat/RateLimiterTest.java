package gift.onnuri.chat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 공개 사이트에 노출되는 유일한 비용 통제 장치 — 한도 초과가 허용되면 LLM 비용이
 * 무한정 발생할 수 있으므로 경계값을 고정한다. (사용자 결정: rate limit만, 게이트 없음)
 */
class RateLimiterTest {

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-08-11T09:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @Test
    void 분당_한도를_넘으면_거부하고_1분_뒤_회복한다() {
        MutableClock clock = new MutableClock();
        RateLimiter rl = new RateLimiter(3, 100, clock);
        assertTrue(rl.tryAcquire("1.2.3.4"));
        assertTrue(rl.tryAcquire("1.2.3.4"));
        assertTrue(rl.tryAcquire("1.2.3.4"));
        assertFalse(rl.tryAcquire("1.2.3.4"), "분당 3회 초과는 거부");
        clock.advance(Duration.ofSeconds(61));
        assertTrue(rl.tryAcquire("1.2.3.4"), "1분 경과 후 회복");
    }

    @Test
    void 일당_한도를_넘으면_다음_날까지_거부한다() {
        MutableClock clock = new MutableClock();
        RateLimiter rl = new RateLimiter(1000, 5, clock);
        for (int i = 0; i < 5; i++) {
            assertTrue(rl.tryAcquire("5.6.7.8"));
            clock.advance(Duration.ofMinutes(2)); // 분당 한도는 안 걸리게
        }
        assertFalse(rl.tryAcquire("5.6.7.8"), "일당 5회 초과는 거부");
        clock.advance(Duration.ofHours(25));
        assertTrue(rl.tryAcquire("5.6.7.8"), "다음 날 회복");
    }

    @Test
    void IP별로_독립_카운트한다() {
        MutableClock clock = new MutableClock();
        RateLimiter rl = new RateLimiter(1, 100, clock);
        assertTrue(rl.tryAcquire("10.0.0.1"));
        assertFalse(rl.tryAcquire("10.0.0.1"));
        assertTrue(rl.tryAcquire("10.0.0.2"), "다른 IP는 영향 없음");
    }
}
