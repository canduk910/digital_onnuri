package gift.onnuri.online.probe;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import gift.onnuri.online.probe.ProbeTarget.Scope;

/**
 * 실시간 조회 대상과 그 판정 규칙 (ADR-17, 최초 실측 2026-08-31).
 *
 * 대상 수는 ALL 이 정한다 — 여기에 곳 수를 적지 않는다. 적어 둔 숫자는 6→7→9→10→11로
 * 늘 때마다 거짓이 됐다(2026-09-02 dev-qa 적발). 대상이 되는 조건:
 *   - app 컨테이너가 21-jre 라 브라우저가 없다 → 정적 HTTP(화면 또는 내부 API)로 결과가 실리는 몰
 *   - 온누리 범위로 좁힐 수 있는 몰(기획전 딥링크의 범위 오염 금지)
 *   - 없음-문구·titlePattern 이 실측된 몰. robots 차단 몰은 ADR-18 ① 로 보류(ADR-19 참조)
 *   - 나머지는 EXCLUSION 이 사유를 전수 명시한다(기본값으로 흘리면 화면 문구가 거짓이 된다)
 * 조사 전문: _workspace/19_online_probe.md
 *
 * 없음-문구 등급:
 *   A(noneMarkersBound) = 문구에 질의가 박혀 있어 약관·푸터가 구조적으로 걸릴 수 없다 → 단독 확정
 *   B(noneMarkersPlain) = 질의와 무관한 문구 → 토큰 카운트가 임계 미만일 때만 인정
 *   C(둘 다 비움)        = 사전을 두지 않는다. 아래 개별 주석에 이유가 있다.
 */
public final class ProbeTargets {

    private static final LocalDate MEASURED = LocalDate.of(2026, 8, 31);
    private static final LocalDate ROBOTS   = LocalDate.of(2026, 8, 31);

    private static java.util.regex.Pattern P(String re) { return java.util.regex.Pattern.compile(re); }

    /**
     * 상품명 샘플 정규식 — **판정 경로가 아니다.** 깨지면 근거 없는 likely 또는 unclear 로
     * 품질이 내려갈 뿐 오답이 되지 않는다(ADR-6 이 우려한 "구조 변경에 취약"을 여기서 봉쇄한다).
     * 2026-08-31 "김치" 조회 실측 매치 수 / 그중 질의어 포함:
     *   hotdeal 20/20 · chance 19/19 · sijang 40/28 · market 21/21 · gonggong 20/20 · epost 17/11
     * sijang·epost 에서 차이가 나는 것은 추천·연관 상품이 섞이기 때문이고,
     * ProbeJudge.extractTitles 가 질의 토큰을 포함하는 것만 남겨 걸러낸다.
     */
    private static final java.util.regex.Pattern T_HOTDEAL =
            P("<p class=\"[^\"]*line-clamp-2[^\"]*text-card-foreground[^\"]*\">([^<]+)</p>");
    private static final java.util.regex.Pattern T_CHANCE =   // <li data-pcode=… data-pname="상품명">
            P("data-pname=\"([^\"]+)\"");
    private static final java.util.regex.Pattern T_SIJANG =   // <p class="text"><strong></strong>상품명</p>
            P("<p class=\"text\">\\s*<strong[^>]*>[^<]*</strong>\\s*([^<]+?)\\s*</p>");
    private static final java.util.regex.Pattern T_MARKET =
            P("<a href=\"[^\"]*item\\.php\\?it_id=\\d+\"[^>]*>([^<]+)</a>");
    private static final java.util.regex.Pattern T_GONGGONG = // <h4 class="nl-name"><b>상품명</b>
            P("<h4 class=\"nl-name\">\\s*<b>([^<]+)</b>");
    private static final java.util.regex.Pattern T_KKUK =      // <p class="pro_title">상품명</p>
            P("<p class=\"pro_title\">([^<]+)</p>");
    // 아래 둘은 화면 HTML 이 아니라 몰의 **내부 검색 API(JSON)** 응답을 읽는다.
    // 정규식 기반이라 JSON 이어도 그대로 동작한다 — 판정 엔진을 고칠 필요가 없었다.
    private static final java.util.regex.Pattern T_EZWEL =     // "GDS_NM":"상품명"
            P("\"GDS_NM\":\"([^\"]+)\"");
    private static final java.util.regex.Pattern T_5ILJANG =   // "product_name":"상품명"
            P("\"product_name\":\"([^\"]+)\"");
    private static final java.util.regex.Pattern T_SHOPPING =  // <div class="name">상품명</div>
            P("<div class=\"name\">\\s*([^<]+?)\\s*</div>");
    private static final java.util.regex.Pattern T_EPOST =    // <div class="goods_text"><p class="tit">상품명</p>
            P("<div class=\"goods_text\">\\s*<p class=\"tit\">\\s*([^<]+?)\\s*</p>");
    // 지니어스몰은 상품명에 class 가 없다 — 카드 안 <em> 이 상품명 자리다.
    // 실측(2026-09-02 로봇청소기): <em> 12개 = 상품 12개로 정확히 일치하고,
    // 없음 응답에는 <em> 이 **하나도 없다**. 목록 밖에서 쓰이지 않는 태그다.
    private static final java.util.regex.Pattern T_GENIUS =   // <em>상품명</em>
            P("<em>([^<]+)</em>");
    // 굿데이·인더마켓은 같은 솔루션이라 마크업이 같다: <a … class="item_name">상품명</a>
    // 실측(2026-09-03 로봇청소기): 굿데이 20건 중 14건·인더마켓 20건 중 19건이 질의어를 담는다.
    // 없음 응답에도 추천상품 20~40건이 붙지만 질의어를 담지 않아 extractTitles 가 걸러 낸다.
    private static final java.util.regex.Pattern T_ITEMNAME =
            P("class=\"item_name\">([^<]+)</a>");
    private static final java.util.regex.Pattern T_PALDO =    // <div class="item_n">상품명</div>
            P("<div class=\"item_n\">([^<]+)</div>");
    private static final java.util.regex.Pattern T_HHOME =    // "slitmNm":"상품명" (JSON)
            P("\"slitmNm\"\\s*:\\s*\"([^\"]+)\"");
    // 11번가 — **앵커가 필수다.** `"title"` 만 쓰면 SEO 제목 `로봇청소기 - 11번가 추천` 이
    // 첫 샘플로 나간다(실측). 상품 객체에서 title 바로 앞에 오는 soldOut 을 앵커로 쓴다.
    // 2026-09-03 실측 매치: 김치 106 · 로봇청소기 21 · 없는 말 15(전부 추천 블록이라
    // 질의어를 담지 않아 extractTitles 가 걸러 낸다).
    private static final java.util.regex.Pattern T_11ST =
            P("\"soldOut\":(?:true|false),\"title\":\"([^\"]+)\"");
    private static final java.util.regex.Pattern T_GONGYOUNG = // <prdNm>상품명</prdNm> (XML)
            P("<prdNm>([^<]+)</prdNm>");
    // 롯데ON — `"pdName"` 은 상품 객체에만 있다. 앵커 없이 써도 안전한 것을 실측으로 확인했다:
    // 매치 수가 itemList 와 **정확히 같다**(로봇청소기 31 = total 31 · 김치 60 = 한 페이지).
    // 연관검색어·배너 문구가 새는 자리가 없다(11번가는 `"title"` 이 SEO 제목까지 물어 앵커가 필요했다).
    private static final java.util.regex.Pattern T_LOTTE =
            P("\"pdName\"\\s*:\\s*\"([^\"]+)\"");

