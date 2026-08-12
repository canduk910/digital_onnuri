package gift.onnuri.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * rag_chunk 벡터 검색 (pgvector cosine). 스키마: V2__rag_chunk.sql.
 * 적재는 _workspace/dev_scripts/build_rag_corpus.py가 수행한다.
 */
@Repository
public class RagRepository {

    public record Hit(String source, String section, String content, String url, String collectedOn) {
    }

    private final JdbcTemplate jdbc;

    public RagRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 코사인 최근접 top-k. sources가 비어있지 않으면 해당 출처로 한정. */
    public List<Hit> search(String embedding, int k, List<String> sources) {
        String where = (sources == null || sources.isEmpty())
                ? "" : "WHERE source = ANY (?) ";
        String sql = "SELECT source, section, content, url, collected_on "
                + "FROM rag_chunk " + where
                + "ORDER BY embedding <=> ?::vector LIMIT ?";
        Object[] args = (sources == null || sources.isEmpty())
                ? new Object[]{embedding, k}
                : new Object[]{sources.toArray(new String[0]), embedding, k};
        return jdbc.query(sql, (rs, i) -> new Hit(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5)), args);
    }

    /** 기준일 스탬프: 코퍼스 최소 수집일 (프로젝트 원칙 min(collected_on)). */
    public String minCollectedOn() {
        try {
            return jdbc.queryForObject(
                    "SELECT min(collected_on) FROM rag_chunk WHERE collected_on IS NOT NULL",
                    String.class);
        } catch (Exception e) {
            return null;   // 테이블 미적재 시에도 챗은 동작(도구 결과 없음으로 답변)
        }
    }
}
