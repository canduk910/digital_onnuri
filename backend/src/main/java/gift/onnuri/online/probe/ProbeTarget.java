package gift.onnuri.online.probe;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 몰 한 곳의 실시간 조회 규칙.
 *
 * 이 규칙은 ProbeJudge 의 분기 구조 그 자체라 DB 가 아니라 코드에 둔다(ADR-17).
 * DB 에 두면 "규칙 행은 새 값인데 배포된 파서는 옛 로직"인 상태가 만들어지고,
 * 그게 이 저장소가 반복해서 경계해 온 "에러 없이 다른 답"이다.
 *
 * 각 필드의 값에는 2026-08-31 실측 근거가 있다 — ProbeTargets 의 주석과
 * _workspace/19_online_probe.md 참조.
 */
public record ProbeTarget(
        String platformId,
        String searchUrlTemplate,      // {q} 자리에 인코딩된 질의
        Charset charset,
        Scope scope,
        List<String> noneMarkersBound, // 등급 A — {q} 를 포함해 단독으로 '없음' 확정
        List<String> noneMarkersPlain, // 등급 B — 토큰 대조를 함께 봐야 함
        boolean echoesQuery,           // 질의를 페이지에 되뿌리는가(true 면 히트 0 판정을 쓸 수 없다)
        int likelyThreshold,           // 이 이상이면 likely
        Pattern titlePattern,          // nullable — 상품명 샘플용. 판정 경로 아님
        int noiseFloor,                // 없는 질의에서도 관측된 히트(빼고 센다)
        String canaryPresentQuery,     // 그 몰에 확실히 있는 일반어 — 야간 자가점검용
        int timeoutMs,                 // 0 이면 전역 기본값. 느린 몰만 개별 지정한다
        LocalDate measuredOn,
        LocalDate robotsCheckedOn,
        String formBody                // null 이면 GET. 값이 있으면 POST(form) 로 이 본문을 보낸다.
) {

    /** formBody 없는 몰(대부분)을 위한 생성자 — GET 조회. */
    public ProbeTarget(String platformId, String searchUrlTemplate, Charset charset, Scope scope,
                       List<String> noneMarkersBound, List<String> noneMarkersPlain,
                       boolean echoesQuery, int likelyThreshold, Pattern titlePattern,
                       int noiseFloor, String canaryPresentQuery, int timeoutMs,
                       LocalDate measuredOn, LocalDate robotsCheckedOn) {
        this(platformId, searchUrlTemplate, charset, scope, noneMarkersBound, noneMarkersPlain,
             echoesQuery, likelyThreshold, titlePattern, noiseFloor, canaryPresentQuery,
             timeoutMs, measuredOn, robotsCheckedOn, null);
    }

    /** true 면 화면 HTML 이 아니라 몰의 내부 검색 API(JSON)를 부른다 — 이용자 링크로 쓰면 안 된다. */
    public boolean isApi() { return formBody != null || searchUrlTemplate.contains("{qq}"); }
    /** 조회 범위. MALL_WIDE 는 온누리 결제 범위 밖 상품이 섞인다(기획전 딥링크 몰). */
    public enum Scope { ONNURI_SCOPE, MALL_WIDE }

    public boolean mallWide() { return scope == Scope.MALL_WIDE; }
}
