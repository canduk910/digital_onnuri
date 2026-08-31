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
        assertTrue(ProbeQuery.of(SelfTestService.ABSENT_QUERY).searchable(),
                "없음 질의가 조회 불가하면 카나리아가 절반만 돈다");
    }
}
