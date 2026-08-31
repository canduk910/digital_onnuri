package gift.onnuri.online.probe;

/**
 * 카나리아 한 건 — 몰 하나에 질의 하나를 던진 결과 (ADR-17 6단계).
 *
 * 배치가 이 값을 그대로 리포트에 옮긴다. 자동으로 무언가를 끄지 않는다 —
 * 조용한 축소이고, ADR-16 이 채록 자동 반영을 기각한 논리와 같다.
 *
 * kind      absent(그 몰에 없을 질의) | present(확실히 있는 일반어)
 * expected  기대 상태. 빈 문자열이면 **기대치를 세울 수 없는 몰**이다
 *           (등급 C + 질의 에코형이라 '없다'를 확정할 수단이 없다 — onnuri-chance).
 *           기대치가 없는 건을 통과로도 실패로도 세지 않는다.
 * echoed / echoDeclared
 *           없는 질의를 응답이 되뿌리는지 실측한 값과 ProbeTarget 선언값.
 *           갈라지면 토큰 0 판정의 전제가 깨진 것이다.
 * bodyLength 배치가 전날 리포트와 비교해 ±50% 변화를 잡는다(비교는 배치가 한다 —
 *           앱이 어제 값을 들고 있으면 그것 자체가 또 하나의 상태가 된다).
 */
public record SelfTestCase(
        String platformId,
        String query,
        String kind,
        String expected,
        String actual,
        boolean ok,
        String reason,
        Integer matchCount,
        int sampleCount,
        int bodyLength,
        boolean echoed,
        boolean echoDeclared,
        String note) {

    public static final String ABSENT  = "absent";
    public static final String PRESENT = "present";
}
