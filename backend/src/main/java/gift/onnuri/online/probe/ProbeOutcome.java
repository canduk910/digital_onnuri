package gift.onnuri.online.probe;

/**
 * 한 몰 조회의 결과. 본문을 받았으면 html, 못 받았으면 reason 이 채워진다.
 * 판정(ProbeJudge)은 html 이 있을 때만 돈다 — 왜 못 받았는지를 판정으로 뭉개지 않는다.
 */
public record ProbeOutcome(String html, String reason) {

    public static ProbeOutcome ok(String html) { return new ProbeOutcome(html, null); }
    public static ProbeOutcome fail(String reason) { return new ProbeOutcome(null, reason); }

    public boolean fetched() { return html != null; }

    public static final String TIMEOUT      = "timeout";
    public static final String HTTP_ERROR   = "http-error";
    public static final String BUSY         = "busy";          // 그 몰에 이미 요청이 진행 중
    public static final String RATE_LIMITED = "rate-limited";  // 몰 단위 한도 소진
    public static final String DISABLED     = "disabled";
}
