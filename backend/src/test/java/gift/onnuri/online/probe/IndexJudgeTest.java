package gift.onnuri.online.probe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import gift.onnuri.online.OnlinePlatformView;
import gift.onnuri.online.probe.OnlineProductIndexRepository.Row;
import gift.onnuri.online.probe.OnlineProductIndexRepository.Summary;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 전일 색인 층의 판정(ADR-18). 순수 함수라 DB·네트워크를 모른다 — 행 목록 in, 층 out.
 *
 * 이 층이 하는 말은 실시간 층과 **다르다**. "어제 이 몰이 이 이름의 상품을 올려 두고
 * 있었다"이지 "지금 검색된다"가 아니다. 그래서 상태 목록(none/likely/…)에 섞지 않는다.
 */
class IndexJudgeTest {

    private static OnlinePlatformView p(String id, String name) {
        return new OnlinePlatformView(id, null, "shopping", name, null, null,
                "https://" + id + ".example", false, null, "2026-09-02", "active", null);
    }

    private static Map<String, OnlinePlatformView> byId(OnlinePlatformView... rows) {
        return java.util.Arrays.stream(rows)
                .collect(Collectors.toMap(OnlinePlatformView::id, v -> v));
    }

    private static Summary s(String id, int n, String date) { return new Summary(id, n, date); }
    private static Row r(String id, String name) { return new Row(id, name, "https://x.example/" + name); }
    private static Row r(String id, String name, String url) { return new Row(id, name, url); }

    // ── 매치 규칙 ────────────────────────────────────────────────────────

    @Test
    void 모든_낱말을_담은_상품명만_찾음으로_센다() {
        var layer = IndexJudge.build(ProbeQuery.of("다이슨 청소기"),
                byId(p("tpirates", "인어교주해적단")),
                List.of(s("tpirates", 3, "2026-09-01")),
                List.of(r("tpirates", "다이슨 무선 청소기 V15"),
                        r("tpirates", "삼성 청소기"),
                        r("tpirates", "다이슨 헤어드라이어")));
        assertEquals(1, layer.items().size());
        IndexHit h = layer.items().get(0);
        assertEquals(1, h.matchCount(), "전 낱말을 담은 이름만 세야 한다");
        assertEquals(List.of("다이슨 무선 청소기 V15"), h.sampleTitles());
        assertFalse(h.samplePartial());
        assertEquals(1, layer.foundCount());
    }

    @Test
    void 전부_담은_이름이_없으면_일부만_맞는다고_밝힌다() {
        // "다이슨 청소기"에 '청소기'만 맞는 이름을 근거로 내밀면 이용자는
        // "이 몰에 다이슨이 있다"로 읽는다(2026-08-31 공공몰 실측과 같은 함정).
        var layer = IndexJudge.build(ProbeQuery.of("다이슨 청소기"),
                byId(p("tpirates", "인어교주해적단")),
                List.of(s("tpirates", 2, "2026-09-01")),
                List.of(r("tpirates", "삼성 로봇 청소기"), r("tpirates", "LG 청소기")));
        IndexHit h = layer.items().get(0);
        assertEquals(0, h.matchCount(), "일부만 맞는 것을 찾음으로 세면 안 된다");
        assertTrue(h.samplePartial());
        assertEquals(2, h.sampleTitles().size());
        assertEquals(0, layer.foundCount());
    }

    @Test
    void 아무_낱말도_안_걸린_몰은_목록에_넣지_않는다() {
        var layer = IndexJudge.build(ProbeQuery.of("다이슨"),
                byId(p("tpirates", "인어교주해적단")),
                List.of(s("tpirates", 1, "2026-09-01")),
                List.of(r("tpirates", "고등어 한 손")));
        assertTrue(layer.items().isEmpty(), "걸리지 않은 몰을 목록에 넣으면 화면이 헛것을 그린다");
        assertEquals(1, layer.platformCount(), "다만 색인해 둔 몰 수는 그대로다");
    }

    @Test
    void 대소문자는_무시한다() {
        var layer = IndexJudge.build(ProbeQuery.of("dyson"),
                byId(p("tpirates", "인어교주해적단")),
                List.of(s("tpirates", 1, "2026-09-01")),
                List.of(r("tpirates", "DYSON Airwrap")));
        assertEquals(1, layer.items().get(0).matchCount());
    }

