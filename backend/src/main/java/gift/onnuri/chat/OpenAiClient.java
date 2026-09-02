package gift.onnuri.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * OpenAI HTTP 클라이언트 (chat completions + embeddings).
 * 별도 SDK 의존성 없이 JDK HttpClient로 직접 호출한다. 키는 OPENAI_API_KEY 환경변수
 * (서버 .env) — 코드·저장소·응답 어디에도 노출하지 않는다.
 */
@Component
public class OpenAiClient {

    private final String apiKey;
    private final String model;
    private final String embedModel;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper om = new ObjectMapper();

    public OpenAiClient(@Value("${app.openai.api-key:}") String apiKey,
                        @Value("${app.openai.model:gpt-5.6-luna}") String model,
                        @Value("${app.openai.embed-model:text-embedding-3-small}") String embedModel) {
        this.apiKey = apiKey;
        this.model = model;
        this.embedModel = embedModel;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 인텐트 사전필터(2026-08-11): 질문이 온누리상품권 안내 범위인지 경량 호출로 판별한다.
     * 본 호출(풀 시스템 프롬프트+도구 ~6k tok) 전 ~200tok 분류로 범위 밖 질문을 차단.
     * 애매하면 true(통과) — 정상 질문을 막는 오탐이 더 나쁘고, 최종 방어선(시스템 프롬프트
     * 거절)이 뒤에 남아 있다. 분류 실패(네트워크·파싱)도 true — 분류기 장애가 챗을 죽이면 안 된다.
     */
    public boolean isOnTopic(String question, String prevTurn) {
        try {
            ObjectNode body = om.createObjectNode();
            body.put("model", model);
            body.put("reasoning_effort", "none");
            ArrayNode msgs = body.putArray("messages");
            ObjectNode sys = msgs.addObject();
            sys.put("role", "system");
            sys.put("content", "온누리상품권(디지털온누리) 안내 챗봇의 범위 판별기다. 질문이 다음 범위면 true: "
                    + "온누리상품권 제도·할인·한도·환불·소득공제, 결제 방법·앱 사용법, 가맹점·사용처(오프라인 매장·온라인몰) 탐색, "
                    + "이 가이드 사이트 이용. 인사말·감사 등 짧은 대화 연결어도 true. "
                    + "일반 상식·코딩·타 상품권·무관한 잡담이면 false. 직전 대화의 후속 질문이면 맥락을 보고 판단. 애매하면 true.");
            ObjectNode user = msgs.addObject();
            user.put("role", "user");
            String ctx = (prevTurn == null || prevTurn.isBlank()) ? "" : "[직전 답변 요약] " + prevTurn + "\n";
            user.put("content", ctx + "[질문] " + question);
            ObjectNode rf = body.putObject("response_format");
            rf.put("type", "json_schema");
            ObjectNode js = rf.putObject("json_schema");
            js.put("name", "intent");
            js.put("strict", true);
            ObjectNode schema = js.putObject("schema");
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            schema.putObject("properties").putObject("on_topic").put("type", "boolean");
            schema.putArray("required").add("on_topic");
            JsonNode resp = post("https://api.openai.com/v1/chat/completions", body);
            String content = resp.path("choices").path(0).path("message").path("content").asText("");
            return om.readTree(content).path("on_topic").asBoolean(true);
        } catch (Exception e) {
            return true;   // fail-open
        }
    }

    /**
     * chat completions 스트리밍 호출 (2026-08-11 실스트리밍 전환).
     * content 델타는 onDelta로 실시간 전달하고, 종료 후 조립된 message 노드
     * ({content, tool_calls[]})를 돌려준다 — 도구 루프는 조립본으로 판단한다.
     */
    public JsonNode chatStream(ArrayNode messages, ArrayNode tools, java.util.function.Consumer<String> onDelta) {
        ObjectNode body = om.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.put("stream", true);
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
            // gpt-5.6 계열: chat/completions에서 function tools 사용 시 reasoning_effort는 none이어야 한다
            body.put("reasoning_effort", "none");
        }
        StringBuilder content = new StringBuilder();
        // tool_calls 델타는 index 기준으로 id/name/arguments가 조각나 도착한다 — 인덱스별 조립
        java.util.Map<Integer, ObjectNode> calls = new java.util.TreeMap<>();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                    .build();
            HttpResponse<java.util.stream.Stream<String>> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() / 100 != 2) {
                String err = resp.body().limit(20).reduce("", (a, b) -> a + b);
                throw new IllegalStateException("OpenAI 응답 " + resp.statusCode() + " — "
                        + err.substring(0, Math.min(300, err.length())));
            }
            resp.body().forEach(line -> {
                if (!line.startsWith("data:")) return;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) return;
                try {
                    JsonNode delta = om.readTree(data).path("choices").path(0).path("delta");
                    JsonNode c = delta.path("content");
                    if (c.isTextual() && !c.asText().isEmpty()) {
                        content.append(c.asText());
                        onDelta.accept(c.asText());
                    }
                    for (JsonNode tc : delta.path("tool_calls")) {
                        int idx = tc.path("index").asInt(0);
                        ObjectNode acc = calls.computeIfAbsent(idx, k -> {
                            ObjectNode n = om.createObjectNode();
                            n.put("type", "function");
                            n.putObject("function").put("name", "").put("arguments", "");
                            return n;
                        });
                        if (tc.hasNonNull("id")) acc.put("id", tc.get("id").asText());
                        JsonNode fn = tc.path("function");
                        ObjectNode af = (ObjectNode) acc.get("function");
                        if (fn.hasNonNull("name")) af.put("name", af.get("name").asText() + fn.get("name").asText());
                        if (fn.hasNonNull("arguments")) af.put("arguments", af.get("arguments").asText() + fn.get("arguments").asText());
                    }
                } catch (IOException ignored) {   // 조각 파싱 실패 라인은 건너뜀
                }
            });
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI 스트림 실패: " + e.getMessage(), e);
        }
        ObjectNode msg = om.createObjectNode();
        if (!content.isEmpty()) msg.put("content", content.toString());
        if (!calls.isEmpty()) {
            ArrayNode arr = msg.putArray("tool_calls");
            calls.values().forEach(arr::add);
        }
        return msg;
    }

    /** 질의 임베딩 — pgvector 리터럴 문자열("[0.1,0.2,...]")로 반환. */
    public String embed(String text) {
        ObjectNode body = om.createObjectNode();
        body.put("model", embedModel);
        body.put("input", text);
        JsonNode resp = post("https://api.openai.com/v1/embeddings", body);
        JsonNode arr = resp.path("data").path(0).path("embedding");
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(arr.get(i).asDouble());
        }
        return sb.append(']').toString();
    }

    private JsonNode post(String url, ObjectNode body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("OpenAI 응답 " + resp.statusCode()
                        + " — " + resp.body().substring(0, Math.min(300, resp.body().length())));
            }
            return om.readTree(resp.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI 호출 실패: " + e.getMessage(), e);
        }
    }

    public ObjectMapper mapper() {
        return om;
    }

    /** 도구 정의(JSON Schema). 이름·파라미터는 ChatService의 실행부와 한 변경 단위. */
    public ArrayNode toolDefs() {
        ArrayNode tools = om.createArrayNode();
        tools.add(fn("search_policy",
                "온누리상품권 제도·정책·이용방법 지식베이스(공식 출처 수집본)를 검색한다. 사용 요건, 결제 방법, 할인율, 한도, 환불, 소득공제, 앱 사용법 등 정책 질문에 반드시 먼저 사용한다.",
                obj("query", "string", "검색 질의 (한국어 자연어)")));
        tools.add(fn("search_online",
                "온라인 사용처(공식 쇼핑몰·배달앱)와 각 몰의 취급 물품종류·브랜드 실측 데이터를 검색한다. '온라인에서 ○○ 살 수 있나' 류 질문에 사용한다.",
                obj("query", "string", "검색 질의 (예: '애플 아이패드 구매 가능한 몰')")));
        tools.add(fn("search_merchants",
                "오프라인 가맹점 데이터베이스(서울·인천·경기·부산, 실시간)를 검색해 가맹점 수와 예시 목록을 돌려준다. 특정 지역·브랜드·업종의 가맹점 존재/개수 질문에 사용한다. 숫자는 반드시 이 도구 결과만 인용한다.",
                obj("region", "string", "시도: 서울|인천|경기|부산 (필수)",
                    "si", "string", "경기 전용 시 이름 (예: 수원시)",
                    "gu", "string", "구 이름 (예: 동작구, 팔달구)",
                    "dong", "string", "법정동 이름 (예: 노량진동)",
                    "cat", "string", "업종 대분류 (예: 음식점, 카페, 편의점)",
                    "brand", "string", "브랜드명 (예: GS25, 다이소)",
                    "q", "string", "가맹점명·주소·시장명 부분 검색어")));
        ObjectNode navParams = obj(
                "page", "string", "merchants(가맹점 찾기)|online(온라인 사용처)|payment(결제 방법 상세)|terms(용어·유의사항)|guide(가이드 index)",
                "tab", "string", "page=online 일 때만 쓰는 착지 탭. 상품명으로 묻는 질문(예: '로봇청소기 어디서 사?')은 live(상품 실시간 검색, params.q 에 상품명), "
                        + "물품종류·브랜드·구분으로 묻는 질문(예: '가전 파는 온누리몰')은 browse(몰 둘러보기, params 에 kind/cat/brand). 판단이 서지 않으면 생략한다.",
                "label", "string", "카드에 표시할 한국어 라벨 (예: '노량진동 GS25 검색 결과 보기')",
                "params", "string", "URL 쿼리 파라미터 JSON 문자열 (예: {\"region\":\"서울\",\"gu\":\"동작구\",\"dong\":\"노량진동\",\"brand\":\"GS25\"})");
        // tab 은 화면이 아는 두 값뿐이다. 자유 문자열로 두면 모델이 지어낸 값이 프론트의
        // 규칙 폴백으로 떨어져 **에러 없이 다른 탭**에 착지한다 — enum 으로 좁힌다.
        ArrayNode tabEnum = ((ObjectNode) navParams.get("properties").get("tab")).putArray("enum");
        tabEnum.add("live");
        tabEnum.add("browse");
        tools.add(fn("navigate",
                "사이트 페이지 이동·검색 실행을 제안하는 카드를 사용자에게 표시한다. 가맹점 검색 결과를 보여주거나 특정 화면으로 안내할 때 사용한다. 카드는 사용자가 눌러야 이동한다.",
                navParams));
        return tools;
    }

    private ObjectNode fn(String name, String desc, ObjectNode params) {
        ObjectNode f = om.createObjectNode();
        f.put("type", "function");
        ObjectNode d = f.putObject("function");
        d.put("name", name);
        d.put("description", desc);
        d.set("parameters", params);
        return f;
    }

    /** (이름, 타입, 설명) 3개 단위 가변 인자로 JSON Schema object를 만든다. */
    private ObjectNode obj(String... nameTypeDesc) {
        ObjectNode schema = om.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        for (int i = 0; i + 2 < nameTypeDesc.length + 1; i += 3) {
            ObjectNode p = props.putObject(nameTypeDesc[i]);
            p.put("type", nameTypeDesc[i + 1]);
            p.put("description", nameTypeDesc[i + 2]);
        }
        return schema;
    }
}
