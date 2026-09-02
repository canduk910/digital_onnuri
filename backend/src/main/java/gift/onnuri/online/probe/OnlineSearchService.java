package gift.onnuri.online.probe;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import gift.onnuri.online.OnlinePlatformView;
import gift.onnuri.online.OnlineRepository;

/**
 * 온라인 사용처 실시간 조회 (ADR-17).
 *
 * 조회는 대상 몰에 병렬로 나가고, 전체 예산을 넘기면 못 받은 곳은 unknown 으로 넘긴다 —
 * 한 몰이 느리다고 나머지를 버리지 않는다. 결과는 질의 단위로 캐시해 같은 질의가
 * 다시 상대 사이트로 나가지 않게 한다.
 *
 * 대상이 아닌 몰도 반드시 목록에 담는다. "확인하지 않았다"와 "없다"는 다르고,
 * 그 구분이 이 기능 정직함의 전부다.
 */
@Service
public class OnlineSearchService {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private static final Logger log = LoggerFactory.getLogger(OnlineSearchService.class);

    private final OnlineRepository repo;
    private final OnlineProductIndexRepository indexRepo;
    private final ProbeFetcher fetcher;
    private final ProbeCache cache;
    private final boolean enabled;
    private final long budgetMs;

    /** 몰 수만큼의 블로킹 I/O 에 플랫폼 스레드를 쓰지 않는다. 동시성 상한은 ProbeFetcher 의 세마포어가 건다. */
    private final ExecutorService pool =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("probe-", 0).factory());

    public OnlineSearchService(OnlineRepository repo, OnlineProductIndexRepository indexRepo,
                               ProbeFetcher fetcher, ProbeCache cache,
                               @Value("${app.online.probe.enabled:true}") boolean enabled,
                               @Value("${app.online.probe.budget-ms:5000}") long budgetMs) {
        this.repo = repo;
        this.indexRepo = indexRepo;
        this.fetcher = fetcher;
        this.cache = cache;
        this.enabled = enabled;
        this.budgetMs = budgetMs;
    }

    /**
     * 캐시에만 있는지 본다(없으면 null) — 컨트롤러가 한도 소비 여부를 정하는 데 쓴다.
     *
     * 색인 층은 **캐시에서 꺼내지 않고 다시 계산한다.** 실시간 결과는 60분 캐시가 맞지만
     * 색인은 매일 밤 갈리는 값이라 수명이 다르다 — 함께 캐시하면 자정을 넘긴 뒤에도
     * 어제 이전 색인을 "전일 기준"이라며 계속 보여 준다. DB 읽기뿐이라 아웃바운드는 0이다.
     */
    public OnlineSearchResult cached(ProbeQuery q) {
        OnlineSearchResult hit = cache.get(q.cacheKey());
        return hit == null ? null : hit.withIndex(indexLayer(q, shopping()));
    }

    /** 캐시 우선. 적중하면 상대 사이트로 나가는 요청이 0이다. */
    public OnlineSearchResult searchCached(ProbeQuery q) {
        OnlineSearchResult hit = cached(q);
        if (hit != null) return hit;
        OnlineSearchResult fresh = search(q);
        cache.put(q.cacheKey(), fresh);
        return fresh;
    }

    /** 이용자가 보는 온라인 사용처(배달은 음식 주문이라 상품 검색 축이 없다). */
    private List<OnlinePlatformView> shopping() {
        return repo.findAll().stream()
                .filter(p -> "active".equals(p.status()))
                .filter(p -> !"delivery".equals(p.kind()))
                .toList();
    }

    /**
     * 전일 색인 층(ADR-18). 실시간 조회와 **독립**이라 킬스위치가 꺼져 있어도 계산한다 —
     * 실시간이 막힌 몰을 위해 만든 층이므로 실시간이 죽을 때 함께 죽으면 뜻이 없다.
     *
     * DB 가 흔들리면 빈 층으로 넘긴다. 색인 조회 실패가 실시간 층까지 죽이면
     * 보조 기능이 본 기능을 끌어내리는 셈이 된다.
     */
    private IndexLayer indexLayer(ProbeQuery q, List<OnlinePlatformView> platforms) {
        try {
            Map<String, OnlinePlatformView> byId = platforms.stream()
                    .collect(Collectors.toMap(OnlinePlatformView::id, Function.identity(), (a, b) -> a));
            List<String> candidates = byId.keySet().stream()
                    .filter(id -> ProbeTargets.byId(id).isEmpty())   // 실시간 대상은 색인 층에서 뺀다
                    .toList();
            var summaries = indexRepo.summarize(candidates);
            if (summaries.isEmpty()) return IndexLayer.empty();
            var rows = indexRepo.findMatching(candidates, q.countTokens());
            return IndexJudge.build(q, byId, summaries, rows);
        } catch (RuntimeException e) {
            log.warn("전일 색인 조회 실패 — 색인 층을 비우고 실시간 결과만 보낸다 q={}", q.normalized(), e);
            return IndexLayer.empty();
        }
    }

    public OnlineSearchResult search(ProbeQuery q) {
        List<OnlinePlatformView> platforms = shopping();
        IndexLayer index = indexLayer(q, platforms);
        Map<String, OnlinePlatformView> byId = platforms.stream()
                .collect(Collectors.toMap(OnlinePlatformView::id, Function.identity(), (a, b) -> a));

        String now = STAMP.format(Instant.now());
        List<ProbeHit> items = new ArrayList<>();

        // 조회 대상이 아닌 몰은 그대로 목록에 담는다 — "확인하지 않았다"와 "없다"는 다르다.
        for (OnlinePlatformView p : platforms) {
            if (ProbeTargets.byId(p.id()).isEmpty()) {
                // 사유를 구분해 보낸다 — 화면이 "왜 확인하지 않았는지"를 말할 수 있어야 한다.
                items.add(notProbed(p, null, ProbeTargets.exclusionReason(p.id()), q, now));
            }
        }
        if (!enabled) {
            for (OnlinePlatformView p : platforms) {
                ProbeTargets.byId(p.id()).ifPresent(t ->
                        items.add(notProbed(p, t, "disabled", q, now)));
            }
            return summarize(q, now, platforms.size(), items, false).withIndex(index);
        }

        // 대상 몰 병렬 조회. 예산을 넘긴 것은 unknown 으로 넘기고 나머지를 그대로 쓴다.
        List<ProbeTarget> targets = ProbeTargets.ALL.stream()
                .filter(t -> byId.containsKey(t.platformId())).toList();
        Map<String, CompletableFuture<ProbeHit>> futures = targets.stream()
                .collect(Collectors.toMap(ProbeTarget::platformId,
                        t -> CompletableFuture.supplyAsync(
                                () -> probeOne(t, byId.get(t.platformId()), q, now), pool)));
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("실시간 조회 예산 초과 — 완료된 곳만 사용 q={}", q.normalized());
        }
        boolean throttled = false;
        for (ProbeTarget t : targets) {
            CompletableFuture<ProbeHit> f = futures.get(t.platformId());
            OnlinePlatformView p = byId.get(t.platformId());
            if (f.isDone() && !f.isCompletedExceptionally()) {
                ProbeHit h = f.join();
                items.add(h);
                if (ProbeOutcome.RATE_LIMITED.equals(h.reason())
                        || ProbeOutcome.BUSY.equals(h.reason())) throttled = true;
            } else {
                f.cancel(true);
                items.add(new ProbeHit(p.id(), p.name(), Verdict.UNKNOWN, null,
                        ProbeOutcome.TIMEOUT, null, List.of(), false, null,
                        t.mallWide(), searchUrlFor(t, p, q), now));
            }
        }
        return summarize(q, now, platforms.size(), items, throttled).withIndex(index);
    }

    private ProbeHit probeOne(ProbeTarget t, OnlinePlatformView p, ProbeQuery q, String now) {
        ProbeOutcome o = fetcher.fetch(t, q);
        String url = searchUrlFor(t, p, q);
        if (!o.fetched()) {
            // 왜 못 받았는지를 판정으로 뭉개지 않는다 — 이용자에게 사유를 보여준다.
            return new ProbeHit(p.id(), p.name(), Verdict.UNKNOWN, null, o.reason(),
                    null, List.of(), false, null, t.mallWide(), url, now);
        }
        Verdict v = ProbeJudge.judge(t, o.html(), q);
        String reason = Verdict.UNKNOWN.equals(v.status()) ? "parse-changed" : null;
        return new ProbeHit(p.id(), p.name(), v.status(), v.confidence(), reason,
                v.matchCount(), v.sampleTitles(), v.samplePartial(), v.evidence(),
                t.mallWide(), url, now);
    }

    private ProbeHit notProbed(OnlinePlatformView p, ProbeTarget t, String reason,
                               ProbeQuery q, String now) {
        return new ProbeHit(p.id(), p.name(), Verdict.NOT_PROBED, null, reason,
                null, List.of(), false, null, t != null && t.mallWide(),
                searchUrlFor(t, p, q), now);
    }

    /**
     * 이용자가 직접 열 링크. 조회 대상이면 코드의 규칙을, 아니면 데이터의
     * search_url_template 을 쓴다. 둘 다 없으면 홈으로 보낸다.
     *
     * 확인하지 않은 몰에도 검색 링크를 주는 것이 이 기능 정직함의 절반이다 —
     * "확인하지 않았다"고 말하면서 갈 곳을 주지 않으면 이용자는 결국 "없다"로 읽는다.
     */
    static String searchUrlFor(ProbeTarget t, OnlinePlatformView p, ProbeQuery q) {
        // **데이터의 링크가 먼저다.** 조회 대상이라도 그 조회 URL 이 이용자가 열 화면이라는
        // 보장이 없다 — 2026-09-02 에 추가한 두 곳은 몰의 내부 검색 API(JSON)를 부른다.
        // 그대로 링크로 주면 이용자가 JSON 을 마주한다. 사람이 볼 화면은 데이터에 있다.
        String tpl = p.searchUrlTemplate();
        if (tpl != null && !tpl.isBlank() && tpl.contains("{q}")) {
            return tpl.replace("{q}", URLEncoder.encode(q.normalized(), StandardCharsets.UTF_8));
        }
        if (t != null && !t.isApi()) return ProbeUrl.build(t, q).toString();
        return p.url() == null ? "" : p.url();
    }

    /** 카운트와 안내 문구를 서버가 만든다 — 프론트가 재계산하면 조용히 어긋난다. */
    static OnlineSearchResult summarize(ProbeQuery q, String now, int total,
                                        List<ProbeHit> items, boolean throttled) {
        int none = 0, likely = 0, unclear = 0, unknown = 0, notProbed = 0, probed = 0, wideLikely = 0;
        for (ProbeHit h : items) {
            switch (h.status()) {
                case Verdict.NONE -> { none++; probed++; }
                // mallWide 는 온누리 결제 범위 밖 상품이 섞이므로 '찾음' 집계에 넣지 않는다.
                case Verdict.LIKELY -> { if (h.mallWide()) wideLikely++; else likely++; probed++; }
                case Verdict.UNCLEAR -> { unclear++; probed++; }
                case Verdict.UNKNOWN -> { unknown++; probed++; }
                default -> notProbed++;
            }
        }
        // 색인 층은 여기서 만들지 않는다 — summarize 는 실시간 조회 결과만 셈한다.
        // 서비스가 withIndex 로 갈아 끼운다(계약상 null 은 없으므로 빈 층으로 채워 둔다).
        return new OnlineSearchResult(q.normalized(), now, total, probed,
                none, likely, unclear, unknown, notProbed, throttled,
                notice(q, total, probed, none, likely, unclear, unknown, notProbed, wideLikely),
                items, IndexLayer.empty());
    }

    static String notice(ProbeQuery q, int total, int probed,
                         int none, int likely, int unclear, int unknown, int notProbed, int wideLikely) {
        if (probed == 0) {
            return "'" + q.normalized() + "' — 지금은 실시간 확인을 하지 않았습니다. "
                    + "아래 " + total + "곳에서 직접 검색해 보세요.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("'").append(q.normalized()).append("' — ")
          .append(total).append("곳 중 ").append(probed).append("곳을 지금 확인했습니다. ");
        // 조각을 연결어미로 잇지 않는다 — 어느 조각이 마지막이 될지 몰라
        // "…없었으며." 처럼 문장이 끊긴다(2026-08-31 라이브에서 실제로 그랬다).
        List<String> parts = new ArrayList<>();
        if (likely > 0) parts.add(likely + "곳에서 관련 상품이 검색됐습니다.");
        if (none > 0) parts.add(none + "곳은 검색 결과가 없었습니다.");
        if (unclear + unknown > 0) parts.add((unclear + unknown) + "곳은 판정하지 못했습니다.");
        // mallWide 를 likely 집계에서 뺐으므로 여기서 말해 주지 않으면 확인한 곳 수와 설명한 곳 수가
        // 어긋난다 — "6곳을 확인했다"면서 5곳만 설명하는 문장이 된다(2026-08-31 실측에서 그랬다).
        if (wideLikely > 0) parts.add(wideLikely + "곳은 온누리 범위 밖 상품이 섞이는 몰이라 목록에서 따로 표시했습니다.");
        if (!parts.isEmpty()) sb.append(String.join(" ", parts)).append(" ");
        if (notProbed > 0) {
            sb.append("나머지 ").append(notProbed).append("곳은 확인하지 않았습니다 — 없다는 뜻이 아닙니다.");
        }
        return sb.toString().trim();
    }
}
