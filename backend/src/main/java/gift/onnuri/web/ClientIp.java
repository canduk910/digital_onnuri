package gift.onnuri.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * rate limit 버킷 키로 쓸 클라이언트 IP 추출(2026-08-18, F-6 수정).
 *
 * <p><b>X-Forwarded-For의 마지막 값을 쓴다.</b> 첫 값은 클라이언트가 헤더에 그대로 실어 보낼 수
 * 있어 위조 가능하고, 그러면 매 요청이 다른 버킷으로 들어가 한도가 통째로 무력화된다.
 * 프록시는 받은 XFF <i>뒤에</i> 자기가 본 TCP 상대 주소를 덧붙이므로 마지막 값만이 위조 불가다.
 *
 * <p><b>전제: 신뢰 프록시 1홉.</b> 프로덕션은 Caddy가 최전선이고(deploy/Caddyfile —
 * {@code reverse_proxy app:8080}, XFF 재작성 없음), 앱 컨테이너는 {@code expose}만 있어
 * Caddy를 우회해 직접 닿을 수 없다. 로컬처럼 프록시가 없으면 XFF가 없어 remoteAddr로 떨어진다.
 *
 * <p>⚠ 앞단에 CDN·LB를 <i>추가로</i> 두면 마지막 값이 그 중계자 IP가 되어 모든 이용자가 한 버킷을
 * 공유한다(정상 이용자가 서로의 한도에 걸린다). 홉이 늘면 이 클래스와 Caddy의 trusted_proxies를
 * 함께 다시 정해야 한다.
 */
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest http) {
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] hops = xff.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (!hop.isEmpty()) return hop;
            }
        }
        return http.getRemoteAddr();
    }
}
