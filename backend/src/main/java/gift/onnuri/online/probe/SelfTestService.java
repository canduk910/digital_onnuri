package gift.onnuri.online.probe;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 판정 규칙이 아직 유효한지 스스로 확인한다 (ADR-17 6단계).
 *
 * **배치가 별도 파서를 갖지 않는다.** 여기서 실제 조회 경로(ProbeFetcher + ProbeJudge)를
 * 그대로 태우고, 배치는 curl 한 번만 한다. 배치에 파서를 두면 앱과 배치가 서로 다른 규칙으로
 * 판정하게 되고, 그때는 "누가 맞는지"를 사람이 매번 따져야 한다.
 *
 * 요청량: 몰당 2질의 × 6곳 = 하루 12건. 캐시를 쓰지 않는다 —
 * 캐시된 답을 다시 보는 것은 깨짐을 못 본다는 뜻이라 카나리아가 성립하지 않는다.
 *
 * 순차로 돈다. 12건뿐이고, 몰당 세마포어가 1이라 병렬로 해도 몰 안에서는 어차피 줄을 선다.
 * 대신 느린 몰(온누리공공몰 8초) 때문에 전체가 30초에 가까울 수 있다 — 배치 curl 타임아웃을
 * 넉넉히 잡는다(DEPLOY.md).
 */
@Service
public class SelfTestService {

