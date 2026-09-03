#!/usr/bin/env python3
"""test_canary_trend.py — 카나리아 재시도 추세 감시 QA (2026-09-03, 단계 E)

실행: python3 _workspace/dev_scripts/test_canary_trend.py

**무엇을 지키나.** 앱은 무작위 absent 질의가 우연히 상품을 물면 새 말로 한 번 다시 묻는다.
그 재시도는 우연을 걸러 주지만 **가리기도 한다** — 어느 몰의 검색이 점점 느슨해져 무작위 낱말을
자주 물기 시작하면 재시도가 매번 통과시켜 주면서 FAIL 이 영영 안 뜬다. 그래서 통과한 재시도에도
note 가 붙고, 배치는 그 note 가 **잦아지는 것**을 본다.

한 회차의 1건은 정상이다. 문제는 **추세**다. 이 파일이 지키는 것은 그 경계 —
언제 알리고 언제 침묵하는가, 그리고 판단 근거가 리포트에 남는가.

**자동으로 아무것도 끄지 않는다**(ADR-17 이 기각한 조용한 축소). 리포트·로그까지다.
바깥으로 요청을 보내지 않는다 — 회차 데이터를 파일로 흉내 낸다.
"""
import json
import shutil
import sys
import tempfile
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


def case(pid, note=None, retried=None, ok=True):
    c = {"platformId": pid, "kind": "absent", "ok": ok, "expected": "none", "actual": "none",
         "sampleCount": 0, "bodyLength": 1000}
    if note is not None:
        c["note"] = note
    if retried is not None:
        c["retried"] = retried
    return c


# 앱이 실제로 쓰는 문구 그대로(2026-09-03 `SelfTestService`). 지어낸 예가 아니다 —
# 문구가 바뀌면 이 테스트가 먼저 깨져야 판별이 조용히 0건이 되는 것을 막는다.
RETRY_NOTE = "첫 질의 'zqkkwm' 가 걸려 새 말로 다시 물었다(그 몰 검색이 느슨하다)"

print("(a) 재시도 판별 — 구조화된 플래그가 있으면 그쪽을 쓴다")
ids, by = nu._retry_ids({"cases": [case("a", retried=True), case("b", retried=False)]})
check(ids == {"a"} and by == "flag", "`retried` 플래그를 우선한다", (ids, by))
ids, by = nu._retry_ids({"cases": [case("a", note=RETRY_NOTE), case("b", note="샘플 3건")]})
check(ids == {"a"} and by == "note", "플래그가 없으면 note 문구로 추론한다", (ids, by))
check(nu._retry_ids({"cases": [case("a", note="응답 길이 1,200")]})[0] == set(),
      "재시도와 무관한 note 는 세지 않는다")
check(nu._retry_ids({})[0] == set() and nu._retry_ids(None)[0] == set(), "응답이 없으면 빈 집합")
check(nu._retry_ids({"cases": [case("a", retried=True, note="아무 말")]})[1] == "flag",
      "플래그가 있으면 note 를 보지 않는다 — 문구가 바뀌어도 판별이 흔들리지 않는다")
# 문구 매칭은 앱이 말을 바꾸면 조용히 0건이 된다. 그래서 어느 방법으로 셌는지 리포트에 남긴다.
check(nu._retry_ids({"cases": [case("a", note="재시도했다")]})[1] == "note", "'재시도' 표지도 잡는다")
# 앱은 다른 note 와 " / " 로 이어 붙인다. 그때도 잡혀야 한다.
check(nu._retry_ids({"cases": [case("a", note=RETRY_NOTE + " / 샘플 3건")]})[0] == {"a"},
      "다른 note 와 이어 붙어도 잡는다")
check(nu._retry_ids({"cases": [case("a", note="echoesQuery 선언과 실측이 다르다")]})[0] == set(),
      "다른 note 를 재시도로 오인하지 않는다")

print("(b) 추세 — 한 회차의 1건은 정상, 문제는 반복이다")
hits, seen = nu._retry_trend([{"a"}, set(), {"a"}, set(), {"a"}, set(), set()])
check(seen == 7 and hits.get("a") == 3, "7회차 중 3번", (hits, seen))
hits, _ = nu._retry_trend([{"a"}, set(), set()])
check(hits.get("a") == 1, "한 번뿐이면 1로 센다(알림 기준은 호출자가 본다)")
hits, seen = nu._retry_trend([{"a"}, {"b"}])
check(hits == {} and seen == 2,
      "회차가 최소치보다 적으면 판단하지 않는다 — 표본 없이 추세를 말하면 새 서버에서 "
      "첫 주 내내 거짓 경고가 난다", (hits, seen))
hits, _ = nu._retry_trend([{"a", "b"}, {"a"}, {"a"}])
check(hits == {"a": 3, "b": 1}, "몰마다 따로 센다", hits)
check(nu._retry_trend([])[0] == {}, "회차가 없으면 빈 결과")
check(nu._retry_trend([None, {"a"}, {"a"}, {"a"}])[1] == 3, "빈 회차(None)는 세지 않는다")