    public static final List<ProbeTarget> ALL = List.of(

            // robots.txt: Allow: / (Disallow 는 /api/, /checkout/komsco-return 뿐)
            // 없음 실측: `"zzqqxyw12345" 검색 결과 검색 결과가 없습니다`
            // 있음 실측: "로봇청소기" 20회 · [로보락] Qrevo Edge 2 로봇청소기 등
            // echoesQuery=false 로 정정(2026-09-03) — 1단계에 true 로 둔 것은 이 몰이
            // 질의를 되뿌린다고 본 것인데, 그 에코는 전부 **에코 블록 안**(제목·검색창)이라
            // 판정이 보는 본문(stripEcho)에는 남지 않는다. 카나리아 기준을 규칙과 맞추자 드러났다.
            // 등급 A 문구가 먼저 확정하므로 판정은 그대로다.
            // ⚠ 2026-09-05 정정: 종전 주석은 "문구가 깨졌을 때만 토큰 0 판정이 unclear 대신
            //   none(medium) 을 낸다"고 적었으나 **그 폴백은 조임(ADR-22)으로 없어졌다.**
            //   이 몰은 문구 사전을 가지므로 문구가 깨지면 unclear 가 되고 카나리아가 알린다.
            new ProbeTarget("onnuri-hotdeal",
                    "https://onnurideal.com/search?q={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of("\"{q}\" 검색 결과 검색 결과가 없습니다"),
                    List.of(),
                    false, 5, T_HOTDEAL, 0, "김치", 0, MEASURED, ROBOTS),

            // robots.txt: Disallow: /include/ 뿐
            // 없음 실측: 명시 문구 없음. 잡히는 것은 `원산지 데이터 없음`(상품 영역 밖 필터 UI)이라 채택 불가 → 등급 C
            // 노이즈: 없는 질의에도 관련어 2회(추천상품 블록) → noiseFloor 2
            // 주의: search_word 만 붙이면 검색이 실행되지 않고 인기상품이 나온다. pn=product.search.list 필수.
            new ProbeTarget("onnuri-chance",
                    "https://onnurichance.com/?pn=product.search.list&search_word={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(), List.of(),
                    true, 5, T_CHANCE, 2, "김치", 0, MEASURED, ROBOTS),

            // robots.txt: HTTP 404 (파일 없음) — 명시적 금지 없음
            // 없음 실측: `검색하신 ' zzqqxyw12345 '에 대한 검색결과가 없습니다`
            //            따옴표 안쪽에 공백이 붙는다 → 유연 매처가 필요(ProbeJudge.bindQuery)
            new ProbeTarget("onnuri-sijang",
                    "https://www.onnuri-mall.co.kr/product/search?searchNm={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of("검색하신 '{q}'에 대한 검색결과가 없습니다"),
                    List.of(),
                    true, 5, T_SIJANG, 0, "김치", 0, MEASURED, ROBOTS),

