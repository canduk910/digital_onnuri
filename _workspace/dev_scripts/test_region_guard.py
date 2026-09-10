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

print("(j) 회차 관측 — 400 이 났을 때 무엇이 남는가 (2026-09-10 신설)")
# 09-08~10 사흘 연속 400 인데 로그에 traceback 밖에 없어 원인을 좁힐 수 없었다.
# 여기서 지키는 계약은 **가짜 HTTP 서버**로만 시험한다 — 공식 API 에 요청이 나가지 않는다.
import threading, http.server, contextlib, io as _io, time as _time

_body_none = json.dumps({"resCode": "0000", "data": {"totalPage": 1, "list": []}}).encode()
_body_400 = json.dumps({"resCode": "9998", "resMsg": "접근 권한이 없습니다"},
                       ensure_ascii=False).encode("utf-8")


class _H(http.server.BaseHTTPRequestHandler):
    n = 0
    def log_message(self, *a):
        pass
    def do_POST(self):
        _H.n += 1
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        blocked = _H.n == 3
        b = _body_400 if blocked else _body_none
        self.send_response(400 if blocked else 200)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Cache", "MISS")
        if blocked:
            self.send_header("Set-Cookie", "sess=SECRETVALUE123; Path=/")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)


_srv = http.server.HTTPServer(("127.0.0.1", 0), _H)
threading.Thread(target=_srv.serve_forever, daemon=True).start()
_base = f"http://127.0.0.1:{_srv.server_address[1]}/api/v3/onrgt/place/search"

_saved_throttle = _mod.THROTTLE_SEC
_mod.THROTTLE_SEC = 0.0
_mod._T0 = _time.time()
_raised = None
try:
    for _i in range(5):
        _mod.DIAG["cursor"] = {"gridIndex": _i + 1, "cell": [10, 20],
                              "lat": 35.05, "lng": 129.0, "page": 1}
        _mod.post(_base, {"latitude": "35.05", "longitude": "129.0"})
except Exception as _e:
    _raised = _e
with contextlib.redirect_stderr(_io.StringIO()):
    _d = _mod._diag_finish(False, "테스트")
_mod.THROTTLE_SEC = _saved_throttle
_srv.shutdown()

check(type(_raised).__name__ == "HTTPError", "계측이 원래 오류를 삼키지 않는다")
_f = _d.get("failure") or {}
check(_f.get("reqNo") == 3, "**몇 번째 요청**에서 죽었는지 남는다 — 지금까지 없던 것", _f.get("reqNo"))
check("접근 권한이 없습니다" in (_f.get("responseHead") or ""),
      "400 의 응답 본문이 남는다 — 상대가 말한 이유를 처음으로 듣는다")
check((_f.get("at") or {}).get("lat") == 35.05,
      "실패 지점이 번호만이 아니라 **좌표로도** 남는다(격자가 바뀌면 번호는 뜻이 달라진다)")
check((_d.get("firstOk") or {}).get("headers", {}).get("X-Cache") == "MISS",
      "정상 200 의 헤더가 기준선으로 남는다 — 없으면 400 헤더를 해석할 수 없다")
check("SECRETVALUE123" not in json.dumps(_d),
      "쿠키 **값**은 파일에 남기지 않는다(존재 여부만) — 리포트가 서버 디스크에 남는다")
check(_d["config"]["throttleSec"] == _saved_throttle or "throttleSec" in _d["config"],
      "설정 스냅샷이 항상 실린다 — 없으면 0.7→2.0 을 모르는 사람이 추세를 오독한다")
check(_d["grid"] == {} or "sha" in _d.get("grid", {}),
      "격자 지문 자리가 있다(collect 를 거치면 채워진다)")

# 성공한 밤에도 리포트를 써야 한다 — 기준선은 성공 회차에서만 얻어진다.
_src = (ROOT / "backend" / "tools" / "nightly_update.py").read_text(encoding="utf-8")
check("--diag-out" in _src, "야간 배치가 수집기에 리포트 경로를 준다")
check("_diag_crosscheck" in _src, "단계 B 결과를 A 리포트에 이어 쓴다(요청 추가 0건)")
_i_ok = _src.find("_diag_crosscheck(True")
_i_skip = _src.find("_diag_crosscheck(False")
check(_i_ok > 0 and _i_skip > 0, "단계 B 의 성공·스킵 두 갈래 모두에서 기록한다")
_bs = (ROOT / "_workspace" / "dev_scripts" / "build_region_full.py").read_text(encoding="utf-8")
check("_diag_write(args.diag_out)" in _bs.split("except BaseException")[0]
      or _bs.count("_diag_write(args.diag_out)") >= 2,
      "성공 경로에서도 리포트를 쓴다 — 실패 전용이면 성공한 밤에 아무 증거도 안 남는다")

