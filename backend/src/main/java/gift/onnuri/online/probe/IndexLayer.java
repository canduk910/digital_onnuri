package gift.onnuri.online.probe;

import java.util.List;

/**
 * 전일 색인 층 (ADR-18). 실시간 조회 응답 끝에 덧붙는 **별도 층**이다.
 *
 * notice 는 서버가 만든다 — 실시간 층과 같은 이유다. 프론트가 items 를 세어
 * 헤드라인을 다시 만들면 계약이 바뀔 때 조용히 틀린 숫자가 나온다.
 *
 * 비어 있어도 null 이 아니다(platformCount 0 · notice null · items 빈 목록) —
 * null 층을 주면 프론트가 매번 방어 코드를 써야 하고, 한 번 빠뜨리면 화면이 깨진다.
 */
public record IndexLayer(
        String asOf,            // 포함 몰의 min(collectedOn). 비면 null
        int platformCount,      // 색인을 갖고 있는 몰 수(실시간 조회 대상 제외)
        int foundCount,         // 그중 검색어 전 낱말을 담은 상품명이 있는 몰 수
        String notice,
        List<IndexHit> items
) {
    public static IndexLayer empty() {
        return new IndexLayer(null, 0, 0, null, List.of());
    }
}
