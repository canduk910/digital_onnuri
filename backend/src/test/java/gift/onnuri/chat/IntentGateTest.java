package gift.onnuri.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gift.onnuri.chat.dto.ChatRequest;
import gift.onnuri.merchant.MerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 인텐트 게이트(2026-08-11): 범위 밖 질문은 본 호출(chatStream) 없이 즉시 거절되고,
 * 범위 내 질문은 게이트를 통과해 본 루프로 진입해야 한다. 게이트가 조용히 뒤집히면
 * 비용 통제·오남용 차단이 무력화되므로 계약으로 고정한다.
 */
class IntentGateTest {

    private final ObjectMapper om = new ObjectMapper();
    private OpenAiClient ai;
    private RagRepository rag;
    private MerchantService merchants;
    private ChatService svc;

    @BeforeEach
    void setUp() {
        ai = mock(OpenAiClient.class);
        rag = mock(RagRepository.class);
        merchants = mock(MerchantService.class);
        when(ai.mapper()).thenReturn(om);
        svc = new ChatService(ai, rag, merchants);
    }

    private ChatRequest req(String... contents) {
        // 홀수 인덱스를 assistant로 취급해 (user, assistant, user...) 이력 구성
        java.util.List<ChatRequest.ChatMessage> msgs = new java.util.ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            msgs.add(new ChatRequest.ChatMessage(i % 2 == 0 ? "user" : "assistant", contents[i]));
        }
        return new ChatRequest(msgs);
    }

    @Test
    void 범위_밖_질문은_본_호출_없이_거절_문구를_스트림한다() {
        when(ai.isOnTopic(any(), any())).thenReturn(false);
        StringBuilder out = new StringBuilder();
        svc.run(req("퀵소트 코드 짜줘"), out::append, a -> fail("액션이 없어야 한다"));
        assertTrue(out.toString().contains("온누리상품권"), "표준 거절 문구가 나가야 한다");
        verify(ai, never()).chatStream(any(), any(), any());
    }

    @Test
    void 범위_내_질문은_게이트를_통과해_본_루프로_진입한다() {
        when(ai.isOnTopic(any(), any())).thenReturn(true);
        ObjectNode msg = om.createObjectNode().put("content", "답변");
        when(ai.chatStream(any(), any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<String> onDelta = inv.getArgument(2, Consumer.class);
            onDelta.accept("답변");
            return msg;
        });
        StringBuilder out = new StringBuilder();
        svc.run(req("환불 어떻게 하나요?"), out::append, a -> {});
        assertEquals("답변", out.toString());
        verify(ai, times(1)).chatStream(any(), any(), any());
    }

    @Test
    void 게이트에는_마지막_질문과_직전_답변이_전달된다() {
        AtomicReference<String> q = new AtomicReference<>(), prev = new AtomicReference<>();
        when(ai.isOnTopic(any(), any())).thenAnswer(inv -> {
            q.set(inv.getArgument(0));
            prev.set(inv.getArgument(1));
            return false;
        });
        svc.run(req("환불 알려줘", "환불은 7일 이내...", "그럼 지류는?"), s -> {}, a -> {});
        assertEquals("그럼 지류는?", q.get(), "마지막 사용자 질문이 게이트에 가야 한다");
        assertTrue(prev.get().startsWith("환불은"), "직전 답변이 맥락으로 가야 한다(후속 질문 오탐 방지)");
    }

    @Test
    void 거절_문구는_면책_원칙과_예시를_담는다() {
        assertTrue(ChatService.OFF_TOPIC_REPLY.contains("전용"));
        assertTrue(ChatService.OFF_TOPIC_REPLY.contains("예:"));
    }
}
