package gift.onnuri.online.probe;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import gift.onnuri.online.OnlinePlatformView;
import gift.onnuri.online.OnlineRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 서비스 전체 경로를 DB·네트워크 없이 검증한다(이 저장소는 SpringBootTest 를 쓰지 않는다).
 * 2단계 현재는 스텁이라 전 항목이 not-probed 로 나오는 것이 정상이다.
 */
class OnlineSearchServiceTest {

    private static OnlinePlatformView p(String id, String name, String kind, String url) {
        return p(id, name, kind, url, null);
    }

    private static OnlinePlatformView p(String id, String name, String kind, String url,
                                        String searchTpl) {
        return new OnlinePlatformView(id, null, kind, name, null, null, url,
                false, null, "2026-08-06", "active", searchTpl);
    }

    /** 네트워크는 타지 않는다 — fetcher 를 모킹해 "받지 못했다"로 고정한다. */
    private static OnlineSearchService svc(List<OnlinePlatformView> rows, boolean enabled) {
        return svc(rows, enabled, ProbeOutcome.fail(ProbeOutcome.TIMEOUT));
    }

    private static OnlineSearchService svc(List<OnlinePlatformView> rows, boolean enabled,
                                           ProbeOutcome outcome) {
        OnlineRepository repo = Mockito.mock(OnlineRepository.class);
        Mockito.when(repo.findAll()).thenReturn(rows);
        ProbeFetcher fetcher = Mockito.mock(ProbeFetcher.class);
        Mockito.when(fetcher.fetch(Mockito.any(), Mockito.any())).thenReturn(outcome);
        ProbeCache cache = new ProbeCache(60, 100);
        return new OnlineSearchService(repo, emptyIndex(), fetcher, cache, enabled, 5000);
    }

    /** 색인 없음(테이블이 비어 있는 상태) — 실시간 층 검증에 색인을 섞지 않는다. */
    private static OnlineProductIndexRepository emptyIndex() {
        OnlineProductIndexRepository idx = Mockito.mock(OnlineProductIndexRepository.class);
        Mockito.when(idx.summarize(Mockito.any())).thenReturn(List.of());
        Mockito.when(idx.findMatching(Mockito.any(), Mockito.any())).thenReturn(List.of());
        return idx;
    }

    @Test
    void 배달앱은_상품검색_축이_없어_목록에서_빠진다() {
        var r = svc(List.of(
                p("onnuri-hotdeal", "온누리핫딜", "shopping", "https://onnurideal.com"),
                p("ddangyo", "땡겨요", "delivery", "https://ddangyo.com")), true)
                .search(ProbeQuery.of("로봇청소기"));
        assertEquals(1, r.totalPlatforms());
        assertTrue(r.items().stream().noneMatch(h -> h.platformId().equals("ddangyo")));
    }

    @Test
    void 조회_대상_몰은_그_몰의_검색URL을_받는다() {
        var r = svc(List.of(p("onnuri-hotdeal", "온누리핫딜", "shopping", "https://onnurideal.com")), true)
                .search(ProbeQuery.of("로봇청소기"));
        ProbeHit h = r.items().get(0);
        assertEquals("onnuri-hotdeal", h.platformId());
        assertTrue(h.searchUrl().startsWith("https://onnurideal.com/search?q="),
                "검색 URL 이 아니라 홈으로 보냈다: " + h.searchUrl());
        assertTrue(h.searchUrl().contains("%"), "질의가 인코딩되지 않았다");
    }

    @Test
    void 조회_대상이_아닌_몰도_목록에_담고_홈_링크를_준다() {
        // "확인하지 않았다"와 "없다"는 다르다 — 빼버리면 이용자는 없는 줄 안다.
        // 예시는 **정적 응답에 결과가 실리지 않는** 몰로 든다(SPA + 검색 API 가 401).
        // 지니어스몰은 2026-09-02 조회 대상이 되어 더는 이 자리에 쓸 수 없다.
        var r = svc(List.of(p("tpirates", "인어교주해적단", "shopping", "https://tpirates.com")), true)
                .search(ProbeQuery.of("로봇청소기"));
        ProbeHit h = r.items().get(0);
        assertEquals(Verdict.NOT_PROBED, h.status());
        // 사유를 뭉뚱그리지 않는다 — 화면이 "왜 확인하지 않았는지"를 말해야
        // 이용자가 "없다"로 읽지 않는다(2026-09-01 사용자 요청).
        assertEquals(ProbeTargets.EX_NO_FETCH, h.reason());
        assertEquals("https://tpirates.com", h.searchUrl());
    }

