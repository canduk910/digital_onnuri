package gift.onnuri.online.probe;

import java.util.List;

/**
 * 카나리아 리포트 (ADR-17 6단계). 배치(nightly_update.py 단계 E)가 curl 한 번으로 받아 남긴다.
 *
 * failed > 0 이 곧 "규칙이 깨졌다"는 신호다. 세 갈래로 조용히 깨진다:
 *   ① 없음-문구 변경   → 없는 것을 likely 로 (absent 실패)
 *   ② 문구 오탐 확대   → 있는 것을 none 으로 (present 실패, 가장 위험)
 *   ③ titlePattern 노후 → sampleCount 0 (present 실패)
 */
public record SelfTestReport(
        String checkedAt,
        boolean probeEnabled,
        int total,
        int passed,
        int failed,
        int skipped,
        List<SelfTestCase> cases) {
}
