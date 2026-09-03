package gift.onnuri.online.probe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import gift.onnuri.online.OnlinePlatformView;

/**
 * 전일 색인 층의 판정 (ADR-18). **DB·네트워크를 모른다** — 행 목록 in, 층 out.
 * ProbeJudge 를 static 으로 뺀 것과 같은 이유다(저장소 없이 테스트).
 *
 * 이 층이 하는 주장은 실시간 층과 다르다 — "어제 이 몰이 이 이름의 상품을 올려 두고
 * 있었다"이지 "지금 검색된다"가 아니다. 그래서 실시간 상태 목록(none/likely/…)에
 * `indexed` 를 더하지 않고 층을 나눴다. 섞으면 문구가 둘 중 하나에 대해 거짓이 된다.
 *
 * 낱말 분리는 {@link ProbeQuery#countTokens()} 를 그대로 쓴다 — 새 분리 규칙을 만들면
 * 실시간 층과 색인 층이 같은 검색어를 다르게 쪼개 서로 다른 답을 낸다.
 */
public final class IndexJudge {

    /** 몰 하나당 보여줄 상품명 샘플 수. 근거를 보이되 카드를 상품 목록으로 만들지 않는다. */
    private static final int MAX_SAMPLES = 3;

    /**
     * @param byId      화면에 그릴 수 있는 몰(active·shopping). 여기 없는 몰은 이름을 모르므로 뺀다.
     * @param summaries 몰별 색인 건수·수집일
     * @param rows      검색어 낱말을 하나라도 담은 후보 행(저장소가 예선한 것 — 판정은 여기서 한다)
     */
    public static IndexLayer build(ProbeQuery q,
                                   Map<String, OnlinePlatformView> byId,
                                   List<OnlineProductIndexRepository.Summary> summaries,
                                   List<OnlineProductIndexRepository.Row> rows) {
        // 실시간 조회 대상은 뺀다 — 한 몰이 두 층에서 다른 말을 하면 이용자는
        // 어느 쪽을 믿어야 할지 알 수 없다.
        Set<String> realtime = Set.copyOf(ProbeTargets.ids());
        Map<String, OnlineProductIndexRepository.Summary> eligible = new LinkedHashMap<>();
        for (var s : summaries) {
            if (s.rows() <= 0) continue;
            if (realtime.contains(s.platformId())) continue;
            if (!byId.containsKey(s.platformId())) continue;
            eligible.put(s.platformId(), s);
        }
        if (eligible.isEmpty()) return IndexLayer.empty();

        // asOf 는 **가장 오래된** 수집일이다. 가장 최근 날짜를 쓰면 "어제 기준"이라면서
        // 사흘 전 데이터를 섞어 말하게 된다.
        String asOf = eligible.values().stream()
                .map(OnlineProductIndexRepository.Summary::collectedOn)
                .filter(d -> d != null && !d.isBlank())
                .min(Comparator.naturalOrder()).orElse(null);

        List<String> tokens = q.countTokens();
        Map<String, List<String>> namesByMall = new LinkedHashMap<>();
        // 이름 → 상품 주소. 같은 이름이 여러 행이면 먼저 본 것을 쓴다(어느 쪽이든 그 상품에 닿는다).
        Map<String, Map<String, String>> urlByName = new LinkedHashMap<>();
        for (var r : rows) {
            if (!eligible.containsKey(r.platformId())) continue;
            if (r.name() == null || r.name().isBlank()) continue;
            String nm = r.name().trim();
            namesByMall.computeIfAbsent(r.platformId(), k -> new ArrayList<>()).add(nm);
            if (r.url() != null && !r.url().isBlank()) {
                urlByName.computeIfAbsent(r.platformId(), k -> new LinkedHashMap<>()).putIfAbsent(nm, r.url());
            }
        }

        List<IndexHit> items = new ArrayList<>();
        for (var e : namesByMall.entrySet()) {
            OnlinePlatformView p = byId.get(e.getKey());
            List<String> full = new ArrayList<>();
            List<String> partial = new ArrayList<>();
            for (String name : e.getValue()) {
                int m = matched(name, tokens);
                if (m == tokens.size()) full.add(name);
                else if (m > 0) partial.add(name);
            }
            // 전 낱말을 담은 이름이 있으면 그것만 근거로 쓴다. 없을 때만 일부 매치를
            // 보여 주되 "일부 낱말만 맞는다"고 밝힌다 — 밝히지 않으면 "다이슨 청소기"에
            // 'LG 청소기'를 내밀고 이용자는 그 몰에 다이슨이 있다고 읽는다.
            boolean hasFull = !full.isEmpty();
            List<String> pool = hasFull ? full : partial;
            if (pool.isEmpty()) continue;   // 아무 낱말도 안 걸린 몰은 그릴 게 없다
            List<String> shown = samples(pool, tokens);
            Map<String, String> urls = urlByName.getOrDefault(e.getKey(), Map.of());
            List<String> shownUrls = shown.stream().map(n -> urls.getOrDefault(n, "")).toList();
            items.add(new IndexHit(
                    e.getKey(), p.name(),
                    hasFull ? full.size() : 0,
                    shown,
                    !hasFull,
                    OnlineSearchService.searchUrlFor(null, p, q),
                    eligible.get(e.getKey()).collectedOn(),
                    shownUrls));
        }
        items.sort(Comparator.comparingInt(IndexHit::matchCount).reversed()
                .thenComparing(IndexHit::platformId));

        int found = (int) items.stream().filter(h -> h.matchCount() > 0).count();
        int partialMalls = items.size() - found;
        return new IndexLayer(asOf, eligible.size(), found,
                notice(eligible.size(), found, partialMalls, asOf), List.copyOf(items));
    }