    @Test
    void 능동_차단된_몰은_그_사유를_말한다() {
        // 못 하는 것과 막힌 것은 다르다. 링크를 눌러 사람이 검색하는 것은 막힌 게 아니므로,
        // 사유를 밝혀야 이용자가 링크를 눌러 볼 근거가 생긴다.
        assertEquals(ProbeTargets.EX_BOT_BLOCKED, ProbeTargets.exclusionReason("cyso"));
        // 조회 대상이 된 몰은 사전에서 빠져 있어야 한다(굿데이·인더마켓·팔도는 2026-09-03 편입).
        assertFalse(ProbeTargets.exclusionIds().contains("onnuri-5iljang"));
        assertFalse(ProbeTargets.exclusionIds().contains("onnuri-goodday"));
        assertFalse(ProbeTargets.exclusionIds().contains("inthemarket-onnuri"));
        assertFalse(ProbeTargets.exclusionIds().contains("onnuri-paldo-sijang"));
        assertFalse(ProbeTargets.exclusionIds().contains("hyundai-home-shopping"));
        assertFalse(ProbeTargets.exclusionIds().contains("11st-onnuri-market"));
        assertFalse(ProbeTargets.exclusionIds().contains("gongyoung-shopping"));
        // 조회 대상에는 제외 사유가 붙을 일이 없다 — 붙으면 목록이 어긋난 것이다.
        for (ProbeTarget t : ProbeTargets.ALL) {
            assertFalse(ProbeTargets.exclusionIds().contains(t.platformId()),
                    t.platformId() + " 가 제외 사전에 들어 있다 — 조회 대상과 겹친다");
        }
    }

    @Test
    void 사유가_네_갈래로_구분돼_화면에_나간다() {
        // 7곳을 한 사유로 뭉뚱그리면 화면이 사실과 다른 말을 한다(ADR-18·19).
        // 특히 scope-mixed 3곳은 "읽지 못한다"가 아니라 "읽어도 대부분 온누리 밖"이다.
        var r = svc(List.of(
                p("onnuri-noljang", "온누리 놀장", "shopping", "https://noljang.co.kr"),
                p("oligopalgo", "시장을 방으로", "shopping", "https://oligopalgo.kr"),
                p("tpirates", "인어교주해적단", "shopping", "https://www.tpirates.com"),
                p("lotte-on-sangsaeng-store", "롯데ON 온누리상생스토어", "shopping", "https://s.lotteon.com/x"),
                p("cyso", "사이소", "shopping", "https://www.cyso.co.kr")), true)
                .search(ProbeQuery.of("로봇청소기"));
        java.util.Map<String, String> byId = new java.util.HashMap<>();
        r.items().forEach(h -> byId.put(h.platformId(), h.reason()));
        assertEquals(ProbeTargets.EX_SCOPE_FIRST, byId.get("onnuri-noljang"));
        assertEquals(ProbeTargets.EX_SCOPE_FIRST, byId.get("oligopalgo"));
        assertEquals(ProbeTargets.EX_NO_FETCH, byId.get("tpirates"));
        assertEquals(ProbeTargets.EX_SCOPE_MIXED, byId.get("lotte-on-sangsaeng-store"));
        assertEquals(ProbeTargets.EX_BOT_BLOCKED, byId.get("cyso"));
    }

    @Test
    void 링크에_검색어를_실을_수_없는_몰은_전용관_주소를_준다() {
        // 현대홈쇼핑 전용관 화면은 URL 의 검색어를 무시한다. {q} 없는 링크라도
        // 데이터에 있으면 그것을 쓴다 — 안 쓰면 조회 URL(JSON)이 이용자에게 나간다.
        var r = svc(List.of(p("hyundai-home-shopping", "현대홈쇼핑", "shopping",
                "https://www.hmall.com/",
                "https://www.hmall.com/md/dpa/searchSpexSectItem?sectId=3132118")), true)
                .search(ProbeQuery.of("세트"));
        String url = r.items().get(0).searchUrl();
        assertEquals("https://www.hmall.com/md/dpa/searchSpexSectItem?sectId=3132118", url);
        assertFalse(url.contains("/api/"), "JSON 조회 URL 이 이용자 링크로 나갔다: " + url);
    }

    @Test
    void 킬스위치를_끄면_이유를_밝히고_링크는_유지한다() {
        var r = svc(List.of(p("onnuri-hotdeal", "온누리핫딜", "shopping", "https://onnurideal.com")), false)
                .search(ProbeQuery.of("로봇청소기"));
        ProbeHit h = r.items().get(0);
        assertEquals("disabled", h.reason(), "기능이 꺼진 것을 조용히 감추면 안 된다");
        assertFalse(h.searchUrl().isBlank(), "꺼져 있어도 직접 확인 경로는 남긴다");
    }

