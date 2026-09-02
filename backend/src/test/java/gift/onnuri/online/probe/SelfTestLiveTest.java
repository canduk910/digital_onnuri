package gift.onnuri.online.probe;

import java.time.Clock;

import gift.onnuri.chat.RateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 6곳을 두드려 카나리아 기대치를 확인한다 — **평소에는 돌지 않는다.**
 *
 *   PROBE_LIVE=1 ./gradlew test --tests '*SelfTestLiveTest'
 *
 * CI 에서 자동으로 돌면 ①빌드가 남의 사이트 사정에 묶이고 ②푸시할 때마다 상대 사이트로
 * 나가는 요청이 늘어난다. 규칙 검증은 픽스처(ProbeJudgeTest)가 하고, 이 테스트는
 * 규칙을 손댔을 때 사람이 한 번 돌려 보는 확인 수단이다.
 */
@EnabledIfEnvironmentVariable(named = "PROBE_LIVE", matches = "1")
class SelfTestLiveTest {

    private SelfTestService live() {
        // 프로덕션 기본값과 같은 구성. 한도는 넉넉히 — 12건이 한도에 걸려 unknown 이 되면
        // "규칙이 깨졌다"와 구분이 안 된다.
        ProbeFetcher f = new ProbeFetcher(
                new RateLimiter(100, 1000, Clock.systemUTC()), true, 4000, 12, 1_000_000);
        return new SelfTestService(f, true);
    }

    @Test
    void 여섯곳_열두질의가_기대치와_일치한다() {
        SelfTestReport r = live().run();
        StringBuilder sb = new StringBuilder("\n");
        for (SelfTestCase c : r.cases()) {
            sb.append(String.format("  %-22s %-8s 기대=%-7s 실제=%-8s 샘플=%d 히트=%s 길이=%d %s%s%n",
                    c.platformId(), c.kind(), c.expected().isEmpty() ? "(없음)" : c.expected(),
                    c.actual(), c.sampleCount(), String.valueOf(c.matchCount()), c.bodyLength(),
                    c.ok() ? "OK" : "실패", c.note().isEmpty() ? "" : " — " + c.note()));
        }
        System.out.println(sb);
        assertTrue(r.probeEnabled(), "킬 스위치가 꺼져 있으면 카나리아는 확인한 것이 없다");
        assertEquals(ProbeTargets.ALL.size() * 2, r.total(),
                "몰당 2질의 — 대상이 늘면 건수도 따라 는다");
        assertEquals(0, r.failed(), "카나리아 실패:" + sb);
    }
}
