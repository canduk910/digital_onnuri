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

    public static final List<ProbeTarget> ALL = List.of(

            // robots.txt: Allow: / (Disallow 는 /api/, /checkout/komsco-return 뿐)
            // 없음 실측: `"zzqqxyw12345" 검색 결과 검색 결과가 없습니다`
            // 있음 실측: "로봇청소기" 20회 · [로보락] Qrevo Edge 2 로봇청소기 등
            new ProbeTarget("onnuri-hotdeal",
                    "https://onnurideal.com/search?q={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of("\"{q}\" 검색 결과 검색 결과가 없습니다"),
                    List.of(),
                    true, 5, T_HOTDEAL, 0, "김치", 0, MEASURED, ROBOTS),

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
            new ProbeTarget("onnuri-market",
                    "https://nurimarket.co.kr/shop/search_product.php?sq={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(), List.of(),
                    false, 5, T_MARKET, 0, "김치", 0, MEASURED, ROBOTS),

            // robots.txt: 그누보드 계열 다수 Disallow 하나 /shop/search.php 는 목록에 없다
            // 없음 실측: `'zzqqxyw12345' 에 대한 0 개의 검색결과` — 건수를 명시해 가장 견고하다
            // 느리다 — 실측 5.2초, 결과가 많은 질의는 6초를 넘긴다(2026-08-31 게이트에서 타임아웃).
            // 커버리지가 큰 종합몰이라 빼지 않고 이 몰만 예산을 늘린다.
            new ProbeTarget("onnuri-gonggong-mall",
                    "https://www.ongong.kr/shop/search.php?stx={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of("'{q}' 에 대한 0 개의 검색결과"),
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
                    // 덕분에 토큰 0 판정을 쓸 수 있어 '없다'를 확정할 수단이 하나 더 있다.
                    false, 5, T_KKUK, 0, "김치", 0, MEASURED, LocalDate.of(2026, 9, 1)),

            // ── 2026-09-02 추가: 화면에서 결과가 만들어지던 몰의 **내부 검색 API** 를 쓴다 ──
            // "화면에서 만들어진다"는 건 JS 가 어딘가로 요청을 보낸다는 뜻이다. 그 요청은
            // 정적 HTTP 로 그대로 재현할 수 있고, 브라우저가 필요 없다(ADR-17 의 제약을 우회하지
            // 않고 푼 것). 공식 화면이 보내는 것과 같은 요청이라 새 경로를 만든 것도 아니다.

            // 현대이지웰 온누리전통시장
            // robots.txt: `User-agent: Yeti / Allow: /` 만 있고 `*` 그룹이 없다 = 제약 없음
            // ⚠ searchTerm 은 **두 번 인코딩**해야 한다({qq}) — 한 번만 하면 0건이 온다.
            // 없음 실측: `"resultDocuments":[]` (있는 질의에는 나오지 않음을 대조 확인) → 등급 B
            // echoesQuery=false — 없는 질의 응답(316자)에 질의어가 전혀 없다. 토큰 0 판정도 쓸 수 있다.
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
                    // 덕분에 토큰 0 판정도 함께 쓸 수 있다.
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
            //   stripEcho 후 토큰 0이다. 문구가 깨져도 토큰 0 판정이 '없다'를 받쳐 준다.
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
    public static final String EX_SCOPE_MIXED = "scope-mixed";      // 검색은 되나 결과가 몰 전체라 좁힐 수 없다
    public static final String EX_NO_FETCH    = "no-static-search"; // 정적 응답에 결과가 실리지 않는다
    // 붙는 몰이 없어진 사유는 상수째 없앤다 — 화면에 설명만 있고 실체가 없는 항목이 생긴다
    // (2026-09-01 rules-unverified 제거와 같은 원칙). 그렇게 사라진 것이 둘이다:
    //   no-search-feature — 유일한 후보 지니어스몰이 조회 대상이 됐다(2026-09-02).
    //   robots-blocked    — 굿데이·인더마켓·팔도가 조회 대상이 됐다(2026-09-03, ADR-19).
    //
    // `scope-mixed` 는 2026-09-03 재조사가 만든 사유다. 그전에는 11번가·롯데ON·공영쇼핑이
    // `no-static-search` 로 묶여 있었는데 **셋 다 검색이 정적으로 재현된다** —
    // 라벨이 사실과 달랐고, 화면은 그 라벨을 근거로 이용자에게 틀린 설명을 하고 있었다.
    // 이 셋의 진짜 문제는 읽지 못하는 것이 아니라 **읽어도 대부분 온누리 밖 상품**이라는 것이다.
    // 그건 우리 사정이 아니라 이용자를 위한 선택이므로 화면이 그렇게 말할 수 있어야 한다.

    /**
     * 7곳 **전수 명시**. 기본값으로 흘려보내지 않는다 —
     * 2026-09-02 이전에는 2곳만 적고 나머지를 `no-static-search` 로 흘려, 화면이
     * "화면에서만 만들어져 읽을 수 없음 10곳"이라는 **사실과 다른 사유**를 말하고 있었다.
     * 전수화하지 않으면 같은 일이 조용히 반복되므로 ProbeTargetsTest 가 완전성을 고정한다.
     */
    private static final java.util.Map<String, String> EXCLUSION = java.util.Map.ofEntries(
            // ── 자동 조회를 능동 차단한다 ────────────────────────────────────────────
            // robots 가 /api/ 를 금지하고, 화면 검색은 WAF 가 1.7KB 응답으로 막는다.
            // 막는 쪽을 뚫는 일은 하지 않는다(ADR-18 이 기각한 A6 — 탐지 회피).
            java.util.Map.entry("cyso", EX_BOT_BLOCKED),

            // ── 범위(시장·주소)를 먼저 골라야 검색된다 — 전역 검색이 없다 ────────────
            java.util.Map.entry("onnuri-noljang", EX_SCOPE_FIRST),   // 시장 선택 → /market/{id} 안에서 검색
            java.util.Map.entry("oligopalgo", EX_SCOPE_FIRST),       // 배달 주소 선택 → /shop/address.php

            // ── 검색은 정적으로 되지만 결과가 몰 전체다 (2026-09-03 실측) ────────────
            // 기획전 안에 자기 검색이 없다. 화면 입력창은 11번가 GNB 통합검색이라
            // search.11st.co.kr 로 나가고 기획전을 가리키는 파라미터가 없다.
            java.util.Map.entry("11st-onnuri-market", EX_SCOPE_MIXED),
            // 상생스토어 안에 검색이 없다. 화면 검색은 mallId=1(롯데ON 전체)로 나간다.
            // 화면 링크 135개를 전수 열거해도 스토어 내 검색·전체상품 탭이 없다.
            java.util.Map.entry("lotte-on-sangsaeng-store", EX_SCOPE_MIXED),
            // 검색 자체는 흠잡을 데 없다(김치 40건·없는 말 0건). 그런데 범위가 몰 전체다 —
            // 김치 40건의 상품 id 를 온누리 기획전 450개와 대조하니 **겹치는 것이 4건**.
            // 나머지 36건은 온누리상품권으로 살 수 없다.
            java.util.Map.entry("gongyoung-shopping", EX_SCOPE_MIXED),

            // ── 정적 응답에 결과가 실리지 않는다 ─────────────────────────────────────
            // api.tpirates.com/v3/www/product/search 실존(onnuri 필터 키까지 있다) — 직접 호출 401.
            // SPA 라 화면 HTML 에도 결과가 없다. 색인 층(ADR-18)이 대신 답한다.
            java.util.Map.entry("tpirates", EX_NO_FETCH));

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