            // robots.txt: User-agent: * / Allow: /
            // 없음 실측: ⚠ 검출되는 "없습니다"가 전부 이용약관 문구다 —
            //   "적립금은 현금으로 환급될 수 없습니다", "고의ㆍ과실이 없음을 입증한 경우"
            //   페이지 전체에 문자열 매칭하면 항상 '없음'이 된다 → 등급 C 로 비운다.
            // 대신 이 몰은 질의를 에코하지 않아(echoesQuery=false) 토큰 0 판정을 쓸 수 있다.
            // **이 문장은 2026-09-05 조임 뒤에도 참인 유일한 자리다** — 조임은 "문구 사전을
            // 가진 몰"만 막고, 이 몰은 등급 C(사전 없음)라 토큰 0 이 유일한 확정 수단으로 남는다.
            new ProbeTarget("onnuri-market",
                    "https://nurimarket.co.kr/shop/search_product.php?sq={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(), List.of(),
                    false, 5, T_MARKET, 0, "김치", 0, MEASURED, ROBOTS),

            // robots.txt: 그누보드 계열 다수 Disallow 하나 /shop/search.php 는 목록에 없다
            // 없음 실측: `'zzqqxyw12345' 에 대한 0개 의 검색결과`(2026-09-05 재실측 — 종전 `0 개의`)
            //   건수를 명시해 가장 견고하다
            // 느리다 — 실측 5.2초, 결과가 많은 질의는 6초를 넘긴다(2026-08-31 게이트에서 타임아웃).
            // 커버리지가 큰 종합몰이라 빼지 않고 이 몰만 예산을 늘린다.
            new ProbeTarget("onnuri-gonggong-mall",
                    "https://www.ongong.kr/shop/search.php?stx={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    // 2026-09-05: 몰이 마크업을 바꿨다 — `<strong>0</strong>개의` → `<strong>0개</strong>의`.
                    // 태그가 옮겨 가면서 텍스트의 공백도 `0 개의` → `0개 의` 로 옮겨 갔고, 템플릿은
                    // **자기가 가진 공백만** `\s*` 로 눅이므로 새 자리의 공백을 흡수하지 못해
                    // 이 몰이 무엇을 물어도 '없음'을 말할 수 없게 됐다(카나리아가 이틀 연속 적발).
                    // 쪼개질 수 있는 자리마다 공백을 넣어 **두 마크업을 모두** 받는다.
                    List.of("'{q}' 에 대한 0 개 의 검색결과"),
                    List.of(),
                    true, 5, T_GONGGONG, 0, "김치", 8000, MEASURED, ROBOTS),

            // robots.txt: Disallow 는 /upload/, /*file*, /*File*, /*adm* — 검색 경로 허용
            // 기획전 딥링크 몰이라 검색이 호스트 몰 전체를 훑는다 → MALL_WIDE.
            //   온누리 결제 범위 밖 상품이 섞이므로 likely 집계에 넣지 않고 라벨을 붙인다
            //   (2026-08-21 롯데ON 딥링크 오염과 같은 위험).
            // 없음 실측: `고객님께서 찾으시는 검색결과가 없습니다` — 질의 비의존형 → 등급 B
            // 있음 실측 히트가 3회로 낮아 임계를 2로 둔다.
            new ProbeTarget("epost-mall",
                    "https://mall.epost.go.kr/fo/search/search.do?searchTerm={q}",
                    StandardCharsets.UTF_8, Scope.MALL_WIDE,
                    List.of(),
                    List.of("고객님께서 찾으시는 검색결과가 없습니다", "해당하는 상품이 없습니다"),
                    false, 2, T_EPOST, 0, "김치", 0, MEASURED, ROBOTS),

            // 2026-09-01 추가. 4단계에서 "?keyword= 로 검색된다"고 판단해 링크만 줬으나,
            // 재조사해 보니 그 URL 은 **검색이 실행되지 않는다** — 브라우저로 열어도 검색창이
            // 비고 결과가 기본 목록 20개로 고정이다. 당시 센 "김치 7회"는 카테고리 메뉴의
            // '김치·반찬'이었다(1단계에서 세운 "에코를 상품으로 오인하지 않는다"를 스스로 어긴 것).
            // 실제 폼을 제출해 보니 keytype 이 필수였다.
            // robots.txt: User-agent:* → Allow: /, 금지는 /api·/login 등. /search 는 허용.
            //   (GPTBot·ChatGPT-User·OAI-SearchBot 만 전면 차단 — 우리 UA 는 해당 없음)
            // 없음 실측: `등록된 상품이 없습니다.` — 있음 응답에는 없다(온누리마켓 같은
            //   약관 상시 노출이 아님을 대조 확인) → 등급 B
            // 실측: 김치 상품 50 · 로봇청소기 17 · 없는 말 0(56KB vs 93KB)
            new ProbeTarget("kkuk-ai-onnuri-mall",
                    "https://onnuri.ai/search?k_order=3&page_num=50"
                            + "&keytype=productname%3Aproductcode%3Acomment&keyword={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(),
                    List.of("등록된 상품이 없습니다"),
                    // echoesQuery=false — 검색어가 href·input value 에는 박히지만 **화면 텍스트로는
                    // 되뿌리지 않는다**(카나리아가 선언 true 와 실측 false 의 차이를 잡아 정정).
                    // ⚠ 2026-09-05 정정: 종전 주석은 "덕분에 토큰 0 판정을 쓸 수 있어 '없다'를
                    //   확정할 수단이 하나 더 있다"고 적었으나 **조임(ADR-22)으로 없어졌다.**
                    //   이 몰은 문구 사전을 가지므로 문구가 깨지면 unclear 가 된다.
                    false, 5, T_KKUK, 0, "김치", 0, MEASURED, LocalDate.of(2026, 9, 1)),

