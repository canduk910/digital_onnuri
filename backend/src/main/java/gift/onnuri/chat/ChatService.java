package gift.onnuri.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gift.onnuri.chat.dto.ChatEvents;
import gift.onnuri.chat.dto.ChatRequest;
import gift.onnuri.merchant.MerchantService;
import gift.onnuri.merchant.dto.MerchantView;
import gift.onnuri.merchant.dto.PageResult;
import gift.onnuri.merchant.dto.SearchQuery;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 챗 오케스트레이션: RAG + 도구 호출 루프 (최대 3회 왕복).
 * 흐름: 질문 → 모델이 search_policy/search_merchants/search_online 호출 → 결과를
 * 대화 컨텍스트에 재주입 → 최종 답변. navigate 호출은 SSE action 이벤트로 위젯에 전달.
 */
@Service
public class ChatService {

    private static final int MAX_TOOL_ROUNDS = 3;
    private static final int MAX_TURNS = 10;          // 프론트가 보내는 이력 상한
    private static final int MAX_MSG_CHARS = 2000;

    /** 인텐트 게이트 거절 문구 — 분류기가 범위 밖으로 판정하면 본 호출 없이 즉시 반환. */
    static final String OFF_TOPIC_REPLY =
            "죄송하지만 이 챗봇은 **온누리상품권(디지털온누리)** 안내 전용입니다.\n\n"
            + "상품권 사용처·결제 방법·환불·가맹점 검색에 대해 물어봐 주세요. "
            + "예: \"환불 어떻게 하나요?\", \"노량진동에 GS25 있나요?\"";

    private final OpenAiClient ai;
    private final RagRepository rag;
    private final MerchantService merchants;
    private final ObjectMapper om;

    public ChatService(OpenAiClient ai, RagRepository rag, MerchantService merchants) {
        this.ai = ai;
        this.rag = rag;
        this.merchants = merchants;
        this.om = ai.mapper();
    }

    /**
     * 대화 실행. onToken(텍스트 증분), onAction(이동 카드) 콜백으로 SSE에 연결된다.
     * 반환 없이 완료되면 컨트롤러가 done 이벤트를 보낸다.
     */
    public void run(ChatRequest req, Consumer<String> onToken,
                    Consumer<ChatEvents.NavigateAction> onAction) {
        // 인텐트 게이트: 마지막 질문(+직전 답변 맥락)만으로 범위 판별 — 범위 밖이면 본 루프 생략
        String lastUser = null, prevAssistant = null;
        for (ChatRequest.ChatMessage m : req.messages()) {
            if ("assistant".equals(m.role())) prevAssistant = m.content();
            else lastUser = m.content();
        }
        if (lastUser != null && !ai.isOnTopic(
                lastUser.substring(0, Math.min(lastUser.length(), 500)),
                prevAssistant == null ? null : prevAssistant.substring(0, Math.min(prevAssistant.length(), 300)))) {
            onToken.accept(OFF_TOPIC_REPLY);
            return;
        }

        ArrayNode messages = om.createArrayNode();
        messages.add(msg("system", systemPrompt()));
        List<ChatRequest.ChatMessage> hist = req.messages();
        int from = Math.max(0, hist.size() - MAX_TURNS);
        for (int i = from; i < hist.size(); i++) {
            ChatRequest.ChatMessage m = hist.get(i);
            String role = "assistant".equals(m.role()) ? "assistant" : "user";
            String content = m.content() == null ? "" : m.content();
            messages.add(msg(role, content.substring(0, Math.min(content.length(), MAX_MSG_CHARS))));
        }

        // 실스트리밍(2026-08-11): 모델의 content 델타를 그대로 SSE token으로 중계한다.
        // 도구 라운드가 앞말(preamble)을 내면 그것도 흘러가고, 도구 실행 후 이어서 스트림된다.
        for (int round = 0; round <= MAX_TOOL_ROUNDS; round++) {
            boolean allowTools = round < MAX_TOOL_ROUNDS;
            JsonNode m = ai.chatStream(messages, allowTools ? ai.toolDefs() : null, onToken);
            JsonNode toolCalls = m.path("tool_calls");
            if (allowTools && toolCalls.isArray() && !toolCalls.isEmpty()) {
                ObjectNode assistant = om.createObjectNode();
                assistant.put("role", "assistant");
                if (m.hasNonNull("content")) assistant.put("content", m.get("content").asText());
                assistant.set("tool_calls", toolCalls);
                messages.add(assistant);
                for (JsonNode call : toolCalls) {
                    String name = call.path("function").path("name").asText();
                    String argsJson = call.path("function").path("arguments").asText("{}");
                    String result = execTool(name, argsJson, onAction);
                    ObjectNode toolMsg = om.createObjectNode();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", call.path("id").asText());
                    toolMsg.put("content", result);
                    messages.add(toolMsg);
                }
                continue;
            }
            return;   // content는 이미 스트림됨
        }
    }

