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
        List<SelfTestCase> cases,
        /**
         * 우리가 **실제로 두드리는** 호스트·경로 목록. 조회가 꺼져 있어도 채운다 —
         * 상수에서 파생되는 값이라 네트워크가 필요 없고, 배치가 도메인을 손으로 적지 않게 하는 것이
         * 이 필드의 목적이라 꺼졌다고 사라지면 뜻이 없다(2026-08-31 도메인 오기 사고).
         */
        List<ProbeEndpoint> probeEndpoints,
        /** robots 판정에 쓴 우리 제품 토큰. 나중에 누가 봐도 어떤 이름으로 판정했는지 알 수 있게. */
        String robotsUserAgent,
        /**
         * 몰별 robots 판정. `platformId` 로 probeEndpoints 와 이어 본다.
         * 조회가 꺼져 있으면 비운다 — 아무도 두드리지 않는데 허락을 물을 이유가 없다.
         */
        List<RobotsCheck> robots) {
}
