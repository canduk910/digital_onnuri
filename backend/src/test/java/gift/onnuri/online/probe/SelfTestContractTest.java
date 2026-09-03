package gift.onnuri.online.probe;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 카나리아 계약 (ADR-17 6단계).
 *
 * 이 리포트는 야간 배치가 읽고 사람이 본다. 키가 바뀌면 배치는 에러 없이
 * "실패 0건"을 기록하고, 규칙이 깨진 채로 몇 주가 지나간다.
 */
class SelfTestContractTest {

    private List<String> components(Class<?> r) {
        return Arrays.stream(r.getRecordComponents()).map(c -> c.getName()).toList();
    }

    @Test
    void 리포트_필드가_계약과_일치한다() {
        // 새 필드는 맨 뒤에 붙인다 — 앞 순서가 바뀌면 배치가 조용히 어긋난다.
        assertEquals(List.of("checkedAt", "probeEnabled", "total", "passed", "failed",
                        "skipped", "cases", "probeEndpoints", "robotsUserAgent", "robots"),
                components(SelfTestReport.class));
        assertEquals(List.of("platformId", "query", "kind", "expected", "actual", "ok",
                        "reason", "matchCount", "sampleCount", "bodyLength",
                        "echoed", "echoDeclared", "note"),
                components(SelfTestCase.class));
    }

