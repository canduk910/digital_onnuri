package gift.onnuri.online.probe;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 규칙 사전 자체의 계약. 코드 상수와 데이터(플랫폼 목록)가 어긋나면 조용히 엉뚱한 몰을 친다.
 */
class ProbeTargetsTest {

    @Test
    void 조회대상이_모두_실측일과_robots_검토일을_가진다() {
        assertEquals(11, ProbeTargets.ALL.size());  // 2026-09-02 온누리쇼핑·지니어스몰 추가
        for (ProbeTarget t : ProbeTargets.ALL) {
            assertNotNull(t.measuredOn(), t.platformId() + " measuredOn 없음");
            assertNotNull(t.robotsCheckedOn(), t.platformId() + " robotsCheckedOn 없음 — "
                    + "robots.txt 를 확인하지 않은 몰을 대상에 넣을 수 없다");
            // 질의가 들어갈 자리는 URL 이거나(대부분) form body 다(온누리5일장).
            // {qq} 는 두 번 인코딩하는 자리로, {q} 를 포함하지 않는다 — 따로 인정한다.
            String where = t.searchUrlTemplate() + (t.formBody() == null ? "" : t.formBody());
            assertTrue(where.contains("{q}") || where.contains("{qq}"),
                    t.platformId() + " — 질의가 들어갈 자리({q}/{qq})가 URL 에도 form body 에도 없다");
            assertNotNull(t.canaryPresentQuery(), t.platformId() + " 카나리아 질의 없음");
            // 카나리아 질의가 최소 길이에 못 미치면 셀프테스트가 매번 400 을 받는다.
            // 실제로 "쌀"(1자)로 두었다가 2026-08-31 라이브 확인에서 잡았다.
            assertTrue(ProbeQuery.of(t.canaryPresentQuery()).searchable(),
                    t.platformId() + " 카나리아 질의를 조회할 수 없다: " + t.canaryPresentQuery());
        }
    }

    @Test
    void robots가_차단한_몰은_대상에_없다() {
        // 온누리굿데이·인더마켓은 Disallow: / + Allow: /$ — 기술적으로는 되지만 조회하지 않는다.
        assertFalse(ProbeTargets.ids().contains("onnuri-goodday"));
        assertFalse(ProbeTargets.ids().contains("inthemarket-onnuri"));
    }

    @Test
    void 대상_id가_모두_플랫폼_목록에_있다() throws Exception {
        Path p = Path.of("../data/online_platforms.json");
        assertTrue(Files.exists(p), "플랫폼 목록을 찾지 못했다: " + p.toAbsolutePath()
                + " — 파일이 없다고 skip 하면 경계면 검증이 사라진다");
        String json = Files.readString(p);
        for (String id : ProbeTargets.ids()) {
            assertTrue(json.contains("\"" + id + "\""),
                    id + " 가 online_platforms.json 에 없다 — 없는 몰을 조회하려 하고 있다");
        }
    }

    @Test
    void 조회하는_몰은_이용자가_열_링크도_함께_가진다() throws Exception {
        // 코드(ProbeTargets)는 자동 조회에, 데이터(online_platforms.json)는 이용자 링크에 쓰인다.
        //
        // 2026-09-02 이전에는 둘이 **같아야** 한다고 봤다. 그런데 그날 추가한 두 곳은 몰의
        // 내부 검색 API(JSON)를 부른다 — 그 URL 을 이용자에게 링크로 주면 JSON 화면을 마주한다.
        // 그래서 규칙을 바꿨다: **화면 URL 로 조회하는 몰은 코드·데이터가 일치해야 하고,
        // API 로 조회하는 몰은 데이터에 사람이 볼 링크가 반드시 있어야 한다.**
        Path p = Path.of("../data/online_platforms.json");
        assertTrue(Files.exists(p), "플랫폼 목록을 찾지 못했다: " + p.toAbsolutePath());
        String json = Files.readString(p);
        for (ProbeTarget t : ProbeTargets.ALL) {
            if (t.isApi()) {
                // 링크가 없으면 이용자는 홈으로 떨어지고, 조회 결과를 스스로 확인할 길이 없다.
                assertTrue(json.contains("\"" + t.platformId() + "\""),
                        t.platformId() + " 가 데이터에 없다");
                int i = json.indexOf("\"" + t.platformId() + "\"");
                String block = json.substring(i, Math.min(json.length(), i + 1200));
                assertTrue(block.contains("search_url_template") && block.contains("{q}"),
                        t.platformId() + " — API 로 조회하는 몰인데 이용자가 열 검색 링크가 데이터에 없다");
                continue;
            }
            String needle = t.searchUrlTemplate().replace("&", "\\u0026");
            assertTrue(json.contains(t.searchUrlTemplate()) || json.contains(needle),
                    t.platformId() + " — 코드의 검색 URL 이 데이터에 없다: " + t.searchUrlTemplate());
        }
    }

    @Test
    void API_로_조회하는_몰은_그_URL_을_이용자_링크로_쓰지_않는다() {
        // JSON 을 링크로 주면 이용자가 무엇을 봐야 할지 알 수 없다.
        for (ProbeTarget t : ProbeTargets.ALL) {
            if (!t.isApi()) continue;
            assertTrue(t.formBody() != null || t.searchUrlTemplate().contains("{qq}"),
                    t.platformId() + " 가 isApi() 인데 근거(formBody·{qq})가 없다");
        }
    }

