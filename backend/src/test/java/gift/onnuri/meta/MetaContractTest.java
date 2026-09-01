package gift.onnuri.meta;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /api/meta 계약. merchants.html이 API 모드에서 이 응답의 merchantsCollectedOn을 읽어
 * "○○ 수집" 스탬프를 표시한다. 키 이름이 바뀌면 스탬프가 조용히 하드코딩 폴백으로 되돌아간다.
 */
class MetaContractTest {

    private List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
    }

    @Test
    void MetaResult_는_수집일과_중단_상태를_노출한다() {
        assertEquals(List.of("merchantsCollectedOn", "merchantsStaleSince", "merchantsStaleReason"),
                components(MetaResult.class));
    }

    @Test
    void 직렬화_키가_계약과_일치한다() throws Exception {
        ObjectMapper om = new ObjectMapper();
        Map<?, ?> back = om.readValue(om.writeValueAsString(
                new MetaResult("2026-08-28", "2026-08-29", "공식 API 응답 변경")), Map.class);
        assertEquals(List.of("merchantsCollectedOn", "merchantsStaleSince", "merchantsStaleReason"),
                back.keySet().stream().map(Object::toString).toList());
        assertEquals("2026-08-28", back.get("merchantsCollectedOn"));
        assertEquals("2026-08-29", back.get("merchantsStaleSince"));
    }

    @Test
    void 값이_없으면_null_을_담는다() throws Exception {
        ObjectMapper om = new ObjectMapper();
        Map<?, ?> back = om.readValue(om.writeValueAsString(new MetaResult(null, null, null)), Map.class);
        assertTrue(back.containsKey("merchantsCollectedOn"));
        assertNull(back.get("merchantsCollectedOn"));
    }

    @Test
    void 갱신이_정상이면_중단_필드가_비어_있다() throws Exception {
        // 배치가 성공하면 stale 키를 지운다 — 남아 있으면 화면이 정상인데도 경고를 띄운다.
        ObjectMapper om = new ObjectMapper();
        Map<?, ?> back = om.readValue(
                om.writeValueAsString(new MetaResult("2026-09-01", null, null)), Map.class);
        assertNull(back.get("merchantsStaleSince"));
        assertNull(back.get("merchantsStaleReason"));
    }
}
