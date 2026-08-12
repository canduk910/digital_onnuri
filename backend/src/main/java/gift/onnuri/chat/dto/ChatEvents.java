package gift.onnuri.chat.dto;

import java.util.Map;

/**
 * SSE 이벤트 계약 — 위젯(chat-widget.js)이 이벤트명·페이로드 키를 그대로 소비한다.
 * 이름 변경은 ChatContractTest + 위젯을 한 변경 단위로 함께 바꾼다.
 */
public final class ChatEvents {

    public static final String TOKEN = "token";   // data: {"text": "..."} 응답 텍스트 증분
    public static final String ACTION = "action"; // data: NavigateAction — 이동 확인 카드
    public static final String DONE = "done";     // data: {}
    public static final String ERROR = "error";   // data: {"message": "..."}

    private ChatEvents() {
    }

    /** 클라이언트 실행 도구: 페이지 이동 제안. 위젯이 확인 버튼 카드로 렌더한다(임의 이동 금지). */
    public record NavigateAction(String page, Map<String, String> params, String label) {
    }
}
