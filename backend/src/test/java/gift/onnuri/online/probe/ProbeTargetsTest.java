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
        assertEquals(9, ProbeTargets.ALL.size());   // 2026-09-02 현대이지웰·온누리5일장 추가(내부 API)
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
}
