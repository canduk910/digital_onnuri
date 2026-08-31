package gift.onnuri.online.probe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProbeQueryTest {

    @Test
    void 전각문자를_통상형으로_모은다() {
        assertEquals("LG 냉장고", ProbeQuery.of("ＬＧ　냉장고").normalized());
    }

    @Test
    void 공백을_접고_제어문자를_지운다() {
        assertEquals("삼성전자 비스포크", ProbeQuery.of("  삼성전자\t\n 비스포크  ").normalized());
    }

    @Test
    void 너무_짧거나_길면_조회하지_않는다() {
        assertFalse(ProbeQuery.of("가").searchable());
        assertFalse(ProbeQuery.of("가".repeat(41)).searchable());
        assertFalse(ProbeQuery.of("!!!").searchable(), "문자·숫자가 없으면 조회 가치가 없다");
        assertTrue(ProbeQuery.of("로봇청소기").searchable());
    }

    @Test
    void 캐시키는_대소문자를_접는다() {
        assertEquals(ProbeQuery.of("DJI 드론").cacheKey(), ProbeQuery.of("dji 드론").cacheKey());
    }

    @Test
    void 토큰이_없으면_정규화형_전체를_센다() {
        assertEquals(java.util.List.of("가나"), ProbeQuery.of("가나").countTokens());
    }
}
