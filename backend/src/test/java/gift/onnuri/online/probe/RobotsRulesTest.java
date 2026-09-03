package gift.onnuri.online.probe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * robots 판정 QA. 케이스는 **2026-09-03 실측한 robots.txt 원문**이거나
 * 그 실측에서 실제로 갈린 지점이다 — 지어낸 예가 아니다.
 *
 * 앞선 감시는 `Disallow: /` 한 줄만 봐서 두 가지를 틀렸다:
 *   ① 경로별로 여는 robots 를 전면 차단으로 오독
 *   ② 우리가 두드리지 않는 호스트를 보고 판정
 */
class RobotsRulesTest {

    private static final String US = ProbeFetcher.ROBOTS_TOKEN;

    private static RobotsRules.Decision decide(String txt, String path) {
        return RobotsRules.parse(txt, US).decide(path);
    }

    // ── 앞선 감시가 틀렸던 지점 ──────────────────────────────────────────

    @Test
    void 전면차단_아래의_경로_허용을_읽는다() {
        // 11번가 plan.11st.co.kr 실측 형태. `Disallow: /` 만 보면 전면 차단으로 오독한다.
        String txt = "User-agent: *\nDisallow: /\nAllow: /plan/front/\n";
        assertTrue(decide(txt, "/plan/front/exhibitions/2210481").allowed(),
                "경로별 Allow 를 못 읽어 열려 있는 경로를 막힌 것으로 봤다");
        assertFalse(decide(txt, "/product/1").allowed());
    }

    @Test
    void 최장일치가_이긴다() {
        String txt = "User-agent: *\nAllow: /a/\nDisallow: /a/b/\n";
        assertTrue(decide(txt, "/a/x").allowed());
        assertFalse(decide(txt, "/a/b/x").allowed(), "더 구체적인 Disallow 를 놓쳤다");
    }

    @Test
    void 길이가_같으면_Allow가_이긴다() {
        String txt = "User-agent: *\nDisallow: /x/\nAllow: /x/\n";
        assertTrue(decide(txt, "/x/y").allowed());
    }

    @Test
    void 빈_Disallow는_금지가_아니다() {
        // `Disallow:` 만 적으면 "아무것도 막지 않는다"는 뜻이다.
        // 빈 값을 규칙으로 다루면 길이 0으로 모든 경로에 걸려 정반대 판정이 된다.
        assertTrue(decide("User-agent: *\nDisallow:\n", "/anything").allowed());
    }

    @Test
    void 규칙이_없으면_허용이다() {
        // robots 는 금지를 적는 파일이지 허가를 적는 파일이 아니다.
        assertTrue(decide("", "/x").allowed());
        assertTrue(decide(null, "/x").allowed());
        assertNull(decide("", "/x").rule(), "걸린 규칙이 없으면 근거도 없다");
        // 온누리팔도시장 robots 404 · 온누리5일장 API 호스트도 404 였다.
    }

    // ── UA 그룹 선택 ────────────────────────────────────────────────────

    @Test
    void 우리를_지목한_그룹이_별표보다_우선한다() {
        String txt = "User-agent: *\nDisallow: /\n\nUser-agent: onnuri-guide\nAllow: /\n";
        assertTrue(decide(txt, "/search").allowed(), "우리 이름의 그룹을 못 골랐다");
    }

    @Test
    void 다른_봇을_지목한_그룹은_우리에게_적용되지_않는다() {
        // 현대이지웰 실측: `User-agent: Yeti / Allow: /` 만 있고 `*` 그룹이 없다 = 제약 없음.
        String txt = "User-agent: Yeti\nDisallow: /\n";
        assertTrue(decide(txt, "/onnuri/main/searchList").allowed(),
                "네이버 봇에게 한 말을 우리에게 적용했다");
        // 꾹AI 실측: AI 크롤러만 전면 차단하고 우리는 해당 없음.
        String kkuk = "User-agent: GPTBot\nDisallow: /\n\nUser-agent: *\nAllow: /\nDisallow: /api\n";
        assertTrue(decide(kkuk, "/search").allowed());
        assertFalse(decide(kkuk, "/api/x").allowed());
    }

