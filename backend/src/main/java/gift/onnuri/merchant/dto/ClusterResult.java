package gift.onnuri.merchant.dto;

import java.util.List;

/**
 * 서버 사이드 클러스터링 응답(2026-08-12) — 상한(max-markers) 초과 뷰에서
 * 개별 핀 대신 격자 집계(셀 중심좌표+개수)를 내려준다. 컴포넌트명은 ApiContractTest가 고정.
 */
public record ClusterResult(List<Cell> items, long total, double grid) {

    public record Cell(double lat, double lng, long count) {
    }
}
