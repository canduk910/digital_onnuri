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

    /** robots 판정에 쓰는 우리 제품 토큰. 위 UA 문자열 안의 이름과 같아야 한다. */
    public static final String ROBOTS_TOKEN = "onnuri-guide";

    /**
     * robots.txt 가 "없다" = 금지가 없다. 오류가 아니라 빈 규칙으로 다룬다.
     *
     * 404 와 **410**(영구 삭제) 둘 다다 — 배치와 DEPLOY.md 가 그렇게 보고 있어 맞춘다.
     * 같은 사실을 두 곳이 다르게 판단하는 것이 이번 라운드가 없애려던 병이다.
     *
     * RFC 9309 는 4xx 전체를 "unavailable"로 보고 접근을 허용해도 된다(MAY)고 하지만
     * **그렇게 넓히지 않는다.** 401·403 은 파일이 없다는 뜻이 아니라 **우리를 막는다**는 신호에
     * 가깝고, 그것을 "금지 없음"으로 적으면 모르는 것을 허용으로 바꿔 적는 셈이 된다.
     * 그런 응답은 예외로 떨어져 `error` 로 남는다 — 모르면 모른다고 적는다.
     */
    static boolean robotsMissing(int status) {
        return status == 404 || status == 410;
    }

    /**
     * 호스트 하나의 robots.txt. 조회 대상이 아니라 **정책 문서**라 몰 단위 한도를 쓰지 않는다
     * (같은 몰의 상품 조회 한도를 이 요청이 갉아먹으면 정작 조회가 막힌다).
     * 전역 동시 상한은 그대로 지킨다. 못 읽으면 예외 대신 null 을 준다 — 판정 쪽에서 사유로 남긴다.
     */
    public String fetchRobots(String host) throws Exception {
        boolean got = false;
        try {
            got = global.tryAcquire(1500, TimeUnit.MILLISECONDS);
            if (!got) throw new IllegalStateException("busy");
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://" + host + "/robots.txt"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", UA)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return robotsBodyOrThrow(req.uri(), resp.statusCode(), resp.uri(), resp.body());
        } finally {
            if (got) global.release();
        }
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
            /* 우리가 부른 그 몰에서 온 응답인가. 리다이렉트를 따라가므로(NORMAL) 200 이라고
               그 몰의 검색 응답인 것은 아니다 — 2026-09-05 이지웰이 점검에 들어가며
               다른 도메인의 안내 페이지로 보냈고, 상품도 없음-문구도 질의어도 없는 그
               페이지가 `echoesQuery=false` 몰의 "질의어 0회 = 없음" 조건을 완벽히 만족해
               **있는 질의를 '없음'으로** 판정했다(ADR-17 이 가장 위험하다고 적은 방향).
               판정 규칙을 정교하게 만들어도 엉뚱한 페이지를 판정하면 소용이 없어 앞에서 막는다. */
            if (!sameHost(req.uri(), resp.uri())) {
                log.warn("실시간 조회가 다른 곳으로 넘어갔다 — {} 요청={} 도착={}",
                        t.platformId(), req.uri().getHost(),
                        resp.uri() == null ? "(모름)" : resp.uri().getHost());
                return ProbeOutcome.fail(ProbeOutcome.REDIRECTED);
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

    /**
     * robots 응답을 어떻게 다룰지. **상태·출처·크기 규칙을 한 곳에 모아** 시험 가능하게 둔다.
     * (`fetchRobots` 는 https 를 고정으로 만들어 로컬 서버로는 잴 수 없어, 판단만 떼어냈다.)
     *
     * 세 갈래다:
     *   - 404·410 → 금지가 없다. 오류가 아니라 **빈 규칙**이다.
     *   - 그 밖의 비-2xx → 관측 실패. 401·403 은 RFC 가 허용해도 **막는다는 신호**라
     *     '파일 없음'으로 보지 않는다(ADR-21).
     *   - 2xx 인데 **다른 호스트에서 왔다** → 그 몰의 robots.txt 가 아니다. 관측 실패.
     *     2026-09-05 현대이지웰이 점검에 들어가며 `www.onnuri-sijang.com/robots.txt` 를
     *     다른 도메인의 **HTML 안내 페이지**로 302 보냈고, 우리는 그것을 robots 로 파싱해
     *     `allowed: true` 로 보고하고 있었다. 마침 실제 규칙과 결과가 같아 눈에 띄지
     *     않았을 뿐, ADR-21 이 "감시 대상이 조용히 다른 사이트가 되는 것"이라 적은 바로
     *     그 상태다 — **판정이 우연히 맞는 것은 맞는 것이 아니다.**
     *
     * 관측 실패는 '파일 없음'(빈 규칙)으로도 규칙으로도 쓰지 않는다. ADR-21 이 error 를
     * 차단 집계와 분리해 두었으므로(모르는 것을 차단으로도 허용으로도 세지 않는다)
     * 리포트에 사유가 그대로 남는다.
     */
    static String robotsBodyOrThrow(URI requested, int status, URI finalUri, String body) {
        if (robotsMissing(status)) return "";
        if (status / 100 != 2) throw new IllegalStateException("HTTP " + status);
        if (!sameHost(requested, finalUri)) {
            throw new IllegalStateException("robots redirect to other host: "
                    + (finalUri == null ? "(unknown)" : finalUri.getHost()));
        }
        // robots.txt 는 작은 파일이다. 비정상적으로 크면 그대로 파싱하지 않는다.
        if (body == null) return "";
        return body.length() > 200_000 ? body.substring(0, 200_000) : body;
    }

    /**
     * 최종 응답이 **요청한 그 호스트**에서 온 것인가.
     *
     * 정확히 같은 호스트를 요구한다 — 2026-09-05 실측에서 조회 대상 18곳 중 17곳이
     * 요청 호스트에 그대로 머물렀고 벗어난 곳은 점검 중이던 이지웰 하나뿐이었다.
     * 어떤 몰이 나중에 www→m 처럼 정당하게 옮겨 가면 카나리아가 곧바로 알려 주고,
     * 그때의 결과는 "확인하지 못했다"이지 "없다"가 아니라 안전한 방향으로 틀어진다.
     *
     * 한계: 같은 호스트 안의 점검 페이지는 이 가드로 잡히지 않는다. 그 경우는 여전히
     * 없음-문구·상품명 패턴·응답 길이 가드에 기댄다.
     */
    static boolean sameHost(URI requested, URI actual) {
        if (requested == null || actual == null) return true;   // 모르는 것을 근거로 실패를 만들지 않는다
        String a = requested.getHost(), b = actual.getHost();
        if (a == null || b == null) return true;
        return a.equalsIgnoreCase(b);
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
