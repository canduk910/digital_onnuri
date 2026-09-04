package gift.onnuri.online.probe;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "우리가 부른 그 몰에서 온 응답인가" 가드.
 *
 * 2026-09-05 라이브 카나리아가 잡은 것: 현대이지웰이 서비스 점검에 들어가면서
 * `www.onnuri-sijang.com/...` 요청을 `withus.ezwel.com/maintenance/index.html` 로
 * **302 리다이렉트**했다. 우리 수집기는 리다이렉트를 따라가므로 최종 응답은 200 이고
 * 8KB 짜리 정상 HTML 이다. 그런데 그 안에는 상품도, 없음-문구도, 질의어도 없다 —
 * 이 몰은 `echoesQuery=false` 라 **"질의어가 한 번도 안 나오면 없음"** 판정을 쓰는데,
 * 점검 안내 페이지가 그 조건을 완벽히 만족한다. 결과: **있는 질의를 '없음'으로** 봤다.
 * ADR-17 이 가장 위험하다고 적은 방향 그대로다.
 *
 * 판정 규칙을 아무리 정교하게 만들어도 **엉뚱한 페이지를 판정하면** 소용이 없다.
 * 그래서 판정 앞에서 막는다 — 최종 호스트가 요청 호스트와 다르면 '못 받았다'로 본다.
 *
 * 왜 정확히 같은 호스트를 요구하나: 2026-09-05 실측에서 조회 대상 18곳 중 17곳이
 * 요청 호스트에 그대로 머물렀고, 벗어난 곳은 점검 중인 이지웰 하나뿐이었다.
 * 어떤 몰이 나중에 www→m 처럼 정당하게 옮겨 가면 카나리아가 곧바로 알려 준다 —
 * 그때의 결과는 "확인하지 못했다"이지 "없다"가 아니므로 안전한 방향으로 틀어진다.
 */
class ProbeFetcherHostTest {

    @Test
    void 같은_호스트면_통과한다() {
        assertTrue(ProbeFetcher.sameHost(
                URI.create("https://www.ongong.kr/shop/search.php?stx=x"),
                URI.create("https://www.ongong.kr/shop/search.php?stx=x")));
        // 경로·쿼리·포트가 달라져도 호스트가 같으면 그 몰의 응답이다.
        assertTrue(ProbeFetcher.sameHost(
                URI.create("https://onnurideal.com/search?q=x"),
                URI.create("https://onnurideal.com:443/search?q=x&page=2")));
        // 대소문자는 호스트의 의미를 바꾸지 않는다.
        assertTrue(ProbeFetcher.sameHost(
                URI.create("https://Www.Ongong.KR/a"), URI.create("https://www.ongong.kr/b")));
    }

    @Test
    void 다른_호스트로_넘어가면_막는다() {
        // 실측 그대로 — 이지웰 점검 리다이렉트.
        assertFalse(ProbeFetcher.sameHost(
                URI.create("https://www.onnuri-sijang.com/onnuri/main/searchList?searchTerm=x"),
                URI.create("https://withus.ezwel.com/maintenance/index.html")));
        // 같은 회사의 다른 호스트도 막는다. 우리가 부른 곳이 아니면 판정의 전제가 깨진다.
        assertFalse(ProbeFetcher.sameHost(
                URI.create("https://www.hmall.com/a"), URI.create("https://m.hmall.com/a")));
    }

    @Test
    void 최종_주소를_모르면_막지_않는다() {
        // 알 수 없는 것을 근거로 실패를 만들지 않는다 — 모르면 종전 경로대로 판정한다.
        assertTrue(ProbeFetcher.sameHost(URI.create("https://www.ongong.kr/a"), null));
        assertTrue(ProbeFetcher.sameHost(null, URI.create("https://www.ongong.kr/a")));
    }
}