print("(c) 임계값 — 상수가 뜻을 갖는다")
check(nu.RETRY_ALERT_MIN >= 2, "1회는 알리지 않는다(무작위 낱말이 가끔 걸리는 것은 정상)")
check(nu.RETRY_WINDOW >= nu.RETRY_ALERT_MIN, "창이 기준보다 넓다")
check(nu.RETRY_MIN_ROUNDS >= 2, "회차 하나로 추세를 말하지 않는다")

print("(d) 실제 회차 파일로 — 알림이 뜨는 경계")
def run_rounds(retry_days, today_retry, tmp):
    """지난 회차 리포트를 만들고 오늘 회차를 돌려 retryTrend 를 돌려준다."""
    for i, has in enumerate(retry_days):
        day = f"2026-08-{10 + i:02d}"
        cases = [case("onnuri-paldo-sijang", note=RETRY_NOTE if has else None)]
        (Path(tmp) / f"probe-canary-{day}.json").write_text(
            json.dumps({"cases": cases}, ensure_ascii=False), encoding="utf-8")
    rep = {"probeEnabled": True, "passed": 1, "failed": 0, "skipped": 0,
           "cases": [case("onnuri-paldo-sijang", note=RETRY_NOTE if today_retry else None)],
           "probeEndpoints": [], "robots": []}
    orig = nu._hosts_from_index
    nu._hosts_from_index = lambda: {}
    try:
        nu.stage_e_canary(tmp, index_on=False, app_rep=rep)
    finally:
        nu._hosts_from_index = orig
    files = sorted(Path(tmp).glob("probe-canary-2026-09*.json"))
    return json.loads(files[-1].read_text(encoding="utf-8"))["retryTrend"]

tmp = tempfile.mkdtemp(prefix="onnuri-canary-")
try:
    t = run_rounds([False, False, False, False, False, False], True, tmp)
    check(t["frequent"] == [], "6회 조용하다 오늘 1번이면 알리지 않는다", t)
    check(t["hits"].get("onnuri-paldo-sijang") == 1, "그래도 횟수는 센다(근거는 남긴다)", t["hits"])
finally:
    shutil.rmtree(tmp, ignore_errors=True)

tmp = tempfile.mkdtemp(prefix="onnuri-canary-")
try:
    t = run_rounds([True, False, False, False, False, False], True, tmp)
    check(t["frequent"] == [], "7회차 중 2번이면 아직 알리지 않는다", t)
    t2 = None
finally:
    shutil.rmtree(tmp, ignore_errors=True)

tmp = tempfile.mkdtemp(prefix="onnuri-canary-")
try:
    t = run_rounds([True, False, True, False, False, False], True, tmp)
    check(t["frequent"] == ["onnuri-paldo-sijang"], "7회차 중 3번이면 알린다", t)
    check(t["hits"]["onnuri-paldo-sijang"] == 3 and t["roundsSeen"] == 7,
          "판단 근거(몇 회차 중 몇 번)가 리포트에 남는다", t)
    check(t["alertMin"] == nu.RETRY_ALERT_MIN and t["window"] == nu.RETRY_WINDOW,
          "어떤 기준으로 판단했는지도 남는다 — 기준이 바뀌면 옛 리포트와 구분된다")
    check(t["countedBy"] == "note", "무엇으로 셌는지 남는다(플래그인지 문구인지)")
finally:
    shutil.rmtree(tmp, ignore_errors=True)

tmp = tempfile.mkdtemp(prefix="onnuri-canary-")
try:
    t = run_rounds([True], True, tmp)
    check(t["frequent"] == [] and t["roundsSeen"] == 2,
          "회차가 적으면 두 번 걸려도 알리지 않는다(표본 부족)", t)
finally:
    shutil.rmtree(tmp, ignore_errors=True)

print("(e) 창 밖 회차는 세지 않는다 — 오래된 일로 오늘을 경고하지 않는다")
tmp = tempfile.mkdtemp(prefix="onnuri-canary-")
try:
    # 아주 오래된 회차 5번 전부 재시도 + 최근 6회차는 조용
    t = run_rounds([True] * 5 + [False] * 6, False, tmp)
    check(t["roundsSeen"] <= nu.RETRY_WINDOW,
          f"최근 {nu.RETRY_WINDOW}회차만 본다", t["roundsSeen"])
    check(t["frequent"] == [], "창 밖의 옛 재시도는 오늘의 경고가 되지 않는다", t)
finally:
    shutil.rmtree(tmp, ignore_errors=True)

print()
if FAIL:
    print(f"실패 {FAIL}건 / 전체 {PASS + FAIL}건")
    sys.exit(1)
print(f"전체 통과 ({PASS}건)")
