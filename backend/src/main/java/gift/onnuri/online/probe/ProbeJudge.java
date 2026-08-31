package gift.onnuri.online.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 응답 HTML 하나를 판정한다. **네트워크를 모른다** — 문자열 in, 판정 out.
 * NewsService.parse() 를 static 으로 뺀 것과 같은 이유다(네트워크 없이 테스트).
 *
 * 판정의 축은 실측에서 나온 비대칭이다(2026-08-31):
 *   "0이면 없다"는 신뢰할 수 있고, "0이 아니면 있다"는 신뢰할 수 없다.
 * 응답에 질의가 있다고 상품이 있는 게 아니다 — 제목·검색창에 되뿌려진 에코가 잡힌다.
 * 온누리굿데이는 "비스포크 로봇청소기 검색결과" 제목만으로 토큰 3종이 각 1회 걸렸다.
 */
public final class ProbeJudge {

    /** 에코가 실리는 자리. 몰별 CSS 셀렉터가 아니라 표준 태그만 본다(개편에 덜 취약). */
    private static final Pattern ECHO_BLOCKS = Pattern.compile(
            "(?is)<title[^>]*>.*?</title>"
                    + "|<h[1-3][^>]*>.*?</h[1-3]>"
                    + "|<footer[^>]*>.*?</footer>"
                    + "|<nav[^>]*>.*?</nav>"
                    + "|<input[^>]*>"
                    + "|<meta[^>]*>"
                    + "|<option[^>]*>.*?</option>");

    private static final Pattern SCRIPTISH = Pattern.compile(
            "(?is)<script[^>]*>.*?</script>|<style[^>]*>.*?</style>|<noscript[^>]*>.*?</noscript>");

