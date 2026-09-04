#!/usr/bin/env python3
"""test_robots_watch.py — 야간 배치 robots 감시 QA (2026-09-03, 단계 E)

실행: python3 _workspace/dev_scripts/test_robots_watch.py

**여기서 보는 것은 배치 쪽 로직뿐이다.** robots 규칙 해석(경로별 Allow·UA 그룹·최장 일치)은
앱이 하고 `RobotsRulesTest` 가 덮는다 — 그것을 여기서 다시 테스트하면 파서가 두 곳이 된다.
이 파일이 지키는 것은 배치가 앱의 판정을 **어떻게 옮기고, 무엇을 스스로 두드리고,
어제와 어떻게 비교하는가** 다.

바깥으로 요청을 보내지 않는다 — urlopen 을 가로채 응답을 흉내 낸다.
그래서 네트워크가 없어도 돌고, 실행 중 상대 사이트를 두드리지 않는다.

이 파일이 있는 이유: 2026-09-03 라운드에서 고친 것들(404·410 만 "파일 없음", 판정 등급 격리,
건너뛴 호스트를 "감시 이탈"로 읽지 않기, 어제 대비 비교, 중복 조회 0)은 **전부 조용히
되돌아갈 수 있는 종류**다. 같은 날 헬퍼 블록이 통째로 두 벌 들어가 실행이 죽었는데
함수는 각각 멀쩡했다 — 임시 스크립트로만 확인하면 다음 사람에게는 없는 것과 같다.
"""
import io
import json
import shutil
import sys
import tempfile
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "tools"))
import nightly_update as nu                        # noqa: E402

PASS = FAIL = 0


