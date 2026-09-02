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
        List<ProbeHit> items,
        /**
         * 전일 색인 층(ADR-18). **null 이 아니다** — 비면 platformCount 0·notice null·빈 목록.
         * 실시간 층과 독립이라 킬스위치가 꺼져 있어도, 캐시가 적중해도 새로 계산한다
         * (DB 읽기뿐이라 아웃바운드가 없고, 색인은 매일 바뀌어 캐시 TTL 과 수명이 다르다).
         */
        IndexLayer index
) {
    /** 캐시에서 꺼낸 결과에 **오늘의** 색인 층을 갈아 끼운다. 기존 필드는 그대로 둔다. */
    public OnlineSearchResult withIndex(IndexLayer idx) {
        return new OnlineSearchResult(query, checkedAt, totalPlatforms, probedCount,
                noneCount, likelyCount, unclearCount, unknownCount, notProbedCount,
                throttled, notice, items, idx == null ? IndexLayer.empty() : idx);
    }
}
