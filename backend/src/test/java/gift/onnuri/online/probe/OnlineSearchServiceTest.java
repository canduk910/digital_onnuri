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
        return new OnlinePlatformView(id, null, kind, name, null, null, url,
                false, null, "2026-08-06", "active");
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
        assertEquals("not-a-probe-target", h.reason());
        assertEquals("https://onnurishop.co.kr", h.searchUrl());
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