    @Test
    void 사전을_비운_몰은_그_이유가_코드에_적혀_있다() throws Exception {
        // 등급 C(onnuri-chance·onnuri-market)는 근거 없이 비우면 다음 사람이 실수로 채운다.
        String src = Files.readString(Path.of("src/main/java/gift/onnuri/online/probe/ProbeTargets.java"));
        assertTrue(src.contains("이용약관 문구"), "온누리마켓을 비운 이유(약관 오탐)가 없다");
        assertTrue(src.contains("원산지 데이터 없음"), "온누리찬스를 비운 이유가 없다");
        List<ProbeTarget> empty = ProbeTargets.ALL.stream()
                .filter(t -> t.noneMarkersBound().isEmpty() && t.noneMarkersPlain().isEmpty()).toList();
        assertEquals(2, empty.size(), "등급 C 는 실측 기준 2곳이다");   // 2026-09-02 추가분은 둘 다 등급 B
    }

    /**
     * 제외 사유 사전은 **비대상 몰 전수**를 명시해야 한다.
     *
     * 기본값(getOrDefault)으로 흘러간 항목이 곧 오표기다 — 2026-09-02 이전 화면은 12곳 중
     * 10곳을 "화면에서만 만들어져 읽을 수 없음"으로 말하고 있었는데, 실제로는 8곳이
     * robots 차단이고 1곳은 검색 기능 자체가 없고 2곳은 시장·주소 선택이 먼저였다
     * (_workspace/20_probe_expansion_analysis.md 0절). 사전에 없으면 여기서 실패한다.
     */
    @Test
    void 조회하지_않는_몰은_전부_사유가_명시돼_있다() throws Exception {
        for (String id : shoppingIds()) {
            if (ProbeTargets.ids().contains(id)) continue;
            assertTrue(ProbeTargets.exclusionIds().contains(id),
                    id + " 가 제외 사유 사전에 없다 — 기본값으로 흘러가면 화면이 틀린 사유를 말한다");
        }
    }

    @Test
    void 사유는_조사표의_세_갈래_중_하나다() {
        // 붙는 몰이 없는 사유는 상수로도 두지 않는다 — 2026-09-01 rules-unverified 를
        // 제거한 것과 같은 이유다. 화면에 설명만 있고 해당하는 곳이 없는 사유는 소음이다.
        List<String> allowed = List.of(ProbeTargets.EX_ROBOTS,
                ProbeTargets.EX_SCOPE_FIRST, ProbeTargets.EX_NO_FETCH);
        for (String id : ProbeTargets.exclusionIds()) {
            assertTrue(allowed.contains(ProbeTargets.exclusionReason(id)),
                    id + " 의 사유가 조사표에 없는 값이다: " + ProbeTargets.exclusionReason(id));
        }
    }

    @Test
    void 사유별_곳_수가_조사표와_일치한다() {
        // _workspace/20_probe_expansion_analysis.md 0·2절 실측 분류.
        // 숫자가 어긋나면 사전이 조사와 갈라진 것이다.
        assertEquals(8, countReason(ProbeTargets.EX_ROBOTS), "robots 차단 8곳");
        assertEquals(2, countReason(ProbeTargets.EX_SCOPE_FIRST), "범위 선행 2곳(놀장·시장을 방으로)");
        assertEquals(1, countReason(ProbeTargets.EX_NO_FETCH), "정적 조회 불가 1곳(인어교주해적단)");
        // 22곳 − 조회 대상 11곳 = 11곳. 곳 수 합이 어긋나면 사전이 조사와 갈라진 것이다.
        assertEquals(11, ProbeTargets.exclusionIds().size());
    }

    @Test
    void 제외_사전과_조회_대상은_겹치지_않는다() {
        for (String id : ProbeTargets.ids()) {
            assertFalse(ProbeTargets.exclusionIds().contains(id),
                    id + " 가 조회 대상이면서 제외 사유도 갖고 있다 — 목록이 어긋났다");
        }
    }

    @Test
    void 사전에_없는_몰_id는_사전에_넣지_않는다() throws Exception {
        List<String> shopping = shoppingIds();
        for (String id : ProbeTargets.exclusionIds()) {
            assertTrue(shopping.contains(id),
                    id + " 는 쇼핑 플랫폼 목록에 없는 id 다 — 오타이거나 몰이 사라졌다");
        }
    }

    private static long countReason(String reason) {
        return ProbeTargets.exclusionIds().stream()
                .filter(id -> reason.equals(ProbeTargets.exclusionReason(id))).count();
    }

    /** data/online_platforms.json 의 kind=shopping id 목록(이용자가 보는 온라인 사용처). */
    static List<String> shoppingIds() throws Exception {
        Path p = Path.of("../data/online_platforms.json");
        assertTrue(Files.exists(p), "플랫폼 목록을 찾지 못했다: " + p.toAbsolutePath());
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(p));
        List<String> ids = new java.util.ArrayList<>();
        root.path("items").forEach(n -> {
            if ("shopping".equals(n.path("kind").asText())) ids.add(n.path("id").asText());
        });
        assertFalse(ids.isEmpty(), "쇼핑 플랫폼을 하나도 읽지 못했다 — JSON 구조가 바뀌었다");
        return ids;
    }
}
