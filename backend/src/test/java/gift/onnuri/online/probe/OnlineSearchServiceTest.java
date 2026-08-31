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
        return new OnlineSearchService(repo, fetcher, cache, enabled, 5000);
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
        var r = svc(List.of(p("onnuri-shopping", "온누리쇼핑", "shopping", "https://onnurishop.co.kr")), true)
                .search(ProbeQuery.of("로봇청소기"));
        ProbeHit h = r.items().get(0);
        assertEquals(Verdict.NOT_PROBED, h.status());
        // 사유를 뭉뚱그리지 않는다 — 화면이 "왜 확인하지 않았는지"를 말해야
        // 이용자가 "없다"로 읽지 않는다(2026-09-01 사용자 요청).
        assertEquals(ProbeTargets.EX_NO_FETCH, h.reason());
        assertEquals("https://onnurishop.co.kr", h.searchUrl());
    }

    @Test
    void robots_로_막힌_몰은_그_사유를_말한다() {
        // 되는데 안 하는 것과 못 하는 것은 다르다. 링크를 눌러 사람이 검색하는 것은
        // robots 대상이 아니므로, 사유를 밝혀야 이용자가 링크를 눌러 볼 근거가 생긴다.
        assertEquals(ProbeTargets.EX_ROBOTS, ProbeTargets.exclusionReason("onnuri-goodday"));
        assertEquals(ProbeTargets.EX_ROBOTS, ProbeTargets.exclusionReason("inthemarket-onnuri"));
        assertEquals(ProbeTargets.EX_NO_FETCH, ProbeTargets.exclusionReason("onnuri-5iljang"));
        // 조회 대상 6곳에는 제외 사유가 붙을 일이 없다 — 붙으면 목록이 어긋난 것이다.
        for (ProbeTarget t : ProbeTargets.ALL) {
            assertEquals(ProbeTargets.EX_NO_FETCH, ProbeTargets.exclusionReason(t.platformId()),
                    t.platformId() + " 가 제외 사전에 들어 있다 — 조회 대상과 겹친다");
        }
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
        // 조회 대상이 아닌 몰로 예시를 든다(꾹AI 는 2026-09-01 조회 대상이 됐다).
        var r = svc(List.of(p("onnuri-goodday", "온누리굿데이", "shopping",
                "https://www.onnurigood.com/",
                "https://www.onnurigood.com/?pn=product.search.list&search_word={q}")), true)
                .search(ProbeQuery.of("김치"));
        ProbeHit h = r.items().get(0);
        assertEquals(Verdict.NOT_PROBED, h.status());
        assertTrue(h.searchUrl().startsWith("https://www.onnurigood.com/?pn="),
                "검색 URL 이 있는데 홈으로 보냈다: " + h.searchUrl());
        assertFalse(h.searchUrl().contains("{q}"), "치환되지 않았다");
    }

    @Test
    void 검색URL이_없으면_홈으로_보낸다() {
        var r = svc(List.of(p("onnuri-shopping", "온누리쇼핑", "shopping",
                "https://onnurishop.co.kr", "")), true).search(ProbeQuery.of("김치"));
        assertEquals("https://onnurishop.co.kr", r.items().get(0).searchUrl());
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
        var s = new OnlineSearchService(repo, fetcher, new ProbeCache(60, 100), true, 5000);

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
        var s = new OnlineSearchService(repo, fetcher, new ProbeCache(60, 100), true, 5000);

        s.searchCached(ProbeQuery.of("DJI 드론"));
        s.searchCached(ProbeQuery.of("dji 드론"));
        Mockito.verify(fetcher, Mockito.times(1)).fetch(Mockito.any(), Mockito.any());
    }
}
