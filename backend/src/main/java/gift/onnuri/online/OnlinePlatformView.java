package gift.onnuri.online;

/**
 * 온라인 사용처 플랫폼 목록 행 — online.html·terms.html이 JSON 키를 그대로 소비한다.
 * 컴포넌트명 == 직렬화 키(camelCase). 이름이 바뀌면 목록이 조용히 깨진다(OnlineContractTest 고정).
 *
 * data/online_platforms.json의 items와 의미상 1:1:
 *   no = post_no(공식 API postNo), regionLimited = region_limited(큐레이션),
 *   sourceUrl = source_url, collectedOn = collected_on, status = active/removed.
 */
public record OnlinePlatformView(
        String id, Integer no, String kind, String name,
        String summary, String note, String url,
        boolean regionLimited, String sourceUrl, String collectedOn, String status) {
}
