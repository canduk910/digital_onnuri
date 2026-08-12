package gift.onnuri.chat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP별 인메모리 요청 한도(분·일). 단일 인스턴스 배포(ADR-5)라 분산 저장소는 두지 않는다.
 * 일 경계는 Asia/Seoul 기준(클록 주입 — 테스트 가능).
 */
public class RateLimiter {

    private final int perMinute;
    private final int perDay;
    private final Clock clock;

    private static final class Bucket {
        final Deque<Instant> minuteWindow = new ArrayDeque<>();
        LocalDate day;
        int dayCount;
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute, int perDay, Clock clock) {
        this.perMinute = perMinute;
        this.perDay = perDay;
        this.clock = clock;
    }

    public boolean tryAcquire(String ip) {
        Bucket b = buckets.computeIfAbsent(ip, k -> new Bucket());
        synchronized (b) {
            Instant now = clock.instant();
            LocalDate today = LocalDate.ofInstant(now, clock.getZone());
            if (!today.equals(b.day)) {
                b.day = today;
                b.dayCount = 0;
            }
            while (!b.minuteWindow.isEmpty()
                    && b.minuteWindow.peekFirst().isBefore(now.minusSeconds(60))) {
                b.minuteWindow.pollFirst();
            }
            if (b.minuteWindow.size() >= perMinute || b.dayCount >= perDay) {
                return false;
            }
            b.minuteWindow.addLast(now);
            b.dayCount++;
            return true;
        }
    }
}