    @Test
    void 샘플은_낱말_많은_순_그다음_짧은_이름_순으로_최대_3건() {
        var layer = IndexJudge.build(ProbeQuery.of("김치"),
                byId(p("tpirates", "인어교주해적단")),
                List.of(s("tpirates", 5, "2026-09-01")),
                List.of(r("tpirates", "포기김치 10kg 국내산 절임배추"),
                        r("tpirates", "김치만두"),
                        r("tpirates", "총각김치 3kg"),
                        r("tpirates", "묵은지 김치찌개용 1kg")));
        List<String> s = layer.items().get(0).sampleTitles();
        assertEquals(3, s.size(), "샘플은 3건까지");
        assertEquals("김치만두", s.get(0), "짧은 이름이 먼저 — 화면에서 읽히는 순서다");
        assertEquals(4, layer.items().get(0).matchCount(), "샘플을 3건으로 잘라도 건수는 전부 센다");
    }

    // ── 층의 범위 ────────────────────────────────────────────────────────

    @Test
    void 실시간_조회_대상은_색인_층에서_뺀다() {
        // 한 몰이 두 층에서 다른 말을 하면 이용자는 어느 쪽을 믿을지 알 수 없다(ADR-18).
        String realtime = ProbeTargets.ids().get(0);
        var layer = IndexJudge.build(ProbeQuery.of("김치"),
                byId(p(realtime, "실시간 대상 몰"), p("tpirates", "인어교주해적단")),
                List.of(s(realtime, 100, "2026-09-01"), s("tpirates", 2, "2026-09-01")),
                List.of(r(realtime, "포기김치"), r("tpirates", "총각김치")));
        assertEquals(1, layer.platformCount());
        assertTrue(layer.items().stream().noneMatch(h -> h.platformId().equals(realtime)),
                realtime + " 이 두 층에 동시에 나왔다");
    }

    @Test
    void 목록에_없는_몰은_이름을_모르므로_뺀다() {
        // 플랫폼이 removed 이거나 배달로 바뀌면 화면에 그릴 근거가 없다.
        var layer = IndexJudge.build(ProbeQuery.of("김치"),
                byId(p("tpirates", "인어교주해적단")),
                List.of(s("tpirates", 1, "2026-09-01"), s("사라진-몰", 5, "2026-09-01")),
                List.of(r("tpirates", "총각김치"), r("사라진-몰", "포기김치")));
        assertEquals(1, layer.platformCount());
        assertTrue(layer.items().stream().noneMatch(h -> h.platformId().equals("사라진-몰")));
    }

    @Test
    void asOf_는_포함_몰의_가장_오래된_수집일이다() {
        // 가장 최근 날짜를 쓰면 "어제 기준"이라면서 사흘 전 데이터를 섞어 말하게 된다.
        var layer = IndexJudge.build(ProbeQuery.of("김치"),
                byId(p("tpirates", "인어교주해적단"), p("onnuri-noljang", "온누리 놀장")),
                List.of(s("tpirates", 1, "2026-09-02"), s("onnuri-noljang", 1, "2026-08-30")),
                List.of(r("tpirates", "총각김치"), r("onnuri-noljang", "포기김치")));
        assertEquals("2026-08-30", layer.asOf());
        assertEquals(2, layer.platformCount());
        assertEquals("2026-09-02",
                layer.items().stream().filter(h -> h.platformId().equals("tpirates"))
                        .findFirst().orElseThrow().collectedOn(),
                "몰별 수집일은 그 몰의 것이어야 한다");
    }

    @Test
    void 색인이_비면_빈_층을_준다() {
        var layer = IndexJudge.build(ProbeQuery.of("김치"),
                byId(p("tpirates", "인어교주해적단")), List.of(), List.of());
        assertEquals(0, layer.platformCount());
        assertEquals(0, layer.foundCount());
        assertNull(layer.notice(), "색인이 없으면 화면에 아무 말도 하지 않는다");
        assertNotNull(layer.items(), "null 을 주면 프론트가 깨진다");
        assertTrue(layer.items().isEmpty());
    }

    @Test
    void 검색URL은_그_몰의_검색_링크를_따른다() {
        OnlinePlatformView v = new OnlinePlatformView("tpirates", null, "shopping", "인어교주해적단",
                null, null, "https://tpirates.com", false, null, "2026-09-02", "active",
                "https://tpirates.com/search?q={q}");
        var layer = IndexJudge.build(ProbeQuery.of("김치"), Map.of("tpirates", v),
                List.of(s("tpirates", 1, "2026-09-01")), List.of(r("tpirates", "총각김치")));
        assertEquals("https://tpirates.com/search?q=%EA%B9%80%EC%B9%98",
                layer.items().get(0).searchUrl());
    }

    // ── 안내 문구 4분기 ──────────────────────────────────────────────────

