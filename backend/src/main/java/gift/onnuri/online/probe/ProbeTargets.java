package gift.onnuri.online.probe;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import gift.onnuri.online.probe.ProbeTarget.Scope;

/**
 * 실시간 조회 대상 6곳과 그 판정 규칙 (ADR-17, 실측 2026-08-31).
 *
 * 22곳 중 6곳인 이유:
 *   - app 컨테이너가 21-jre 라 브라우저가 없다 → 정적 HTTP 응답에 결과가 실리는 몰만 가능(8곳)
 *   - 그중 온누리굿데이·인더마켓은 robots.txt 가 `Disallow: /` + `Allow: /$` → 제외
 *   - 나머지 14곳은 검색 폼이 정적 HTML 에 없거나(SPA) URL 추정 실패
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

    public static final List<ProbeTarget> ALL = List.of(

            // robots.txt: Allow: / (Disallow 는 /api/, /checkout/komsco-return 뿐)
            // 없음 실측: `"zzqqxyw12345" 검색 결과 검색 결과가 없습니다`
            // 있음 실측: "로봇청소기" 20회 · [로보락] Qrevo Edge 2 로봇청소기 등
            new ProbeTarget("onnuri-hotdeal",
                    "https://onnurideal.com/search?q={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of("\"{q}\" 검색 결과 검색 결과가 없습니다"),
                    List.of(),
                    true, 5, null, 0, "쌀", 0, MEASURED, ROBOTS),

            // robots.txt: Disallow: /include/ 뿐
            // 없음 실측: 명시 문구 없음. 잡히는 것은 `원산지 데이터 없음`(상품 영역 밖 필터 UI)이라 채택 불가 → 등급 C
            // 노이즈: 없는 질의에도 관련어 2회(추천상품 블록) → noiseFloor 2
            // 주의: search_word 만 붙이면 검색이 실행되지 않고 인기상품이 나온다. pn=product.search.list 필수.
            new ProbeTarget("onnuri-chance",
                    "https://onnurichance.com/?pn=product.search.list&search_word={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(), List.of(),
                    true, 5, null, 2, "쌀", 0, MEASURED, ROBOTS),

            // robots.txt: HTTP 404 (파일 없음) — 명시적 금지 없음
            // 없음 실측: `검색하신 ' zzqqxyw12345 '에 대한 검색결과가 없습니다`
            //            따옴표 안쪽에 공백이 붙는다 → 유연 매처가 필요(ProbeJudge.bindQuery)
            new ProbeTarget("onnuri-sijang",
                    "https://www.onnuri-mall.co.kr/product/search?searchNm={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of("검색하신 '{q}'에 대한 검색결과가 없습니다"),
                    List.of(),
                    true, 5, null, 0, "쌀", 0, MEASURED, ROBOTS),

            // robots.txt: User-agent: * / Allow: /
            // 없음 실측: ⚠ 검출되는 "없습니다"가 전부 이용약관 문구다 —
            //   "적립금은 현금으로 환급될 수 없습니다", "고의ㆍ과실이 없음을 입증한 경우"
            //   페이지 전체에 문자열 매칭하면 항상 '없음'이 된다 → 등급 C 로 비운다.
            // 대신 이 몰은 질의를 에코하지 않아(echoesQuery=false) 토큰 0 판정을 쓸 수 있다.
            new ProbeTarget("onnuri-market",
                    "https://nurimarket.co.kr/shop/search_product.php?sq={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of(), List.of(),
                    false, 5, null, 0, "쌀", 0, MEASURED, ROBOTS),

            // robots.txt: 그누보드 계열 다수 Disallow 하나 /shop/search.php 는 목록에 없다
            // 없음 실측: `'zzqqxyw12345' 에 대한 0 개의 검색결과` — 건수를 명시해 가장 견고하다
            // 느리다 — 실측 5.2초, 결과가 많은 질의는 6초를 넘긴다(2026-08-31 게이트에서 타임아웃).
            // 커버리지가 큰 종합몰이라 빼지 않고 이 몰만 예산을 늘린다.
            new ProbeTarget("onnuri-gonggong-mall",
                    "https://www.ongong.kr/shop/search.php?stx={q}",
                    StandardCharsets.UTF_8, Scope.ONNURI_SCOPE,
                    List.of("'{q}' 에 대한 0 개의 검색결과"),
                    List.of(),
                    true, 5, null, 0, "쌀", 8000, MEASURED, ROBOTS),

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
                    false, 2, null, 0, "쌀", 0, MEASURED, ROBOTS)
    );

    public static Optional<ProbeTarget> byId(String platformId) {
        return ALL.stream().filter(t -> t.platformId().equals(platformId)).findFirst();
    }

    public static List<String> ids() { return ALL.stream().map(ProbeTarget::platformId).toList(); }

    private ProbeTargets() {}
}
