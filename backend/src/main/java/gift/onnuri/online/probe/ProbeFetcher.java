package gift.onnuri.online.probe;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import gift.onnuri.chat.RateLimiter;

/**
 * 몰 한 곳을 실제로 조회한다. NewsService 의 JDK HttpClient 선례를 따른다
 * (별도 SDK 없이, connect/request 타임아웃 명시, 실패는 예외 대신 사유로 반환).
 *
 * 상대 사이트 부담을 억제하는 장치가 세 겹 들어 있다(ADR-17):
 *   - 몰당 동시 1건(Semaphore) — 같은 몰을 동시에 여러 번 두드리지 않는다
 *   - 몰당 분·일 한도(RateLimiter 재사용, 키는 IP 가 아니라 platformId)
 *   - 응답 본문 상한 — 20만 바이트짜리 페이지를 ofString() 으로 받으면 4GB 박스가 위험하다
 */
@Component
public class ProbeFetcher {

    private static final Logger log = LoggerFactory.getLogger(ProbeFetcher.class);

    /** 식별·연락이 가능한 UA. 상대가 우리를 차단하고 싶을 때 차단할 수 있어야 한다. */
    private static final String UA =
            "Mozilla/5.0 (compatible; onnuri-guide/1.0; +https://onnuri.koscomlabor.cloud)";

    private final HttpClient http;
    private final RateLimiter targetLimiter;
    private final Map<String, Semaphore> perTarget = new ConcurrentHashMap<>();
    private final Semaphore global;
    private final int timeoutMs;
    private final int maxBytes;
    private final boolean enabled;

    public ProbeFetcher(RateLimiter onlineTargetRateLimiter,
                        @Value("${app.online.probe.enabled:true}") boolean enabled,
                        @Value("${app.online.probe.timeout-ms:4000}") int timeoutMs,
                        @Value("${app.online.probe.max-concurrent:12}") int maxConcurrent,
                        @Value("${app.online.probe.max-bytes:1000000}") int maxBytes) {
        this.targetLimiter = onlineTargetRateLimiter;
        this.enabled = enabled;
        this.timeoutMs = timeoutMs;
        this.maxBytes = maxBytes;
        this.global = new Semaphore(maxConcurrent);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ProbeOutcome fetch(ProbeTarget t, ProbeQuery q) {
        if (!enabled) return ProbeOutcome.fail(ProbeOutcome.DISABLED);

        // 몰 단위 한도 — 이용자가 몰려도 상대 사이트가 받는 부담은 여기서 잘린다.
        if (!targetLimiter.tryAcquire(t.platformId())) {
            return ProbeOutcome.fail(ProbeOutcome.RATE_LIMITED);
        }
        Semaphore one = perTarget.computeIfAbsent(t.platformId(), k -> new Semaphore(1));
        boolean gotOne = false, gotGlobal = false;
        try {
            gotOne = one.tryAcquire(1500, TimeUnit.MILLISECONDS);
            if (!gotOne) return ProbeOutcome.fail(ProbeOutcome.BUSY);
            gotGlobal = global.tryAcquire(1500, TimeUnit.MILLISECONDS);
            if (!gotGlobal) return ProbeOutcome.fail(ProbeOutcome.BUSY);

            URI uri = ProbeUrl.build(t, q);
            int ms = t.timeoutMs() > 0 ? t.timeoutMs() : timeoutMs;
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(ms))
                    .header("User-Agent", UA)
                    .header("Accept-Language", "ko");
            if (t.formBody() == null) {
                rb.GET();
            } else {
                // 몰의 내부 검색 API 가 form POST 를 받는 경우(온누리5일장).
                // 화면이 보내는 것과 같은 요청이다 — 새로 만든 경로가 아니다.
                rb.header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(HttpRequest.BodyPublishers.ofString(
                          ProbeUrl.fill(t.formBody(), t, q), t.charset()));
            }
            HttpRequest req = rb.build();
            HttpResponse<InputStream> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                log.warn("실시간 조회 HTTP {} — {} q={}", resp.statusCode(), t.platformId(), q.normalized());
                return ProbeOutcome.fail(ProbeOutcome.HTTP_ERROR);
            }
            return ProbeOutcome.ok(readCapped(resp.body(), t, maxBytes));
        } catch (java.net.http.HttpTimeoutException e) {
            return ProbeOutcome.fail(ProbeOutcome.TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProbeOutcome.fail(ProbeOutcome.TIMEOUT);
        } catch (Exception e) {
            log.warn("실시간 조회 실패 — {} q={} : {}", t.platformId(), q.normalized(), e.toString());
            return ProbeOutcome.fail(ProbeOutcome.HTTP_ERROR);
        } finally {
            if (gotGlobal) global.release();
            if (gotOne) one.release();
        }
    }

    /** 상한까지만 읽고 끊는다. 판정에 필요한 신호는 페이지 앞부분에 다 있다. */
    static String readCapped(InputStream in, ProbeTarget t, int maxBytes) throws Exception {
        try (in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(Math.min(maxBytes, 1 << 16));
            byte[] chunk = new byte[8192];
            int n, total = 0;
            while ((n = in.read(chunk)) > 0) {
                int room = maxBytes - total;
                if (room <= 0) break;
                buf.write(chunk, 0, Math.min(n, room));
                total += n;
            }
            return buf.toString(t.charset());
        }
    }
}
