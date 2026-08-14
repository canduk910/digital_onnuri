package gift.onnuri.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/** 정적 프론트(GitHub Pages·로컬)에서 이 API로의 교차 호출 허용. */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] origins;

    public CorsConfig(@Value("${app.cors.allowed-origins:}") String allowed) {
        this.origins = Arrays.stream(allowed.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST")   // POST는 챗봇(/api/chat) SSE용
                .allowedHeaders("*")             // X-Admin-Key(제보 관리) 등 커스텀 헤더 허용(2026-08-14)
                .maxAge(3600);
    }
}