    // ---------- 도구 실행 ----------

    private String execTool(String name, String argsJson,
                            Consumer<ChatEvents.NavigateAction> onAction) {
        try {
            JsonNode a = om.readTree(argsJson);
            return switch (name) {
                case "search_policy" -> ragSearch(a.path("query").asText(), null);
                case "search_online" -> ragSearch(a.path("query").asText(),
                        List.of("online_catalog", "online_platforms"));
                case "search_merchants" -> searchMerchants(a);
                case "navigate" -> navigate(a, onAction);
                default -> "알 수 없는 도구: " + name;
            };
        } catch (Exception e) {
            return "도구 실행 실패: " + e.getMessage();
        }
    }

    private String ragSearch(String query, List<String> sources) {
        if (query == null || query.isBlank()) return "질의가 비어있다.";
        List<RagRepository.Hit> hits = rag.search(ai.embed(query), 6, sources);
        if (hits.isEmpty()) return "지식베이스에서 관련 내용을 찾지 못했다. 확인되지 않은 내용은 답하지 말 것.";
        StringBuilder sb = new StringBuilder("지식베이스 검색 결과 (출처·수집일 포함 — 답변에 근거로 인용할 것):\n");
        for (RagRepository.Hit h : hits) {
            sb.append("--- [").append(h.source());
            if (h.collectedOn() != null) sb.append(" · ").append(h.collectedOn()).append(" 수집");
            sb.append("] ").append(h.section() == null ? "" : h.section()).append('\n')
              .append(h.content()).append('\n');
        }
        return sb.toString();
    }

    private String searchMerchants(JsonNode a) {
        SearchQuery qy = new SearchQuery(
                text(a, "region"), text(a, "si"), text(a, "gu"), text(a, "dong"),
                text(a, "cat"), text(a, "brand"), null, null, text(a, "q"),
                null, null, null, null);
        if (qy.region() == null) return "region(서울|인천|경기|부산)은 필수다.";
        PageResult<MerchantView> r = merchants.search(qy, 0, 5, "name");
        StringBuilder sb = new StringBuilder();
        sb.append("가맹점 검색 결과: 총 ").append(r.total()).append("곳");
        sb.append(" (조건: ").append(describe(qy)).append(")\n");
        if (r.total() > 0) {
            sb.append("예시 (최대 5곳):\n");
            for (MerchantView v : r.items()) {
                sb.append("- ").append(v.name()).append(" | ").append(v.cat());
                if (v.addr() != null) sb.append(" | ").append(v.addr());
                sb.append(" | 카드형 ").append("Y".equals(v.card()) ? "가능" : "불가")
                  .append("·QR ").append("Y".equals(v.qr()) ? "가능" : "불가").append('\n');
            }
            sb.append("전체 목록은 navigate 도구로 가맹점 찾기 페이지 이동 카드를 제안할 것.");
        } else {
            sb.append("해당 조건의 가맹점이 없다. 조건 완화(동→구, 브랜드 제거)를 제안할 것.");
        }
        return sb.toString();
    }

    private String navigate(JsonNode a, Consumer<ChatEvents.NavigateAction> onAction) {
        String page = text(a, "page");
        if (page == null || !List.of("merchants", "online", "guide", "payment", "terms").contains(page)) {
            return "page는 merchants|online|guide 중 하나여야 한다.";
        }
        Map<String, String> params = new HashMap<>();
        String pj = text(a, "params");
        if (pj != null) {
            try {
                om.readTree(pj).properties().forEach(e -> params.put(e.getKey(), e.getValue().asText()));
            } catch (Exception ignored) {
            }
        }
        String label = text(a, "label");
        onAction.accept(new ChatEvents.NavigateAction(page, params,
                label == null ? "페이지로 이동" : label));
        return "이동 카드를 표시했다. 사용자가 누르면 이동한다. 답변에서 카드를 눌러 이동할 수 있다고 안내할 것.";
    }

