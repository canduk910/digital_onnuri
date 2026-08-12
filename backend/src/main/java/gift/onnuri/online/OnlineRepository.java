package gift.onnuri.online;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** online_platform 조회. 30여 행 규모라 JdbcTemplate 직조회(report 패턴). */
@Repository
public class OnlineRepository {

    private final JdbcTemplate jdbc;

    public OnlineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** removed 포함 전체. 정렬 ord ASC NULLS LAST, name(프론트가 status로 필터). */
    public List<OnlinePlatformView> findAll() {
        return jdbc.query(
                "SELECT id, post_no, kind, name, summary, note, url, region_limited, "
                        + "source_url, collected_on, status FROM online_platform "
                        + "ORDER BY ord ASC NULLS LAST, name",
                (rs, i) -> new OnlinePlatformView(
                        rs.getString("id"),
                        (Integer) rs.getObject("post_no"),
                        rs.getString("kind"),
                        rs.getString("name"),
                        rs.getString("summary"),
                        rs.getString("note"),
                        rs.getString("url"),
                        rs.getBoolean("region_limited"),
                        rs.getString("source_url"),
                        rs.getObject("collected_on") == null ? null
                                : rs.getObject("collected_on", java.time.LocalDate.class).toString(),
                        rs.getString("status")));
    }

    /** "기준" 스탬프 원천: active 항목의 min(collected_on). active가 없으면 null. */
    public String minActiveCollectedOn() {
        return jdbc.queryForObject(
                "SELECT to_char(min(collected_on), 'YYYY-MM-DD') FROM online_platform "
                        + "WHERE status = 'active'",
                String.class);
    }
}