def check(cond, label, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  [PASS] " + label)
    else:
        FAIL += 1
        print("  [FAIL] " + label + (("\n         → " + str(detail)) if detail else ""))


# ── robots.txt 응답을 흉내 내는 가짜 urlopen ────────────────────────────────
class _Resp(io.BytesIO):
    def __init__(self, body, status=200, url=None):
        super().__init__(body.encode())
        self.status = status
        # 실제 urlopen 응답은 **리다이렉트를 따라간 최종 주소**를 `url` 로 준다.
        # 그것을 흉내 내야 "다른 호스트에서 온 것"을 시험할 수 있다.
        self.url = url

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class FakeNet:
    """열린 robots.txt 를 흉내 내고 **누가 몇 번 두드렸는지** 센다."""

    def __init__(self, bodies=None, codes=None, finals=None):
        self.bodies = bodies or {}
        self.codes = codes or {}
        self.finals = finals or {}          # host → 리다이렉트가 실제로 닿은 주소
        self.calls = []

    def __call__(self, req, timeout=None):
        url = req.full_url if hasattr(req, "full_url") else str(req)
        host = url.split("//", 1)[-1].split("/", 1)[0]
        self.calls.append(host)
        if host in self.codes:
            raise urllib.error.HTTPError(url, self.codes[host], "err", None, None)
        final = self.finals.get(host, url)      # 리다이렉트 최종 주소(기본은 요청 그대로)
        return _Resp(self.bodies.get(host, "User-agent: *\nAllow: /\n"), url=final)


def with_net(net, fn):
    orig = nu.urllib.request.urlopen
    nu.urllib.request.urlopen = net
    try:
        return fn()
    finally:
        nu.urllib.request.urlopen = orig


# ── 실제 응답 모양(2026-09-03 라이브: 조회 대상 18곳, 서로 다른 호스트 18개) ──
IDS = ["onnuri-hotdeal", "onnuri-chance", "onnuri-sijang", "onnuri-market",
       "onnuri-gonggong-mall", "epost-mall", "genius-mall", "onnuri-paldo-sijang",
       "hyundai-ezwel-onnuri", "onnuri-5iljang", "onnuri-shopping", "11st-onnuri-market",
       "onnuri-goodday", "inthemarket-onnuri", "gongyoung-shopping",
       "hyundai-home-shopping", "kkuk-ai-onnuri-mall", "lotte-on-sangsaeng-store"]
HOSTS = ["onnurideal.com", "onnurichance.com", "www.onnuri-mall.co.kr", "nurimarket.co.kr",
         "www.ongong.kr", "mall.epost.go.kr", "luxurysystem.co.kr", "e-jangter.com",
         "www.onnuri-sijang.com", "api.samaint.co.kr", "onnurishop.co.kr", "apis.11st.co.kr",
         "www.onnurigood.com", "inthemarket.co.kr", "www.gongyoungshop.kr", "www.hmall.com",
         "onnuri.ai", "www.lotteon.com"]
BLOCKED = {"onnuri-goodday", "inthemarket-onnuri", "hyundai-home-shopping",
           "lotte-on-sangsaeng-store", "gongyoung-shopping"}
INDEX_HOSTS = {"mall.noljang.co.kr": {"onnuri-noljang"},
               "tpirates.com": {"tpirates"}, "pub-api.tpirates.com": {"tpirates"}}


def live_report(**over):
    rep = {
        "probeEnabled": True, "passed": 18, "failed": 0, "skipped": 0, "cases": [],
        "robotsUserAgent": "onnuri-guide",
        "probeEndpoints": [{"platformId": i, "host": h, "path": "/s?q=Q"}
                           for i, h in zip(IDS, HOSTS)],
        "robots": [{"platformId": i, "allowed": i not in BLOCKED,
                    "rule": "Disallow: /" if i in BLOCKED else None,
                    "group": "*" if i in BLOCKED else None, "error": None} for i in IDS],
    }
    rep.update(over)
    return rep


def run_watch(rep, probe_on=True, index_on=True, net=None, out_dir=None, admin_key="dummy"):
    """`_robots_watch` 를 실제로 돌리되 색인 호스트는 고정한다(node 실행에 기대지 않는다).

    `admin_key` 를 비우면 "배치 설정 미비" 경로가 된다 — 그때만 데이터 폴백이 돈다.
    킬 스위치를 시험할 때 이걸 안 세우면 폴백 경로를 시험하게 되어 결과를 오독한다.
    """
    net = net or FakeNet()
    orig_idx, orig_key = nu._hosts_from_index, nu.APP_ADMIN_KEY
    nu._hosts_from_index = lambda: {h: set(v) for h, v in INDEX_HOSTS.items()}
    nu.APP_ADMIN_KEY = admin_key
    try:
        res = with_net(net, lambda: nu._robots_watch(out_dir, rep, probe_on, index_on))
    finally:
        nu._hosts_from_index, nu.APP_ADMIN_KEY = orig_idx, orig_key
    return res, net


# ═══════════════════════════════════════════════════════════════════════════
print("(a) 호스트 정규화 — 링크 주소든 조회 주소든 호스트만 남긴다")
check(nu._norm_host("https://apis.11st.co.kr/search/api/tab?kwd=Q") == "apis.11st.co.kr",
      "URL 에서 호스트")
check(nu._norm_host("PUB-API.tpirates.com") == "pub-api.tpirates.com", "호스트만 와도 · 소문자화")
check(nu._norm_host("https://www.gongyoungshop.kr:443/a") == "www.gongyoungshop.kr", "포트 제거")
check(nu._norm_host("") is None and nu._norm_host(None) is None, "빈 값은 None")
check(nu._norm_host("javascript:void(0)") is None, "http(s) 가 아니면 None")
check(nu._norm_host("그냥문자열") is None, "호스트 모양이 아니면 None")

print("(b) 앱 판정 옮기기 — 배치는 판정하지 않고 그대로 옮긴다")
p = nu._probe_robots_from_app(live_report())
check(len(p) == 18, "18행을 전부 옮긴다(상위 몇 건만이 아니다)", len(p))
check({v["host"] for v in p.values()} == set(HOSTS), "조회 호스트를 probeEndpoints 에서 짝짓는다")
check(p["11st-onnuri-market"]["host"] == "apis.11st.co.kr",
      "11번가는 조회 호스트(apis) — 이용자 링크 호스트(search)가 아니다")
check(p["onnuri-5iljang"]["host"] == "api.samaint.co.kr", "5일장은 본몰이 아니라 API 호스트")
check(p["lotte-on-sangsaeng-store"]["host"] == "www.lotteon.com",
      "롯데ON 은 몰 본체 — 단축주소(s.lotteon.com)가 아니다")
check(sum(1 for v in p.values() if not v["allowed"]) == 5, "차단 5곳")
check(all(v["grade"] == "app" for v in p.values()), "전부 등급 app")
check(nu._probe_robots_from_app(None) == {} and nu._probe_robots_from_app({}) == {},
      "응답이 없으면 빈 판정")

print("(b2) 반쪽 응답 — 앱이 구버전이라 한쪽만 와도 안전해야 한다")
only_ep = nu._probe_robots_from_app({"probeEndpoints": live_report()["probeEndpoints"]})
check(only_ep == {}, "probeEndpoints 만 오면 판정 없음(엔드포인트는 판정이 아니다)", len(only_ep))
only_rb = nu._probe_robots_from_app({"robots": live_report()["robots"]})
check(len(only_rb) == 18, "robots 만 와도 18행을 옮긴다", len(only_rb))
check(all(v["host"] is None for v in only_rb.values()),
      "짝지을 엔드포인트가 없으면 host 는 None — 지어내지 않는다")

print("(c) 중복 조회 0 — 앱이 판정한 호스트를 배치가 다시 가져오지 않는다")
res, net = run_watch(live_report())
app_hosts = {v["host"] for v in res["probe"].values() if v["host"]}
check(len(res["probe"]) == 18, "앱 판정 18건")
check(len(app_hosts) == 18, "서로 다른 호스트 18개")
check(sorted(set(net.calls) & app_hosts) == [], "배치가 두드린 호스트 ∩ 앱 판정 호스트 = 공집합",
      sorted(set(net.calls) & app_hosts))
check(sorted(set(net.calls)) == sorted(INDEX_HOSTS),
      "배치가 두드린 것은 색인 3호스트뿐", sorted(set(net.calls)))
check(len(net.calls) == 3, "그 회차 총 아웃바운드 3건(앱 판정 18곳은 0건)", len(net.calls))
check(len(net.calls) == len(set(net.calls)), "같은 호스트를 두 번 두드리지 않는다", net.calls)

print("(c2) 반쪽 응답에서도 아웃바운드가 늘지 않는다")
_, net_ep = run_watch(live_report(robots=[]))
check(len(net_ep.calls) == 3, "robots 가 비어도 색인 3건뿐 — 폴백으로 새지 않는다", net_ep.calls)
_, net_rb = run_watch(live_report(probeEndpoints=[]))
check(len(net_rb.calls) == 3, "probeEndpoints 가 비어도 색인 3건뿐", net_rb.calls)

print("(d) 꺼진 층은 두드리지 않는다 — 관측은 접촉을 따라간다")
OFF = {"probeEnabled": False, "probeEndpoints": live_report()["probeEndpoints"], "robots": []}
_, net_off = run_watch(OFF, probe_on=False, index_on=True)
check(sorted(set(net_off.calls)) == sorted(INDEX_HOSTS),
      "킬 스위치 OFF + 단계 F ON → 색인 3건만(그 층은 그날 실제로 크롤한다)", net_off.calls)
_, net_all_off = run_watch(OFF, probe_on=False, index_on=False)
check(net_all_off.calls == [], "둘 다 OFF → 아웃바운드 0건", net_all_off.calls)
res_off, _ = run_watch(OFF, probe_on=False, index_on=True)
check(res_off["probe"] == {}, "꺼진 회차에는 조회 대상 판정이 없다(비어 있음이 곧 '확인 안 함')")

print("(d2) 폴백은 어제 리포트에서 나온다 — 손으로 적는 목록이 없다")
tmp2 = tempfile.mkdtemp(prefix="onnuri-robots-fb-")
try:
    # 어제: 정상 회차라 조회 대상 18곳이 리포트에 남았다
    run_watch(live_report(), out_dir=tmp2)
    prev = sorted(Path(tmp2).glob("robots-*.json"))[0]
    # 파일명만 바꾸면 안 된다 — 폴백이 밝히는 날짜는 **리포트 내용의 date** 다(관측한 날).
    doc_prev = json.loads(prev.read_text(encoding="utf-8"))
    doc_prev["date"] = "2026-01-01"
    prev.with_name("robots-2026-01-01.json").write_text(
        json.dumps(doc_prev, ensure_ascii=False), encoding="utf-8")
    prev.unlink()
    # 오늘: 앱이 안 뜬다 → 어제 리포트의 조회 대상을 거칠게 본다
    _, net_fb = run_watch(None, probe_on=False, index_on=False, admin_key="", out_dir=tmp2)
    check(sorted(set(net_fb.calls)) == sorted(HOSTS),
          "어제 리포트의 조회 호스트 18곳을 폴백으로 본다", len(set(net_fb.calls)))
    check("apis.11st.co.kr" in net_fb.calls,
          "폴백도 **실제 조회 호스트**다 — 데이터의 링크 호스트가 아니다")
    check("search.11st.co.kr" not in net_fb.calls,
          "링크 호스트(search.11st)는 폴백에 들어오지 않는다 — 이번 라운드가 고친 병이다")
    doc_fb = json.loads(sorted(Path(tmp2).glob("robots-2026-09*.json"))[-1].read_text(encoding="utf-8"))
    check(doc_fb["fallbackFrom"] == "2026-01-01",
          "어느 날짜 회차를 폴백으로 썼는지 리포트가 밝힌다", doc_fb["fallbackFrom"])
finally:
    shutil.rmtree(tmp2, ignore_errors=True)

tmp3 = tempfile.mkdtemp(prefix="onnuri-robots-first-")
try:
    _, net_first = run_watch(None, probe_on=False, index_on=False, admin_key="", out_dir=tmp3)
    check(net_first.calls == [],
          "리포트가 하나도 없는 첫 실행은 아무것도 관측하지 않는다 — "
          "손으로 적은 목록으로 흉내 내는 것보다 '확인하지 않았다'가 정직하다", net_first.calls)
    doc = json.loads(sorted(Path(tmp3).glob("robots-*.json"))[0].read_text(encoding="utf-8"))
    check("폴백 없음" in (doc["probeSkipped"] or ""), "사유에 폴백이 없었다는 사실이 남는다",
          doc["probeSkipped"])
finally:
    shutil.rmtree(tmp3, ignore_errors=True)

print("(d3) 불변식 — 어떤 호스트도 한 회차에 두 번 조회되지 않는다")
# 오늘은 두 집합이 안 겹치지만 그건 우연이다. 서로 다른 몰이 한 도메인을 쓰면 그날부터 겹친다.
overlap_rep = live_report(
    probeEndpoints=[{"platformId": "some-mall", "host": "tpirates.com", "path": "/s?q=Q"}],
    robots=[{"platformId": "some-mall", "allowed": True, "rule": None, "group": None, "error": None}])
res_ov, net_ov = run_watch(overlap_rep)
check("tpirates.com" not in net_ov.calls,
      "앱이 판정한 호스트는 색인과 겹쳐도 배치가 다시 두드리지 않는다", net_ov.calls)
check(sorted(net_ov.calls) == ["mall.noljang.co.kr", "pub-api.tpirates.com"],
      "겹치지 않는 색인 호스트만 남는다", sorted(net_ov.calls))
app_h = {v["host"] for v in res_ov["probe"].values() if v["host"]}
check(set(net_ov.calls) & app_h == set(), "불변식: 배치 조회 ∩ 앱 판정 = 공집합")

print("(d4) 실시간 대상이 된 몰은 색인 크롤을 건너뛴다")
check(nu._realtime_ids(live_report()) == set(IDS), "robots[] 에서 실시간 대상 id 를 읽는다")
check(nu._realtime_ids({"probeEndpoints": [{"platformId": "x", "host": "h.com"}]}) == {"x"},
      "probeEndpoints 만 와도 읽는다")
check(nu._realtime_ids(None) == set() and nu._realtime_ids({}) == set(), "응답이 없으면 빈 집합")
keep, drop = nu._index_ids_to_crawl(["onnuri-noljang", "tpirates"], {"tpirates", "genius-mall"})
check(keep == ["onnuri-noljang"] and drop == ["tpirates"],
      "실시간이 된 몰만 빠지고 나머지는 크롤한다", (keep, drop))
keep, drop = nu._index_ids_to_crawl(["onnuri-noljang", "tpirates"], set())
check(keep is None and drop == [],
      "앱 응답이 없으면 아무것도 건너뛰지 않는다 — 모르면 하던 대로 "
      "(여기서 건너뛰면 앱이 안 뜬 날 색인이 통째로 비어 50% 가드에 걸린다)")
keep, drop = nu._index_ids_to_crawl(["onnuri-noljang", "tpirates"], {"onnuri-noljang", "tpirates"})
check(keep == [] and len(drop) == 2, "전부 실시간이 되면 크롤할 것이 없다(호출자가 스킵한다)")
keep, drop = nu._index_ids_to_crawl(["onnuri-noljang"], {"genius-mall"})
check(keep is None and drop == [], "겹치는 몰이 없으면 평소대로 전부 크롤")

print("(e) 관측 실패와 차단은 다르다 — 404·410 만 '파일 없음'이다")
net = FakeNet(codes={"mall.noljang.co.kr": 404, "tpirates.com": 403, "pub-api.tpirates.com": 301})
r = with_net(net, lambda: nu._robots_scan(sorted(INDEX_HOSTS)))
check(r["mall.noljang.co.kr"] == {"status": 404, "blocked_all": False, "bytes": 0},
      "404 는 금지 없음(파일이 없는 것이다)", r["mall.noljang.co.kr"])
check(r["tpirates.com"].get("error") == "HTTP 403" and "blocked_all" not in r["tpirates.com"],
      "403 은 판정이 아니라 관측 실패 — '금지 없음'으로 적지 않는다", r["tpirates.com"])
check(r["pub-api.tpirates.com"].get("error") == "HTTP 301",
      "리다이렉트 루프도 관측 실패(단축주소 호스트가 그렇다)", r["pub-api.tpirates.com"])
net410 = FakeNet(codes={"a.com": 410})
check(with_net(net410, lambda: nu._robots_scan(["a.com"]))["a.com"]["blocked_all"] is False,
      "410 도 파일 없음")
net_blocked = FakeNet(bodies={"a.com": "User-agent: *\nDisallow: /\n"})
check(with_net(net_blocked, lambda: nu._robots_scan(["a.com"]))["a.com"]["blocked_all"] is True,
      "`Disallow: /` 한 줄이면 전면 차단")

# 2026-09-05: **다른 호스트에서 온 것은 그 몰의 robots.txt 가 아니다.**
# 앱 쪽에서 실측으로 걸렸다 — 현대이지웰이 점검에 들어가며 robots.txt 요청을 다른
# 도메인의 HTML 안내 페이지로 302 보냈고, 그것을 robots 로 파싱해 '허용'이라 적고 있었다.
# 배치 스캐너도 urlopen 이 리다이렉트를 따라가므로 같은 함정이 있다. 관측 실패로 남긴다 —
# '금지 없음'으로도 '전면 차단'으로도 적지 않는다(ADR-21: 모르는 것을 어느 쪽으로도 세지 않는다).
net_moved = FakeNet(bodies={"a.com": "<!DOCTYPE html><html><title>점검 안내</title></html>"},
                    finals={"a.com": "https://maint.other.com/index.html"})
r_moved = with_net(net_moved, lambda: nu._robots_scan(["a.com"]))["a.com"]
check(r_moved.get("error") is not None,
      "robots 가 다른 호스트에서 오면 관측 실패로 남긴다", str(r_moved))
check("blocked_all" not in r_moved,
      "관측 실패를 차단 여부로 적지 않는다", str(r_moved))
check("other host" in str(r_moved.get("error", "")),
      "사유가 무슨 일인지 말한다", str(r_moved.get("error")))
# 같은 호스트 안에서 경로만 바뀌는 리다이렉트는 흔하다 — 그것까지 막으면 고장이다.
net_same = FakeNet(bodies={"a.com": "User-agent: *\nDisallow: /\n"},
                   finals={"a.com": "https://a.com/robots.txt?v=2"})
r_same = with_net(net_same, lambda: nu._robots_scan(["a.com"]))["a.com"]
check(r_same.get("blocked_all") is True and r_same.get("error") is None,
      "같은 호스트 안의 경로 이동은 그대로 읽는다", str(r_same))

print("(f) 어제와 비교 — 기준선은 손으로 적는 상수가 아니다")
now = nu._probe_robots_from_app(live_report())
chg, add, rm = nu._probe_delta(now, {**now, "11st-onnuri-market":
                                     {**now["11st-onnuri-market"], "allowed": False}})
check(chg == [("11st-onnuri-market", "허용", True, False)], "허용 → 차단", chg)
chg, _, _ = nu._probe_delta(now, {**now, "onnuri-goodday":
                                  {**now["onnuri-goodday"], "rule": "Disallow: /shop/"}})
check(chg and chg[0][1] == "근거 규칙",
      "같은 차단이라도 근거 규칙이 바뀌면 알린다(상대가 정책을 손본 것이다)", chg)
chg, _, _ = nu._probe_delta(now, {**now, "epost-mall":
                                  {**now["epost-mall"], "error": "timeout"}})
check(chg and "실패" in chg[0][1], "정상 → 관측 실패도 신호", chg)
check(nu._probe_delta({}, now) == ([], [], []), "첫 회차는 변화 없음(거짓 경고 방지)")
check(nu._probe_delta({"x": {"grade": "coarse"}},
                      {"x": {"grade": "app", "allowed": False, "rule": None, "error": None}})[0] == [],
      "등급이 다르면 비교하지 않는다 — 정밀 판정과 거친 판정을 맞대면 매번 거짓 변화가 난다")

print("(g) 거친 판정의 어제 비교 — 건너뛴 것을 '감시 이탈'로 읽지 않는다")
prev = {"a.com": {"blocked_all": False}, "b.com": {"blocked_all": False}}
check(nu._robots_delta(prev, {"a.com": {"blocked_all": True}}, skipped={"b.com"})[2] == [],
      "일부러 안 본 호스트는 빠진 것이 아니다")
check(nu._robots_delta(prev, {"a.com": {"blocked_all": False}}, skipped=set())[2] == ["b.com"],
      "정말 없어진 호스트는 빠졌다고 말한다")
check(nu._robots_delta(prev, {"a.com": {"blocked_all": True}})[0] == [("a.com", False, True)],
      "전면 차단 변화")
check(nu._robots_delta({"a.com": {"error": "x"}}, {"a.com": {"blocked_all": True}})[0] == [],
      "어느 한쪽이 관측 실패면 비교하지 않는다")
check(nu._robots_delta(None, {"a.com": {"blocked_all": True}}) == ([], [], []), "첫 회차")

print("(h) 안 본 이유는 성격이 다른 셋을 구분해 적는다")
_key = nu.APP_ADMIN_KEY
nu.APP_ADMIN_KEY = ""
check("no-admin-key" in nu._probe_skip_reason(None), "키 없음 = 배치 설정 미비")
nu.APP_ADMIN_KEY = "dummy"
check("app-unreachable" in nu._probe_skip_reason(None), "앱 응답 없음")
check("kill-switch-off" in nu._probe_skip_reason({"probeEnabled": False}),
      "킬 스위치 = 의도된 중단(운영사 요청으로 껐을 수 있다)")
check(len({nu._probe_skip_reason(None), nu._probe_skip_reason({"probeEnabled": False})}) == 2,
      "셋이 서로 다른 문구 — 뭉뚱그리면 로그에서 같아 보인다")
nu.APP_ADMIN_KEY = _key

print("(i) 리포트 — 판정 등급이 남아야 다음 사람이 두 값을 섞지 않는다")
tmp = tempfile.mkdtemp(prefix="onnuri-robots-test-")
try:
    res, _ = run_watch(live_report(), out_dir=tmp)
    files = sorted(Path(tmp).glob("robots-*.json"))
    check(len(files) == 1, "robots-YYYY-MM-DD.json 을 남긴다", [f.name for f in files])
    doc = json.loads(files[0].read_text(encoding="utf-8"))
    check(doc["robotsUserAgent"] == "onnuri-guide", "앱이 쓴 UA 를 기록한다")
    check({v["grade"] for v in doc["probe"].values()} == {"app"}, "조회 대상은 등급 app")
    check({v["grade"] for v in doc["coarse"].values()} == {"coarse"}, "색인은 등급 coarse")
    check(set(doc["grades"]) == {"app", "coarse"}, "등급 설명을 함께 남긴다")
    check(doc["probe"]["lotte-on-sangsaeng-store"]["rule"] == "Disallow: /",
          "차단은 근거 규칙과 함께 남는다")
finally:
    shutil.rmtree(tmp, ignore_errors=True)

print("(j) 색인 호스트의 출처는 크롤러다 — 배치가 도메인을 손으로 적지 않는다")
hosts = nu._hosts_from_index()
check(hosts != {}, "`index_nightly.js --print-hosts` 로 받아 온다(node 필요)", hosts)
if hosts:
    check(set(hosts) == set(INDEX_HOSTS),
          "크롤러가 말한 호스트와 이 테스트의 가정이 같다 — 어긋나면 한쪽이 낡은 것이다",
          f"크롤러 {sorted(hosts)} vs 가정 {sorted(INDEX_HOSTS)}")

print()
if FAIL:
    print(f"실패 {FAIL}건 / 전체 {PASS + FAIL}건")
    sys.exit(1)
print(f"전체 통과 ({PASS}건)")
