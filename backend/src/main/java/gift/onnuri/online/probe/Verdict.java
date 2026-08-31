package gift.onnuri.online.probe;

import java.util.List;

/**
 * 한 몰에 대한 판정.
 *
 * status 에 "있음"이 없는 것이 의도다(ADR-17). 우리가 확인한 사실은
 * "그 몰 검색 결과에 이런 이름의 상품이 나왔다"까지이지 "그 상품이 있다"가 아니다.
 * 2026-08-31 실측에서 "로보락 큐레보" 질의에 "로보락 호환 리필 물걸레"가 걸렸다.
 */
public record Verdict(String status, String confidence, Integer matchCount,
                      List<String> sampleTitles, String evidence,
                      boolean samplePartial) {

    public static final String NONE       = "none";        // 그 몰이 결과 없음을 명시
    public static final String LIKELY     = "likely";      // 관련 상품이 검색됨(단정 아님)
    public static final String UNCLEAR    = "unclear";     // 판정 불가
    public static final String UNKNOWN    = "unknown";     // 타임아웃·오류
    public static final String NOT_PROBED = "not-probed";  // 조회 대상 아님

    public static final String HIGH = "high", MEDIUM = "medium", LOW = "low";

    public static Verdict none(String confidence, String evidence, int matchCount) {
        return new Verdict(NONE, confidence, matchCount, List.of(), evidence, false);
    }
    /** samplePartial = 샘플이 검색어의 일부 낱말만 담고 있다(ProbeJudge.samplesPartial). */
    public static Verdict likely(String confidence, int matchCount, List<String> samples,
                                 boolean samplePartial) {
        return new Verdict(LIKELY, confidence, matchCount, samples, null, samplePartial);
    }
    public static Verdict unclear(int matchCount) {
        return new Verdict(UNCLEAR, null, matchCount, List.of(), null, false);
    }
    public static Verdict unknown() {
        return new Verdict(UNKNOWN, null, null, List.of(), null, false);
    }
}
