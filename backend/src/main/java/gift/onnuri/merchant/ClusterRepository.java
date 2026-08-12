package gift.onnuri.merchant;

import gift.onnuri.merchant.dto.ClusterResult;
import gift.onnuri.merchant.dto.SearchQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 격자 집계 클러스터링(2026-08-12). floor(좌표/격자)로 셀을 나눠 개수·중심을 집계한다.
 *
 * 주의(경계면): WHERE 절 규칙은 MerchantSpecs와 1:1로 같아야 한다 — 다르면 리스트 총계와
 * 클러스터 합계가 어긋나는 "조용히 틀린 숫자"가 된다. 규칙 변경 시 두 곳을 함께 고치고,
 * 검증은 sum(cluster.count) == /merchants total 대조로 한다(dev-testing).
 */
@Repository
public class ClusterRepository {

    private final JdbcTemplate jdbc;

    public ClusterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ClusterResult clusters(SearchQuery qy, double grid) {
        double g = Math.min(Math.max(grid, 0.001), 2.0);
        StringBuilder where = new StringBuilder("WHERE lat IS NOT NULL AND lng IS NOT NULL ");
        List<Object> args = new ArrayList<>();

        if (has(qy.region())) { where.append("AND region = ? "); args.add(qy.region()); }
        // 뷰포트 자동 지도(2026-08-12): bounds = 필터에 추가되는 AND (MerchantSpecs와 1:1)
        if (qy.hasBounds()) {
            where.append("AND lat BETWEEN ? AND ? AND lng BETWEEN ? AND ? ");
            args.add(qy.minLat()); args.add(qy.maxLat()); args.add(qy.minLng()); args.add(qy.maxLng());
        }
        if (has(qy.si())) { where.append("AND si = ? "); args.add(qy.si()); }
        if (has(qy.gu())) { where.append("AND gu = ? "); args.add(qy.gu()); }
        if (has(qy.dong())) {
            if (SearchQuery.UNKNOWN_DONG.equals(qy.dong())) where.append("AND dong IS NULL ");
            else { where.append("AND dong = ? "); args.add(qy.dong()); }
        }
        // 다중 필터(2026-08-12): FilterCsv 규칙으로 IN — MerchantSpecs와 1:1 유지
        appendIn(where, args, "cat", gift.onnuri.merchant.dto.FilterCsv.parse(qy.cat()));
        appendIn(where, args, "brand", gift.onnuri.merchant.dto.FilterCsv.parse(qy.brand()));
        appendIn(where, args, "market_type", gift.onnuri.merchant.dto.FilterCsv.parse(qy.mtype()));
        if (Boolean.TRUE.equals(qy.digital())) where.append("AND (card = 'Y' OR qr = 'Y') ");
        if (has(qy.q())) {
            where.append("AND (lower(name) LIKE ? OR lower(COALESCE(addr,'')) LIKE ? OR lower(COALESCE(market,'')) LIKE ?) ");
            String like = "%" + qy.q().trim().toLowerCase() + "%";
            args.add(like); args.add(like); args.add(like);
        }

        // 셀 중심 = floor(좌표/격자)*격자 + 격자/2, 표시 좌표는 셀 내 평균이 더 자연스러움 → avg 사용
        List<Object> full = new ArrayList<>(args);
        String sql = "SELECT avg(lat) AS clat, avg(lng) AS clng, count(*) AS cnt FROM merchant "
                + where + "GROUP BY floor(lat / " + g + "), floor(lng / " + g + ")";
        List<ClusterResult.Cell> cells = jdbc.query(sql,
                (rs, i) -> new ClusterResult.Cell(rs.getDouble(1), rs.getDouble(2), rs.getLong(3)),
                full.toArray());
        long total = cells.stream().mapToLong(ClusterResult.Cell::count).sum();
        return new ClusterResult(cells, total, g);
    }

    private static void appendIn(StringBuilder where, List<Object> args, String col, List<String> vals) {
        if (vals == null) return;
        where.append("AND ").append(col).append(" IN (")
             .append(String.join(",", java.util.Collections.nCopies(vals.size(), "?")))
             .append(") ");
        args.addAll(vals);
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank() && !"전체".equals(s);
    }
}