print("(k) 셀 단위 재시도·스킵·이어받기 (2026-09-11 사용자 결정)")
# 09-11 계측이 보여 준 것: 실패는 격자 9번 **한 지점**이었고 상대가 낸 것은
# HTTP 500 · resCode 9999(시스템 오류)였다. 그런데 그 한 셀 때문에 1,267 셀이 통째로
# 죽는다. 셀을 건너뛰되 **그 안의 가맹점은 지우지 않는다**(그 셀 중앙값이 77곳이다).
# 전부 가짜 HTTP 서버로만 시험한다 — 공식 API 에 요청이 나가지 않는다.
import http.server as _hs, tempfile as _tf


def _serve(fn):
    class _H(_hs.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass
        def do_POST(self):
            b = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            code, payload = fn(b)
            raw = json.dumps(payload, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
    srv = _hs.HTTPServer(("127.0.0.1", 0), _H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def _ok(body):
    lng = body.get("longitude", "0")
    return 200, {"resCode": "0000", "data": {"totalPage": 1, "list": [
        {"frCd": f"F{lng}", "frcsNm": "가게", "frcsAddr": "부산광역시 강서구 x",
         "addrCd": "26000", "latitude": 35.05, "longitude": float(lng),
         "placeTypeNm": "가공식품", "mrktNm": "", "mrktType": "",
         "paperYn": "Y", "cardYn": "Y", "qrYn": "Y"}]}}


def _boom(body):
    return 500, {"resCode": "9999", "resMsg": "시스템에 오류가 발생하였습니다.", "data": None}


def _collect_with(handler, grid_n=40, prev_rows=()):
    """가짜 서버를 세우고 collect() 를 돌린다. (캐시dict, stderr) 를 돌려준다."""
    _spec2 = importlib.util.spec_from_file_location("brf_t", SRC)
    mm = importlib.util.module_from_spec(_spec2)
    try:
        _spec2.loader.exec_module(mm)
    except SystemExit:
        pass
    srv = _serve(handler)
    mm.API_SEARCH = f"http://127.0.0.1:{srv.server_address[1]}/x"
    mm.THROTTLE_SEC = 0.0
    mm.CELL_RETRY_BACKOFF = (0.0, 0.0)
    mm._T0 = _time.time()
    mm.load_districts = lambda: {"26000": ("강서구", "부산")}
    mm.load_grid = lambda: ({(1389, 4185)}, [(1389, 4180 + i) for i in range(grid_n)])
    mm.save_grid = lambda seed, rows: None
    mm.CACHE = Path(_tf.mkdtemp()) / "c.json"
    mm.CACHE.write_text(json.dumps({"collected_on": "2026-09-07", "rows": list(prev_rows)},
                                   ensure_ascii=False), encoding="utf-8")
    buf = _io.StringIO()
    out = exc = None
    with contextlib.redirect_stderr(buf):
        try:
            out = mm.collect("2026-09-12")
        except Exception as e:      # noqa: BLE001
            exc = e
    srv.shutdown()
    return out, exc, buf.getvalue(), mm


_BADLNG = "128.969167"          # 09-11 에 실제로 터진 격자 9번의 경도
_PREV = [{"frCd": f"OLD{i}", "frcsNm": f"기존{i}", "frcsAddr": "부산광역시 강서구 y",
          "addrCd": "26000", "latitude": 35.0505, "longitude": 128.9692,
          "placeTypeNm": "가공식품", "mrktNm": "", "mrktType": "",
          "paperYn": "Y", "cardYn": "Y", "qrYn": "Y"} for i in range(96)]

# ① 한 셀만 계속 실패 — 스킵하되 그 안의 가맹점은 이어받는다
_hits = {"n": 0}
def _one_bad(body):
    if body.get("longitude") == _BADLNG:
        _hits["n"] += 1
        return _boom(body)
    return _ok(body)

_out, _exc, _log, _m = _collect_with(_one_bad, prev_rows=_PREV)
check(_exc is None, "셀 하나가 죽어도 회차 전체가 죽지 않는다")
check(_hits["n"] == 3, "셀당 3번 시도한다(첫 시도 + 재시도 2회)", _hits["n"])
_carried = [r for r in (_out or {}).get("rows", []) if r["frCd"].startswith("OLD")]
check(len(_carried) == 96,
      "**스킵한 셀의 가맹점을 지우지 않는다** — 이전 수집분을 그대로 이어받는다", len(_carried))
check(len([r for r in _out["rows"] if r["frCd"].startswith("F")]) == 39,
      "나머지 셀은 정상적으로 새로 수집된다")
check("스킵한 셀 1개" in _log, "스킵한 셀 **개수**가 로그에 남는다(사용자 요청)")
check("2026-09-07" in _log, "어느 회차 수집분을 이어받았는지 로그가 말한다")
check(json.loads(_m.CACHE.read_text(encoding="utf-8")).get("skipped_cells"),
      "스킵 내역이 캐시에도 남아 다음 회차가 추적할 수 있다")

# ①-b 이어받기 기준은 '셀 안'이 아니라 **그 셀 조회가 덮었을 반경 2km** 다.
#     격자 색인은 경도 폭을 위도로 나누는데 _cell() 은 점의 위도를, _center() 는 행의 중앙
#     위도를 쓴다 — 어긋나서 '셀 안'과 '조회로 나옴'이 다른 집합이 된다.
#     실측(79,800곳): 덮는 셀이 하나뿐인 가맹점 37,111곳 중 **9,797곳은 자기 셀이 아닌
#     이웃 셀 조회로만 나온다.** 멤버십으로 이어받으면 그 부류가 조용히 사라진다.
_C = (1389, 4185)
_ctr = _mod._center(*_C)
# 실측 좌표를 쓴다 — 2026-09-11 캐시에 실제로 있던 가맹점 위치다.
# 셀 (1390,4185) 에 속하는데 셀 (1389,4185) 중심에서 1.789km 라 그 조회에 나온다.
_near = {"frCd": "NEAR", "latitude": 35.063815, "longitude": 128.980103}
# FAR 는 **이웃 범위 안이면서 반경 밖**이어야 한다. 너무 멀리 두면 이웃 훑기에서
# 아예 빠져 반경 검사를 타지 않고, 그러면 이 검사는 무엇을 넣어도 통과하는 죽은 검사가 된다
# (2026-09-11 변조 실험이 실제로 그렇게 적발했다). 2칸 북쪽 ≈ 5.6km.
_far = {"frCd": "FAR", "latitude": _ctr[0] + 2 * (2.8 / 111.0), "longitude": _ctr[1]}
_idx = {}
for _row in (_near, _far):
    _idx.setdefault(_mod._cell(_row["latitude"], _row["longitude"]), []).append(_row)
_got = {r["frCd"] for r in _mod._prev_rows_near(_idx, _C[0], _C[1], *_ctr)}
check(_mod._cell(_near["latitude"], _near["longitude"]) != _C,
      "시험 재료 확인 — NEAR 는 그 셀 '안'이 아니다(그런데 조회에는 나온다)")
check("NEAR" in _got,
      "**셀 밖이어도 조회 반경 안이면 이어받는다** — 멤버십 기준이면 놓쳤을 9,797곳 부류")
check(abs(_mod._cell(_far["latitude"], _far["longitude"])[0] - _C[0]) <= _mod.CARRY_NEIGHBOR,
      "시험 재료 확인 — FAR 는 이웃 훑기 **범위 안**이다(그래야 반경 검사를 시험한다)")
check("FAR" not in _got, "조회 반경 밖은 이어받지 않는다 — 없던 가맹점을 만들어 내면 안 된다")

# ② 일시적 실패 — 재시도로 회복하면 스킵이 아니다
_st = {"n": 0}
def _flaky(body):
    if body.get("longitude") == _BADLNG:
        _st["n"] += 1
        if _st["n"] == 1:
            return _boom(body)
    return _ok(body)

_out2, _exc2, _log2, _ = _collect_with(_flaky)
check(_exc2 is None and len(_out2["rows"]) == 40, "재시도로 회복하면 전 셀이 수집된다")
check("스킵한 셀 없음" in _log2, "회복했으면 스킵으로 세지 않는다")

# ③ 차단기 — API 가 전면적으로 죽은 날 재시도가 요청을 세 배로 늘리면 안 된다
_cnt = {"n": 0}
def _dead(body):
    _cnt["n"] += 1
    return _boom(body)

_out3, _exc3, _log3, _ = _collect_with(_dead, grid_n=1267, prev_rows=_PREV)
check(_exc3 is not None and "연속" in str(_exc3),
      "연속 실패가 이어지면 '나쁜 셀'이 아니라 API 문제로 보고 중단한다")
check(_cnt["n"] <= 3 * _mod.MAX_CONSECUTIVE_SKIPS,
      f"차단기가 요청 폭증을 막는다(1,267셀×3=3,801 이 될 뻔했다)", _cnt["n"])

# ④ 스킵이 너무 많으면 그것을 '오늘 수집분'이라 부르지 않는다
def _scattered(body):
    i = round((float(body.get("longitude", 0)) - 128.815101) / 0.030813)
    return _boom(body) if (i > 0 and i % 3 == 0) else _ok(body)

_out4, _exc4, _log4, _ = _collect_with(_scattered, grid_n=1267, prev_rows=_PREV)
check(_exc4 is not None and "%" in str(_exc4),
      "스킵이 5%를 넘으면 중단한다 — 확인하지 않은 것의 날짜를 올리지 않는다")

# ⑤ 이어받을 재료가 없으면 **스킵을 허용하지 않는다.** `_workspace/raw/` 는 .gitignore
#    대상이라 신선한 클론에는 캐시가 없고, 그 상태의 스킵은 '유지'가 아니라 '삭제'다.
#    2026-09-11 적대적 검토가 잡았다 — 그때 로그는 "지우지 않는다"고 **거짓을 말하고 있었다**.
_out5, _exc5, _log5, _ = _collect_with(_one_bad2 := (lambda b: _boom(b) if b.get("longitude") == _BADLNG else _ok(b)),
                                       prev_rows=())          # 캐시 없음
check(_exc5 is not None and "이어받을" in str(_exc5),
      "**이어받을 것이 없으면 스킵하지 않고 회차를 실패시킨다** — 스킵이 삭제가 되면 안 된다")
check(_out5 is None, "그 회차는 산출물을 내지 않는다(fail-open 으로 기존 데이터가 지켜진다)")
check("지우지 않는다" not in _log5,
      "이어받지 못한 회차가 '지우지 않는다'고 말하면 안 된다 — 검토가 잡은 거짓 문구")

# ⑥ 재시도로 **성공**해도 요청은 세 배로 나간다. 차단기가 '스킵'만 세면 그 날이 조용히 지나간다.
_wob = {"n": {}}
def _wobbly(body):
    k = body.get("longitude")
    _wob["n"][k] = _wob["n"].get(k, 0) + 1
    return _ok(body) if _wob["n"][k] >= 3 else _boom(body)

_out6, _exc6, _log6, _ = _collect_with(_wobbly, grid_n=1267, prev_rows=_PREV)
check(_exc6 is not None and ("재시도" in str(_exc6) or "예산" in str(_exc6)),
      "**재시도가 쌓이면 스킵이 0이어도 중단한다** — 종전엔 요청 3배·회차 +7시간이 침묵으로 지나갔다")

# ⑦ 같은 셀이 여러 회차 실패하면 이어받기가 누적된다. 그때 **나이를 정직하게** 말해야 한다.
#    캐시 스탬프(prev_on)는 매 회차 오늘로 갱신되므로 그걸 관측일이라 쓰면 매일 "어제 것"이라
#    보고하게 된다 — 실제로는 며칠째 같은 관측분이다(2026-09-11 적대적 검토가 재현해 잡았다).
_agecache = Path(_tf.mkdtemp()) / "c.json"
_agecache.write_text(json.dumps({"collected_on": "2026-09-07", "rows": _PREV},
                                ensure_ascii=False), encoding="utf-8")
_ages = []
for _day in ("2026-09-12", "2026-09-13", "2026-09-14"):
    _sp = importlib.util.spec_from_file_location("brf_age", SRC)
    _mm = importlib.util.module_from_spec(_sp)
    try:
        _sp.loader.exec_module(_mm)
    except SystemExit:
        pass
    _srv = _serve(_one_bad)
    _mm.API_SEARCH = f"http://127.0.0.1:{_srv.server_address[1]}/x"
    _mm.THROTTLE_SEC = 0.0
    _mm.CELL_RETRY_BACKOFF = (0.0, 0.0)
    _mm._T0 = _time.time()
    _mm.load_districts = lambda: {"26000": ("강서구", "부산")}
    _mm.load_grid = lambda: ({(1389, 4185)}, [(1389, 4180 + i) for i in range(40)])
    _mm.save_grid = lambda seed, rows: None
    _mm.CACHE = _agecache
    with contextlib.redirect_stderr(_io.StringIO()):
        _mm.collect(_day)
    _ages.append(_mm.DIAG["skipped"])
    _srv.shutdown()

check(all(a["carriedOldestObserved"] == "2026-09-07" for a in _ages),
      "**실제 관측일이 회차를 거듭해도 안 움직인다** — 이어받기는 데이터를 늙게 만들지 젊게 하지 않는다",
      [a["carriedOldestObserved"] for a in _ages])
check([a["carriedMaxRounds"] for a in _ages] == [1, 2, 3],
      "몇 회차째 이어받는 중인지 센다 — 이어받기는 하루짜리 응급처치다")
check(_ages[-1]["carriedFrom"] != _ages[-1]["carriedOldestObserved"],
      "캐시 스탬프와 실제 관측일을 구분한다(둘을 같은 것으로 쓰면 매일 '어제 것'이라 거짓 보고)")

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
