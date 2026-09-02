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

    /**
     * 히트 픽스처를 **어떤 질의로 채록했는지**. 기본은 `로봇청소기` 다.
     *
     * 몰마다 파는 것이 다르니 한 질의를 전부에 들이댈 수 없다 — 현대홈쇼핑 온누리샵에는
     * 로봇청소기가 0건이다(세트 20건·쌀 10건·청소기 3건). 그 몰의 카나리아 질의와 같은 말로
     * 채록해 픽스처와 라이브 카나리아가 같은 것을 확인하게 한다.
     */
    private static String fixtureQuery(String platformId) {
        // 현대홈쇼핑 온누리샵·공영쇼핑 온누리 필터에는 로봇청소기가 0건이다(둘 다 식품 중심).
        return switch (platformId) {
            case "hyundai-home-shopping" -> "세트";
            case "gongyoung-shopping" -> "김치";
            default -> "로봇청소기";
        };
    }

    private static Verdict judgeFixture(String platformId, String kind, String query) {
        ProbeTarget t = ProbeTargets.byId(platformId).orElseThrow();
        return ProbeJudge.judge(t, fixture(platformId + "-" + kind + ".html"), ProbeQuery.of(query));
    }

    // ── 상품명 샘플 ─────────────────────────────────────────────────────

    @Test
    void 모든_조회대상이_상품명_샘플_규칙을_갖는다() {
        // 샘플은 "있다"고 단정하지 않고 근거를 보여주는 수단이다 — 이게 비면
        // 화면에 남는 것은 카운트뿐이고, 야간 카나리아의 기대치(샘플 ≥1)도 성립하지 않는다.
        // 2026-08-31 실측: 6곳 전부 null 이라 샘플이 하나도 나오지 않았다.
        for (ProbeTarget t : ProbeTargets.ALL) {
            assertNotNull(t.titlePattern(), t.platformId() + " 에 titlePattern 이 없다");
        }
    }

    @Test
    void 히트_픽스처에서_질의어를_담은_상품명을_뽑는다() {
        for (ProbeTarget t : ProbeTargets.ALL) {
            String id = t.platformId();
            String q = fixtureQuery(id);
            List<String> names = ProbeJudge.extractTitles(
                    t, fixture(id + "-hit.html"), ProbeQuery.of(q), 3);
            assertFalse(names.isEmpty(), id + " 에서 상품명을 뽑지 못했다 — 마크업이 바뀌었을 수 있다");
            for (String n : names) {
                assertTrue(n.contains(q),
                        id + " 가 질의와 무관한 이름을 샘플로 냈다: " + n);
                assertTrue(n.length() >= 4 && n.length() <= 80, id + " 샘플 길이 이상: " + n);
            }
        }
    }

    @Test
    void 없음_픽스처에서는_상품명_샘플이_나오지_않는다() {
        // 샘플이 나오면 judge 가 likely 로 기울어 "없는데 있다"가 된다 — 가장 위험한 방향.
        for (ProbeTarget t : ProbeTargets.ALL) {
            String id = t.platformId();
            assertTrue(ProbeJudge.extractTitles(
                            t, fixture(id + "-none.html"), ProbeQuery.of("zzqqxyw12345"), 3).isEmpty(),
                    id + " 가 없는 질의에 상품명을 냈다");
        }
    }

    @Test
    void 검색어_낱말을_더_많이_담은_상품명이_앞에_온다() {
        // "다이슨 청소기" 로 공공몰을 조회하면 '청소기'만 맞는 이름이 먼저 걸린다.
        // 앞에서 3개를 자르면 질의 전체를 담은 이름이 있어도 잘려 나간다.
        ProbeTarget t = ProbeTargets.byId("onnuri-hotdeal").orElseThrow();
        List<String> names = ProbeJudge.extractTitles(
                t, fixture("onnuri-hotdeal-hit.html"), ProbeQuery.of("로보락 로봇청소기"), 3);
        assertFalse(names.isEmpty());
        ProbeQuery q = ProbeQuery.of("로보락 로봇청소기");
        int prev = Integer.MAX_VALUE;
        for (String n : names) {
            int hit = (int) q.countTokens().stream().filter(n::contains).count();
            assertTrue(hit <= prev, "낱말을 덜 담은 이름이 앞에 왔다: " + names);
            prev = hit;
        }
    }

    @Test
    void 검색어_일부만_맞는_샘플은_그렇다고_표시한다() {
        // 2026-08-31 라이브: "다이슨 청소기" → 온누리공공몰 20건이 **전부 '청소기'만** 맞고
        // 다이슨은 하나도 없었다. 그대로 두면 "공공몰에 다이슨이 있다"로 읽힌다.
        ProbeQuery q = ProbeQuery.of("다이슨 청소기");
        assertTrue(ProbeJudge.samplesPartial(
                        List.of("진공 청소기 20L 대용량 상업용청소기", "스틱 무선청소기 필터청소기"), q),
                "일부만 맞는데 표시하지 않았다");
        assertFalse(ProbeJudge.samplesPartial(
                        List.of("진공 청소기 20L", "[다이슨] 클린앤워시 하이진 물청소기"), q),
                "전부 맞는 이름이 하나라도 있으면 부분 일치가 아니다");
        // 낱말이 하나뿐인 질의에는 '일부'라는 개념이 없다.
        assertFalse(ProbeJudge.samplesPartial(List.of("포기김치 3kg"), ProbeQuery.of("김치")));
        assertFalse(ProbeJudge.samplesPartial(List.of(), q), "샘플이 없으면 표시할 것도 없다");
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
            Verdict v = judgeFixture(id, "hit", fixtureQuery(id));
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
                true, 5, null, 0, "쌀", 0, LocalDate.now(), LocalDate.now());
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
                false, 5, null, 0, "쌀", 0, LocalDate.now(), LocalDate.now());
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

    @Test
    void 지니어스몰은_건수_문구로_없음을_확정하고_있으면_상품명을_낸다() {
        // 2026-09-02 승격 실측. 이 몰은 '없음'을 **건수로** 말한다 — `총 0 개의 상품이 있습니다`.
        // 판정은 태그를 걷어낸 텍스트에 대고 하므로 원문의 `총 <i>0</i>개` 가 아니라 이 형태로 잡힌다.
        Verdict none = judgeFixture("genius-mall", "none", "zzqqxyw12345");
        assertEquals(Verdict.NONE, none.status());
        assertEquals(Verdict.HIGH, none.confidence());
        assertNotNull(none.evidence(), "확정 근거 문구를 남겨야 한다");

        Verdict hit = judgeFixture("genius-mall", "hit", "로봇청소기");
        assertEquals(Verdict.LIKELY, hit.status());
        assertFalse(hit.sampleTitles().isEmpty(), "근거 없는 likely 는 화면에서 카운트만 남는다");
        assertFalse(hit.samplePartial(), "낱말이 하나뿐인 질의에는 '일부'가 없다");
    }

    @Test
    void 지니어스몰의_없음문구는_있음_응답에_없다() {
        // 등급 B 의 전제다. 상시 노출되는 문구라면 상품이 있어도 늘 '없음'이 된다
        // (온누리마켓 약관 오탐과 같은 함정). 건수를 찍는 자리라 구조적으로 함께 나올 수 없다.
        ProbeTarget t = ProbeTargets.byId("genius-mall").orElseThrow();
        String marker = t.noneMarkersPlain().get(0);
        assertFalse(ProbeJudge.toText(fixture("genius-mall-hit.html")).contains(marker),
                "있음 응답에 없음 문구가 함께 있다 — 등급 B 전제가 깨졌다");
        assertTrue(ProbeJudge.toText(fixture("genius-mall-none.html")).contains(marker));
    }

    // ── 회귀 고정: 2026-09-03 편입 3곳에서 실제로 걸린 함정 ────────────────

    @Test
    void 인더마켓은_없음_응답이_더_커도_없음으로_판정한다() {
        // 실측: 있음 199,404B < **없음 269,296B**. 없음 화면이 추천상품을 40건이나 붙이기 때문이다.
        // 응답 길이로 있음·없음을 가르는 규칙을 만들었다면 정확히 거꾸로 판단했을 것이다.
        assertTrue(fixture("inthemarket-onnuri-none.html").length()
                        > fixture("inthemarket-onnuri-hit.html").length(),
                "전제 확인 — 이 몰은 없음 응답이 더 크다");
        assertEquals(Verdict.NONE, judgeFixture("inthemarket-onnuri", "none", "zzqqxyw12345").status());
        assertEquals(Verdict.LIKELY, judgeFixture("inthemarket-onnuri", "hit", "로봇청소기").status());
    }

    @Test
    void 없음_화면의_추천상품을_검색결과로_세지_않는다() {
        // 굿데이·인더마켓의 '결과 없음' 화면에는 추천상품 20~40건이 함께 온다.
        // 상품 카드 수를 근거로 삼았다면 없는 질의에도 늘 '있음'이 됐을 것이다.
        for (String id : List.of("onnuri-goodday", "inthemarket-onnuri")) {
            assertTrue(fixture(id + "-none.html").contains("class=\"item_name\">"),
                    id + " 전제 확인 — 없음 화면에도 상품 카드가 있어야 한다");
            assertEquals(Verdict.NONE, judgeFixture(id, "none", "zzqqxyw12345").status(),
                    id + " — 추천상품을 검색결과로 셌다");
        }
    }

    @Test
    void 팔도시장_자동완성_스크립트의_없음문구는_판정을_뒤집지_않는다() {
        // `검색된 정보가 없습니다.` 가 <script> 안 자동완성 코드에 **모든 응답에** 들어 있다.
        // 원본 HTML 에 문자열 매칭했다면 상품이 있어도 늘 '없음'이 된다(온누리마켓 약관과 같은 함정).
        String hit = fixture("onnuri-paldo-sijang-hit.html");
        assertTrue(hit.contains("검색된 정보가 없습니다"), "전제 확인 — 있음 응답에도 그 문구가 있다");
        assertFalse(ProbeJudge.toText(hit).contains("검색된 정보가 없습니다"),
                "script 를 걷어내지 못했다 — 판정이 뒤집힌다");
        assertEquals(Verdict.LIKELY, judgeFixture("onnuri-paldo-sijang", "hit", "로봇청소기").status());
    }

    @Test
    void 팔도시장은_질의가_박힌_문구로_없음을_확정한다() {
        // 등급 A — `‘{q}’의 대한 검색결과 총 0 개의 상품이 있습니다`.
        // 이 몰은 질의를 되뿌리므로(echoesQuery=true) 토큰 0 판정을 쓸 수 없다.
        // 등급 A 문구가 유일한 확정 수단이라 깨지면 바로 unclear 로 내려간다.
        Verdict v = judgeFixture("onnuri-paldo-sijang", "none", "zzqqxyw12345");
        assertEquals(Verdict.NONE, v.status());
        assertEquals(Verdict.HIGH, v.confidence(), "등급 A 는 단독 확정이어야 한다");
        assertNotNull(v.evidence());
    }

    @Test
    void 편입_3곳의_없음문구는_있음_응답에_없다() {
        // 등급 B 의 전제다. 상시 노출되는 문구라면 상품이 있어도 늘 '없음'이 된다.
        for (String id : List.of("onnuri-goodday", "inthemarket-onnuri", "onnuri-paldo-sijang")) {
            ProbeTarget t = ProbeTargets.byId(id).orElseThrow();
            String hitText = ProbeJudge.toText(fixture(id + "-hit.html"));
            for (String marker : t.noneMarkersPlain()) {
                assertFalse(hitText.contains(marker),
                        id + " — 있음 응답에 없음 문구가 함께 있다: " + marker);
            }
        }
    }

    @Test
    void 현대홈쇼핑은_전용관_범위가_응답으로_확인된다() {
        // 11번가·롯데ON·공영쇼핑을 뺀 이유(결과가 몰 전체)가 이 몰에는 해당하지 않는다.
        // 응답이 스스로 범위를 밝히므로 그 사실을 픽스처로 고정한다 — 이 문자열이 사라지면
        // 전용관이 아닌 무언가를 조회하고 있다는 뜻이다.
        for (String kind : List.of("hit", "none")) {
            assertTrue(fixture("hyundai-home-shopping-" + kind + ".html")
                            .contains("현대홈쇼핑 온누리샵"),
                    kind + " 응답에 전용관 이름이 없다 — 조회 범위가 바뀌었을 수 있다");
        }
        assertEquals(Verdict.NONE, judgeFixture("hyundai-home-shopping", "none", "zzqqxyw12345").status());
        assertEquals(Verdict.LIKELY, judgeFixture("hyundai-home-shopping", "hit", "세트").status());
    }

    @Test
    void 짧은_JSON_없음응답을_판정하지_못한_것으로_뭉개지_않는다() {
        // 현대홈쇼핑의 '없음' 응답은 658바이트다. 본문 하한이 HTML 기준(500자)이면
        // 확실한 '없음'이 unknown 으로 뭉개진다 — 2026-09-02 이지웰(316자)에서 겪은 그 함정이다.
        ProbeTarget t = ProbeTargets.byId("hyundai-home-shopping").orElseThrow();
        assertTrue(t.isApi(), "GET 이라도 JSON 을 주는 몰은 API 로 선언돼야 한다");
    }

    @Test
    void XML_응답의_없음문구는_원문에서_잡는다() {
        // 공영쇼핑은 XML 이라 toText 가 태그를 걷으면 `<rsltYn>N</rsltYn>` 이 ` N ` 으로
        // 뭉개져 사라진다. 태그 제거는 HTML 산문에서 문구가 쪼개지는 것을 막는 규칙인데,
        // 구조화된 API 응답에서는 그 구조 자체가 신호라 정반대로 작용한다.
        String none = fixture("gongyoung-shopping-none.html");
        assertTrue(none.contains("<rsltYn>N</rsltYn>"), "전제 확인 — 원문에는 있어야 한다");
        assertFalse(ProbeJudge.toText(none).contains("<rsltYn>N</rsltYn>"),
                "전제 확인 — 텍스트에서는 사라진다");
        assertEquals(Verdict.NONE, judgeFixture("gongyoung-shopping", "none", "zzqqxyw12345").status());
        assertEquals(Verdict.LIKELY, judgeFixture("gongyoung-shopping", "hit", "김치").status());
    }

    @Test
    void 화면_HTML_몰은_원문_매칭을_쓰지_않는다() {
        // 원문 매칭을 화면 몰까지 허용하면 태그로 쪼개진 문구를 놓치던 옛 결함이 되살아난다.
        // API 가 아닌 몰은 이 경로를 타지 않아야 한다.
        for (ProbeTarget t : ProbeTargets.ALL) {
            if (t.isApi()) continue;
            for (String marker : t.noneMarkersPlain()) {
                assertFalse(marker.contains("<"),
                        t.platformId() + " — 화면 몰의 없음 문구에 태그가 들어 있다: " + marker);
            }
        }
    }

    @Test
    void 십일번가_상품명_앵커를_빼면_SEO_제목이_섞인다() {
        // `"title"` 만 쓰면 `로봇청소기 - 11번가 추천` 이 첫 샘플로 나간다(6-5절 제목 에코 함정).
        String hit = fixture("11st-onnuri-market-hit.html");
        assertTrue(hit.contains("\"title\":\"로봇청소기 - 11번가 추천\""),
                "전제 확인 — 응답에 SEO 제목이 들어 있다");
        List<String> names = ProbeJudge.extractTitles(
                ProbeTargets.byId("11st-onnuri-market").orElseThrow(), hit,
                ProbeQuery.of("로봇청소기"), 3);
        assertFalse(names.isEmpty());
        for (String n : names) {
            assertFalse(n.contains("11번가 추천"), "SEO 제목이 상품명 샘플로 나갔다: " + n);
        }
    }

    @Test
    void 십일번가는_에코_9회를_상품_신호로_세지_않는다() {
        // 없는 질의 4종에서 전부 정확히 9회였다(SEO 제목·설명·안내 문구).
        // noiseFloor 가 없으면 hits 9 가 임계를 넘겨 등급 B 문구가 있어도 unclear 로 빠진다.
        ProbeTarget t = ProbeTargets.byId("11st-onnuri-market").orElseThrow();
        assertEquals(9, t.noiseFloor());
        Verdict v = judgeFixture("11st-onnuri-market", "none", "zzqqxyw12345");
        assertEquals(Verdict.NONE, v.status(), "에코를 상품 신호로 세어 없음 확정을 놓쳤다");
        assertEquals(0, v.matchCount(), "noiseFloor 를 뺀 히트가 0이어야 한다");
    }
}
