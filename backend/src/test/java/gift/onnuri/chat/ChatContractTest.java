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
}