    @Test
    void 그룹이_여럿_맞으면_더_구체적인_것을_고른다() {
        String txt = "User-agent: onnuri\nDisallow: /\n\nUser-agent: onnuri-guide\nAllow: /\n";
        assertTrue(decide(txt, "/x").allowed());
        assertEquals("onnuri-guide", RobotsRules.parse(txt, US).group());
    }

    // ── 실측 원문 ───────────────────────────────────────────────────────

    @Test
    void 굿데이_인더마켓_실측_원문() {
        // 2026-09-03 실측: `Disallow: /` + `Allow: /$` — 첫 화면만 열려 있다.
        String txt = "User-agent: *\nDisallow: /\nAllow: /$\n";
        assertTrue(decide(txt, "/").allowed(), "`Allow: /$` 는 루트만 연다");
        assertFalse(decide(txt, "/?pn=product.search.list").allowed());
    }

    @Test
    void 팔도시장_실측_원문은_대문자_경로만_막는다() {
        // 2026-09-03 실측: `disallow: /Goods/` — 키가 소문자여도 읽어야 한다.
        String txt = "User-agent: *\ndisallow: /Goods/\n";
        assertFalse(decide(txt, "/Goods/Content.aspx").allowed());
        assertTrue(decide(txt, "/goods/search.aspx").allowed(),
                "대소문자가 다른 경로까지 막힌 것으로 보면 사실과 다르다");
    }

    @Test
    void 공영쇼핑_실측_원문은_우리_조회_경로를_막는다() {
        // 2026-09-03 실측: `Allow: /` + `Disallow: /search/ /api/`.
        // 우리 조회 경로가 `/search/ajaxSearchGoodsList.do` 라 **막히는 쪽**이다 —
        // 이 사실이 리포트에 뜨는 것이 이번 수정의 목적이다.
        String txt = "User-agent: *\nAllow: /\nDisallow: /search/\nDisallow: /api/\n";
        RobotsRules.Decision d = decide(txt, "/search/ajaxSearchGoodsList.do");
        assertFalse(d.allowed());
        assertEquals("Disallow: /search/", d.rule(), "근거 규칙을 그대로 남겨야 한다");
    }

    @Test
    void 주석과_빈줄을_건너뛴다() {
        String txt = "# 주석\n\nUser-agent: *   # 뒤 주석\nDisallow: /x   # 경로\n";
        assertFalse(decide(txt, "/x/y").allowed());
        assertTrue(decide(txt, "/y").allowed());
    }

    @Test
    void 와일드카드와_끝앵커를_읽는다() {
        assertFalse(decide("User-agent: *\nDisallow: /*.pdf$\n", "/a/b.pdf").allowed());
        assertTrue(decide("User-agent: *\nDisallow: /*.pdf$\n", "/a/b.pdf.html").allowed());
        assertFalse(decide("User-agent: *\nDisallow: /*?\n", "/a?b=1").allowed());
    }

    // ── robots.txt 를 못 받았을 때 ───────────────────────────────────────

    @Test
    void 파일이_없다는_응답은_금지가_없다는_뜻이다() {
        // 404 와 410 둘 다. 배치·DEPLOY.md 가 그렇게 보고 있어 앱도 맞춘다 —
        // 같은 사실을 두 곳이 다르게 판단하는 것이 이 라운드가 없애려던 병이다.
        assertTrue(ProbeFetcher.robotsMissing(404));
        assertTrue(ProbeFetcher.robotsMissing(410));
    }

    @Test
    void 막는_응답을_금지_없음으로_바꿔_적지_않는다() {
        // RFC 9309 는 4xx 전체를 허용으로 봐도 된다(MAY)고 하지만 그렇게 넓히지 않는다.
        // 401·403 은 파일이 없다는 뜻이 아니라 **우리를 막는다**는 신호에 가깝다.
        // 그런 응답은 예외로 떨어져 error 로 남고, allowed 는 false 가 된다(모르면 모른다고 적는다).
        for (int s : new int[]{401, 403, 429, 451, 500, 503}) {
            assertFalse(ProbeFetcher.robotsMissing(s), s + " 를 '파일 없음'으로 봤다");
        }
    }
}