    /**
     * 태그를 걷어내고 공백을 접는다.
     *
     * 반드시 태그 제거 후에 문구를 매칭해야 한다. `<span>등록된</span> 상품이 없습니다` 처럼
     * 문구가 태그로 쪼개져 있으면 원본 HTML 문자열 매칭은 실패한다 —
     * survey_probe.js 의 innerText→textContent 교훈(43→132개)과 같은 층위의 문제다.
     */
    public static String toText(String html) {
        if (html == null) return "";
        String s = SCRIPTISH.matcher(html).replaceAll(" ");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
             .replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** 에코가 실리는 태그를 먼저 걷어낸 텍스트. 토큰 카운트는 이걸로 센다. */
    public static String stripEcho(String html) {
        if (html == null) return "";
        String s = SCRIPTISH.matcher(html).replaceAll(" ");
        s = ECHO_BLOCKS.matcher(s).replaceAll(" ");
        return toText(s);
    }

    /**
     * 없음-문구 템플릿을 정규식으로 컴파일한다.
     *
     * {q} 자리는 토큰 사이 공백을 허용하는 유연 매처가 된다 — 온누리시장은
     * `' zzqqxyw12345 '` 처럼 따옴표 안쪽에 공백을 넣어서, 정확 일치로 잡으면 놓친다.
     * 리터럴 구간의 공백도 \s* 로 눅여 마크업이 만드는 공백 차이를 흡수한다.
     */
    public static Pattern bindQuery(String template, ProbeQuery q) {
        StringBuilder re = new StringBuilder();
        for (String part : template.split(Pattern.quote("{q}"), -1)) {
            if (re.length() > 0) {                       // {q} 자리
                String tokens = String.join("\\s*",
                        q.countTokens().stream().map(Pattern::quote).toList());
                re.append("\\s*").append(tokens).append("\\s*");
            }
            re.append(Pattern.quote(part).replace(" ", "\\E\\s*\\Q"));
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    /** 에코 제거 텍스트에서 토큰별 출현 횟수의 최댓값. noiseFloor 를 뺀 값이다. */
    static int maxTokenHits(String echoStripped, ProbeQuery q, int noiseFloor) {
        int max = 0;
        for (String t : q.countTokens()) {
            int n = 0, from = 0;
            while (true) {
                int i = echoStripped.indexOf(t, from);
                if (i < 0) break;
                n++; from = i + t.length();
                if (n > 500) break;
            }
            max = Math.max(max, n);
        }
        return Math.max(0, max - noiseFloor);
    }

    /**
     * 상품명 샘플. **판정 경로가 아니다** — 실패해도 결과는 "근거 없는 likely" 로
     * 품질만 떨어지고 오답이 되지 않는다. ADR-6 이 우려한 "구조 변경에 취약"을
     * 판정에서 격리하는 장치다. titlePattern 이 null 이면 그냥 빈 목록.
     */
    public static List<String> extractTitles(ProbeTarget t, String html, ProbeQuery q, int max) {
        if (t.titlePattern() == null || html == null) return List.of();
        // 후보를 먼저 다 모은다. 앞에서 max 개를 자르면 "청소기"만 맞는 이름이 먼저 걸려
        // "다이슨 청소기"를 통째로 담은 이름이 잘려 나간다(2026-08-31 라이브에서 그랬다).
        List<String> cand = new ArrayList<>();
        Matcher m = t.titlePattern().matcher(html);
        while (m.find() && cand.size() < 200) {
            String name = toText(m.groupCount() >= 1 ? m.group(1) : m.group());
            if (name.length() < 4 || name.length() > 80) continue;
            if (matchedTokens(name, q) > 0 && !cand.contains(name)) cand.add(name);
        }
        // 질의 낱말을 많이 담은 것부터. 같으면 관찰 순서를 지킨다(몰의 정렬을 뒤집지 않는다).
        cand.sort((a, b) -> Integer.compare(matchedTokens(b, q), matchedTokens(a, q)));
        return List.copyOf(cand.subList(0, Math.min(max, cand.size())));
    }

    private static int matchedTokens(String name, ProbeQuery q) {
        return (int) q.countTokens().stream().filter(name::contains).count();
    }

    /**
     * 샘플이 검색어의 **일부 낱말만** 담고 있는가.
     *
     * "다이슨 청소기"로 온누리공공몰을 조회하면 20건이 나오는데 **전부 '청소기'만 맞고
     * 다이슨은 하나도 없다**(2026-08-31 실측). 그대로 두면 화면은 "관련 상품이 검색됨"과
     * 함께 다이슨이 아닌 이름을 근거로 내밀고, 이용자는 "공공몰에 다이슨이 있다"로 읽는다.
     *
     * 판정(status)은 낮추지 않는다 — 샘플은 판정 경로 밖이라는 원칙을 지킨다.
     * 대신 화면이 "검색어 일부만 맞는 결과"라고 말할 수 있게 사실만 넘긴다.
     */
    static boolean samplesPartial(List<String> samples, ProbeQuery q) {
        if (samples.isEmpty() || q.countTokens().size() < 2) return false;
        int all = q.countTokens().size();
        return samples.stream().noneMatch(n -> matchedTokens(n, q) == all);
    }

    public static Verdict judge(ProbeTarget t, String html, ProbeQuery q) {
        if (html == null || html.isBlank()) return Verdict.unknown();

        String full = toText(html);
        // 응답은 200 인데 본문이 거의 없다 = SPA 전환 등 구조 변화 신호. 판정하지 않는다.
        if (full.length() < 500) return Verdict.unknown();

        String stripped = stripEcho(html);
        int hits = maxTokenHits(stripped, q, t.noiseFloor());
        List<String> samples = extractTitles(t, html, q, 3);

        // 등급 A — 질의가 박힌 문구. 약관·푸터가 구조적으로 걸릴 수 없어 단독 확정.
        for (String tpl : t.noneMarkersBound()) {
            Matcher m = bindQuery(tpl, q).matcher(full);
            if (m.find()) return Verdict.none(Verdict.HIGH, m.group().trim(), hits);
        }

        // 등급 B — 질의 비의존 문구. 상품 신호가 약할 때만 인정한다.
        for (String plain : t.noneMarkersPlain()) {
            if (full.contains(plain)) {
                if (hits < t.likelyThreshold() && samples.isEmpty()) {
                    return Verdict.none(Verdict.HIGH, plain, hits);
                }
                // 문구도 있는데 상품 신호도 강하다 = 몰이 배너를 상시 노출하도록 바뀐 신호.
                // 어느 쪽으로도 접지 않는다.
                return Verdict.unclear(hits);
            }
        }

        // 질의를 되뿌리지 않는 몰에서 토큰이 0이면 없는 것이다(온누리마켓·우체국).
        if (hits == 0 && !t.echoesQuery()) return Verdict.none(Verdict.MEDIUM, null, 0);

        // 상품명이 뽑혔으면 그게 카운트보다 강한 근거다.
        if (!samples.isEmpty()) return Verdict.likely(Verdict.MEDIUM, hits, samples, samplesPartial(samples, q));

        if (hits >= t.likelyThreshold()) return Verdict.likely(Verdict.LOW, hits, List.of(), false);

        // 1 ~ 임계 미만 = 에코일 가능성이 높은 구간. "없음"으로도 "있음"으로도 접지 않는다.
        return Verdict.unclear(hits);
    }

    private ProbeJudge() {}
}
