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

    @Bean
    public RateLimiter chatRateLimiter(
            @Value("${app.chat.rate-per-minute:10}") int perMinute,
            @Value("${app.chat.rate-per-day:200}") int perDay) {
        return new RateLimiter(perMinute, perDay, Clock.system(ZoneId.of("Asia/Seoul")));
    }
}