    @Test
    void 조회_대상이_아니어도_검색URL이_있으면_그리로_보낸다() {
        // 4단계에서 22곳에 search_url_template 을 넣었다 — 확인하지 않은 몰도
        // 홈이 아니라 그 몰의 검색 결과로 바로 갈 수 있어야 한다.
        // 조회 대상이 아닌 몰로 예시를 든다(굿데이는 2026-09-03 조회 대상이 됐다).
        var r = svc(List.of(p("cyso", "사이소", "shopping",
                "https://www.cyso.co.kr/",
                "https://www.cyso.co.kr/search?q={q}")), true)
                .search(ProbeQuery.of("김치"));
        ProbeHit h = r.items().get(0);
        assertEquals(Verdict.NOT_PROBED, h.status());
        assertTrue(h.searchUrl().startsWith("https://www.cyso.co.kr/search?q="),
                "검색 URL 이 있는데 홈으로 보냈다: " + h.searchUrl());
        assertFalse(h.searchUrl().contains("{q}"), "치환되지 않았다");
    }

    @Test
    void 검색URL이_없으면_홈으로_보낸다() {
        var r = svc(List.of(p("tpirates", "인어교주해적단", "shopping",
                "https://tpirates.com", "")), true).search(ProbeQuery.of("김치"));
        assertEquals("https://tpirates.com", r.items().get(0).searchUrl());
    }

    @Test
    void 딥링크_몰에는_범위_밖_표시가_붙는다() {
        var r = svc(List.of(p("epost-mall", "우체국쇼핑", "shopping", "https://mall.epost.go.kr")), true)
                .search(ProbeQuery.of("로봇청소기"));
        assertTrue(r.items().get(0).mallWide(), "온누리 범위 밖이 섞인다는 표시가 없다");
    }

    @Test
    void 조회에_실패하면_사유를_남기고_없음으로_접지_않는다() {
        // 타임아웃을 "없음"으로 뭉개면 있는 상품을 없다고 하는 오답이 된다.
        var r = svc(List.of(p("onnuri-hotdeal", "온누리핫딜", "shopping", "https://onnurideal.com")), true)
                .search(ProbeQuery.of("로봇청소기"));
        ProbeHit h = r.items().get(0);
        assertEquals(Verdict.UNKNOWN, h.status());
        assertEquals(ProbeOutcome.TIMEOUT, h.reason());
        assertEquals(0, r.noneCount(), "실패를 없음으로 셌다");
        assertEquals(1, r.unknownCount());
    }

    @Test
    void 응답을_받으면_판정해서_돌려준다() {
        String html = "<html><body><div>" + "로봇청소기 ".repeat(30)
                + "본문 ".repeat(200) + "</div></body></html>";
        var r = svc(List.of(p("onnuri-hotdeal", "온누리핫딜", "shopping", "https://onnurideal.com")),
                true, ProbeOutcome.ok(html)).search(ProbeQuery.of("로봇청소기"));
        assertEquals(Verdict.LIKELY, r.items().get(0).status());
        assertEquals(1, r.likelyCount());
    }

    @Test
    void 같은_질의는_캐시로_돌려주고_다시_밖으로_나가지_않는다() {
        OnlineRepository repo = Mockito.mock(OnlineRepository.class);
        Mockito.when(repo.findAll()).thenReturn(
                List.of(p("onnuri-hotdeal", "온누리핫딜", "shopping", "https://onnurideal.com")));
        ProbeFetcher fetcher = Mockito.mock(ProbeFetcher.class);
        Mockito.when(fetcher.fetch(Mockito.any(), Mockito.any()))
                .thenReturn(ProbeOutcome.fail(ProbeOutcome.TIMEOUT));
        var s = new OnlineSearchService(repo, emptyIndex(), fetcher, new ProbeCache(60, 100), true, 5000);

        s.searchCached(ProbeQuery.of("로봇청소기"));
        s.searchCached(ProbeQuery.of("로봇청소기"));
        s.searchCached(ProbeQuery.of("로봇청소기"));
        Mockito.verify(fetcher, Mockito.times(1)).fetch(Mockito.any(), Mockito.any());
    }

    @Test
    void 대소문자만_다른_질의는_같은_캐시를_쓴다() {
        OnlineRepository repo = Mockito.mock(OnlineRepository.class);
        Mockito.when(repo.findAll()).thenReturn(
                List.of(p("onnuri-hotdeal", "온누리핫딜", "shopping", "https://onnurideal.com")));
        ProbeFetcher fetcher = Mockito.mock(ProbeFetcher.class);
        Mockito.when(fetcher.fetch(Mockito.any(), Mockito.any()))
                .thenReturn(ProbeOutcome.fail(ProbeOutcome.TIMEOUT));
        var s = new OnlineSearchService(repo, emptyIndex(), fetcher, new ProbeCache(60, 100), true, 5000);

        s.searchCached(ProbeQuery.of("DJI 드론"));
        s.searchCached(ProbeQuery.of("dji 드론"));
        Mockito.verify(fetcher, Mockito.times(1)).fetch(Mockito.any(), Mockito.any());
    }

