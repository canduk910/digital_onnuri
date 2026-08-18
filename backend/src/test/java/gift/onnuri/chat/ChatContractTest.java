package gift.onnuri.chat;

import gift.onnuri.chat.dto.ChatEvents;
import gift.onnuri.chat.dto.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 챗 API↔위젯 경계면 계약 테스트.
 *
 * 위젯(chat-widget.js)은 SSE 이벤트명("token"/"action"/"done"/"error")과 페이로드
 * 키를 문자열로 소비한다 — 이름이 바뀌면 에러 없이 대화창이 조용히 멈춘다.
 * 이름을 바꿔야 한다면 이 테스트와 chat-widget.js를 한 변경 단위로 함께 바꾼다.
 */
class ChatContractTest {

    private List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(c -> c.getName()).toList();
    }

    @Test
    void ChatRequest_는_role_content_메시지_배열을_받는다() {
        assertEquals(List.of("messages"), components(ChatRequest.class));
        assertEquals(List.of("role", "content"), components(ChatRequest.ChatMessage.class));
    }

    @Test
    void SSE_이벤트명은_위젯이_소비하는_문자열과_일치한다() {
        assertEquals("token", ChatEvents.TOKEN);
        assertEquals("action", ChatEvents.ACTION);
        assertEquals("done", ChatEvents.DONE);
        assertEquals("error", ChatEvents.ERROR);
    }

    @Test
    void NavigateAction_은_page_params_label_을_노출한다() {
        assertEquals(List.of("page", "params", "label"),
                components(ChatEvents.NavigateAction.class));
    }

    /**
     * 시스템 프롬프트의 그림 우선 지시(2026-08-18 사용자 요청)를 고정한다.
     * 위젯은 ```mermaid 코드블록만 다이어그램으로 렌더하므로(htmlLabels:false·
     * flowchart), 프롬프트가 이 형식과 문법 가드를 잃으면 그림이 조용히 사라진다.
     */
    @Test
    void 시스템_프롬프트는_그림_우선_설명을_지시한다() {
        RagRepository rag = org.mockito.Mockito.mock(RagRepository.class);
        org.mockito.Mockito.when(rag.minCollectedOn()).thenReturn(null);
        OpenAiClient ai = org.mockito.Mockito.mock(OpenAiClient.class);   // 생성자가 ai.mapper() 호출
        org.mockito.Mockito.when(ai.mapper()).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper());
        String p = new ChatService(ai, rag, null).systemPrompt();
        assertTrue(p.contains("```mermaid"), "mermaid 코드블록 형식 지시");
        assertTrue(p.contains("그림을 우선"), "설명형 답변의 기본 모드 = 그림");
        assertTrue(p.contains("flowchart"), "위젯이 검증한 다이어그램 종류");
        assertTrue(p.contains("대괄호"), "라벨 문법 가드(파싱 실패 방지)");
        assertTrue(p.contains("억지 그림 금지"), "단순 사실 답변엔 그림 강요 금지");
    }
}
