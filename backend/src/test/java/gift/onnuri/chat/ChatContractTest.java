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

    /**
     * 출처 등급 병기 지시(2026-08-25)를 고정한다.
     * 코퍼스에는 공식 FAQ와 등급이 다른 원천(onnuri_customer_center = 고객센터 유선 확인,
     * 공식 문서 미기재)이 섞여 있다. 이 지시가 빠지면 챗봇이 유선 확인 사항을 공식 안내처럼
     * 단정해 계산대·충전 시점의 오판을 만든다.
     */
    @Test
    void 시스템_프롬프트는_출처_등급이_다른_근거를_밝히도록_지시한다() {
        RagRepository rag = org.mockito.Mockito.mock(RagRepository.class);
        org.mockito.Mockito.when(rag.minCollectedOn()).thenReturn(null);
        OpenAiClient ai = org.mockito.Mockito.mock(OpenAiClient.class);
        org.mockito.Mockito.when(ai.mapper()).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper());
        String p = new ChatService(ai, rag, null).systemPrompt();
        assertTrue(p.contains("onnuri_customer_center"), "유선 확인 원천을 이름으로 식별");
        assertTrue(p.contains("고객센터 유선 확인"), "답변에 붙일 출처 표기 문구");
        assertTrue(p.contains("공식 홈페이지에는 없는"), "공식 문서 미기재 사실을 밝히도록");
    }

    /**
     * 낡은 근거 회피 지시(2026-08-27)를 고정한다.
     * 코퍼스에는 공식 FAQ 원본 채록이 그대로 들어 있고, 그중 충전 한도 항목은 2026-01-01
     * 정책 변경 전 값(월 200만 원)이다. 최신 공지를 우선하라는 지시가 빠지면 챗봇이
     * 낡은 한도를 그대로 답해 이용자가 충전 시점에 한도 초과로 막힌다.
     */
    @Test
    void 시스템_프롬프트는_최신_공지를_FAQ보다_우선하도록_지시한다() {
        RagRepository rag = org.mockito.Mockito.mock(RagRepository.class);
        org.mockito.Mockito.when(rag.minCollectedOn()).thenReturn(null);
        OpenAiClient ai = org.mockito.Mockito.mock(OpenAiClient.class);
        org.mockito.Mockito.when(ai.mapper()).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper());
        String p = new ChatService(ai, rag, null).systemPrompt();
        assertTrue(p.contains("정정"), "검색 결과에 붙은 정정 표기를 따르도록");
        assertTrue(p.contains("최신 공지"), "출처 간 우선순위 규칙");
        assertTrue(p.contains("구매(충전)한도"), "한도 두 종류를 구분해 답하도록");
        assertTrue(p.contains("보유한도"), "보유한도 개념 명시");
    }

    // ---------- 탭 라우팅 (2026-09-02, online.html 2탭 분리) ----------

    private ChatService svc() {
        RagRepository rag = org.mockito.Mockito.mock(RagRepository.class);
        org.mockito.Mockito.when(rag.minCollectedOn()).thenReturn(null);
        OpenAiClient ai = org.mockito.Mockito.mock(OpenAiClient.class);
        org.mockito.Mockito.when(ai.mapper()).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper());
        return new ChatService(ai, rag, null);
    }

    private com.fasterxml.jackson.databind.JsonNode args(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * navigate 도구 스키마가 tab(live|browse) 을 enum 으로 노출하는지 고정한다.
     * online.html 이 두 탭으로 갈라진 뒤, 모델이 임의 문자열(예: "search")을 보내면
     * 프론트는 그 값을 모르고 규칙 폴백으로 떨어져 **에러 없이 다른 탭**에 착지한다.
     */
    @Test
    void navigate_도구_스키마는_tab_enum_을_노출한다() {
        OpenAiClient ai = new OpenAiClient("", "m", "e");
        com.fasterxml.jackson.databind.JsonNode nav = null;
        for (com.fasterxml.jackson.databind.JsonNode t : ai.toolDefs()) {
            if ("navigate".equals(t.path("function").path("name").asText())) nav = t;
        }
        assertNotNull(nav, "navigate 도구 정의");
        com.fasterxml.jackson.databind.JsonNode tab =
                nav.path("function").path("parameters").path("properties").path("tab");
        assertFalse(tab.isMissingNode(), "tab 파라미터");
        assertEquals("string", tab.path("type").asText());
        List<String> vals = new java.util.ArrayList<>();
        tab.path("enum").forEach(v -> vals.add(v.asText()));
        assertEquals(List.of("live", "browse"), vals, "허용값은 live|browse 뿐");
        String d = tab.path("description").asText();
        assertTrue(d.contains("상품명"), "상품명 질문 → live 라는 판단 기준");
        assertTrue(d.contains("online"), "page=online 에만 쓰이는 필드임을 명시");
    }

    /** page=online + tab=live 는 params 에 실려 프론트로 그대로 전달된다(위젯 무수정 전제). */
    @Test
    void navigate_는_online_탭을_params_에_실어_보낸다() {
        java.util.List<ChatEvents.NavigateAction> got = new java.util.ArrayList<>();
        svc().navigate(args("{\"page\":\"online\",\"tab\":\"live\",\"params\":\"{\\\"q\\\":\\\"로봇청소기\\\"}\"}"), got::add);
        assertEquals(1, got.size());
        assertEquals("live", got.get(0).params().get("tab"));
        assertEquals("로봇청소기", got.get(0).params().get("q"));
    }

    /** page 가 online 이 아니면 tab 은 조용히 버린다 — 오류가 아니라 무시(계약). */
    @Test
    void navigate_는_online_이_아닌_page_의_tab_을_무시한다() {
        java.util.List<ChatEvents.NavigateAction> got = new java.util.ArrayList<>();
        String r = svc().navigate(args("{\"page\":\"merchants\",\"tab\":\"live\"}"), got::add);
        assertEquals(1, got.size(), "이동 카드 자체는 정상 표시");
        assertFalse(got.get(0).params().containsKey("tab"), "merchants 에는 tab 이 없다");
        assertFalse(r.startsWith("page는"), "오류 반환이 아니다");
    }

    /** enum 밖 값은 버린다 — 그대로 흘리면 프론트 폴백 규칙과 어긋난 탭에 착지한다. */
    @Test
    void navigate_는_알_수_없는_tab_값을_버린다() {
        java.util.List<ChatEvents.NavigateAction> got = new java.util.ArrayList<>();
        svc().navigate(args("{\"page\":\"online\",\"tab\":\"search\",\"params\":\"{\\\"kind\\\":\\\"shopping\\\"}\"}"), got::add);
        assertEquals(1, got.size());
        assertFalse(got.get(0).params().containsKey("tab"));
        assertEquals("shopping", got.get(0).params().get("kind"));
    }

    /** params JSON 안에 직접 넣은 tab 도 같은 검문을 받는다(입력 지점이 둘이다). */
    @Test
    void navigate_는_params_안의_tab_도_같은_규칙으로_검문한다() {
        java.util.List<ChatEvents.NavigateAction> got = new java.util.ArrayList<>();
        svc().navigate(args("{\"page\":\"online\",\"params\":\"{\\\"tab\\\":\\\"browse\\\"}\"}"), got::add);
        assertEquals("browse", got.get(0).params().get("tab"));
        got.clear();
        svc().navigate(args("{\"page\":\"online\",\"params\":\"{\\\"tab\\\":\\\"zzz\\\"}\"}"), got::add);
        assertFalse(got.get(0).params().containsKey("tab"));
    }

    /**
     * 시스템 프롬프트의 탭 라우팅 지시(2026-09-02)를 고정한다.
     * 지시가 빠지면 모델이 tab 을 아예 보내지 않고, 프론트 규칙 폴백이 대신 판단한다 —
     * 에러 없이 다른 탭에 착지하므로 발견이 늦다. live 착지는 자동 조회를 실행하지
     * 않으므로 "조회했다"는 말도 금지한다.
     */
    @Test
    void 시스템_프롬프트는_online_탭_라우팅을_지시한다() {
        String p = svc().systemPrompt();
        assertTrue(p.contains("tab(live|browse)"), "탭 파라미터 이름·허용값");
        assertTrue(p.contains("상품명"), "상품명 질문 → live");
        assertTrue(p.contains("자동으로 실행되지 않는다"), "live 착지는 조회 미실행");
    }
}