    // ── 전일 색인 층 (ADR-18) ────────────────────────────────────────────

    /** 색인 행이 있는 저장소. 실시간 대상이 아닌 몰만 색인된다. */
    private static OnlineProductIndexRepository indexOf(String platformId, String... names) {
        OnlineProductIndexRepository idx = Mockito.mock(OnlineProductIndexRepository.class);
        Mockito.when(idx.summarize(Mockito.any())).thenReturn(
                List.of(new OnlineProductIndexRepository.Summary(platformId, names.length, "2026-09-01")));
        Mockito.when(idx.findMatching(Mockito.any(), Mockito.any())).thenReturn(
                java.util.Arrays.stream(names)
                        .map(n -> new OnlineProductIndexRepository.Row(platformId, n)).toList());
        return idx;
    }

    private static OnlineSearchService svcWithIndex(List<OnlinePlatformView> rows, boolean enabled,
                                                    OnlineProductIndexRepository idx) {
        OnlineRepository repo = Mockito.mock(OnlineRepository.class);
        Mockito.when(repo.findAll()).thenReturn(rows);
        ProbeFetcher fetcher = Mockito.mock(ProbeFetcher.class);
        Mockito.when(fetcher.fetch(Mockito.any(), Mockito.any()))
                .thenReturn(ProbeOutcome.fail(ProbeOutcome.TIMEOUT));
        return new OnlineSearchService(repo, idx, fetcher, new ProbeCache(60, 100), enabled, 5000);
    }

    @Test
    void 색인_층은_항상_채워서_보낸다() {
        // null 층을 주면 프론트가 매번 방어 코드를 써야 하고, 한 번 빠뜨리면 화면이 깨진다.
        var r = svc(List.of(p("onnuri-hotdeal", "온누리핫딜", "shopping", "https://onnurideal.com")), true)
                .search(ProbeQuery.of("김치"));
        assertNotNull(r.index());
        assertEquals(0, r.index().platformCount());
        assertNull(r.index().notice(), "색인이 없으면 화면에 아무 말도 하지 않는다");
    }

    @Test
    void 킬스위치가_꺼져도_색인_층은_계산한다() {
        // 실시간이 막힌 몰을 위해 만든 층이다 — 실시간이 죽을 때 함께 죽으면 뜻이 없다.
        var r = svcWithIndex(List.of(
                p("onnuri-hotdeal", "온누리핫딜", "shopping", "https://onnurideal.com"),
                p("tpirates", "인어교주해적단", "shopping", "https://tpirates.com")),
                false, indexOf("tpirates", "남송 꽃게 1kg")).search(ProbeQuery.of("꽃게"));
        assertTrue(r.items().stream().anyMatch(h -> "disabled".equals(h.reason())),
                "킬스위치가 꺼진 것이 실시간 층에 반영되지 않았다");
        assertEquals(1, r.index().foundCount(), "킬스위치가 색인 층까지 껐다");
    }

    @Test
    void 캐시가_적중해도_색인은_다시_계산한다() {
        // 실시간 결과는 60분 캐시가 맞지만 색인은 매일 밤 갈린다 — 함께 캐시하면
        // 자정을 넘긴 뒤에도 옛 색인을 "전일 기준"이라며 계속 보여 준다.
        var idx = indexOf("tpirates", "남송 꽃게 1kg");
        var s = svcWithIndex(List.of(p("tpirates", "인어교주해적단", "shopping", "https://tpirates.com")),
                true, idx);
        s.searchCached(ProbeQuery.of("꽃게"));
        s.searchCached(ProbeQuery.of("꽃게"));
        var r = s.searchCached(ProbeQuery.of("꽃게"));
        assertEquals(1, r.index().foundCount());
        Mockito.verify(idx, Mockito.times(3)).summarize(Mockito.any());
    }

    @Test
    void 색인_조회가_실패해도_실시간_결과는_그대로_나간다() {
        // 보조 기능이 본 기능을 끌어내리면 안 된다.
        OnlineProductIndexRepository idx = Mockito.mock(OnlineProductIndexRepository.class);
        Mockito.when(idx.summarize(Mockito.any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        var r = svcWithIndex(List.of(p("onnuri-hotdeal", "온누리핫딜", "shopping", "https://onnurideal.com")),
                true, idx).search(ProbeQuery.of("김치"));
        assertEquals(1, r.items().size(), "색인 장애가 실시간 목록을 죽였다");
        assertEquals(0, r.index().platformCount());
        assertNotNull(r.index());
    }
}
