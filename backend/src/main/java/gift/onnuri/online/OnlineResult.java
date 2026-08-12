package gift.onnuri.online;

import java.util.List;
import java.util.Map;

/**
 * GET/POST /api/online/platforms 응답. 프론트 JSON 폴백(data/online_platforms.json)과 shape 대칭.
 *
 * meta 키는 JSON 파일과 동일하게 collected_on(snake) · source · sourceUrl.
 *   - collected_on = active 항목의 min(collected_on)("기준" 스탬프 — 확인 안 한 항목 날짜를 올리지 않는다).
 *     active가 없으면 null.
 *   - source/sourceUrl = 상수(온라인 전통시장관).
 * items 는 removed 포함 전체(프론트가 status로 필터). 정렬 ord ASC NULLS LAST, name.
 */
public record OnlineResult(Map<String, String> meta, List<OnlinePlatformView> items) {
}
