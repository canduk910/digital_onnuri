package gift.onnuri.online.probe;

import java.util.List;

/**
 * 몰 한 곳의 조회 결과. 프론트가 그대로 그리는 계약이다(OnlineSearchContractTest 로 고정).
 *
 * searchUrl 은 **모든 상태에서 채운다**. 판정이 실패해도 이용자는 그 링크로 직접
 * 확인할 수 있어야 한다 — 실시간 조회가 안 되는 것이 이용자의 손해가 되면 안 된다.
 */
public record ProbeHit(
        String platformId,
        String name,
        String status,            // Verdict.NONE | LIKELY | UNCLEAR | UNKNOWN | NOT_PROBED
        String confidence,        // high | medium | low | null
        String reason,            // timeout | http-error | parse-changed | busy | rate-limited
                                  // | disabled | not-a-probe-target | null
        Integer matchCount,
        List<String> sampleTitles,
        boolean samplePartial,    // true = 샘플이 검색어의 일부 낱말만 담고 있다
                                  //   ("다이슨 청소기" → '청소기'만 맞는 이름들). 화면이 그렇게 말한다.
        String evidence,          // none 판정의 근거 문구 원문
        boolean mallWide,         // true = 온누리 결제 범위 밖 상품이 섞인다(기획전 딥링크)
        String searchUrl,
        String checkedAt          // 이 항목을 확인한 시각(캐시면 과거일 수 있다)
) {}