    /** 질의 낱말을 많이 담은 것부터, 같으면 짧은 이름부터. 짧은 쪽이 화면에서 먼저 읽힌다. */
    private static List<String> samples(List<String> pool, List<String> tokens) {
        List<String> sorted = new ArrayList<>(new java.util.LinkedHashSet<>(pool));
        sorted.sort(Comparator.<String>comparingInt(n -> -matched(n, tokens))
                .thenComparingInt(String::length));
        return List.copyOf(sorted.subList(0, Math.min(MAX_SAMPLES, sorted.size())));
    }

    /** 대소문자만 접는다. 한글은 소문자 개념이 없어 영향이 없다(ProbeQuery.cacheKey 와 같은 처리). */
    private static int matched(String name, List<String> tokens) {
        String n = name.toLowerCase(Locale.KOREAN);
        int c = 0;
        for (String t : tokens) if (n.contains(t.toLowerCase(Locale.KOREAN))) c++;
        return c;
    }

    /**
     * 안내 문구. 실시간 층과 **말이 달라야 한다** — 이 층은 "가 볼 만한 곳"까지만 말하고
     * 지금 재고·결제 가능 여부를 확정하지 않는다.
     */
    static String notice(int platformCount, int foundCount, int partialMalls, String asOf) {
        if (platformCount == 0) return null;
        String stamp = (asOf == null ? "" : asOf) + " 수집 기준";
        if (foundCount > 0) {
            return "전일 색인: " + foundCount + "곳에서 검색어 전체를 담은 상품명을 찾았습니다("
                    + stamp + "). 어제 올라와 있던 이름이라, 지금 재고와 온누리 결제 가능 여부는"
                    + " 몰에서 확인하세요.";
        }
        if (partialMalls > 0) {
            return "전일 색인: 검색어 전체를 담은 상품명은 없고, 일부 낱말만 맞는 상품명이 "
                    + partialMalls + "곳에 있습니다(" + stamp + "). 찾는 상품이 아닐 수 있습니다.";
        }
        return "전일 색인 " + platformCount + "곳(" + stamp + ")에는 이 검색어를 담은 상품명이"
                + " 없습니다 — 색인에 없다는 뜻이지, 그 몰에 없다는 확정은 아닙니다.";
    }

    private IndexJudge() {}
}
