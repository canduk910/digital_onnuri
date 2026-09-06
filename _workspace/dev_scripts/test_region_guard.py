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
addr_sido = _mod.addr_sido
market_centers = _mod.market_centers
haversine_km = _mod.haversine_km
COORD_FAR_KM = _mod.COORD_FAR_KM
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

print("(e) 주소가 말하는 시도 — 읽지 못하면 판단하지 않는다")
for a, want, why in [
    ("서울특별시 종로구 …", "서울", "정식 표기"),
    ("부산광역시 부산진구 …", "부산", "정식 표기"),
    ("경기도 수원시 …", "경기", "도 이름"),
    ("경상남도 진주시 …", "타시도", "우리 밖 — 긴 이름이 먼저 걸려야 한다"),
    ("경상북도 …", "타시도", "'경상'으로 잘리면 안 된다"),
    ("대구광역시 달서구 …", "타시도", "우리 밖"),
    ("성남시 분당구 내정로174번길 42", None, "도 이름 없는 경기 주소 — 실제로 5건 있다"),
    ("안양시 만안구 냉천로 196", None, "같은 경우"),
    ("-", None, "주소가 비어 있는 것과 같다"),
    ("", None, "빈 문자열"),
    ("광주광역시 …", None, "'광주'는 경기 광주시와 겹쳐 사전에 없다 — 모르면 판단하지 않는다"),
]:
    check(addr_sido(a) == want, f"{a[:22]!r} → {want} ({why})", addr_sido(a))

print("(f) 시장 무리 — 이상치 하나가 무리를 통째로 빼면 안 된다")


def _row(lat, lng, market):
    return {"lat": lat, "lng": lng, "market": market, "name": "x"}
tight = [("부산", _row(35.15 + i * 0.001, 129.05 + i * 0.001, "가시장")) for i in range(8)]
# 한 곳만 멀리 — 최대-최소 폭으로 재면 이 무리가 통째로 빠지고, 정작 찾으려던 것이 사라진다
outlier = [("부산", _row(37.22, 127.22, "가시장"))]
c = market_centers(tight + outlier)
check(("가시장", "부산") in c, "이상치가 하나 있어도 무리는 기준으로 남는다 (중앙값 척도)")
if ("가시장", "부산") in c:
    d = haversine_km(37.22, 127.22, *c[("가시장", "부산")])
    check(d > COORD_FAR_KM, f"그 이상치는 임계를 넘는다 ({d:.0f}km > {COORD_FAR_KM:.0f}km)")

scattered = [("경기", _row(35.1 + i * 0.4, 126.5 + i * 0.4, "전국제도")) for i in range(8)]
check(("전국제도", "경기") not in market_centers(scattered),
      "흩어진 모임은 기준으로 쓰지 않는다 (「백년소상공인」 같은 지정 제도)")

few = [("서울", _row(37.5, 127.0, "작은시장")) for _ in range(4)]
check(("작은시장", "서울") not in market_centers(few), "표본이 5건 미만이면 기준으로 쓰지 않는다")

print("(i) 좌표를 비우는 데에 주소 일치를 요구하지 않는다 (2026-09-07 정정)")
# 처음에는 "주소가 배정과 일치할 때만" 비웠다. 그 조건 때문에 인천 지도에 대구 마커가,
# 경기 지도에 진주 마커가 그대로 남았고 사용자가 그것을 제보했다.
# 어느 신호가 옳든 그 마커는 틀리다 — addrCd 가 옳으면 좌표가 틀린 것이고,
# 주소가 옳으면 애초에 그 지역 지도에 있으면 안 된다. 그래서 조건을 뺐다.
_src = SRC.read_text(encoding="utf-8")
_i = _src.find("coord_cleared.append(")
_seg = _src[max(0, _i - 900):_i]
check("continue   # 주소가 다른 곳을 가리킨다" not in _seg,
      "주소가 달라도 좌표를 비우는 경로를 막지 않는다")
check("어느 신호가 옳든" in _src or "어느 쪽이 옳은지 정할 필요가 없다" in _src,
      "왜 조건을 뺐는지 코드가 설명한다")

# 실데이터: 좌표를 비운 것이 세 파일에 흩어져 있어야 한다(한 지역에만 있으면 조건이 남은 것)
import json as _j
_regions = []
for _f in sorted((ROOT / "data" / "merchants").glob("*.json")):
    _d = _j.loads(_f.read_text(encoding="utf-8"))
    _n = sum(1 for i in _d["items"] if i.get("lat") is None)
    if _n:
        _regions.append((_f.name, _n))
check(len(_regions) >= 2,
      "좌표를 비운 레코드가 두 지역 이상에 있다(주소 일치 조건이 남아 있으면 한 곳뿐이다)",
      _regions)

print("(g) 같은 날 두 번 재수집 가드")
import json as _json
import subprocess
import time as _time

_cache = ROOT / "_workspace" / "raw" / "capital_merchants_raw.json"
if not _cache.exists():
    print("  [skip] 캐시가 없어 가드를 시험할 수 없다")
else:
    _day = _json.load(open(_cache, encoding="utf-8")).get("collected_on")
    _r = subprocess.run([sys.executable, str(SRC), "--refresh", "--collected-on", _day],
                        capture_output=True, text=True, cwd=str(ROOT))
    check(_r.returncode == 4, "같은 날 재수집은 종료 코드 4로 막힌다", _r.returncode)
    check("--force-refresh" in _r.stderr, "빠져나갈 길을 함께 알려 준다")
    check("다음 날" in _r.stderr, "대가를 밝힌다 — 차단이 다음 날 배치까지 간다")
    # 강제 인자를 주면 막지 않는다. 실제 수집이 시작되므로 곧바로 끊는다 —
    # 여기서 끝까지 돌리면 이 테스트가 공식 API 를 두드리게 된다.
    _p = subprocess.Popen([sys.executable, str(SRC), "--refresh", "--force-refresh",
                           "--collected-on", _day],
                          stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                          text=True, cwd=str(ROOT))
    _time.sleep(4)
    _p.terminate()
    _err = _p.stderr.read()
    check("이미 재수집한 캐시" not in _err, "--force-refresh 는 막지 않는다")
    check("force-refresh" in _err, "강행한다는 사실을 경고로 남긴다")

print("(h) 배치가 가드의 종료 코드 4를 공식 API 실패로 적지 않는다")
_ny = (ROOT / "backend" / "tools" / "nightly_update.py").read_text(encoding="utf-8")
check("if r.returncode == 4:" in _ny, "종료 코드 4를 따로 가른다")
_i4 = _ny.find("if r.returncode == 4:")
_ig = _ny.find("if r.returncode != 0:", _i4)
check(0 < _i4 < _ig, "4 갈래가 일반 실패 갈래보다 먼저 온다 — 뒤에 있으면 영영 안 걸린다")
check("_mark_stale" not in _ny[_i4:_ig],
      "4 갈래에서는 중단 표시를 세우지 않는다 — 공식 API 실패가 아니다")
check("공식 API 실패가 아니" in _ny[_i4:_ig], "로그가 그 사실을 말한다")

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
