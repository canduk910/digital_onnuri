package gift.onnuri.online.probe;

/**
 * 몰 하나의 robots 판정 (ADR-19 후속).
 *
 * "전면 차단인가"가 아니라 **"우리가 두드리는 그 경로가 아직 허용되는가"** 에 답한다.
 * 앞선 감시는 `Disallow: /` 한 줄만 봐서, 경로별로 여는 robots 를 전면 차단으로 오독했다.
 *
 * 이 값으로 조회를 **자동 비활성화하지 않는다**(ADR-17 이 기각한 '조용한 축소').
 * 리포트와 로그까지가 이 기능의 범위이고, 끄고 켜는 것은 사람이 한다.
 */
public record RobotsCheck(
        String platformId,
        boolean allowed,
        String rule,     // 근거가 된 규칙 원문. null 이면 걸린 규칙이 없다(= 기본 허용)
        String group,    // 판정에 쓴 User-agent 그룹(`*` 또는 우리를 지목한 이름)
        String error     // robots 를 못 읽었을 때의 사유. null 이면 정상
) {}
