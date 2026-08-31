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
        assertEquals(7, ProbeTargets.ALL.size());   // 2026-09-01 꾹AI온누리몰 추가
        for (ProbeTarget t : ProbeTargets.ALL) {
            assertNotNull(t.measuredOn(), t.platformId() + " measuredOn 없음");
            assertNotNull(t.robotsCheckedOn(), t.platformId() + " robotsCheckedOn 없음 — "
                    + "robots.txt 를 확인하지 않은 몰을 대상에 넣을 수 없다");
            assertTrue(t.searchUrlTemplate().contains("{q}"), t.platformId() + " — {q} 자리 없음");
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
    void 코드의_검색URL과_데이터의_검색URL이_같다() throws Exception {
        // 코드(ProbeTargets)는 자동 조회에, 데이터(online_platforms.json)는 이용자 링크에 쓰인다.
        // 둘이 갈라지면 "화면 링크로는 결과가 나오는데 판정은 없음" 같은 모순이 생긴다.
        Path p = Path.of("../data/online_platforms.json");
        assertTrue(Files.exists(p), "플랫폼 목록을 찾지 못했다: " + p.toAbsolutePath());
        String json = Files.readString(p);
        for (ProbeTarget t : ProbeTargets.ALL) {
            String needle = t.searchUrlTemplate().replace("&", "\\u0026");
            assertTrue(json.contains(t.searchUrlTemplate()) || json.contains(needle),
                    t.platformId() + " — 코드의 검색 URL 이 데이터에 없다: " + t.searchUrlTemplate());
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
        assertEquals(2, empty.size(), "등급 C 는 실측 기준 2곳이다");
    }
}
