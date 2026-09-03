package gift.onnuri.online.probe;

/**
 * 우리가 **실제로 두드리는** 주소의 호스트·경로 (ADR-19 후속).
 *
 * 이용자 링크 호스트가 아니다 — 둘은 갈릴 수 있고 실제로 갈린다:
 *   11번가 조회 `apis.11st.co.kr` / 링크 `search.11st.co.kr`
 *   온누리5일장 조회 `api.samaint.co.kr` / 본몰이 아니다
 *   롯데ON 조회 `www.lotteon.com` / 링크는 단축주소 `s.lotteon.com`
 * robots 판정과 감시는 **조회 호스트** 기준이어야 뜻이 선다.
 *
 * <p><b>path 에는 쿼리스트링이 붙는다. 지우지 마라 — 판정이 뒤집힌다.</b>
 * robots 매칭은 경로와 쿼리를 함께 본다. 온누리굿데이·인더마켓의 조회 주소는 경로가 `/` 뿐이고
 * 검색 조건이 전부 쿼리에 있어서, 쿼리를 버리면 두 몰의 `Disallow: /` + `Allow: /$`
 * (루트만 연다) 중 **Allow 쪽에 걸려 차단이 허용으로 뒤집힌다.**
 * 2026-09-03 구현 중 실제로 그렇게 나왔고 쿼리를 붙여 바로잡았다.
 * `SelfTestContractTest.경로에_쿼리를_붙여야_robots_판정이_맞는다` 가 이 뒤집힘을 고정한다.
 *
 * <p>질의어는 담기지 않는다 — 검색어 자리는 이미 고정 토큰 `Q` 로 치환돼 있다.
 * 리포트가 배치 로그에 남지만 이용자가 무엇을 검색했는지는 실리지 않는다.
 */
public record ProbeEndpoint(String platformId, String host, String path) {}
