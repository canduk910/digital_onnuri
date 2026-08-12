package gift.onnuri.chat.dto;

import java.util.List;

/**
 * 챗 요청. stateless — 대화 이력은 프론트가 최근 턴을 함께 보낸다(서버 저장 없음).
 * 컴포넌트명은 ChatContractTest가 고정한다(위젯 chat-widget.js와 한 변경 단위).
 */
public record ChatRequest(List<ChatMessage> messages) {

    public record ChatMessage(String role, String content) {
    }
}