    @Test
    void 직렬화_키가_계약과_일치한다() throws Exception {
        ObjectMapper om = new ObjectMapper();
        var c = new SelfTestCase("onnuri-hotdeal", "김치", SelfTestCase.PRESENT,
                Verdict.LIKELY, Verdict.LIKELY, true, null, 20, 3, 333760, true, true, "");
        var r = new SelfTestReport("2026-08-31 03:10", true, 12, 10, 1, 1, List.of(c),
                List.of(new ProbeEndpoint("onnuri-hotdeal", "onnurideal.com", "/search")),
                ProbeFetcher.ROBOTS_TOKEN,
                List.of(new RobotsCheck("onnuri-hotdeal", true, null, "*", null)));
        var back = om.readValue(om.writeValueAsString(r), java.util.Map.class);
        // 새 필드는 **맨 뒤**에 붙인다 — 앞 순서가 바뀌면 배치가 조용히 어긋난다.
        assertEquals(List.of("checkedAt", "probeEnabled", "total", "passed", "failed",
                        "skipped", "cases", "probeEndpoints", "robotsUserAgent", "robots"),
                back.keySet().stream().map(Object::toString).toList());
        assertEquals(List.of("platformId", "host", "path"),
                java.util.Arrays.stream(ProbeEndpoint.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(List.of("platformId", "allowed", "rule", "group", "error"),
                java.util.Arrays.stream(RobotsCheck.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
    }

    @Test
    void 없음을_확정할_수단이_없는_몰에는_기대치를_세우지_않는다() {
        // onnuri-chance 는 등급 C + 질의 에코형이라 unclear 가 정답이다.
        // 여기에 none 을 기대하면 카나리아가 매일 거짓 실패를 낸다(1단계에서 겪은 오인).
        ProbeTarget chance = ProbeTargets.byId("onnuri-chance").orElseThrow();
        assertFalse(SelfTestService.canDecideAbsent(chance),
                "onnuri-chance 에 확정 수단이 생겼다면 기대치를 다시 세워야 한다");
    }

    @Test
    void 확정_수단이_없는_몰은_정책으로_정한_두_곳뿐이다() {
        // 나머지는 전부 없음-문구나 토큰 0 판정으로 '없다'를 확정할 수 있어야 한다 —
        // 확정 수단이 없으면 그 몰의 absent 카나리아는 기대치를 세울 수 없고, 그만큼 눈이 먼다.
        //   onnuri-chance          등급 C + 에코형이라 확정 수단이 원래 없다(ADR-17 1단계)
        //   lotte-on-sangsaeng-store 130바이트 '없음'이 필터 깨진 응답과 바이트까지 같다(ADR-19)
        java.util.List<String> policy =
                java.util.List.of("onnuri-chance", "lotte-on-sangsaeng-store");
        for (ProbeTarget t : ProbeTargets.ALL) {
            if (policy.contains(t.platformId())) {
                assertFalse(SelfTestService.canDecideAbsent(t),
                        t.platformId() + " — 정책 선언이 풀렸다");
                continue;
            }
            assertTrue(SelfTestService.canDecideAbsent(t),
                    t.platformId() + " 가 '없다'를 확정할 수단을 잃었다 — 카나리아가 의미를 잃는다");
        }
    }

    @Test
    void 모든_대상에_카나리아_질의가_있고_검색_가능한_길이다() {
        // 1자 질의("쌀")를 두면 MIN_LEN=2 에 걸려 카나리아가 매번 400 을 받는다(2026-08-31 적발).
        for (ProbeTarget t : ProbeTargets.ALL) {
            String q = t.canaryPresentQuery();
            assertNotNull(q, t.platformId() + " 에 카나리아 질의가 없다");
            assertTrue(ProbeQuery.of(q).searchable(),
                    t.platformId() + " 의 카나리아 질의가 조회 불가: " + q);
        }
        assertTrue(ProbeQuery.of(SelfTestService.absentQuery()).searchable(),
                "없음 질의가 조회 불가하면 카나리아가 절반만 돈다");
    }

    @Test
    void 카나리아의_에코_실측은_판정과_같은_기준을_쓴다() throws Exception {
        // echoesQuery 가 뜻하는 것은 "히트 0 판정을 쓸 수 없는가"이고, 그 히트는
        // ProbeJudge 가 **stripEcho** 위에서 센다. 카나리아가 toText 로 재면
        // <title>·<meta> 에만 남는 에코까지 잡혀 **선언이 옳은 몰을 틀렸다고 신고한다.**
        // 2026-09-03 라이브에서 굿데이·인더마켓이 그렇게 걸렸고, 그 신고를 믿었다면
        // 쓸 수 있는 '없다' 확정 수단을 근거 없이 버릴 뻔했다.
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/gift/onnuri/online/probe/SelfTestService.java"));
        assertTrue(src.contains("boolean echoed = ProbeJudge.stripEcho(html)"),
                "카나리아가 판정과 다른 기준으로 에코를 재고 있다");
        assertFalse(src.contains("boolean echoed = ProbeJudge.toText(html)"),
                "toText 기준으로 되돌아갔다 — 거짓 경보가 다시 난다");
    }

    @Test
    void 제목_메타에만_남는_에코는_에코로_세지_않는다() throws Exception {
        // 굿데이·인더마켓 실응답(2026-09-03). 없는 질의가 원문에 22~23회 있지만
        // 전부 <title>·<meta> 라 판정이 보는 본문에는 하나도 없다.
        for (String id : java.util.List.of("onnuri-goodday", "inthemarket-onnuri")) {
            String html = java.nio.file.Files.readString(
                    java.nio.file.Path.of("src/test/resources/probe/" + id + "-none.html"));
            assertTrue(ProbeJudge.toText(html).contains("zzqqxyw12345"),
                    id + " 전제 확인 — 제목·메타에는 질의어가 있다");
            assertFalse(ProbeJudge.stripEcho(html).contains("zzqqxyw12345"),
                    id + " — 판정이 보는 본문에 질의어가 남았다. echoesQuery 선언을 다시 봐야 한다");
            assertFalse(ProbeTargets.byId(id).orElseThrow().echoesQuery(),
                    id + " — 본문에 에코가 없는데 echoesQuery 를 true 로 선언했다");
        }
    }

    @Test
    void 없음을_확정할_수단이_없는_몰은_에코를_대조하지_않는다() {
        // onnuri-chance 의 echoesQuery=true 는 사실 주장이 아니라 **정책 선언**이다 —
        // 등급 C 라 없음-문구가 없고, 토큰 0 판정까지 열면 근거 없이 '없다'를 말하게 된다.
        // 실측이 false 로 나와도 선언을 바꿀 일이 아니라서 매일 뜨는 note 는 소음일 뿐이고,
        // note 가 소음이 되면 사람이 note 를 통째로 무시한다.
        ProbeTarget chance = ProbeTargets.byId("onnuri-chance").orElseThrow();
        assertTrue(chance.noneMarkersBound().isEmpty() && chance.noneMarkersPlain().isEmpty(),
                "전제 확인 — 등급 C 여야 한다");
        assertTrue(chance.echoesQuery(), "정책 선언이 유지돼야 한다");
        assertFalse(SelfTestService.canDecideAbsent(chance),
                "확정 수단이 없으므로 에코 대조 대상에서 빠진다");
    }

    @Test
    void 등급C여도_선언이_없다의_근거인_몰은_계속_대조한다() {
        // onnuri-market 은 등급 C 지만 echoesQuery=false 다 — **그 선언이 곧 '없다'의 근거**라
        // 사실이어야 한다. 등급만 보고 대조를 끄면 이 몰의 안전망이 조용히 사라진다.
        ProbeTarget market = ProbeTargets.byId("onnuri-market").orElseThrow();
        assertTrue(market.noneMarkersBound().isEmpty() && market.noneMarkersPlain().isEmpty());
        assertFalse(market.echoesQuery());
        assertTrue(SelfTestService.canDecideAbsent(market), "대조 대상에서 빠지면 안 된다");
    }

    @Test
    void 카나리아_에코_대조가_선언과_어긋나는_몰이_없다() throws Exception {
        // 픽스처가 있는 조회 대상 전부에서 선언과 실측이 일치해야 한다.
        // 어긋난 채로 두면 매일 note 가 뜨고, 그 소음이 진짜 신호를 덮는다.
        for (ProbeTarget t : ProbeTargets.ALL) {
            if (!SelfTestService.canDecideAbsent(t)) continue;
            java.nio.file.Path f = java.nio.file.Path.of(
                    "src/test/resources/probe/" + t.platformId() + "-none.html");
            if (!java.nio.file.Files.exists(f)) continue;
            boolean echoed = ProbeJudge.stripEcho(java.nio.file.Files.readString(f))
                    .contains("zzqqxyw12345");
            assertEquals(t.echoesQuery(), echoed,
                    t.platformId() + " — echoesQuery 선언과 실측이 다르다(카나리아가 매일 신고한다)");
        }
    }

    @Test
    void 없는말_질의는_회차마다_달라진다() {
        // 같은 말을 매일 보내면 **상대 몰의 인기 검색어에 우리 질의가 쌓인다** —
        // 2026-09-03 실측에서 온누리굿데이 인기 검색어 2위가 우리 카나리아 질의였다.
        // 그 몰 이용자 화면에 우리가 만든 낱말이 보이는 것이라 그 자체로 폐를 끼치고,
        // 그 블록은 stripEcho 가 걷지 않는 본문이라 판정 히트로도 잡힌다.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) seen.add(SelfTestService.absentQuery());
        assertTrue(seen.size() > 190, "질의가 충분히 흩어지지 않는다: " + seen.size() + "/200");
        for (String q : seen) {
            assertTrue(q.matches("zq[a-z]{8}"), "형태가 다르다: " + q);
            assertTrue(ProbeQuery.of(q).searchable(), "조회할 수 없는 질의다: " + q);
        }
    }

    @Test
    void 없는말_질의에_숫자를_넣지_않는다() {
        // 숫자가 들어가면 상품 코드·수량(`1kg`)과 우연히 겹칠 수 있다.
        for (int i = 0; i < 50; i++) {
            assertFalse(SelfTestService.absentQuery().matches(".*\\d.*"),
                    "숫자가 섞였다");
        }
    }

    // ── 조회 호스트·경로와 robots 판정 (ADR-19 후속) ────────────────────

    @Test
    void 조회_대상_전부가_엔드포인트_목록에_있다() {
        // 배치가 도메인을 손으로 적지 않게 하는 것이 이 목록의 목적이다 —
        // 2026-08-31 에 굿데이를 엉뚱한 도메인으로 적었는데 **그 도메인이 마침
        // Disallow:/ 라 기대치와 맞아떨어져 통과**했다. 빠진 몰이 있으면 그 사고가 되살아난다.
        var eps = SelfTestService.endpoints();
        assertEquals(ProbeTargets.ALL.size(), eps.size());
        for (var e : eps) {
            assertNotNull(e.host(), e.platformId() + " 호스트를 못 뽑았다");
            assertFalse(e.host().isBlank());
            assertTrue(e.path().startsWith("/"), e.platformId() + " 경로가 이상하다: " + e.path());
        }
        assertEquals(ProbeTargets.ids().stream().sorted().toList(),
                eps.stream().map(ProbeEndpoint::platformId).sorted().toList());
    }

    @Test
    void 엔드포인트에_질의어가_섞이지_않는다() {
        // 리포트는 배치 로그에 남는다. 템플릿의 {q} 자리가 그대로 실리면 안 된다.
        // 쿼리 자체는 붙인다 — robots 매칭이 경로+쿼리를 보기 때문이다. 다만 값은 고정 토큰이다.
        for (var e : SelfTestService.endpoints()) {
            assertFalse(e.path().contains("{q}"), e.platformId() + " 경로에 질의 자리가 남았다");
            assertFalse(e.path().contains("{qq}"), e.platformId() + " 경로에 질의 자리가 남았다");
            assertFalse(e.host().contains("{"), e.platformId() + " 호스트가 이상하다");
        }
    }

    @Test
    void 경로에_쿼리를_붙여야_robots_판정이_맞는다() {
        // 굿데이·인더마켓은 조회 주소의 경로가 `/` 뿐이고 검색 조건이 전부 쿼리에 있다.
        // 경로만 보면 그 몰들의 `Allow: /$`(루트만 연다)에 걸려 **허용으로 잘못 읽힌다** —
        // 2026-09-03 실측에서 실제로 그렇게 나왔고, 쿼리를 붙이자 `Disallow: /` 로 뒤집혔다.
        var byId = new java.util.HashMap<String, String>();
        SelfTestService.endpoints().forEach(e -> byId.put(e.platformId(), e.path()));
        String goodday = byId.get("onnuri-goodday");
        assertTrue(goodday.startsWith("/?"), "쿼리가 빠졌다: " + goodday);

        String robots = "User-agent: *\nDisallow: /\nAllow: /$\n";
        var rules = RobotsRules.parse(robots, ProbeFetcher.ROBOTS_TOKEN);
        assertTrue(rules.decide("/").allowed(), "전제 확인 — 루트만은 열려 있다");
        assertFalse(rules.decide(goodday).allowed(),
                "우리가 두드리는 주소는 루트가 아니다 — 허용으로 읽으면 사실과 다르다");
    }

    @Test
    void 조회_호스트는_이용자_링크_호스트와_다를_수_있다() {
        // **이 사실이 이번 수정의 이유다.** 감시가 이용자 링크 호스트를 보고 있었는데,
        // 우리가 두드리는 곳은 다른 호스트다. 11번가가 그 실례다.
        var byId = new java.util.HashMap<String, String>();
        SelfTestService.endpoints().forEach(e -> byId.put(e.platformId(), e.host()));
        assertEquals("apis.11st.co.kr", byId.get("11st-onnuri-market"),
                "11번가 조회는 apis 호스트로 나간다 — 이용자 링크(search.11st.co.kr)와 다르다");
        assertEquals("www.lotteon.com", byId.get("lotte-on-sangsaeng-store"),
                "롯데ON 조회는 몰 본체로 나간다");
        assertEquals("api.samaint.co.kr", byId.get("onnuri-5iljang"),
                "온누리5일장 조회는 본몰이 아닌 API 호스트다");
    }

    @Test
    void robots_판정에_쓰는_이름을_리포트에_남긴다() {
        // 나중에 누가 봐도 "어떤 이름으로 판정했는지"를 알 수 있어야 한다.
        assertEquals("onnuri-guide", ProbeFetcher.ROBOTS_TOKEN);
        assertTrue(ProbeFetcher.ROBOTS_TOKEN.length() > 3, "토큰이 너무 짧으면 아무 그룹에나 걸린다");
    }
}
