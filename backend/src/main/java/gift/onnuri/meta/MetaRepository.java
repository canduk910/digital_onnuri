package gift.onnuri.meta;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** app_meta 키-값 조회. 값이 없으면 null. */
@Repository
public class MetaRepository {

    private final JdbcTemplate jdbc;

    public MetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String get(String key) {
        List<String> rows = jdbc.queryForList("SELECT v FROM app_meta WHERE k = ?", String.class, key);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