    private static String text(JsonNode a, String f) {
        JsonNode v = a.path(f);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String describe(SearchQuery qy) {
        StringBuilder sb = new StringBuilder(qy.region());
        if (qy.si() != null) sb.append(' ').append(qy.si());
        if (qy.gu() != null) sb.append(' ').append(qy.gu());
        if (qy.dong() != null) sb.append(' ').append(qy.dong());
        if (qy.cat() != null) sb.append(", 업종=").append(qy.cat());
        if (qy.brand() != null) sb.append(", 브랜드=").append(qy.brand());
        if (qy.q() != null) sb.append(", 검색어=").append(qy.q());
        return sb.toString();
    }

    // ---------- 프롬프트 ----------

    String systemPrompt() {   // 패키지 가시성 — ChatContractTest가 그림 우선 지시를 고정
        String stamp = rag.minCollectedOn();
        return """
                너는 '코스콤 디지털온누리 가이드'의 챗봇이다. 코스콤 임직원에게 온누리상품권(특히 디지털온누리)의 \
                사용처·결제 방법·제도 정보를 정확하게 안내한다.

                [정확성 원칙 — 최우선]
                - 정책·제도 질문은 반드시 search_policy 도구로 지식베이스를 먼저 검색하고, 검색 결과에 근거해서만 답한다.
                - 가맹점 개수·존재 여부는 반드시 search_merchants 도구 결과의 숫자만 인용한다. 기억으로 숫자를 만들지 않는다.
                - 온라인몰 취급 품목·브랜드는 search_online 결과에 근거한다.
                - 지식베이스·도구에서 확인되지 않는 내용은 "확인된 정보가 없다"고 말하고 공식 채널(고객센터 1670-1600, onnuri.gift)을 안내한다. 추측 금지.
                - 답변에 근거의 출처와 수집일을 짧게 병기한다. 예: (공식 FAQ, 2026-08-11 수집 기준)%s
                - 출처 onnuri_customer_center 는 공식 홈페이지에는 없는 내용을 고객센터에 직접 물어 확인한 것이다. \
                이 출처에 근거해 답할 때는 (고객센터 유선 확인, 2026-08-25) 처럼 확인 경로를 밝히고, \
                공식 홈페이지에는 없는 안내라는 점과 금액이 큰 충전 전 앱·고객센터 재확인을 함께 안내한다.
                - 서비스 지역 안내: 가맹점 검색 데이터는 코스콤·한국거래소 소재지(서울·인천·경기·부산)만 보유한다. \
                그 외 지역 가맹점 질문에는 데이터가 없다고 밝히고 공식 지도(onnuri.gift/place)를 안내한다.

                [페이지 이동·검색 실행]
                - 가맹점/온라인 검색 결과를 안내한 뒤에는 navigate 도구로 해당 검색 화면 이동 카드를 제안한다.
                - merchants 페이지 파라미터: region(서울|인천|경기|부산), si(경기 시), gu, dong, cat, brand, q
                - online 페이지 파라미터: kind(shopping|delivery), cat(물품 대분류), brand
                - guide 페이지 파라미터: hash(offline|online)
                - 용어(골목형상점가·SSM·선차감)·유의사항은 terms 페이지로 안내한다.
                - 결제 방법(카드형·QR형 상세, 카드 실적·잔액부족 처리)은 payment 페이지로 안내한다.

                [출력 형식 — 그림 우선 설명]
                - 마크다운으로 답한다. 핵심 결론·주의사항은 **볼드**로 강조한다.
                - 설명형 답변은 그림을 우선한다: 절차·흐름(결제·충전·환불), 조건 분기(가능/불가 판단), \
                구조·관계(상품권 유형, 가맹 체계), 순서·기한이 들어가는 내용은 mermaid 코드블록(```mermaid, \
                flowchart TD 또는 LR)으로 먼저 그리고, 그림 아래 한두 문장으로 보충한다. 텍스트만으로 긴 설명을 \
                쓰기 전에 항상 "이걸 그림으로 보여줄 수 있나"를 먼저 판단한다.
                - 다이어그램은 작게 — 노드 8개 이내(모바일 패널에서도 읽혀야 한다). 조건 분기는 `C{예/아니오}` 마름모를 쓴다.
                - mermaid 노드는 반드시 `A[라벨]` 대괄호 형태로 쓴다(공백 라벨을 대괄호 없이 쓰면 파싱 실패). 라벨 안에 괄호·따옴표·대괄호를 넣지 않는다. 예: `flowchart TD\\n  A[앱 설치] --> B[카드 등록]`
                - 항목 비교(디지털 vs 지류, 할인율·한도 등)는 반드시 마크다운 표로 정리한다.
                - 단순 사실 확인(숫자 하나, 가능/불가 한 줄)처럼 그림이 이해를 더하지 않는 답변은 텍스트로만 — 억지 그림 금지.
                - 답변은 간결하게 — 인사말·군더더기 없이 바로 본론. 목록은 5개 이내로 요약.
                - 가맹점 검색(search_merchants) 결과의 숫자에는 수집일을 붙이지 말고 "실시간 조회 기준"으로 표기한다(수집일은 지식베이스 출처에만).

                [범위]
                - 온누리상품권과 무관한 질문(일반 상식, 코딩, 다른 상품권)은 정중히 범위 밖이라고 안내한다.
                """.formatted(stamp == null ? "" : "\n- 지식베이스 기준일: " + stamp + " (min collected_on)");
    }

    private ObjectNode msg(String role, String content) {
        ObjectNode m = om.createObjectNode();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
