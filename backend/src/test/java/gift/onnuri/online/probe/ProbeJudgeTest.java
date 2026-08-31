package gift.onnuri.online.probe;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 판정 엔진 QA. 픽스처는 2026-08-31 각 몰의 실제 응답이다(src/test/resources/probe/).
 * 케이스는 전부 그때 실제로 겪은 오탐이며 지어낸 예가 아니다 — survey_probe.js 테스트와 같은 원칙.
 */
class ProbeJudgeTest {

    private static String fixture(String name) {
        try {
            Path p = Path.of("src/test/resources/probe/" + name);
            assertTrue(Files.exists(p), "픽스처 없음: " + p.toAbsolutePath()
                    + " — 조용히 skip 하지 않는다(실측 없는 통과는 통과가 아니다)");
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Verdict judgeFixture(String platformId, String kind, String query) {
        ProbeTarget t = ProbeTargets.byId(platformId).orElseThrow();
        return ProbeJudge.judge(t, fixture(platformId + "-" + kind + ".html"), ProbeQuery.of(query));
    }

    // ── 실측 응답 전량 대조 ───────────────────────────────────────────────

    @Test
    void 없음문구_사전이_있는_몰은_없는_질의를_없음으로_확정한다() {
        for (ProbeTarget t : ProbeTargets.ALL) {
            boolean hasDict = !t.noneMarkersBound().isEmpty() || !t.noneMarkersPlain().isEmpty();
            if (!hasDict && t.echoesQuery()) continue;   // 등급 C + 에코형은 확정 수단이 없다(아래 테스트)
            Verdict v = judgeFixture(t.platformId(), "none", "zzqqxyw12345");
            assertEquals(Verdict.NONE, v.status(), t.platformId() + " — 없는 질의인데 없음이 아니다");
        }
    }

    @Test
    void 확정_수단이_없는_몰은_없다고_말하지_않는다() {
        // onnuri-chance: 없음 문구가 없고(등급 C) 질의를 에코해 토큰 0 판정도 못 쓴다.
        // 이때 "없음"으로 접으면 있는 상품을 없다고 하는 오답이 된다 — unclear 가 정직하다.
        Verdict v = judgeFixture("onnuri-chance", "none", "zzqqxyw12345");
        assertEquals(Verdict.UNCLEAR, v.status(), "확정 근거가 없는데 없음으로 단정했다");
        assertNull(v.confidence(), "확정하지 못한 판정에 신뢰도를 붙이지 않는다");
    }

    @Test
    void 있는_질의는_없음으로_판정되지_않는다() {
        for (String id : ProbeTargets.ids()) {
            Verdict v = judgeFixture(id, "hit", "로봇청소기");
            assertNotEquals(Verdict.NONE, v.status(),
                    id + " — 상품이 있는데 없음으로 판정됐다(가장 위험한 오답)");
        }
    }

    @Test
    void 질의를_에코하지_않는_몰은_없음을_확정한다() {
        // 온누리마켓·우체국은 응답에 질의를 되뿌리지 않아 토큰 0 판정을 쓸 수 있다.
        assertEquals(Verdict.NONE, judgeFixture("onnuri-market", "none", "zzqqxyw12345").status());
        assertEquals(Verdict.NONE, judgeFixture("epost-mall", "none", "zzqqxyw12345").status());
    }

    // ── 회귀 고정: 2026-08-31 실측에서 실제로 걸린 오탐 ──────────────────

    @Test
    void 온누리마켓_약관문구는_없음_판정을_유발하지_않는다() {
        // 이 몰의 응답에서 검출되는 "없습니다"는 전부 이용약관이다.
        // 페이지 전체 문자열 매칭을 하면 상품이 있어도 항상 '없음'이 된다.
        String html = fixture("onnuri-market-hit.html");
        assertTrue(ProbeJudge.toText(html).contains("없습니다"),
                "전제 확인 — 이 픽스처에는 약관의 '없습니다'가 들어 있어야 한다");
        Verdict v = ProbeJudge.judge(ProbeTargets.byId("onnuri-market").orElseThrow(),
                html, ProbeQuery.of("로봇청소기"));
        assertNotEquals(Verdict.NONE, v.status(), "약관 문구에 걸려 없음으로 판정됐다");
    }

    @Test
    void 제목_에코만으로는_likely가_되지_않는다() {
        // 온누리굿데이형 결함: "비스포크 로봇청소기 검색결과" 제목만으로 토큰이 1회씩 잡힌다.
        ProbeTarget t = new ProbeTarget("t", "http://x/?q={q}", StandardCharsets.UTF_8,
                ProbeTarget.Scope.ONNURI_SCOPE, List.of(), List.of(),
                true, 5, null, 0, "쌀", LocalDate.now(), LocalDate.now());
        String html = "<html><head><title>비스포크 로봇청소기 검색결과</title></head><body>"
                + "<h2>비스포크 로봇청소기 검색결과</h2>"
                + "<input type='text' value='비스포크 로봇청소기'>"
                + "<div>추천 상품을 준비 중입니다. " + "다른 내용 ".repeat(120) + "</div>"
                + "</body></html>";
        Verdict v = ProbeJudge.judge(t, html, ProbeQuery.of("비스포크 로봇청소기"));
        assertNotEquals(Verdict.LIKELY, v.status(), "제목·검색창 에코를 상품으로 셌다");
    }

    @Test
    void 태그로_쪼개진_없음문구도_매치된다() {
        // <span>등록된</span> 상품이 없습니다 — 원본 HTML 문자열 매칭이면 놓친다.
        ProbeTarget t = new ProbeTarget("t", "http://x/?q={q}", StandardCharsets.UTF_8,
                ProbeTarget.Scope.ONNURI_SCOPE, List.of(), List.of("등록된 상품이 없습니다"),
                false, 5, null, 0, "쌀", LocalDate.now(), LocalDate.now());
        String html = "<html><body><div><span>등록된</span> 상품이 <b>없습니다</b></div>"
                + "<p>" + "본문 ".repeat(200) + "</p></body></html>";
        assertEquals(Verdict.NONE, ProbeJudge.judge(t, html, ProbeQuery.of("무언가")).status());
    }

    // ── 문구 매처 ───────────────────────────────────────────────────────

    @Test
    void 따옴표_안쪽_공백이_있어도_없음문구를_잡는다() {
        // 온누리시장 실측: 검색하신 ' zzqqxyw12345 '에 대한 검색결과가 없습니다
        Verdict v = judgeFixture("onnuri-sijang", "none", "zzqqxyw12345");
        assertEquals(Verdict.NONE, v.status());
        assertEquals(Verdict.HIGH, v.confidence(), "등급 A 는 단독 확정이어야 한다");
        assertNotNull(v.evidence(), "확정 근거 문구를 남겨야 한다");
    }

    @Test
    void 다중토큰_질의도_없음문구에_바인딩된다() {
        ProbeTarget t = ProbeTargets.byId("onnuri-sijang").orElseThrow();
        var p = ProbeJudge.bindQuery("검색하신 '{q}'에 대한 검색결과가 없습니다",
                ProbeQuery.of("삼성전자 비스포크"));
        assertTrue(p.matcher("검색하신 ' 삼성전자 비스포크 '에 대한 검색결과가 없습니다").find());
        assertTrue(p.matcher("검색하신 '삼성전자 비스포크'에 대한 검색결과가 없습니다").find());
        assertNotNull(t);
    }

    // ── 방어 ────────────────────────────────────────────────────────────

    @Test
    void 본문이_비면_판정하지_않는다() {
        ProbeTarget t = ProbeTargets.byId("onnuri-hotdeal").orElseThrow();
        assertEquals(Verdict.UNKNOWN, ProbeJudge.judge(t, null, ProbeQuery.of("가")).status());
        assertEquals(Verdict.UNKNOWN, ProbeJudge.judge(t, "<html><body>짧다</body></html>",
                ProbeQuery.of("가")).status(), "SPA 전환 등 구조 변화는 unknown 이어야 한다");
    }

    @Test
    void 노이즈플로어만큼_히트를_깎는다() {
        // 온누리찬스는 없는 질의에도 추천 블록에서 관련어가 2회 나온다.
        assertEquals(2, ProbeTargets.byId("onnuri-chance").orElseThrow().noiseFloor());
    }
}
