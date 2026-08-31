package gift.onnuri.online.probe;

import java.util.List;

/**
 * 실시간 조회 응답.
 *
 * 버킷(found/notFound/…)으로 쪼개지 않고 items + status 로 둔다 — 리스트를 나누면
 * 카운트와 리스트가 어긋날 여지가 생긴다.
 *
 * notice 문자열까지 서버가 만든다. 프론트가 items.filter() 로 헤드라인을 재계산하면
 * 계약이 바뀔 때 조용히 틀린 숫자가 나온다(2026-08-27 normKind 결함과 같은 유형).
 */
public record OnlineSearchResult(
        String query,
        String checkedAt,
        int totalPlatforms,     // 22 — 이용자가 보는 온라인 사용처 전체
        int probedCount,        // 실제로 조회한 곳
        int noneCount,
        int likelyCount,        // mallWide 는 여기 넣지 않는다(온누리 범위 밖이 섞이므로)
        int unclearCount,
        int unknownCount,
        int notProbedCount,
        boolean throttled,
        String notice,
        List<ProbeHit> items
) {}