            // ── 2026-09-02 추가: 화면에서 결과가 만들어지던 몰의 **내부 검색 API** 를 쓴다 ──
            // "화면에서 만들어진다"는 건 JS 가 어딘가로 요청을 보낸다는 뜻이다. 그 요청은
            // 정적 HTTP 로 그대로 재현할 수 있고, 브라우저가 필요 없다(ADR-17 의 제약을 우회하지
            // 않고 푼 것). 공식 화면이 보내는 것과 같은 요청이라 새 경로를 만든 것도 아니다.

            // 현대이지웰 온누리전통시장
            // robots.txt: `User-agent: Yeti / Allow: /` 만 있고 `*` 그룹이 없다 = 제약 없음
            // ⚠ searchTerm 은 **두 번 인코딩**해야 한다({qq}) — 한 번만 하면 0건이 온다.
            // 없음 실측: `"resultDocuments":[]` (있는 질의에는 나오지 않음을 대조 확인) → 등급 B
            // echoesQuery=false — 없는 질의 응답(316자)에 질의어가 전혀 없다.
            // ⚠ 2026-09-05 정정: 종전 주석은 "토큰 0 판정도 쓸 수 있다"고 적었으나 **조임으로
            //   없어졌다**(ADR-22 — 이 몰의 점검 페이지 오판이 그 조임을 유발했다).
            // 실측: 김치 totalSize 891 · 로봇청소기 28 · 없는 말 0
            new ProbeTarget("hyundai-ezwel-onnuri",
                    "https://www.onnuri-sijang.com/onnuri/main/searchList"
                            + "?searchTerm={qq}&displaySize=50&currentPage=1&clientCd=onnuri_b2c&dvcCd=",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(),
                    List.of("\"resultDocuments\":[]"),
                    false, 5, T_EZWEL, 0, "김치", 0, MEASURED, LocalDate.of(2026, 9, 2)),

            // 온누리5일장 — form POST. API 호스트가 본몰과 다르다(api.samaint.co.kr, robots 404 = 금지 없음).
            // cate_gno=45 가 이 몰의 온누리 전용관 검색이다. `all` 은 검색을 무시하고 전체 목록을
            // 주므로 쓰면 안 된다(없는 질의에도 578KB 가 온다 — 그대로 뒀으면 늘 '있음'이 됐을 것).
            // 없음 실측: `"data":[]`(있는 질의에는 없다) → 등급 B
            // noiseFloor=4 — 응답의 last_query 에 SQL 이 실려 검색어가 4회 반복된다.
            new ProbeTarget("onnuri-5iljang",
                    "https://api.samaint.co.kr/main/product_search_cate",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(),
                    List.of("\"data\":[]"),
                    true, 5, T_5ILJANG, 4, "김치", 0, MEASURED, LocalDate.of(2026, 9, 2),
                    "mall_id=onnuri&member_id=&cno=&cate_gno=45&search_str={q}"
                            + "&request_method=POST&page=1&perPage=50"),

            // 온누리쇼핑 — 검색 UI 가 클릭으로 열려 폼을 못 찾았을 뿐, 결과는 **서버가 렌더한다**.
            // 검색을 실제로 실행해 보고서야 주소를 알았다(`/search?searchWrd=`).
            // robots.txt: 전면 차단 없음, /search 에 대한 금지도 없다.
            // 없음 실측: `검색된 상품이 없습니다.` — 있음 응답에는 없다(대조 확인) → 등급 B
            // 실측: 김치 126회·64KB · 로봇청소기 105회·60KB · 없는 말 3회·15KB(전부 검색창 에코)
            new ProbeTarget("onnuri-shopping",
                    "https://onnurishop.co.kr/search?searchWrd={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(),
                    List.of("검색된 상품이 없습니다"),
                    // echoesQuery=false — 검색어가 <input value> 와 JS 변수에만 있어 stripEcho 후
                    // 텍스트에는 남지 않는다(카나리아가 선언 true 와 실측 false 의 차이를 잡았다).
                    // ⚠ 2026-09-05 정정: 종전 주석은 "덕분에 토큰 0 판정도 함께 쓸 수 있다"고
                    //   적었으나 **조임(ADR-22)으로 없어졌다.** 문구가 깨지면 unclear 가 된다.
                    false, 5, T_SHOPPING, 0, "김치", 0, MEASURED, LocalDate.of(2026, 9, 2)),

