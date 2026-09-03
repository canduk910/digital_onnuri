package gift.onnuri.online.probe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * online_product_index 읽기 (ADR-18). 쓰기는 야간 배치(단계 F)가 몰 단위로 교체 적재한다 —
 * **앱은 읽기만 한다.** 앱이 쓰기 시작하면 "반쯤 걷힌 회차"를 앱이 만들어 낼 수 있고,
 * 배치의 50% 가드가 그것을 막지 못한다.
 *
 * OnlineRepository 와 같은 JdbcTemplate 직조회다(엔티티를 두지 않는다 — 스키마는 Flyway 소유).
 */
@Repository
public class OnlineProductIndexRepository {

    /**
     * 색인 행 하나.
     *
     * 2026-09-04: url 을 함께 읽는다. 종전에는 "배치의 중복 제거 키(PK)일 뿐 화면에 나가지 않는다"고
     * 보고 읽지 않았는데, 그 결과 화면이 **상품명을 근거로 내밀면서 몰 홈으로만 보내고** 있었다.
     * 색인 대상은 정의상 '검색이 안 되는 몰'이라 홈에서는 그 상품을 찾을 수 없다 —
     * 놀장은 시장을 먼저 골라야 하고 인어교주해적단은 검색 UI 자체가 없다.
     * 배치는 이미 상품에 닿는 주소를 넣어 두고 있다(예: /market/36#상품명).
     */
    public record Row(String platformId, String name, String url) {}

    /** 몰별 요약. collectedOn 은 그 몰 행들의 **가장 오래된** 수집일(신선도를 부풀리지 않는다). */
    public record Summary(String platformId, int rows, String collectedOn) {}

    /**
     * 한 요청이 읽어 갈 행 수 상한. 몰당 하루 100~150건 규모(ADR-18)라 실사용에선 닿지 않는다.
     * 색인이 예상 밖으로 커졌을 때 요청 하나가 메모리를 통째로 먹지 않게 하는 안전판이다.
     */
    private static final int MAX_ROWS = 5000;

    private final JdbcTemplate jdbc;

    public OnlineProductIndexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 몰별 색인 건수·수집일. 테이블이 비면 빈 목록. */
    public List<Summary> summarize(Collection<String> platformIds) {
        if (platformIds == null || platformIds.isEmpty()) return List.of();
        String sql = "SELECT platform_id, count(*) AS n, "
                + "to_char(min(collected_on), 'YYYY-MM-DD') AS d "
                + "FROM online_product_index WHERE platform_id IN (" + marks(platformIds.size()) + ") "
                + "GROUP BY platform_id";
        return jdbc.query(sql, (rs, i) -> new Summary(
                rs.getString("platform_id"), rs.getInt("n"), rs.getString("d")),
                platformIds.toArray());
    }

    /**
     * 검색어 낱말 중 **하나라도** 담은 상품명 행.
     *
     * 이 SQL 은 **선별이 아니라 예선**이다 — 무엇을 매치로 셀지는 IndexJudge 가 정한다.
     * 판정 규칙을 SQL 과 Java 두 곳에 두면 서로 갈라지고, 그게 이 저장소가 반복해서
     * 겪어 온 "에러 없이 다른 숫자"다. 여기서는 **버릴 것만** 버린다 —
     * 낱말이 하나도 없는 행은 IndexJudge 도 어차피 버리므로 결과가 달라지지 않는다.
     */
    public List<Row> findMatching(Collection<String> platformIds, List<String> tokens) {
        if (platformIds == null || platformIds.isEmpty()) return List.of();
        if (tokens == null || tokens.isEmpty()) return List.of();

        List<Object> args = new ArrayList<>(platformIds);
        StringBuilder like = new StringBuilder();
        for (String t : tokens) {
            if (like.length() > 0) like.append(" OR ");
            like.append("name ILIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(t) + "%");
        }
        String sql = "SELECT platform_id, name, url FROM online_product_index "
                + "WHERE platform_id IN (" + marks(platformIds.size()) + ") "
                + "AND (" + like + ") LIMIT " + MAX_ROWS;
        return jdbc.query(sql,
                (rs, i) -> new Row(rs.getString("platform_id"), rs.getString("name"), rs.getString("url")),
                args.toArray());
    }

    /**
     * LIKE 메타문자를 값으로 취급한다 — 검색어의 %·_ 가 와일드카드가 되면 엉뚱한 행이 걸린다.
     * 대소문자는 접지 않는다(ILIKE 가 이미 무시한다).
     */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String marks(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
