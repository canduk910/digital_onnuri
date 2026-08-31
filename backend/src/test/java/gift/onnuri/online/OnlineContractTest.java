package gift.onnuri.online;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 온라인 플랫폼 계약(2026-08-12 이관) — online.html·terms.html이 JSON 키를 그대로 소비하고,
 * 이 키는 프론트 JSON 폴백(data/online_platforms.json)과 매핑된다. 이름이 바뀌면 목록이
 * 에러 없이 조용히 깨진다. record 컴포넌트명 + 직렬화 키를 함께 고정한다.
 */
class OnlineContractTest {

    private List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(r -> r.getName()).toList();
    }

    @Test
    void OnlinePlatformView_는_프론트가_소비하는_필드를_노출한다() {
        assertEquals(List.of("id", "no", "kind", "name", "summary", "note", "url",
                        "regionLimited", "sourceUrl", "collectedOn", "status", "searchUrlTemplate"),
                components(OnlinePlatformView.class));
    }

    @Test
    void OnlineResult_는_meta_와_items_로_감싼다() {
        assertEquals(List.of("meta", "items"), components(OnlineResult.class));
    }

    @Test
    void 직렬화_키가_계약과_일치한다() throws Exception {
        ObjectMapper om = new ObjectMapper();
        OnlinePlatformView v = new OnlinePlatformView(
                "onnuri-hotdeal", 12, "shopping", "온누리핫딜",
                "요약", "메모", "https://onnurideal.com",
                false, "https://www.onnuri.gift/visit/market", "2026-08-06", "active",
                "https://onnurideal.com/search?q={q}");
        Map<?, ?> item = om.readValue(om.writeValueAsString(v), Map.class);
        assertEquals(List.of("id", "no", "kind", "name", "summary", "note", "url",
                        "regionLimited", "sourceUrl", "collectedOn", "status", "searchUrlTemplate"),
                item.keySet().stream().map(Object::toString).toList());

        Map<String, String> meta = new HashMap<>();
        meta.put("collected_on", "2026-08-06");
        meta.put("source", "온누리상품권 공식 홈페이지 〉 온라인 전통시장관");
        meta.put("sourceUrl", "https://www.onnuri.gift/visit/market");
        Map<?, ?> back = om.readValue(
                om.writeValueAsString(new OnlineResult(meta, List.of(v))), Map.class);
        Map<?, ?> metaBack = (Map<?, ?>) back.get("meta");
        // 프론트 JSON 폴백은 meta.collected_on(snake)을 읽는다 — 키 이름 고정.
        assertTrue(metaBack.containsKey("collected_on"), "meta.collected_on 키 누락");
        assertTrue(metaBack.containsKey("source"));
        assertTrue(metaBack.containsKey("sourceUrl"));
    }
}
