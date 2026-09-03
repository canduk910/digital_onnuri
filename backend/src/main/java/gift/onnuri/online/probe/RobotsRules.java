package gift.onnuri.online.probe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * robots.txt 파서·판정기 (ADR-19 후속). **네트워크를 모른다** — 텍스트 in, 판정 out.
 * ProbeJudge 를 static 으로 뺀 것과 같은 이유다(픽스처로 테스트).
 *
 * 이것이 앱에 있는 이유: 판정이 **경로**를 봐야 하는데, 우리가 실제로 두드리는 경로를 아는 곳은
 * `ProbeTargets` 뿐이다. 배치에 파서를 두면 그 경로를 손으로 옮겨 적게 되고,
 * 2026-08-31 에 감시 도메인을 손으로 적었다가 엉뚱한 사이트를 보고 있던 사고가 형태만 바꿔 되살아난다.
 *
 * 앞선 감시는 `Disallow: /` 한 줄만 봤다. 그러면 `Disallow: /` + `Allow: /plan/front/` 처럼
 * **경로별로 여는** robots 를 전면 차단으로 오독한다. 여기서는 표준대로 판정한다:
 *   ① UA 그룹 선택 — 우리 토큰에 맞는 것 중 **가장 구체적인**(긴) 그룹, 없으면 `*`
 *   ② 그 그룹 안에서 경로에 걸리는 규칙 중 **최장 일치**
 *   ③ 길이가 같으면 **Allow 우선**
 * 규칙이 하나도 안 걸리면 허용이다(robots 의 기본값).
 */
public final class RobotsRules {

    /** 판정 결과. rule 은 근거가 된 규칙 원문(없으면 null — 걸린 규칙이 없다는 뜻). */
    public record Decision(boolean allowed, String rule) {}

    private record Rule(boolean allow, String pattern) {}

    private final List<Rule> rules;
    private final String group;   // 어느 User-agent 그룹을 골랐는지(리포트용)

    private RobotsRules(List<Rule> rules, String group) {
        this.rules = rules;
        this.group = group;
    }

    /** 고른 UA 그룹 이름. 규칙이 없으면 null. */
    public String group() { return group; }

    /**
     * @param text      robots.txt 원문. null·빈 문자열이면 규칙 없음(= 전부 허용).
     * @param ourToken  우리 크롤러 제품 토큰(`onnuri-guide`). 그룹의 UA 값이 이 토큰에
     *                  대소문자 무시하고 포함되면 그 그룹이 우리를 가리킨다(표준 매칭).
     */
    public static RobotsRules parse(String text, String ourToken) {
        if (text == null || text.isBlank()) return new RobotsRules(List.of(), null);
        Map<String, List<Rule>> groups = new LinkedHashMap<>();
        List<String> current = new ArrayList<>();
        boolean sawRule = false;

        for (String raw : text.split("\\R")) {
            String line = raw;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String val = line.substring(colon + 1).trim();

            if ("user-agent".equals(key)) {
                // 규칙이 한 번 나온 뒤의 User-agent 는 **새 그룹의 시작**이다.
                if (sawRule) { current = new ArrayList<>(); sawRule = false; }
                current.add(val.toLowerCase(Locale.ROOT));
                groups.computeIfAbsent(val.toLowerCase(Locale.ROOT), k -> new ArrayList<>());
            } else if ("allow".equals(key) || "disallow".equals(key)) {
                if (current.isEmpty()) continue;      // 그룹 밖의 규칙은 버린다
                sawRule = true;
                // 빈 값은 규칙이 아니다 — `Disallow:` 만 있으면 "아무것도 막지 않는다"는 뜻이다.
                // 빈 패턴을 규칙으로 다루면 길이 0으로 모든 경로에 걸려 정반대 판정이 된다.
                if (val.isEmpty()) continue;
                for (String ua : current) {
                    groups.get(ua).add(new Rule("allow".equals(key), val));
                }
            }
        }

        // 우리를 가리키는 그룹 중 가장 구체적인(긴) 것. 없으면 `*`.
        String best = null;
        String token = ourToken == null ? "" : ourToken.toLowerCase(Locale.ROOT);
        for (String ua : groups.keySet()) {
            if ("*".equals(ua)) continue;
            if (!token.isEmpty() && token.contains(ua)
                    && (best == null || ua.length() > best.length())) {
                best = ua;
            }
        }
        if (best == null && groups.containsKey("*")) best = "*";
        if (best == null) return new RobotsRules(List.of(), null);
        return new RobotsRules(List.copyOf(groups.get(best)), best);
    }

    /**
     * 이 경로를 두드려도 되는가. 최장 일치, 동률이면 Allow 우선.
     * 어느 규칙에도 안 걸리면 허용 — robots 는 금지를 적는 파일이지 허가를 적는 파일이 아니다.
     */
    public Decision decide(String path) {
        String p = (path == null || path.isEmpty()) ? "/" : path;
        Rule best = null;
        for (Rule r : rules) {
            if (!matches(p, r.pattern())) continue;
            if (best == null
                    || r.pattern().length() > best.pattern().length()
                    || (r.pattern().length() == best.pattern().length() && r.allow())) {
                best = r;
            }
        }
        if (best == null) return new Decision(true, null);
        return new Decision(best.allow(),
                (best.allow() ? "Allow: " : "Disallow: ") + best.pattern());
    }

    /** `*`(임의 문자열)와 끝의 `$`(경로 끝)를 지원하는 접두 일치. 그 외 문자는 문자 그대로. */
    static boolean matches(String path, String pattern) {
        boolean anchored = pattern.endsWith("$");
        String pat = anchored ? pattern.substring(0, pattern.length() - 1) : pattern;
        StringBuilder re = new StringBuilder();
        for (String part : pat.split("\\*", -1)) {
            if (re.length() > 0) re.append(".*");
            re.append(Pattern.quote(part));
        }
        if (anchored) re.append("$");
        Matcher m = Pattern.compile(re.toString()).matcher(path);
        return m.find() && m.start() == 0;
    }

}
