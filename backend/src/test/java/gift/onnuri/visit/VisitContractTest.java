package gift.onnuri.visit;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 방문자 카운트 계약(2026-08-11) — shell.js가 today/total 키를 그대로 소비한다.
 * 이름이 바뀌면 사이드바 표기가 조용히 사라진다.
 */
class VisitContractTest {

    @Test
    void VisitStats_는_today_total_을_노출한다() {
        List<String> c = Arrays.stream(VisitStats.class.getRecordComponents())
                .map(r -> r.getName()).toList();
        assertEquals(List.of("today", "total"), c);
    }
}
