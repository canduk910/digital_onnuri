package gift.onnuri.online.probe;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

    /** 어느 몰에도 없을 문자열. 사람이 검색할 리 없는 형태여야 상대 로그도 오염시키지 않는다. */
    public static final String ABSENT_QUERY = "zzqqxyw12345";

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
            return new SelfTestReport(now, false, 0, 0, 0, 0, List.of());
        }
        for (ProbeTarget t : ProbeTargets.ALL) {
            cases.add(one(t, ProbeQuery.of(ABSENT_QUERY), SelfTestCase.ABSENT));
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
        return new SelfTestReport(now, true, cases.size(), passed, failed, skipped, cases);
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
        boolean echoed = ProbeJudge.toText(html).contains(q.normalized());

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
        if (absent && echoed != t.echoesQuery()) {
            note = (note.isEmpty() ? "" : note + " / ")
                    + "echoesQuery 선언(" + t.echoesQuery() + ")과 실측(" + echoed + ")이 다르다";
        }
        return new SelfTestCase(t.platformId(), q.normalized(), kind, expected,
                v.status(), ok, null, v.matchCount(), samples, html.length(),
                echoed, t.echoesQuery(), note);
    }
}
