package gift.onnuri.online.probe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실시간 조회 응답 계약. 프론트가 이 키를 그대로 읽으므로 이름이 바뀌면 화면이 조용히 깨진다.
 * OnlineContractTest 와 같은 방식(리플렉션 + Jackson 직렬화 키 고정).
 */
class OnlineSearchContractTest {

    private List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(r -> r.getName()).toList();
    }

    @Test
    void ProbeHit_은_프론트가_소비하는_필드를_노출한다() {
        assertEquals(List.of("platformId", "name", "status", "confidence", "reason",
                        "matchCount", "sampleTitles", "samplePartial", "evidence", "mallWide",
                        "searchUrl", "checkedAt"),
                components(ProbeHit.class));
    }

    @Test
    void OnlineSearchResult_는_카운트와_안내를_함께_준다() {
        // index 는 **맨 뒤**에 붙인다 — 기존 필드 순서가 바뀌면 프론트가 조용히 어긋난다.
        assertEquals(List.of("query", "checkedAt", "totalPlatforms", "probedCount",
                        "noneCount", "likelyCount", "unclearCount", "unknownCount",
                        "notProbedCount", "throttled", "notice", "items", "index"),
                components(OnlineSearchResult.class));
    }

    @Test
    void 전일_색인_층이_프론트가_소비하는_필드를_노출한다() {
        assertEquals(List.of("asOf", "platformCount", "foundCount", "notice", "items"),
                components(IndexLayer.class));
        assertEquals(List.of("platformId", "name", "matchCount", "sampleTitles",
                        "samplePartial", "searchUrl", "collectedOn"),
                components(IndexHit.class));
    }

    @Test
    void 색인_층_직렬화_키가_계약과_일치한다() throws Exception {
        ObjectMapper om = new ObjectMapper();
        IndexLayer layer = new IndexLayer("2026-09-01", 3, 1, "전일 색인: …",
                List.of(new IndexHit("genius-mall", "지니어스몰", 2,
                        List.of("총각김치 3kg"), false, "https://x", "2026-09-01")));
        Map<?, ?> back = om.readValue(om.writeValueAsString(layer), Map.class);
        assertEquals(List.of("asOf", "platformCount", "foundCount", "notice", "items"),
                back.keySet().stream().map(Object::toString).toList());
    }

    @Test
    void 빈_색인_층은_null_이_아니라_빈_값이다() {
        IndexLayer e = IndexLayer.empty();
        assertEquals(0, e.platformCount());
        assertEquals(0, e.foundCount());
        assertNull(e.notice());
        assertNotNull(e.items());
        assertTrue(e.items().isEmpty());
    }

    @Test
    void 색인_층은_실시간_상태값을_쓰지_않는다() {
        // "어제 올라와 있었다"와 "지금 검색된다"는 다른 주장이다. 같은 상태 목록에 담으면
        // 화면 문구가 둘 중 하나에 대해 거짓이 된다(ADR-18).
        assertFalse(java.util.Arrays.stream(IndexHit.class.getRecordComponents())
                        .anyMatch(c -> c.getName().equals("status")),
                "색인 항목에 status 가 생겼다 — 실시간 층과 섞였다");
    }

    @Test
    void 직렬화_키가_계약과_일치한다() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ProbeHit h = new ProbeHit("onnuri-hotdeal", "온누리핫딜", Verdict.LIKELY, "medium",
                null, 20, List.of("[로보락] Qrevo Edge 2 로봇청소기"), false, null, false,
                "https://onnurideal.com/search?q=x", "2026-08-31 16:00");
        Map<?, ?> back = om.readValue(om.writeValueAsString(h), Map.class);
        assertEquals(List.of("platformId", "name", "status", "confidence", "reason",
                        "matchCount", "sampleTitles", "samplePartial", "evidence", "mallWide",
                        "searchUrl", "checkedAt"),
                back.keySet().stream().map(Object::toString).toList());

        OnlineSearchResult r = new OnlineSearchResult("로봇청소기", "2026-08-31 16:00",
                22, 6, 3, 2, 1, 0, 16, false, "안내", List.of(h), IndexLayer.empty());
        Map<?, ?> rb = om.readValue(om.writeValueAsString(r), Map.class);
        assertTrue(rb.containsKey("notice"), "안내 문구는 서버가 만든다 — 키 누락");
        assertTrue(rb.containsKey("notProbedCount"), "미확인 곳 수를 감추면 안 된다");
        assertTrue(rb.containsKey("index"), "전일 색인 층이 빠졌다(ADR-18)");
    }

    // ── 집계 규칙 ────────────────────────────────────────────────────────

    private static ProbeHit hit(String id, String status, boolean mallWide) {
        return new ProbeHit(id, id, status, null, null, null, List.of(), false, null,
                mallWide, "https://x/" + id, "now");
    }

    @Test
    void 딥링크_몰의_likely는_찾음_집계에_넣지_않는다() {
        // 온누리 결제 범위 밖 상품이 섞이므로 "N곳에서 찾았다"에 포함하면 과장이 된다.
        var r = OnlineSearchService.summarize(ProbeQuery.of("로봇청소기"), "now", 22,
                List.of(hit("a", Verdict.LIKELY, false), hit("epost-mall", Verdict.LIKELY, true)),
                false);
        assertEquals(1, r.likelyCount(), "딥링크 몰이 찾음 카운트에 들어갔다");
        assertEquals(2, r.probedCount(), "다만 조회는 했으므로 probed 에는 든다");
    }

    @Test
    void 딥링크_몰만_찾은_경우에도_안내문구가_그_곳을_설명한다() {
        // likelyCount 에서 뺐으므로 문구에서도 빠지면 "6곳을 확인했다"면서 5곳만 설명하는
        // 문장이 된다 — 확인한 곳 수와 설명한 곳 수가 어긋난다(2026-08-31 '김치' 실측).
        var r = OnlineSearchService.summarize(ProbeQuery.of("김치"), "now", 22,
                List.of(hit("a", Verdict.LIKELY, false), hit("epost-mall", Verdict.LIKELY, true)),
                false);
        assertTrue(r.notice().contains("온누리 범위 밖"),
                "딥링크 몰이 문구에서 사라졌다: " + r.notice());
    }

    @Test
    void 확인하지_않은_곳은_없음이_아니라_미확인으로_센다() {
        var r = OnlineSearchService.summarize(ProbeQuery.of("로봇청소기"), "now", 22,
                List.of(hit("a", Verdict.NONE, false), hit("b", Verdict.NOT_PROBED, false)),
                false);
        assertEquals(1, r.noneCount());
        assertEquals(1, r.notProbedCount());
        assertEquals(1, r.probedCount(), "미확인은 조회 수에 들지 않는다");
    }

    @Test
    void 안내문구는_미확인_곳이_없다는_뜻이_아님을_밝힌다() {
        var r = OnlineSearchService.summarize(ProbeQuery.of("로봇청소기"), "now", 22,
                List.of(hit("a", Verdict.NONE, false), hit("b", Verdict.NOT_PROBED, false)),
                false);
        assertTrue(r.notice().contains("없다는 뜻이 아닙니다"),
                "미확인 곳을 '없음'으로 오해하게 두면 안 된다: " + r.notice());
    }

    @Test
    void 안내문구의_모든_문장이_제대로_끝난다() {
        // 2026-08-31 라이브에서 "2곳은 검색 결과가 없었으며." 로 끊겼다 —
        // 연결어미로 이으면 어느 조각이 마지막이 될지 몰라 생기는 문제다.
        for (var combo : List.of(
                List.of(hit("a", Verdict.LIKELY, false), hit("b", Verdict.NONE, false)),
                List.of(hit("a", Verdict.NONE, false)),
                List.of(hit("a", Verdict.LIKELY, false)),
                List.of(hit("a", Verdict.UNCLEAR, false), hit("b", Verdict.NONE, false)),
                List.of(hit("a", Verdict.LIKELY, true)),
                List.of(hit("a", Verdict.LIKELY, false), hit("b", Verdict.LIKELY, true)))) {
            var r = OnlineSearchService.summarize(ProbeQuery.of("로봇청소기"), "now", 22, combo, false);
            for (String sentence : r.notice().split("(?<=\\.)\\s+")) {
                String x = sentence.trim();
                if (x.isEmpty()) continue;
                assertTrue(x.endsWith(".") || x.endsWith("니다"),
                        "문장이 끊겼다: [" + x + "] 전체: " + r.notice());
                assertFalse(x.endsWith("며.") || x.endsWith("고."),
                        "연결어미로 끝났다: [" + x + "]");
            }
        }
    }

    @Test
    void 조회를_한_곳이_없으면_직접_검색을_안내한다() {
        var r = OnlineSearchService.summarize(ProbeQuery.of("로봇청소기"), "now", 22,
                List.of(hit("a", Verdict.NOT_PROBED, false)), false);
        assertTrue(r.notice().contains("직접 검색"), "실제: " + r.notice());
    }

    @Test
    void 모든_항목이_직접_열_링크를_가진다() {
        // 판정이 어떻게 되든 이용자는 스스로 확인할 수 있어야 한다.
        for (String st : List.of(Verdict.NONE, Verdict.LIKELY, Verdict.UNCLEAR,
                Verdict.UNKNOWN, Verdict.NOT_PROBED)) {
            ProbeHit h = hit("x", st, false);
            assertFalse(h.searchUrl() == null || h.searchUrl().isBlank(),
                    st + " 상태에 링크가 없다");
        }
    }
}
