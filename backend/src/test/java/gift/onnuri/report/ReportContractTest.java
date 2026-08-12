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
}