    @Test
    void 찾았으면_수집_시점_기준임을_밝힌다() {
        var layer = IndexJudge.build(ProbeQuery.of("김치"),
                byId(p("tpirates", "인어교주해적단")),
                List.of(s("tpirates", 1, "2026-09-01")), List.of(r("tpirates", "총각김치")));
        // 상대 표현("어제")을 쓰지 않는다 — 배치는 당일 00:30 에 돌고, 실패한 날에는
        // 사흘 전 것이 남는다. 이름은 성격으로, 시점은 실제 날짜로만 말한다.
        assertTrue(layer.notice().contains("상품명 색인"), layer.notice());
        assertFalse(layer.notice().contains("어제"), layer.notice());
        assertTrue(layer.notice().contains("1곳"), layer.notice());
        assertTrue(layer.notice().contains("2026-09-01"), "수집일을 밝히지 않았다: " + layer.notice());
        assertTrue(layer.notice().contains("확인"), "지금 재고를 확정하지 않는다는 단서가 없다");
    }

    @Test
    void 일부만_맞으면_찾는_상품이_아닐_수_있다고_말한다() {
        var layer = IndexJudge.build(ProbeQuery.of("다이슨 청소기"),
                byId(p("tpirates", "인어교주해적단")),
                List.of(s("tpirates", 1, "2026-09-01")), List.of(r("tpirates", "LG 청소기")));
        assertTrue(layer.notice().contains("일부 낱말"), layer.notice());
        assertTrue(layer.notice().contains("찾는 상품이 아닐 수 있습니다"), layer.notice());
    }

    @Test
    void 하나도_없으면_색인에_없다는_뜻임을_밝힌다() {
        // "색인에 없다"를 "그 몰에 없다"로 읽게 두면 실시간 층이 쌓아 온 정직함이 무너진다.
        var layer = IndexJudge.build(ProbeQuery.of("다이슨"),
                byId(p("tpirates", "인어교주해적단")),
                List.of(s("tpirates", 1, "2026-09-01")), List.of(r("tpirates", "고등어")));
        assertTrue(layer.notice().contains("색인에 없다는 뜻"), layer.notice());
        assertTrue(layer.notice().contains("확정은 아닙니다"), layer.notice());
    }

    @Test
    void 안내문구의_모든_문장이_제대로_끝난다() {
        for (var q : List.of("김치", "다이슨 청소기", "존재하지않는말")) {
            var layer = IndexJudge.build(ProbeQuery.of(q),
                    byId(p("tpirates", "인어교주해적단")),
                    List.of(s("tpirates", 2, "2026-09-01")),
                    List.of(r("tpirates", "총각김치"), r("tpirates", "LG 청소기")));
            for (String sentence : layer.notice().split("(?<=\\.)\\s+")) {
                String x = sentence.trim();
                if (x.isEmpty()) continue;
                assertTrue(x.endsWith(".") || x.endsWith("니다") || x.endsWith("요"),
                        "문장이 끊겼다: [" + x + "] 전체: " + layer.notice());
            }
        }
    }

    @Test
    void 샘플에는_그_상품에_닿는_주소가_같은_순서로_실린다() {
        // 색인 대상은 정의상 '검색이 안 되는 몰'이라 몰 홈으로 보내면 이용자가 그 상품을
        // 찾을 수 없다. 배치가 넣어 둔 상품 주소를 샘플과 **같은 순서**로 싣는다.
        var layer = IndexJudge.build(ProbeQuery.of("김치"),
                byId(p("onnuri-noljang", "온누리 놀장")),
                List.of(s("onnuri-noljang", 2, "2026-09-03")),
                List.of(r("onnuri-noljang", "총각김치 3kg", "https://mall.example/market/36#총각김치"),
                        r("onnuri-noljang", "배추김치 5kg", "https://mall.example/market/12#배추김치")));
        var hit = layer.items().get(0);
        assertEquals(hit.sampleTitles().size(), hit.sampleUrls().size(),
                "샘플 수와 주소 수가 같아야 자리가 어긋나지 않는다");
        for (int i = 0; i < hit.sampleTitles().size(); i++) {
            String name = hit.sampleTitles().get(i);
            assertTrue(hit.sampleUrls().get(i).contains(name.split(" ")[0]),
                    "i=" + i + " 샘플 '" + name + "' 의 주소가 다른 상품을 가리킨다: " + hit.sampleUrls().get(i));
        }
    }

    @Test
    void 주소가_없는_행은_빈_문자열로_자리를_지킨다() {
        // 주소를 못 걷은 행이 섞여도 자리가 밀리면 안 된다 — 밀리면 화면이 엉뚱한 상품에 링크를 건다.
        var layer = IndexJudge.build(ProbeQuery.of("김치"),
                byId(p("onnuri-noljang", "온누리 놀장")),
                List.of(s("onnuri-noljang", 1, "2026-09-03")),
                List.of(new Row("onnuri-noljang", "총각김치 3kg", null)));
        var hit = layer.items().get(0);
        assertEquals(hit.sampleTitles().size(), hit.sampleUrls().size());
        assertEquals("", hit.sampleUrls().get(0));
    }
}
