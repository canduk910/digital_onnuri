package gift.onnuri.online.probe;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 실시간 조회 질의. 원문·정규화형·토큰을 함께 들고 다닌다.
 *
 * 정규화를 하는 이유는 두 가지다. 하나는 캐시 적중률을 올려 상대 사이트로 나가는 요청을
 * 줄이는 것이고(같은 뜻의 질의가 다른 키로 갈라지면 그만큼 아웃바운드가 는다), 다른 하나는
 * 길이·문자 제한으로 쓸모없는 조회를 애초에 막는 것이다.
 *
 * 토큰을 쪼개 두지만 각 몰에 쪼개서 보내지는 않는다 — 2026-08-31 실측에서 몰들이
 * "삼성전자 비스포크 로봇청소기" 같은 다중 토큰 질의를 그대로 받아 처리했다.
 * 토큰은 응답에서 히트를 셀 때만 쓴다.
 */
public record ProbeQuery(String raw, String normalized, List<String> tokens) {

    public static final int MIN_LEN = 2;
    public static final int MAX_LEN = 40;

    public static ProbeQuery of(String raw) {
        String r = raw == null ? "" : raw;
        // NFKC — 전각 영숫자·호환 문자를 통상형으로 모은다(ＬＧ → LG).
        String n = Normalizer.normalize(r, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        List<String> tokens = n.isEmpty() ? List.of()
                : Arrays.stream(n.split(" ")).filter(t -> t.length() >= MIN_LEN).toList();
        return new ProbeQuery(r, n, tokens);
    }

    /** 조회할 가치가 있는 질의인가. 너무 짧으면 아무 몰에서나 걸리고, 너무 길면 어차피 0건이다. */
    public boolean searchable() {
        return normalized.length() >= MIN_LEN
                && normalized.length() <= MAX_LEN
                && normalized.matches(".*[0-9A-Za-z가-힣].*");
    }

    /** 캐시 키. 대소문자만 접는다 — 한글은 소문자 개념이 없어 영향이 없다. */
    public String cacheKey() {
        return normalized.toLowerCase(Locale.KOREAN);
    }

    /** 히트를 셀 토큰. 토큰이 하나도 없으면(예: 한 글자씩만) 정규화형 전체를 쓴다. */
    public List<String> countTokens() {
        return tokens.isEmpty() ? List.of(normalized) : tokens;
    }
}
