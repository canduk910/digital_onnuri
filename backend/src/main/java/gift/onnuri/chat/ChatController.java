package gift.onnuri.chat;

import gift.onnuri.chat.dto.ChatEvents;
import gift.onnuri.chat.dto.ChatRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * POST /api/chat — SSE(text/event-stream) 스트리밍 챗.
 * EventSource는 GET 전용이므로 위젯은 fetch + ReadableStream으로 소비한다.
 * stateless: 대화 이력은 요청 본문으로만 전달되고 서버에 저장하지 않는다.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService svc;
    private final OpenAiClient ai;
    private final RateLimiter limiter;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public ChatController(ChatService svc, OpenAiClient ai, RateLimiter chatRateLimiter) {
        this.svc = svc;
        this.ai = ai;
        this.limiter = chatRateLimiter;   // 빈 2개(chat/report) — 파라미터명으로 선택
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest req, HttpServletRequest http) {
        SseEmitter emitter = new SseEmitter(120_000L);
        String ip = clientIp(http);
        if (!limiter.tryAcquire(ip)) {
            // SSE 계약 유지: 429 대신 error 이벤트로 위젯에 사유 전달
            sendError(emitter, "요청이 많아 잠시 제한되었습니다. 1분 후 다시 시도해 주세요.");
            return emitter;
        }
        if (!ai.isConfigured()) {
            sendError(emitter, "챗봇이 아직 설정되지 않았습니다(서버 API 키 미등록). 관리자에게 문의해 주세요.");
            return emitter;
        }
        if (req == null || req.messages() == null || req.messages().isEmpty()) {
            sendError(emitter, "메시지가 비어있습니다.");
            return emitter;
        }
        pool.execute(() -> {
            try {
                svc.run(req,
                        text -> send(emitter, ChatEvents.TOKEN, Map.of("text", text)),
                        action -> send(emitter, ChatEvents.ACTION, action));
                send(emitter, ChatEvents.DONE, Map.of());
                emitter.complete();
            } catch (Exception e) {
                log.warn("chat 실패 (ip={}): {}", ip, e.getMessage());
                sendError(emitter, "답변 생성 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            throw new RuntimeException("SSE 전송 실패(클라이언트 종료 가능): " + e.getMessage(), e);
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event().name(ChatEvents.ERROR)
                    .data(Map.of("message", message), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException | IllegalStateException ignored) {
        }
    }

    /** Caddy 리버스 프록시 뒤에서 실제 클라이언트 IP (X-Forwarded-For 첫 항목). */
    private String clientIp(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return http.getRemoteAddr();
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> onError(Exception e) {
        return Map.of("message", "요청 처리 실패");
    }
}
