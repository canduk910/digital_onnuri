package gift.onnuri.report;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 버그 제보 계약(2026-08-11) — report.html이 JSON 키를 그대로 소비한다.
 * 이름이 바뀌면 목록·등록이 조용히 깨진다.
 */
class ReportContractTest {

    private List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(r -> r.getName()).toList();
    }

    @Test
    void ReportView_는_목록_렌더_필드를_노출한다() {
        assertEquals(List.of("id", "title", "content", "page", "nickname", "status", "createdAt"),
                components(ReportView.class));
    }

    @Test
    void ReportCreate_는_제출_폼_필드를_받는다() {
        assertEquals(List.of("title", "content", "page", "nickname"),
                components(ReportCreate.class));
    }

    @Test
    void 처리상태는_접수_반영만_허용한다() {
        assertTrue(ReportController.isValidStatus("접수"));
        assertTrue(ReportController.isValidStatus("반영"));
        assertFalse(ReportController.isValidStatus("완료"));
        assertFalse(ReportController.isValidStatus(null));
    }

    @Test
    void 관리자_키_미설정이면_기능_비활성_그리고_정확_일치만_통과() {
        assertFalse(ReportController.authorized("", "anything"));      // 미설정 → 비활성
        assertFalse(ReportController.authorized(null, "anything"));
        assertFalse(ReportController.authorized("secret", null));
        assertFalse(ReportController.authorized("secret", "wrong"));
        assertTrue(ReportController.authorized("secret", "secret"));
    }
}