    private static final Logger log = LoggerFactory.getLogger(SelfTestService.class);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    /**
     * 어느 몰에도 없을 검색어를 **회차마다 새로 만든다.**
     *
     * 고정 문자열(`zzqqxyw12345`)을 매일 보냈더니 **상대 몰의 인기 검색어에 우리 질의가 올라갔다**
     * — 2026-09-03 실측: 온누리굿데이 인기 검색어 10개 중 2위, 자동완성 배열에도 포함.
     * 그 몰을 쓰는 사람들 화면에 우리가 만든 낱말이 보이는 것이라 그 자체로 폐를 끼치는 일이고,
     * 부수적으로 판정도 흔든다(그 블록은 stripEcho 가 걷지 않는 본문이라 히트로 잡힌다).
     *
     * <p><b>고정 접두를 두지 않는다.</b> 처음에 `zq` + 소문자 8자로 만들었더니 라이브에서
     * 온누리팔도시장이 매일 실패했다 — 그 몰에서 **`zq` 자체가 386건을 무는 접두어**이고
     * (`zqh` 11건 · 그 회차 질의 `zqhaerxqaq` 7건), 검색이 정확 일치가 아니라 뒤에 무엇이
     * 붙느냐로 0이 되기도 7이 되기도 한다. 접두가 고정이면 **한 몰에서 걸린 날 매일 걸린다.**
     * 열 자를 전부 무작위로 뽑아 그 고리를 끊는다.
     *
     * <p>숫자는 넣지 않는다 — 상품 코드·수량(`1kg`)과 우연히 겹칠 수 있다.
     * 길이 10은 ProbeQuery 의 제한(2~40)을 넉넉히 지킨다.
     * 리포트에는 그 회차에 실제로 쓴 말이 SelfTestCase.query 로 남는다.
     *
     * <p>그래도 **우연히 걸리는 회차를 없앨 수는 없다** — 위 실측이 그 증거다.
     * 그것은 {@link #absentCase} 의 재시도가 맡는다.
     */
    static String absentQuery() {
        StringBuilder sb = new StringBuilder(10);
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < 10; i++) sb.append((char) ('a' + r.nextInt(26)));
        return sb.toString();
    }

    private final ProbeFetcher fetcher;
    private final boolean enabled;

    /** 동시에 두 번 돌면 아웃바운드가 곱절이 된다. 겹치면 뒤엣것을 거절한다. */
    private final java.util.concurrent.atomic.AtomicBoolean running =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public SelfTestService(ProbeFetcher fetcher,
                           @Value("${app.online.probe.enabled:true}") boolean enabled) {
        this.fetcher = fetcher;
        this.enabled = enabled;
    }

    public boolean tryLock()  { return running.compareAndSet(false, true); }
    public void    unlock()   { running.set(false); }

    public SelfTestReport run() {
        String now = STAMP.format(Instant.now());
        List<SelfTestCase> cases = new ArrayList<>();
        if (!enabled) {
            // 꺼져 있으면 "이상 없음"이 아니라 "확인하지 않았다"다. 통과로 눙치지 않는다.
            // 엔드포인트 목록은 그래도 채운다 — 상수에서 파생돼 네트워크가 필요 없고,
            // 배치가 도메인을 손으로 적지 않게 하는 것이 그 목적이라 꺼졌다고 사라지면 뜻이 없다.
            return new SelfTestReport(now, false, 0, 0, 0, 0, List.of(),
                    endpoints(), ProbeFetcher.ROBOTS_TOKEN, List.of());
        }
        for (ProbeTarget t : ProbeTargets.ALL) {
            cases.add(absentCase(t));
            cases.add(one(t, ProbeQuery.of(t.canaryPresentQuery()), SelfTestCase.PRESENT));
        }
        int passed = 0, failed = 0, skipped = 0;
        for (SelfTestCase c : cases) {
            if (c.expected().isEmpty()) skipped++;
            else if (c.ok()) passed++;
            else failed++;
        }
        if (failed > 0) {
            log.warn("실시간 조회 카나리아 실패 {}건 — 판정 규칙 점검 필요", failed);
        }
        return new SelfTestReport(now, true, cases.size(), passed, failed, skipped, cases,
                endpoints(), ProbeFetcher.ROBOTS_TOKEN, robots());
    }

    /**
     * 우리가 **실제로 두드리는** 호스트·경로. `ProbeTargets` 에서 파생한다 —
     * 데이터의 이용자 링크가 아니다(11번가는 조회 apis.11st.co.kr / 링크 search.11st.co.kr).
     * 질의어는 담지 않는다(리포트가 로그에 남는다) — 자리를 고정 토큰으로 바꾸고 쿼리는 버린다.
     */
    static List<ProbeEndpoint> endpoints() {
        List<ProbeEndpoint> out = new ArrayList<>();
        for (ProbeTarget t : ProbeTargets.ALL) {
            java.net.URI u = java.net.URI.create(
                    t.searchUrlTemplate().replace("{qq}", "Q").replace("{q}", "Q"));
            String path = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();
            // **쿼리까지 붙인다.** robots 매칭은 경로+쿼리를 본다 —
            // 굿데이·인더마켓은 조회 주소의 경로가 `/` 뿐이고 검색 조건이 전부 쿼리에 있다.
            // 경로만 보면 그 몰들의 `Allow: /$`(루트만 연다)에 걸려 **허용으로 잘못 읽힌다**
            // (2026-09-03 실측에서 실제로 그렇게 나왔다 — 붙이면 `Disallow: /` 로 뒤집힌다).
            // 질의어 자리는 이미 고정 토큰 Q 로 바뀌어 있어 이용자 검색어가 실리지 않는다.
            if (u.getRawQuery() != null && !u.getRawQuery().isBlank()) {
                path = path + "?" + u.getRawQuery();
            }
            out.add(new ProbeEndpoint(t.platformId(), u.getHost(), path));
        }
        return List.copyOf(out);
    }

    /**
     * 몰별 robots 판정. **호스트 단위로 한 번만 읽는다** — 같은 호스트를 여러 몰이 쓸 수 있고,
     * 하루 한 번 도는 배치가 같은 파일을 두 번 받을 이유가 없다.
     *
     * 판정 결과로 조회를 끄지 않는다(ADR-17 이 기각한 '조용한 축소'). 리포트와 로그까지다.
     */
    private List<RobotsCheck> robots() {
        Map<String, RobotsRules> byHost = new HashMap<>();
        Map<String, String> errByHost = new HashMap<>();
        List<RobotsCheck> out = new ArrayList<>();
        for (ProbeEndpoint e : endpoints()) {
            if (!byHost.containsKey(e.host()) && !errByHost.containsKey(e.host())) {
                try {
                    byHost.put(e.host(),
                            RobotsRules.parse(fetcher.fetchRobots(e.host()), ProbeFetcher.ROBOTS_TOKEN));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    errByHost.put(e.host(), "interrupted");
                } catch (Exception ex) {
                    errByHost.put(e.host(), ex.getClass().getSimpleName());
                }
            }
            String err = errByHost.get(e.host());
            if (err != null) {
                // 못 읽은 것을 "허용"으로 적지 않는다 — 모르면 모른다고 적는다.
                out.add(new RobotsCheck(e.platformId(), false, null, null, err));
                continue;
            }
            RobotsRules r = byHost.get(e.host());
            RobotsRules.Decision d = r.decide(e.path());
            out.add(new RobotsCheck(e.platformId(), d.allowed(), d.rule(), r.group(), null));
        }
        long blocked = out.stream().filter(c -> !c.allowed() && c.error() == null).count();
        if (blocked > 0) {
            log.warn("robots 가 막는 조회 경로 {}건 — 대상 목록 점검 필요", blocked);
        }
        return List.copyOf(out);
    }

    /**
     * 없는 질의를 '없음'으로 확정할 수단이 있는 몰인가.
     * 없음-문구 사전(등급 A·B)이 있거나, 질의를 되뿌리지 않아 토큰 0 판정을 쓸 수 있어야 한다.
     * 둘 다 없으면(onnuri-chance) 설계상 unclear 가 정답이므로 기대치를 세우지 않는다 —
     * 1단계에서 이 몰을 '결함'으로 오인했다가 '설계대로'로 정정한 그 지점이다.
     */
    static boolean canDecideAbsent(ProbeTarget t) {
        return !t.noneMarkersBound().isEmpty() || !t.noneMarkersPlain().isEmpty() || !t.echoesQuery();
    }

    /**
     * 없는 질의 한 건. **실패하면 새 무작위 말로 한 번 더 부른다.**
     *
     * 무작위 낱말이 어느 몰에 우연히 걸리는 일은 드물지 않다(2026-09-03 팔도시장 7건 실측).
     * 그런데 **서로 다른 두 낱말이 같은 몰에서 연달아 걸릴 확률은 훨씬 낮다.**
     * 그래서 두 번 다 실패할 때만 FAIL 로 올린다 — 이렇게 하면
     * "우리 말이 우연히 걸렸다"와 "없음-문구가 깨졌다"가 갈린다.
     *
     * 요청량은 실패한 건에서만 +1이라 평시에는 늘지 않는다.
     * 리포트에는 **두 질의를 모두** 남긴다 — 무엇을 보고 판단했는지가 남아야 한다.
     */
    SelfTestCase absentCase(ProbeTarget t) {
        String q1 = absentQuery();
        SelfTestCase first = one(t, ProbeQuery.of(q1), SelfTestCase.ABSENT);
        // 기대치가 없는 몰(onnuri-chance·롯데ON)은 재시도해도 판단이 달라지지 않는다.
        if (first.expected().isEmpty() || first.ok()) return first;

        SelfTestCase second = one(t, ProbeQuery.of(absentQuery()), SelfTestCase.ABSENT);
        String head = second.ok()
                // 통과했다 = 첫 낱말이 우연히 걸린 것이다. 그 몰 검색이 느슨하다는 신호이고,
                // 나중에 규칙을 볼 때 재료가 된다.
                ? "첫 질의 '" + q1 + "' 가 걸려 새 말로 다시 물었다(그 몰 검색이 느슨하다)"
                : "두 질의 모두 실패 — '" + q1 + "' 그리고 '" + second.query() + "'";
        return second.withNote(second.note().isEmpty() ? head : head + " / " + second.note());
    }

    private SelfTestCase one(ProbeTarget t, ProbeQuery q, String kind) {
        ProbeOutcome o = fetcher.fetch(t, q);
        boolean absent = SelfTestCase.ABSENT.equals(kind);
        String expected = absent ? (canDecideAbsent(t) ? Verdict.NONE : "") : Verdict.LIKELY;

        if (!o.fetched()) {
            // 못 받은 것은 규칙이 깨진 것과 다르다 — 사유를 남기고 실패로 센다(모르면 실패다).
            return new SelfTestCase(t.platformId(), q.normalized(), kind, expected,
                    Verdict.UNKNOWN, false, o.reason(), null, 0, 0, false, t.echoesQuery(),
                    "응답을 받지 못했다");
        }
        String html = o.html();
        Verdict v = ProbeJudge.judge(t, html, q);
        int samples = v.sampleTitles() == null ? 0 : v.sampleTitles().size();
        // **stripEcho 로 잰다.** echoesQuery 가 뜻하는 것은 "이 몰이 어딘가에 질의어를
        // 되뿌리는가"가 아니라 "**히트 0 판정을 쓸 수 없는가**"이고, 그 히트는 ProbeJudge 가
        // stripEcho(제목·메타·검색창 등 에코 자리를 걷어낸 본문) 위에서 센다.
        //
        // toText 로 재면 <title>·<meta> 에만 남는 에코까지 잡혀 **선언이 옳은 몰을 틀렸다고
        // 신고한다.** 2026-09-03 라이브에서 굿데이·인더마켓이 그렇게 걸렸다 — 두 몰의 에코는
        // 전부 <title>·<meta> 뿐이라(없는 질의 3종 × 2몰 = 6건 실측) 판정에는 닿지 않는다.
        // 그 신고를 믿고 선언을 true 로 바꿨다면 **쓸 수 있는 '없다' 확정 수단 하나를
        // 근거 없이 버리는 것**이 됐다. 카나리아가 규칙과 다른 것을 재고 있었다.
        boolean echoed = ProbeJudge.stripEcho(html).contains(q.normalized());

        boolean ok;
        String note;
        if (expected.isEmpty()) {
            ok = true;   // 기대치 없음 — skipped 로 따로 센다
            note = "없음을 확정할 수단이 없는 몰이라 기대치를 세우지 않는다(설계대로)";
        } else if (absent) {
            ok = Verdict.NONE.equals(v.status());
            note = ok ? "" : "없는 질의를 '" + v.status() + "'로 봤다 — 없음-문구 사전 점검";
        } else {
            // 샘플까지 요구한다. 상태만 보면 titlePattern 노후를 놓친다 —
            // 카운트만으로 likely 가 되어 화면에 근거 없는 '검색됨'이 남는다.
            boolean statusOk = Verdict.LIKELY.equals(v.status());
            ok = statusOk && samples >= 1;
            note = statusOk
                    ? (ok ? "" : "상품명 샘플이 없다 — titlePattern 노후 점검")
                    : "있는 질의를 '" + v.status() + "'로 봤다 — 문구 오탐 점검(가장 위험)";
        }
        // 선언과 실측이 갈라지면 토큰 0 판정의 전제가 무너진다. 실패로 세지는 않고 리포트에 남긴다.
        //
        // 다만 **'없다'를 확정할 수단이 아예 없는 몰은 대조하지 않는다**(onnuri-chance).
        // 그 몰의 `echoesQuery=true` 는 사실 주장이 아니라 **정책 선언**이다 —
        // 등급 C 라 없음-문구가 없고, 여기에 토큰 0 판정까지 열면 근거 없이 '없다'를 말하게 된다.
        // ADR-17 1단계가 "확정 수단이 없는 몰은 없다고 말하지 않는다"로 정한 그 자리이고,
        // 실측이 false 로 나와도 선언을 바꿀 일이 아니라서 매일 뜨는 note 는 소음일 뿐이다.
        // note 가 소음이 되면 사람이 note 를 통째로 무시하게 된다 — 카나리아가 죽는 길이다.
        //
        // 조건을 `canDecideAbsent` 로 잡은 것이 중요하다. 등급 C 여도 `echoesQuery=false` 인
        // 몰(onnuri-market)은 **그 선언이 곧 '없다'의 근거**라 사실이어야 하고, 계속 대조한다.
        if (absent && canDecideAbsent(t) && echoed != t.echoesQuery()) {
            note = (note.isEmpty() ? "" : note + " / ")
                    + "echoesQuery 선언(" + t.echoesQuery() + ")과 실측(" + echoed + ")이 다르다";
        }
        return new SelfTestCase(t.platformId(), q.normalized(), kind, expected,
                v.status(), ok, null, v.matchCount(), samples, html.length(),
                echoed, t.echoesQuery(), note);
    }
}
