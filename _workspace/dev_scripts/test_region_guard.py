#!/usr/bin/env python3
"""가맹점 수집기의 시·구 조합 가드 테스트 (2026-09-06 신설, F18)

경기 레코드는 `si` 를 공식 API 의 `addrCd` 에서, `gu` 를 주소 파싱에서 가져온다.
2026-09-01 에 그렇게 정한 이유는 인천 자치구 개편이 API 목록에는 반영됐는데 가맹점
주소에는 옛 이름이 다수 남아 있어서였다. 그 원칙은 지금도 옳다.

문제는 두 원천이 어긋나는 건이 생기면 **존재하지 않는 조합**이 조용히 만들어진다는
것이다 — 안양시에 팔달구는 없다. 값을 고치지는 않는다(주소를 믿어 si 를 덮어쓰면 위
원칙이 무너진다). 대신 수집기가 세어서 알린다.

    python3 _workspace/dev_scripts/test_region_guard.py
"""
import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "_workspace" / "dev_scripts" / "build_region_full.py"

_spec = importlib.util.spec_from_file_location("build_region_full", SRC)
_mod = importlib.util.module_from_spec(_spec)
try:
    _spec.loader.exec_module(_mod)
except SystemExit:      # 스크립트가 인자 없이 실행되면 종료할 수 있다 — 정의만 쓴다
    pass

si_gu_ok = _mod.si_gu_ok
BY_SI = _mod.GYEONGGI_GU_BY_SI
FLAT = _mod.GYEONGGI_GU

_pass = _fail = 0


def check(cond, label, extra=None):
    global _pass, _fail
    if cond:
        _pass += 1
        print(f"  [ok] {label}")
    else:
        _fail += 1
        print(f"  [FAIL] {label}" + (f" — {extra}" if extra is not None else ""))


print("(a) 화이트리스트를 시별로 나눠도 걸러 내는 집합은 그대로다")
# 2026-09-06 에 평탄한 집합을 시별 사전으로 바꿨다. 이 검사가 없으면 그 이관에서
# 구 하나가 빠져도 아무도 모르고, 그 구의 가맹점이 통째로 gu=None 이 된다.
EXPECTED = {
    "장안구", "권선구", "팔달구", "영통구", "수정구", "중원구", "분당구",
    "만안구", "동안구", "상록구", "단원구", "덕양구", "일산동구", "일산서구",
    "처인구", "기흥구", "수지구", "원미구", "소사구", "오정구",
}
check(FLAT == EXPECTED, "평탄화한 집합이 2026-09-01 목록과 같다",
      f"빠짐 {sorted(EXPECTED - FLAT)} · 새로 생김 {sorted(FLAT - EXPECTED)}")
check(sum(len(v) for v in BY_SI.values()) == len(FLAT),
      "시별 목록에 같은 구가 두 번 들어가 있지 않다")

print("(b) 조합 판정")
cases = [
    ("수원시", "팔달구", True,  "실재하는 조합"),
    ("안양시", "동안구", True,  "실재하는 조합"),
    ("안양시", "팔달구", False, "안양시에 팔달구는 없다 — 실제로 데이터에 있던 조합이다"),
    ("군포시", "동안구", False, "군포시에는 일반구가 없다"),
    ("성남시", "일산동구", False, "다른 시의 구"),
]
for si, gu, want, why in cases:
    check(si_gu_ok(si, gu) is want, f"{si} + {gu} → {want} ({why})")

print("(c) 모르는 것은 어긋난 것이 아니다 — 판단하지 않는다")
check(si_gu_ok(None, "팔달구") is True, "si 가 없으면 판단하지 않는다")
check(si_gu_ok("수원시", None) is True, "gu 가 없으면 판단하지 않는다(화이트리스트가 이미 거른 정상 경로)")
check(si_gu_ok(None, None) is True, "둘 다 없으면 판단하지 않는다")
check(si_gu_ok("", "팔달구") is True, "빈 문자열도 없는 것으로 본다")

print("(d) 실데이터에 적용 — 어긋난 것이 폭증하지 않는가")
# **고정 숫자로 적지 않는다.** 가맹점은 매일 새로 수집되므로 이 값은 움직인다
# (2026-09-06 실측 6건 / 30,021건 = 0.02%). 여기서 보는 것은 "갑자기 쏟아지지 않는가"다 —
# 쏟아진다면 수집기나 주소 파싱이 깨진 것이고, 그때는 값을 고칠 게 아니라 원인을 봐야 한다.
path = ROOT / "data" / "merchants" / "gyeonggi.json"
if not path.exists():
    print("  [skip] data/merchants/gyeonggi.json 이 없다")
else:
    doc = json.loads(path.read_text(encoding="utf-8"))
    items = doc["items"] if isinstance(doc, dict) and "items" in doc else doc
    bad = [i for i in items if not si_gu_ok(i.get("si"), i.get("gu"))]
    ratio = len(bad) / max(len(items), 1)
    print(f"       {len(items)}건 중 {len(bad)}건 ({ratio:.3%})")
    for b in bad[:8]:
        print(f"         {b.get('si')} {b.get('gu')}  {b.get('name')}  | {b.get('addr')}")
    check(ratio < 0.01, "어긋난 비율이 1% 미만이다", f"{ratio:.3%}")
    check(len(items) > 10000, "경기 레코드가 충분히 실려 있다(수집 실패분으로 재지 않는다)", len(items))

print()
if _fail:
    print(f"실패 {_fail}건 / 전체 {_pass + _fail}건")
    sys.exit(1)
print(f"전체 통과 ({_pass}건)")
