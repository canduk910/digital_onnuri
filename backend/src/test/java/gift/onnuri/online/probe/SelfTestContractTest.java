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
        assertEquals(List.of("checkedAt", "probeEnabled", "total", "passed", "failed",
                        "skipped", "cases"),
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
        var r = new SelfTestReport("2026-08-31 03:10", true, 12, 10, 1, 1, List.of(c));
        var back = om.readValue(om.writeValueAsString(r), java.util.Map.class);
        assertEquals(List.of("checkedAt", "probeEnabled", "total", "passed", "failed",
                        "skipped", "cases"),
                back.keySet().stream().map(Object::toString).toList());
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
    void 나머지_다섯곳은_없는_질의를_확정할_수_있다() {
        for (ProbeTarget t : ProbeTargets.ALL) {
            if ("onnuri-chance".equals(t.platformId())) continue;
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
}