            // 지니어스몰 — 2026-09-02 승격. 앞서 "검색 기능 자체가 없다"고 본 것이 **틀렸다.**
            // 플랫폼 기본 검색 URL 4종(product.html?mode=search·list.html·search.html·search_text)
            // 만 시험하고 접었는데, 실제 폼은 `<form class="search_bbs" action="/product/product.html"
            // method="GET">` + `name="search"` 였다 — 파라미터 이름 하나가 달랐다.
            // 없는 URL 을 만들어 시험하기 전에 **그 몰의 폼을 먼저 읽어야 한다**는 것이
            // 2026-09-01 꾹AI 에 이어 두 번째로 확인된 교훈이다.
            // robots.txt: `Allow : /` · 금지는 `/ko_mall/` 뿐 — /product/ 는 허용.
            // 실측(2026-09-02): 로봇청소기 200·50,936B·상품 12건·0.20초 /
            //                   zzqqxyw12345 200·29,844B·상품 0건.
            // 없음 실측: `총 <i>0</i>개의 상품이 있습니다` — 판정은 태그를 걷어낸 텍스트에
            //   대고 하므로 사전에는 그 형태(`총 0 개…`)로 적는다. 질의 비의존형 → 등급 B.
            //   **있음 응답에는 이 문구가 없다**(대조 확인 — 건수가 12로 찍힌다).
            // echoesQuery=false — 없는 질의가 원문에 1회 있으나 검색창 <input value> 라
            //   stripEcho 후 토큰 0이다.
            // ⚠ 2026-09-05 정정: 종전 주석은 "문구가 깨져도 토큰 0 판정이 '없다'를 받쳐 준다"고
            //   적었으나 **그 받침은 조임(ADR-22)으로 없어졌다.** 문구가 깨지면 unclear 가 된다.
            // 가전 전문몰이라 카나리아 present 질의는 김치가 아니라 로봇청소기다(김치 0건).
            new ProbeTarget("genius-mall",
                    "https://luxurysystem.co.kr/product/product.html?search={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(),
                    List.of("총 0 개의 상품이 있습니다"),
                    false, 5, T_GENIUS, 0, "로봇청소기", 0,
                    LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 2)),

            // ── 2026-09-03 편입 3곳 (ADR-19) ──────────────────────────────────────
            // 이 셋은 2026-08-31 조사에서 **정적 조회가 되는데도 robots.txt 때문에 뺐던** 곳이다.
            // 편입은 사용자 결정이며, 규칙은 이번에 새로 실측했다.
            // robots.txt 는 그대로다(2026-09-03 재확인): 굿데이·인더마켓 `Disallow: /` + `Allow: /$`,
            // 팔도시장 `disallow: /Goods/`(검색 경로는 소문자 `/goods/`, 상품 상세는 대문자 `/Goods/`).

            // 온누리굿데이
            // 없음 실측: `입력하신 단어로 검색된 결과가 없습니다.` — 질의 비의존형 → 등급 B.
            //   **있음 응답 2종(로봇청소기·김치)에는 없다**(대조 확인).
            // ⚠ 없음 응답도 추천상품 20건을 함께 준다 — 응답 길이·상품 수로는 판정할 수 없다.
            //   실제로 상품코드 집합이 질의마다 완전히 disjoint 인 것으로 검색 실행을 확인했다
            //   (2026-09-01 꾹AI 에서 겪은 "검색이 실행되지 않는데 결과처럼 보이는" 함정 대조).
            // echoesQuery=false — 없는 질의가 원문에 22회 있으나 전부 <title>·<meta> 라
            //   stripEcho 후 토큰 0이다(2026-08-31 조사에서 "제목 에코"로 관찰한 그 자리다).
            // 실측(2026-09-03): 로봇청소기 200·256,139B·상품 20건·1.13초 /
            //                   zzqqxyw12345 200·193,231B·검색 결과 0(추천 20건).
            new ProbeTarget("onnuri-goodday",
                    "https://www.onnurigood.com/?pn=product.search.list&search_word={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(),
                    List.of("입력하신 단어로 검색된 결과가 없습니다"),
                    false, 5, T_ITEMNAME, 0, "김치", 0,
                    LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3)),

            // 인더마켓온누리몰 — 굿데이와 같은 솔루션(같은 파라미터·같은 마크업·같은 없음 문구).
            // ⚠ **없음 응답이 있음 응답보다 크다**(269KB vs 199KB) — 추천상품이 40건으로 늘기 때문.
            //   길이 비교로 판정하는 규칙을 만들면 정확히 거꾸로 판단한다.
            // 없음 응답 추천 40건 중 1건이 '김치'를 담고 있었다 — 그런 회차에는 등급 B 가
            //   none 대신 unclear 로 물러선다(설계대로다. '있다'로 기울지는 않는다).
            // echoesQuery=false — 굿데이와 같은 이유(<title>·<meta> 뿐).
            // 실측(2026-09-03): 로봇청소기 200·199,404B·상품 20건·0.80초 /
            //                   zzqqxyw12345 200·269,296B·검색 결과 0(추천 40건).
            new ProbeTarget("inthemarket-onnuri",
                    "https://inthemarket.co.kr/?pn=product.search.list&search_word={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(),
                    List.of("입력하신 단어로 검색된 결과가 없습니다"),
                    false, 5, T_ITEMNAME, 0, "김치", 0,
                    LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3)),

            // 온누리팔도시장
            // 없음 실측(등급 A): `‘{q}’의 대한 검색결과 총 0 개의 상품이 있습니다` —
            //   질의가 박혀 있어 약관·푸터가 구조적으로 걸릴 수 없다. (원문은 `‘<span>q</span>’…
            //   총 <b>0</b> 개…` 라 태그를 걷어낸 뒤의 형태로 적는다.)
            // 등급 B 보조: `고객님이 검색하신 상품이 없어요` — 있음 응답 2종에 없음을 대조 확인.
            // echoesQuery=true — 검색어를 결과 문구에 되뿌린다(위 등급 A 문구가 그것이다).
            //   그래서 토큰 0 판정은 쓸 수 없고, noiseFloor 1 로 그 1회를 뺀다.
            // ⚠ 자동완성 JS 안에 `검색된 정보가 없습니다.` 가 **모든 응답에** 있다 —
            //   ProbeJudge 가 <script> 를 먼저 걷어내 텍스트에 남지 않는 것을 실측 확인했다.
            //   원본 HTML 에 문자열 매칭했다면 늘 '없음'이 됐을 것이다(온누리마켓 약관과 같은 함정).
            // 실측(2026-09-03): 로봇청소기 200·64,182B·상품 14건·1.88초 /
            //                   zzqqxyw12345 200·38,658B·총 0개.
            new ProbeTarget("onnuri-paldo-sijang",
                    "https://e-jangter.com/goods/search.aspx?q={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of("\u2018{q}\u2019의 대한 검색결과 총 0 개의 상품이 있습니다"),
                    List.of("고객님이 검색하신 상품이 없어요"),
                    true, 5, T_PALDO, 1, "김치", 0,
                    LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3)),

            // 현대홈쇼핑 온누리샵 — 2026-09-03 편입(15번째).
            // 앞선 조사가 robots 만 보고 접었을 때 **놓친 것**: 전용관 화면에 헤더 통합검색과
            // 별개로 `#productSearchText`("온누리샵 상품을 검색해 보세요") 입력창이 따로 있다.
            // 화면을 열어 봤다면 첫 화면에서 보였다 — 11번가·롯데ON 과 갈리는 지점이 이것이다.
            //
            // **범위가 응답 자체로 확인된다** — sectInfo.sectNm 이 "현대홈쇼핑 온누리샵".
            // 11번가·롯데ON·공영쇼핑을 뺀 이유(몰 전체가 섞인다)가 이 몰에는 해당하지 않는다.
            //
            // URL 은 **화면이 부르는 캐시 경로를 그대로 쓴다**(ADR-17 의 "공식 화면이 보내는 것과
            // 같은 요청" 원칙). 캐시를 우회한 직접 경로도 동작하지만 그것은 화면이 보내는 요청이
            // 아니다. 신선도를 실측 대조했다 — 4질의 × 두 경로에서 **상품명 집합이 완전히 동일**
            // (청소기 3건·쌀 10건·로봇청소기 0건·없는 말 0건). 응답에 늘 붙는
            // `__cache_metadata.status=HIT_STALE` 이 실제 차이를 만들지 않는다는 뜻이다.
            // 신선도가 문제가 되면 `https://www.hmall.com/api/hf/dp/v1/sect-mng/plansale-ancr-paging?…`
            // 로 바꾸면 된다(같은 파라미터, 응답 구조 동일).
            //
            // jsonApi=true — **평범한 GET 인데 JSON 을 준다.** formBody 도 {qq} 도 없어
            // 기존 두 신호로는 API 임이 드러나지 않는다. 선언하지 않았다면 searchUrlFor 가
            // 이 JSON URL 을 이용자 링크로 내보냈을 것이다(2026-09-02 와 같은 사고).
            // 이용자 링크는 데이터의 전용관 주소다 — 그 화면에 자기 검색창이 있어 검색어 없이도 쓸 수 있다.
            //
            // 없음 실측: `"itemList":[]` — 있음 응답에는 없다(등급 B, 이지웰·5일장과 같은 형태).
            // echoesQuery=true — sectInfo.searchTxt 에 질의어가 그대로 실린다(JSON 이라 stripEcho 밖).
            // 실측(2026-09-03, 캐시 경로): 세트 200·46,738B·20건·0.07초 / 쌀 10건 / 청소기 3건 /
            //   로봇청소기 0건 / zzqqxyw12345 200·658B·0건. 조회 대상 중 가장 빠르다.
            // 카나리아 present 는 `세트`(20건 — 페이지 상한을 채운다).
            //   김치는 1건이라 그 상품 하나가 빠지면 매일 거짓 실패가 난다.
            //   **`쌀`(10건)로 두려다 테스트가 막았다** — 1자라 ProbeQuery.MIN_LEN(2)에 미달해
            //   카나리아가 매일 400 을 받는다. 2026-08-31 에 바로 이 낱말로 겪은 일이고,
            //   그때 넣어 둔 가드가 같은 실수를 두 번째로 잡았다.
            new ProbeTarget("hyundai-home-shopping",
                    "https://www.hmall.com/md/api/cache"
                            + "?url=/api/hf/dp/v1/sect-mng/plansale-ancr-paging"
                            + "&sectId=3132118&chPlanSaleSectID=&sortType=sale_cnt"
                            + "&page=1&listSize=20&searchTxt={q}&deviceInfo=pc",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(),
                    List.of("\"itemList\":[]"),
                    true, 5, T_HHOME, 1, "세트", 0,
                    LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3), null, true),

            // ── 2026-09-03 편입 2곳 — 전체 검색의 **상세 필터**로 온누리 범위를 잡는다 ──
            // 앞선 조사가 "전용관 안에 검색이 없다 → 범위 오염"으로 접었던 곳들이다.
            // 전용관(범위)과 전체 검색(도구)을 따로 본 것이 잘못이었다 —
            // **전체 검색에 범위 필터가 있으면 둘이 만난다.** 한 번의 클릭 뒤에 답이 있었다.

            // 11번가 온누리마켓 — 퀵필터 `filters=ONNURI`
            // 범위가 기획전보다 정확하다: 이 필터는 11번가가 스스로 붙인 **결제 가능 속성**이고
            // (상품 상세에 `디지털온누리상품권 결제 가능` 배지), 기획전 242건을 포함하는 상위집합이다.
            // 없음 실측: `"groupName":"noSearchData"` — 있음 0회 / 없음 1회 → 등급 B.
            //   ⚠ `"totalCount":0` 도 같은 성질이라 함께 둘 수 있으나 **넣지 않았다** —
            //   면(facet)마다 카운트가 실리는 응답이라 어느 한 면이 0이면 있는 상품을 없다고 할 수 있다.
            //   가장 위험한 방향이고, noSearchData 가 늘 함께 나오므로 얻는 것도 없다.
            // echoesQuery=true · noiseFloor=9 — 없는 질의 4종에서 **전부 정확히 9회**(SEO 제목·설명·안내).
            //   이 값이 없으면 hits 9 ≥ 임계라 등급 B 문구가 있어도 unclear 로 빠진다.
            // jsonApi=true — GET 이지만 JSON API 다. 이용자 링크는 데이터의 화면 주소를 쓴다
            //   (`search.11st.co.kr/pc/total-search?…&filters=ONNURI` — 새로 열면 칩이 checked 로 걸린다).
            // 실측(2026-09-03): 김치 200·841,317B·938건·0.62초 / 로봇청소기 65,963B·22건 /
            //   zzqqxyw12345 161,052B·0건.
            new ProbeTarget("11st-onnuri-market",
                    "https://apis.11st.co.kr/search/api/tab"
                            + "?kwd={q}&tabId=TOTAL_SEARCH&filters=ONNURI",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(),
                    List.of("\"groupName\":\"noSearchData\""),
                    true, 5, T_11ST, 9, "김치", 0,
                    LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3), null, true),

            // 공영쇼핑 — 혜택 필터 `benefit=trdit_mrkt_goods_yn`
            // 이 값을 몰이 **'온누리'로 부른다**(혜택 목록 응답에 `<nm>온누리</nm>`).
            // 기획전(ebtNo=4328) 표본 20건을 이 필터로 조회하니 20/20 이 걸렸다.
            // ⚠ 불확실성: 이 값이 결제 가능 속성인지를 **상품 화면에서 직접 확인하지는 못했다.**
            //   몰의 이름표와 기획전 표본 일치까지가 근거다. 필터 뜻이 바뀌면 범위가 조용히 어긋난다.
            // 필터를 빼면 몰 전체가 나온다 — 김치 40건 중 온누리 기획전 상품이 4건뿐이었다(6-9절).
            //   그래서 formBody 의 benefit 은 **지워서는 안 되는 값**이다.
            // 없음 실측: `<rsltYn>N</rsltYn>` — 있음은 Y → 등급 B.
            //   ⚠ 이 문구는 **원문에서만 잡힌다.** XML 이라 toText 가 태그를 걷으면 ` N ` 만 남는다.
            //   ProbeJudge 가 API 몰에 한해 원문도 대조하게 고친 이유가 이것이다.
            // echoesQuery=true · noiseFloor=4 — 없는 질의가 텍스트에 4회(input value·kwd·recKwd 2회).
            // 이용자 링크는 데이터의 기획전 홈이다({q} 없음 — 2026-09-03 계약 완화 적용).
            //   몰 전체 검색 화면을 링크로 주면 90%가 범위 밖이라 그렇게 하지 않는다.
            // 실측(2026-09-03): 김치 200·10,688B·184건·0.10초 / 로봇청소기 1,136B·0건 /
            //   zzqqxyw12345 1,124B·0건.
            new ProbeTarget("gongyoung-shopping",
                    "https://www.gongyoungshop.kr/search/ajaxSearchGoodsList.do",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(),
                    List.of("<rsltYn>N</rsltYn>"),
                    true, 5, T_GONGYOUNG, 4, "김치", 0,
                    LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3),
                    "kwd={q}&reSrchFlag=false&pageNum=1&pageSize=40&sort=r&kwdType=0"
                            + "&benefit=trdit_mrkt_goods_yn&brandSort=cou&logFlag=true"),

            // 롯데ON 온누리상생스토어 — 프로모션 필터 `u31=onnuri` (2026-09-03 편입)
            //
            // **범위는 정확하다.** 반환 상품 전부가 `"type":"onnuri"` 와 `emblemName:"온누리"` 를
            // 갖는다(로봇청소기 31/31 실측). 무필터로 부르면 60건 중 15건만 온누리다.
            //
            // ⚠ **이 몰은 '없다'고 말하지 않는다 — 등급 C 로 넣은 이유가 여기 있다.**
            //   ① 온누리 결과가 0이면 `{"itemList":[],"total":0}` 130바이트가 온다. 그런데
            //      **존재하지 않는 필터값(`u31=onnuriZZ`)을 보내도 바이트·md5 가 완전히 같다.**
            //      즉 130바이트는 "온누리에 없다"와 "필터가 깨졌다"를 구분하지 못한다.
            //      롯데ON 이 파라미터 이름을 바꾸는 날 **모든 질의가 조용히 '없음'** 이 된다 —
            //      ADR-17 이 가장 경계한 방향이라 이 근거로 '없음'을 만들지 않는다.
            //   ② 무의미어·`q` 누락·`q` 빈 값은 200 · text/html · **본문 0바이트**로 전부 같다.
            //      현실 다어절 질의 8건 중 2건(`다이슨 김치냉장고`·`샤넬 클래식백`)이 실제로 0바이트였다.
            //   따라서 없음-문구 사전을 비우고(등급 C) `echoesQuery=true` 를 **정책으로 선언**한다
            //   (온누리찬스 선례 — 토큰 0 판정을 쓰지 않겠다는 뜻이지 에코 실측값이 아니다).
            //   결과적으로 이 몰은 **likely / unclear 만 낸다.**
            //   `canDecideAbsent` 가 false 가 되어 카나리아 absent 대조에서도 자동으로 빠지므로,
            //   **필터가 깨졌을 때 그것을 알아채는 유일한 수단이 present 기대치**다(김치 → likely + 샘플).
            //
            // ⚠ 이용자 링크에 필터를 실을 수 없다 — `promo=onnuri` 는 모바일 UA 첫 화면에서만 먹고
            //   PC UA 는 302 로 필터가 증발하며, 모바일도 첫 스크롤부터 풀린다(온누리 100%→29%).
            //   세 실패가 전부 **조용해서** 이용자는 온누리 링크인 줄 알고 결제 안 되는 상품을 본다.
            //   그래서 데이터의 search_url_template 을 비워 둔다 — 링크는 상생스토어 홈으로 나간다.
            //
            // robots: ADR-19 로 대상 선정 기준에서 빠졌으나 기록은 남긴다 —
            //   **조회 호스트가 몰 본체(www.lotteon.com)이고 `Disallow: /` 다.**
            //   11번가는 조회가 apis.11st.co.kr 로 갈라지는 것과 다르다.
            //
            // 실측(2026-09-03, 헤더 없이 UA 만): 로봇청소기 200·114,923B·31건·0.60초 /
            //   김치 220,870B·60건(total 1,166) / 골프채 88,419B / 샤넬 200·130B·0건 /
            //   zzqqxyw12345 200·**0B**(text/html).
            new ProbeTarget("lotte-on-sangsaeng-store",
                    "https://www.lotteon.com/csearch/search/search"
                            + "?u2=0&u3=60&u16=ranking.desc&u31=onnuri&u37=true&u39=0"
                            + "&render=qapi&platform=pc&collection_id=9&mallId=1&q={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(), List.of(),
                    true, 5, T_LOTTE, 0, "김치", 0,
                    LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 3), null, true)
    );

    /**
     * 조회 대상이 **아닌** 몰에 대해 "왜 확인하지 않았는지"를 이용자에게 말해 주기 위한 사전.
     *
     * "확인하지 않았습니다"만 던지면 이용자는 결국 그 이유를 우리 사정으로 읽거나,
     * 나쁘게는 "없다"로 읽는다. 사유를 대면 링크를 눌러 볼 근거가 된다.
     * 근거는 2026-08-31 22곳 전수 실현성 조사(_workspace/19_online_probe.md 1절).
     */
    public static final String EX_BOT_BLOCKED = "bot-blocked";      // 몰이 자동 조회를 능동 차단한다
    public static final String EX_SCOPE_FIRST = "scope-first";      // 시장·주소를 먼저 골라야 검색된다
    public static final String EX_NO_FETCH    = "no-static-search"; // 정적 응답에 결과가 실리지 않는다
    // 붙는 몰이 없어진 사유는 상수째 없앤다 — 화면에 설명만 있고 실체가 없는 항목이 생긴다.
    // 그렇게 사라진 것이 넷이다:
    //   rules-unverified   (2026-09-01) · no-search-feature (2026-09-02, 지니어스몰 편입)
    //   robots-blocked     (2026-09-03, 굿데이·인더마켓·팔도 편입)
    //   scope-mixed        (2026-09-03, 11번가·공영쇼핑 편입 뒤 롯데ON 마저 편입)

    /**
     * 4곳 **전수 명시**. 기본값으로 흘려보내지 않는다 —
     * 2026-09-02 이전에는 2곳만 적고 나머지를 `no-static-search` 로 흘려, 화면이
     * "화면에서만 만들어져 읽을 수 없음 10곳"이라는 **사실과 다른 사유**를 말하고 있었다.
     * 전수화하지 않으면 같은 일이 조용히 반복되므로 ProbeTargetsTest 가 완전성을 고정한다.
     */
    private static final java.util.Map<String, String> EXCLUSION = java.util.Map.of(
            // ── 자동 조회를 능동 차단한다 ────────────────────────────────────────────
            // robots 가 /api/ 를 금지하고, 화면 검색은 WAF 가 1.7KB 응답으로 막는다.
            // 막는 쪽을 뚫는 일은 하지 않는다(ADR-18 이 기각한 A6 — 탐지 회피).
            "cyso", EX_BOT_BLOCKED,

            // ── 범위(시장·주소)를 먼저 골라야 검색된다 — 전역 검색이 없다 ────────────
            "onnuri-noljang", EX_SCOPE_FIRST,   // 시장 선택 → /market/{id} 안에서 검색
            "oligopalgo", EX_SCOPE_FIRST,       // 배달 주소 선택 → /shop/address.php

            // ── 정적 응답에 결과가 실리지 않는다 ─────────────────────────────────────
            // api.tpirates.com/v3/www/product/search 실존(onnuri 필터 키까지 있다) — 직접 호출 401.
            // SPA 라 화면 HTML 에도 결과가 없다. 색인 층(ADR-18)이 대신 답한다.
            "tpirates", EX_NO_FETCH);

    /**
     * 조회 대상이 아닌 이유. 사전에 없으면 정적 조회 불가로 본다 —
     * 다만 그 폴백에 기대지 않는다(위 사전이 전수여야 하고, 테스트가 그것을 고정한다).
     */
    public static String exclusionReason(String platformId) {
        return EXCLUSION.getOrDefault(platformId, EX_NO_FETCH);
    }

    /** 사유가 **명시된** 몰. 테스트가 데이터와 대조해 완전성을 지킨다. */
    public static java.util.Set<String> exclusionIds() { return EXCLUSION.keySet(); }

    public static Optional<ProbeTarget> byId(String platformId) {
        return ALL.stream().filter(t -> t.platformId().equals(platformId)).findFirst();
    }

    public static List<String> ids() { return ALL.stream().map(ProbeTarget::platformId).toList(); }

    private ProbeTargets() {}
}
