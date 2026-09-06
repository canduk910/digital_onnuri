#!/usr/bin/env python3
"""서울·인천·경기·부산 가맹점 재수집·통합 빌드 + 빈도·지점명 기반 브랜드 자동탐지.

**수집 방식(2026-09-01 개편)**: 공식이 가맹점 API 를 v2(onr)에서 v3(onrgt)로 옮기며
v2 를 닫았다(resCode 9998). v3 는 **좌표가 필수이고 반경 2km 로 고정**이라 —
addrCd 만 주면 0건, baseRange 는 어떤 값도 무시된다 — 기존의 "구·군 addrCd 순회"가
성립하지 않는다. 그래서 **2.8km 좌표 격자를 순회**해 모으고, 응답의 addrCd 로
시도·구를 확정한다(계층 원천은 여전히 API 다). 실측 커버리지 99.56%.

주소 계층 파싱·업종 카테고리·브랜드 자동탐지를 붙여
data/merchants/{seoul,incheon,gyeonggi}.json을 재생성한다. 공공데이터(CSV)
스냅샷과 키워드 기반 brand_stores.json을 이 통합 데이터가 대체·흡수한다.

멱등 재실행:
    python3 _workspace/dev_scripts/build_region_full.py                 # 캐시 재사용
    python3 _workspace/dev_scripts/build_region_full.py --refresh       # API 재수집(1초 스로틀)
    python3 _workspace/dev_scripts/build_region_full.py --collected-on 2026-08-08

계층 소스 원칙(구·군 addrCd 순회라 상위 레벨을 addrCd로 확정 — 주소 파싱 아티팩트 배제):
- 서울·인천: region → gu(쿼리 addrCd 확정) → dong(주소 괄호 파싱)
- 경기: region → si(쿼리 addrCd 확정) → gu(주소 파싱+화이트리스트) → dong(주소 파싱)

브랜드(brand) 자동탐지:
- 상호명 정규화 → 끝 지점표기('…점') 분리로 브랜드 base 추출(공백형+붙임형 접두매칭)
- base별 빈도 집계, 지점명 비율(=지점표기가 붙은 비율) 산출
- 빈도 ≥ MIN_FREQ(7) AND 지점비율 ≥ MIN_BRANCH_RATIO(0.40) → 브랜드 확정
- 표기변형 병합(씨유=CU, 지에스25=GS25, GS THE FRESH=GS더프레시 …)
- brand 필드 = 확정 브랜드명(개별) 또는 null. 후보 CSV(_workspace/13_brand_candidates.csv)에
  확정/제외를 판정근거와 함께 기록(사람 검토용)

집계·계층 인덱스는 저장하지 않는다(파생 값 — 렌더 시 items에서 계산).
"""

import argparse
import csv
import json
import math
import re
import sys
import time
import urllib.request
from collections import Counter, defaultdict
from statistics import median
from datetime import date
from pathlib import Path

# 2026-09-01: 공식이 v2(onr)를 닫고 v3(onrgt)로 옮겼다. v2 는 이제 resCode 9998("접근 권한이 없습니다").
# v3 의 결정적 차이 — **좌표가 필수이고 반경 2km 로 고정**이다. addrCd 만 주면 0건이고,
# baseRange 는 0.5~50 어느 값을 넣어도 무시된다(실측: 전부 같은 건수, 최대 거리 2.0km).
# 그래서 "구·군 addrCd 순회"가 성립하지 않고 **좌표 격자 순회**로 바꿨다.
API_SEARCH = "https://www.onnuri.gift/api/v3/onrgt/place/search"
API_ADDR_SIDO = "https://www.onnuri.gift/api/v3/onrgt/addr/{sido}"   # POST, body {} → 구·군 목록
HEADERS = {
    "Content-Type": "application/json",
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
    "Referer": "https://www.onnuri.gift/place",
}
THROTTLE_SEC = 0.7
GRID_KM = 2.8          # 반경 2km 원이 완전히 덮는 정사각형 한 변(2√2). 이보다 크면 사이가 빈다.
GRID_SEED = Path("_workspace/raw/merchant_grid_seed.json")
SIDO = {"11000": "서울", "28000": "인천", "41000": "경기", "26000": "부산"}
CACHE = Path("_workspace/raw/capital_merchants_raw.json")
OUT_DIR = Path("data/merchants")
REGION_FILE = {"서울": "seoul", "인천": "incheon", "경기": "gyeonggi", "부산": "busan"}
CAND_CSV = Path("_workspace/13_brand_candidates.csv")

# 경기 실제 일반구 — 시별로 적는다. 주소 파싱 아티팩트(타 시도 구 오등록) 차단이 1차 목적이고,
# 시별로 나눠 두면 **si 와 gu 가 서로 맞는지**도 볼 수 있다(2026-09-06, F18).
GYEONGGI_GU_BY_SI = {
    "수원시": {"장안구", "권선구", "팔달구", "영통구"},
    "성남시": {"수정구", "중원구", "분당구"},
    "안양시": {"만안구", "동안구"},
    "안산시": {"상록구", "단원구"},
    "고양시": {"덕양구", "일산동구", "일산서구"},
    "용인시": {"처인구", "기흥구", "수지구"},
    "부천시": {"원미구", "소사구", "오정구"},
}
GYEONGGI_GU = {g for gus in GYEONGGI_GU_BY_SI.values() for g in gus}


# ── 지역 대조 (2026-09-06, 사용자 개선 요청) ───────────────────────────────────
# 배정 지역(addrCd 원천)과 **다른 신호**를 견준다. 주소와 시장 두 가지다.
# 어느 하나로도 지역을 다시 정하지 않는다 — addrCd 를 계층 원천으로 삼은 2026-09-01 결정을
# 되돌리면 인천 자치구 개편분이 옛 구 이름으로 돌아간다. 여기서 하는 것은 두 가지뿐이다:
#   ① 어긋난 것을 세어 보고한다(F18 과 같은 처분)
#   ② 그 지역 지도에 그릴 근거가 없는 좌표만 비운다 — 목록에서는 빼지 않는다

# 시도 이름은 긴 것부터 본다("경상남도"가 "경상"으로 먼저 걸리지 않게).
# '광주'는 광주광역시와 경기 광주시가 겹치므로 넣지 않는다 — 읽지 못하면 판단하지 않는다.
_SIDO_PREFIX = [
    ("서울특별시", "서울"), ("서울", "서울"),
    ("인천광역시", "인천"), ("인천", "인천"),
    ("경기도", "경기"), ("부산광역시", "부산"), ("부산", "부산"),
    ("대구광역시", "타시도"), ("대전광역시", "타시도"), ("울산광역시", "타시도"),
    ("세종특별자치시", "타시도"), ("제주특별자치도", "타시도"),
    ("강원특별자치도", "타시도"), ("강원도", "타시도"),
    ("충청북도", "타시도"), ("충청남도", "타시도"),
    ("전북특별자치도", "타시도"), ("전라북도", "타시도"), ("전라남도", "타시도"),
    ("경상북도", "타시도"), ("경상남도", "타시도"),
]


def addr_sido(addr):
    """주소 문자열이 말하는 시도. **읽지 못하면 None** — 모르는 것은 어긋난 것이 아니다.

    '성남시 분당구 …' 처럼 도 이름 없이 적힌 주소가 실제로 있다(2026-09-06 실측 5건).
    그것을 '다른 지역'으로 읽으면 멀쩡한 경기 가맹점을 어긋난 것으로 센다.
    """
    a = (addr or "").strip()
    if not a:
        return None
    for pre, sido in _SIDO_PREFIX:
        if a.startswith(pre):
            return sido
    return None


def market_centers(rows, min_members=5, max_median_km=3.0):
    """(시장, 시도) 무리의 좌표 중앙값. 한곳에 모여 있는 무리만 돌려준다.

    rows 는 (시도, 항목) 쌍의 목록이다 — 항목 자체에는 지역 필드를 넣지 않는다(스키마 불변).

    **산포를 최대-최소 폭으로 재면 안 된다.** 멀리 떨어진 레코드 하나가 그 무리의 폭을
    통째로 늘려, 정작 찾으려던 무리(부전시장·수원남문로데오시장·송현시장)가 기준에서
    빠진다 — 2026-09-06 에 실제로 그렇게 헛디뎠다. 중앙값 거리로 재면 이상치 하나에
    흔들리지 않는다. '백년소상공인'처럼 장소가 아닌 지정 제도(153곳이 네 지역에 흩어져
    있다)는 이 검사로 자동으로 빠진다.
    """
    groups = defaultdict(list)
    for region, r in rows:
        m = (r.get("market") or "").strip()
        if m and r.get("lat") is not None and r.get("lng") is not None:
            groups[(m, region)].append(r)
    out = {}
    for key, members in groups.items():
        if len(members) < min_members:
            continue
        clat = median([float(x["lat"]) for x in members])
        clng = median([float(x["lng"]) for x in members])
        ds = sorted(haversine_km(float(x["lat"]), float(x["lng"]), clat, clng) for x in members)
        if ds[len(ds) // 2] > max_median_km:
            continue
        out[key] = (clat, clng)
    return out


def haversine_km(lat1, lng1, lat2, lng2):
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lng2 - lng1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


# 자기 시장 무리에서 이만큼 넘게 떨어지면 그 지역 지도에 그릴 근거가 없다고 본다.
# 2026-09-06 실측: 30km 초과가 8곳인데 53km 와 250km 사이가 비어 있다. 아래 다섯은
# 안성·성남·화성·용인·의정부 주소로, 시장 조합에 이름만 올린 정상 사례로 보인다.
COORD_FAR_KM = 100.0


def si_gu_ok(si, gu):
    """경기 레코드의 시·구 조합이 실재하는가. 순수 함수 — 시험 가능하게 떼어 뒀다.

    둘 중 하나가 비면 판단하지 않는다(True). 없는 것은 어긋난 것이 아니고,
    gu 가 None 인 경우는 화이트리스트가 이미 걸러 낸 정상 경로다.
    모르는 시(단일 구가 없는 시)도 판단하지 않는다 — 일반구가 없는 시가 대부분이라
    그쪽은 애초에 gu 가 None 이고, 목록에 없는 시에 구가 붙었다면 그것도 어긋남이다.
    """
    if not si or not gu:
        return True
    return gu in GYEONGGI_GU_BY_SI.get(si, set())

# 업종 카테고리(cat) — 이름 규칙(오탐 가드) 우선 → placeTypeNm 매핑
NAME_RULES = [
    ("약국", re.compile(r"약국")),
    ("학원", re.compile(r"학원(?!가)|교습소")),
    ("편의점", re.compile(
        r"씨유|(?:^|[^A-Za-z0-9])CU(?![A-Za-z0-9])|GS\s?25|지에스\s?25"
        r"|세븐\s?일레븐|이마트\s?24|미니스톱|(?<!세탁)편의점", re.I)),
    ("마트·슈퍼", re.compile(r"(?<!스)마트|슈퍼|수퍼|스토아|식자재|다이소")),
]
BIZ_MAP = {
    "음식점업": "음식점", "의류및신발": "의류·신발",
    "농산물": "농축수산·식품", "수산물": "농축수산·식품",
    "축산물": "농축수산·식품", "가공식품": "농축수산·식품",
    "가정용품": "생활·잡화", "근린상권서비스": "생활서비스", "기타소매업": "기타",
}

# ===================== 브랜드 자동탐지 파라미터 =====================
MIN_FREQ = 7            # 사용자 기준: 7회 이상
MIN_BRANCH_RATIO = 0.40  # 사용자 기준: 지점명 비율 40% 이상 → 브랜드
VOCAB_MIN = 5           # 붙임형 접두 매칭용 어휘 최소 빈도

# base로 잡히면 안 되는 법인/일반어
BLOCK_BASE = {norm for norm in (
    "주식회사", "(주)", "주", "유한회사", "유", "합자회사", "협동조합", "영농조합법인",
    "농협", "수협", "축협", "일반", "본점", "직영점",
)}

# 표기변형 병합 {정규화키: 표준명}
VARIANT_MAP = {
    "씨유": "CU", "cu": "CU",
    "지에스25": "GS25", "gs25": "GS25",
    "세븐일레븐": "세븐일레븐", "7-eleven": "세븐일레븐",
    "이마트24": "이마트24", "emart24": "이마트24",
    "파리바게트": "파리바게뜨", "파리바게뜨": "파리바게뜨", "parisbaguette": "파리바게뜨",
    "뚜레쥬르": "뚜레쥬르", "touslesjours": "뚜레쥬르",
    "비비큐": "BBQ", "bbq": "BBQ",
    "비에이치씨": "BHC", "bhc": "BHC",
    "배스킨라빈스": "배스킨라빈스", "베스킨라빈스": "배스킨라빈스", "baskinrobbins": "배스킨라빈스",
    "메가엠지씨커피": "메가커피", "메가mgc커피": "메가커피", "메가커피": "메가커피",
    "지에스더프레시": "GS더프레시", "gs더프레시": "GS더프레시", "gsthefresh": "GS더프레시",
    "더프레시": "GS더프레시",
    "아성다이소": "다이소", "다이소": "다이소",
}


def clean(n):
    return re.sub(r"\([^)]*\)", "", n or "").strip()


def norm_key(s):
    return re.sub(r"\s+", "", s or "").lower()


def canonical(base):
    return VARIANT_MAP.get(norm_key(base), base.strip())


def is_branch(name):
    """지점명 신호: 정규화 상호가 '점'으로 끝나면 지점표기로 본다."""
    return clean(name).endswith("점")


def spaced_base(name):
    toks = clean(name).split()
    if len(toks) >= 2 and toks[-1].endswith("점"):
        return " ".join(toks[:-1])
    return None


# ------------------------------- 브랜드 탐지(전체 상호 대상 2패스)
def detect_brands(rows):
    """rows(원시)에서 브랜드 탐지. 반환: (brand_of{frCd:brand|None}, candidates[list])."""
    names = [(r["frCd"], (r["frcsNm"] or "").strip(), (r.get("frcsAddr") or "").strip()) for r in rows]

    # 1패스: 공백형 base 어휘
    vocab = Counter()
    for _, nm, _ in names:
        sb = spaced_base(nm)
        if sb:
            cb = canonical(sb)
            if norm_key(cb) not in BLOCK_BASE:
                vocab[cb] += 1
    key_map = {norm_key(b): canonical(b) for b, c in vocab.items() if c >= VOCAB_MIN}
    for variant, std in VARIANT_MAP.items():
        key_map.setdefault(norm_key(variant), std)
    vocab_keys = sorted(key_map.items(), key=lambda kv: len(kv[0]), reverse=True)

    # 2패스: 모든 상호를 base에 귀속 (공백형 → 붙임형 접두 → 정규화 전체상호)
    members = defaultdict(list)   # base(표준) → [(frCd, name, branch, addr, raw_base)]
    for fr, nm, addr in names:
        cn = clean(nm)
        sb = spaced_base(nm)
        base = None
        raw = None
        if sb and norm_key(canonical(sb)) not in BLOCK_BASE:
            base, raw = canonical(sb), sb
        if base is None:
            nk = norm_key(cn)
            for k, std in vocab_keys:
                if len(k) >= 2 and nk.startswith(k) and nk != k:
                    base, raw = std, k
                    break
        if base is None:
            if norm_key(cn) in BLOCK_BASE or not cn:
                continue
            base, raw = canonical(cn), cn   # 비지점 상호는 전체상호가 base(동일상호 빈도 집계)
        members[base].append((fr, nm, is_branch(nm), addr, raw))

    brand_of = {}
    candidates = []
    for base, mem in members.items():
        cnt = len(mem)
        if cnt < MIN_FREQ:
            continue
        branch = sum(1 for _, _, b, _, _ in mem if b)
        ratio = branch / cnt
        variants = sorted({raw for _, _, _, _, raw in mem if norm_key(raw) != norm_key(base)})
        sample_addr = next((a for _, _, _, a, _ in mem if a), "")
        confirmed = ratio >= MIN_BRANCH_RATIO
        verdict = "브랜드" if confirmed else "제외"
        reason = (f"빈도 {cnt}·지점비율 {ratio*100:.0f}% "
                  f"{'≥' if confirmed else '<'}40% → {'브랜드 확정' if confirmed else '일반상호 제외'}")
        candidates.append({
            "brand": base, "count": cnt, "branch_ratio": round(ratio, 2),
            "variants": "; ".join(variants), "sample_addr": sample_addr,
            "verdict": verdict, "reason": reason,
            "borderline": (7 <= cnt <= 10) or (0.30 <= ratio < 0.50),
        })
        if confirmed:
            for fr, _, _, _, _ in mem:
                brand_of[fr] = base
    candidates.sort(key=lambda c: (c["verdict"] != "브랜드", -c["count"]))
    return brand_of, candidates


# --------------------------------------------------------------- 주소 파싱
def region_prefix(tok):
    for r in ("서울", "인천", "경기", "부산"):
        if tok.startswith(r):
            return r
    return None


def parse_addr(addr, region):
    addr = (addr or "").strip()
    toks = addr.split()
    idx = 1 if (toks and region_prefix(toks[0])) else 0
    rest = toks[idx:]
    dong = None
    for inner in reversed(re.findall(r"\(([^)]*)\)", addr)):
        dm = re.search(r"([가-힣]+\d*(?:동|가|리))(?![가-힣])", inner)
        if dm:
            dong = dm.group(1)
            break
    gu = None
    if region == "경기":
        if rest and rest[0].endswith("시"):
            rest = rest[1:]
        if rest and rest[0].endswith(("구", "군")):
            gu = rest[0]
    else:
        if rest and rest[0].endswith(("구", "군")):
            gu = rest[0]
    return gu, dong


def categorize(name, biz_type):
    for cat, rx in NAME_RULES:
        if rx.search(name):
            return cat
    return BIZ_MAP.get(biz_type, "기타")


# --------------------------------------------------------------- 수집(전수)
def post(url, body):
    req = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), headers=HEADERS, method="POST")
    with urllib.request.urlopen(req, timeout=30) as res:
        payload = json.loads(res.read().decode("utf-8"))
    time.sleep(THROTTLE_SEC)
    if payload.get("resCode") != "0000":
        raise RuntimeError(f"API 오류 {payload.get('resCode')}: {payload.get('resMsg')}")
    return payload["data"]


def load_districts():
    """addrCd → (구·군 이름, 시도). 계층의 원천은 여전히 API 다 — 주소 파싱이 아니다.

    이게 중요한 이유: 2026년 인천 자치구 개편(중구·동구 → 제물포구 등)이 API 목록에는
    반영돼 있는데 **가맹점 주소 문자열에는 옛 이름이 다수 남아 있다**(실측: 28125 의
    주소 두 번째 토큰이 중구 767 · 동구 580 · 제물포구 45). 주소에서 구를 뽑으면
    화면에서 신설 구가 사라진다.
    """
    out = {}
    for sido, sido_nm in SIDO.items():
        for row in post(API_ADDR_SIDO.format(sido=sido), {})["list"]:
            out[row["addrCd"]] = (row["addrNm"], sido_nm)
    return out


def _cell(lat, lng):
    dlat = GRID_KM / 111.0
    dlng = GRID_KM / (111.0 * math.cos(math.radians(lat)))
    return (math.floor(lat / dlat), math.floor(lng / dlng))


def _center(r, c):
    dlat = GRID_KM / 111.0
    lat = (r + 0.5) * dlat
    return lat, (c + 0.5) * (GRID_KM / (111.0 * math.cos(math.radians(lat))))


def load_grid():
    """조회할 격자 지점. 시드 파일이 있으면 그것을, 없으면 기존 수집본에서 만든다.

    시드는 **누적**한다(한 번이라도 가맹점이 관측된 셀은 지우지 않는다). 매번 결과로
    덮어쓰면 그날 0건이던 셀이 빠지고, 다음 날 그 자리에 새 가맹점이 생겨도 영영 못 본다.
    여기에 이웃 1칸을 더해 경계 밖 신규 가맹까지 덮는다(실측 커버리지 99.56%).
    """
    seed = set()
    if GRID_SEED.exists():
        seed = {tuple(x) for x in json.load(open(GRID_SEED, encoding="utf-8"))["cells"]}
    if not seed:
        for code in REGION_FILE.values():
            f = OUT_DIR / f"{code}.json"
            if not f.exists():
                continue
            for i in json.load(open(f, encoding="utf-8"))["items"]:
                if i.get("lat") and i.get("lng"):
                    seed.add(_cell(i["lat"], i["lng"]))
    if not seed:
        raise RuntimeError(
            "격자 시드가 없다. 기존 data/merchants/*.json 이나 "
            f"{GRID_SEED} 중 하나가 있어야 한다(최초 1회는 사람이 만든다).")
    grid = set(seed)
    for (r, c) in seed:
        for dr in (-1, 0, 1):
            for dc in (-1, 0, 1):
                grid.add((r + dr, c + dc))
    return seed, sorted(grid)


def save_grid(seed, rows):
    """관측된 셀을 시드에 누적해 저장한다."""
    cells = set(seed)
    for r in rows:
        if r.get("latitude") and r.get("longitude"):
            cells.add(_cell(float(r["latitude"]), float(r["longitude"])))
    GRID_SEED.parent.mkdir(parents=True, exist_ok=True)
    with open(GRID_SEED, "w", encoding="utf-8") as f:
        json.dump({"grid_km": GRID_KM, "cells": sorted(cells)}, f)


def collect(collected_on):
    districts = load_districts()
    print(f"구·군 {len(districts)}개 로드", file=sys.stderr)
    seed, grid = load_grid()
    print(f"격자 {len(grid)}지점(시드 {len(seed)} + 이웃 1칸)", file=sys.stderr)

    n_req, empty = 0, 0
    found = {}
    for n, (r, c) in enumerate(grid, 1):
        lat, lng = _center(r, c)
        page, total_page = 1, 1
        while page <= total_page:
            data = post(API_SEARCH, {
                "keyword": "", "addrCd": "", "addrNm": "", "placeTypeList": [],
                "paperYn": "", "cardYn": "", "qrYn": "",
                "latitude": f"{lat:.6f}", "longitude": f"{lng:.6f}",
                "baseRange": 2, "currPage": page})
            n_req += 1
            total_page = data.get("totalPage") or 1
            lst = data.get("list") or []
            if page == 1 and not lst:
                empty += 1
            for row in lst:
                found[row["frCd"]] = row
            page += 1
        if n % 200 == 0:
            print(f"  {n}/{len(grid)} 지점 · 요청 {n_req} · 고유 {len(found)}건", file=sys.stderr)

    # 격자는 시도 경계를 넘어 인접 지역(대구·경남 등)까지 물어온다.
    # addrCd 로 우리 4개 시도만 남긴다 — 안 걸러내면 "부산 사람에게 대구 가맹점"이 나간다.
    rows, district_counts, outside = [], Counter(), 0
    for row in found.values():
        d = districts.get(str(row.get("addrCd") or ""))
        if not d:
            outside += 1
            continue
        addr_nm, sido_nm = d
        row["_query_addrNm"] = addr_nm
        row["_query_sido"] = sido_nm
        rows.append(row)
        district_counts[f"{sido_nm} {addr_nm}"] += 1

    print(f"수집 {len(found)}건 → 대상 {len(rows)}건 (범위 밖 {outside}건 제외), "
          f"요청 {n_req} · 빈 지점 {empty}", file=sys.stderr)
    save_grid(seed, rows)
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    with open(CACHE, "w", encoding="utf-8") as f:
        json.dump({"collected_on": collected_on, "n_requests": n_req,
                   "district_counts": dict(district_counts), "rows": rows}, f, ensure_ascii=False)
    return {"collected_on": collected_on, "rows": rows,
            "district_counts": dict(district_counts)}


# ----------------------------------------------------------------- 빌드
def _guard_same_day(args):
    """같은 날 두 번째 재수집을 막는다 (2026-09-06, 사용자 결정 ④).

    2026-09-05 에 같은 날 두 번 돌렸더니 공식 API 가 400 을 냈다. 그 차단은 그날로
    끝나지 않고 **다음 날 00:30 배치까지 물었다** — 09-06 새벽 수집이 1분 만에 400 으로
    죽었고 라이브에 중단 배너가 떴다(그날 11시에는 같은 호출이 정상이었다).

    그때 조치는 DEPLOY.md 에 "같은 날 두 번 돌리지 말 것"을 적는 것까지였고 코드 가드는
    없었다. 원인을 캐려는 사람이 정확히 그 자리를 밟는다 — 실제로 밟았다.

    막되 길은 열어 둔다. `--force-refresh` 를 주면 강행한다. 판단은 사람 몫이고,
    이 가드가 하는 일은 **모르고 밟는 것을 막는 것**뿐이다.
    """
    if args.force_refresh:
        print("⚠ --force-refresh — 같은 날 두 번째 재수집을 강행한다. "
              "공식 API 가 400 을 낼 수 있고 그 차단은 다음 날 배치까지 갈 수 있다.",
              file=sys.stderr)
        return
    if not CACHE.exists():
        return
    try:
        stamp = json.load(open(CACHE, encoding="utf-8")).get("collected_on")
    except Exception:
        return   # 캐시를 못 읽으면 막지 않는다 — 모르면 막지 않는다
    if stamp != args.collected_on:
        return
    print(f"오늘({stamp}) 이미 재수집한 캐시가 있습니다. 같은 날 두 번 두드리면 공식 API 가",
          file=sys.stderr)
    print("400 을 내고, 그 차단이 다음 날 00:30 배치까지 갈 수 있습니다(2026-09-05·06 실측).",
          file=sys.stderr)
    print("  · 조립만 다시 하려면  --refresh 를 빼고 돌리세요(캐시를 씁니다)", file=sys.stderr)
    print("  · 그래도 재수집하려면 --force-refresh 를 주세요", file=sys.stderr)
    sys.exit(4)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--collected-on", default=date.today().isoformat())
    ap.add_argument("--refresh", action="store_true", help="API 재수집(캐시 무시)")
    ap.add_argument("--force-refresh", action="store_true",
                    help="같은 날 두 번째 재수집도 강행한다(진단용 — 대가를 알고 쓸 것)")
    args = ap.parse_args()

    if args.refresh or not CACHE.exists():
        _guard_same_day(args)
        cache = collect(args.collected_on)
    else:
        cache = json.load(open(CACHE, encoding="utf-8"))
        print(f"캐시 재사용: {CACHE} ({len(cache['rows'])}행, {cache['collected_on']})", file=sys.stderr)
    rows = cache["rows"]
    collected_on = args.collected_on if args.refresh else cache["collected_on"]

    # frCd 중복 제거(첫 등장 유지)
    seen, uniq_rows = set(), []
    for r in rows:
        if r["frCd"] in seen:
            continue
        seen.add(r["frCd"])
        uniq_rows.append(r)

    # 브랜드 자동탐지
    brand_of, candidates = detect_brands(uniq_rows)

    by_region = {"서울": [], "인천": [], "경기": [], "부산": []}
    cat_counter = Counter()
    dong_missing = 0
    si_gu_mismatch = []   # 시·구 조합이 실재하지 않는 레코드(F18)
    addr_other = []       # 주소가 다른 시도를 가리키는 레코드(2026-09-06) — 보고만 한다
    for r in uniq_rows:
        region = r["_query_sido"]
        query_nm = r["_query_addrNm"]
        name = (r["frcsNm"] or "").strip()
        cat = categorize(name, r.get("placeTypeNm", ""))
        parsed_gu, dong = parse_addr(r.get("frcsAddr"), region)
        if dong is None:
            dong_missing += 1
        if region == "경기":
            si = query_nm
            gu = parsed_gu if parsed_gu in GYEONGGI_GU else None
            # si 는 공식 API 의 addrCd 에서, gu 는 주소에서 온다(19~21행 계층 소스 원칙).
            # 두 원천이 어긋나면 **있을 수 없는 조합**이 조용히 만들어진다 — 안양시에 팔달구는 없다.
            # 값을 고치지 않는다. 주소를 믿어 si 를 덮어쓰면 2026-09-01 에 인천 자치구 개편 때문에
            # 이 구조를 택한 이유가 무너진다(가맹점 주소에는 옛 구 이름이 다수 남아 있다).
            # 세어서 알린다 — 조용히 남는 것이 문제였지 어느 쪽이 옳은지는 여기서 정할 일이 아니다.
            if not si_gu_ok(si, gu):
                si_gu_mismatch.append({"name": name, "si": si, "gu": gu,
                                       "addr": (r.get("frcsAddr") or "").strip()})
        else:
            si = None
            gu = query_nm
        # 주소가 말하는 시도가 배정과 다른가. **값은 고치지 않는다** — 어느 신호가 옳은지
        # 데이터만으로 정할 수 없다(2026-09-06 실측: 7건 중 6건은 소속 시장이 우리 지역이라
        # 주소 쪽이 낡았거나 잘못 적힌 것으로 보인다). 세어서 알리는 것까지만 한다.
        a_sido = addr_sido(r.get("frcsAddr"))
        if a_sido is not None and a_sido != region:
            addr_other.append({"name": name, "region": region, "says": a_sido,
                               "addr": (r.get("frcsAddr") or "").strip(),
                               "market": r.get("mrktNm", "")})

        by_region[region].append({
            "id": r["frCd"], "name": name, "cat": cat,
            "brand": brand_of.get(r["frCd"]),   # 확정 브랜드명 or None
            "si": si, "gu": gu, "dong": dong,
            "addr": (r.get("frcsAddr") or "").strip(),
            "market": r.get("mrktNm", ""), "market_type": r.get("mrktType", ""),
            "paper": r.get("paperYn", ""), "card": r.get("cardYn", ""), "qr": r.get("qrYn", ""),
            "lat": r.get("latitude"), "lng": r.get("longitude"),
        })
        cat_counter[(region, cat)] += 1

    # ── 좌표 처분 (2026-09-06) ────────────────────────────────────────────────
    # 자기 시장 무리에서 아주 멀리 떨어진 좌표는 **그 지역 지도에 그릴 근거가 없다.**
    # 주소와 시장이 서로 일치하는데 좌표만 튄 경우에 한해 좌표를 비운다(None).
    #   · 레코드는 지운다 → 하지 않는다. 실재하는 가맹점이 목록에서 사라진다.
    #   · 좌표를 시장 한가운데로 옮긴다 → 하지 않는다. 모르는 것을 아는 척하는 일이다.
    #   · 비운다 = "여기 있다"도 "없다"도 아닌 "어디인지 모른다"(ADR-22 의 관측 실패와 같은 처분).
    # 주소가 배정과 다른 건도 **똑같이 비운다**(2026-09-07 정정). 처음에는 "주소가 배정과
    # 일치할 때만" 비우도록 했는데 그 조건이 틀렸다. 사용자가 인천 지도의 대구 마커와
    # 경기 지도의 진주 마커를 제보해 드러났다.
    #
    #   어느 신호가 옳든 그 마커는 틀리다 —
    #     · addrCd 가 옳다면(이 가게는 인천 것) 좌표(대구)가 틀렸으니 그리면 안 된다.
    #     · 주소가 옳다면(인천 것이 아니다) 애초에 인천 지도에 있으면 안 된다.
    #
    # 즉 좌표를 비우는 데에는 **어느 쪽이 옳은지 정할 필요가 없다.** "어느 신호가 옳은지
    # 판단하지 않는다"를 "아무것도 하지 않는다"로 잘못 읽은 것이었다. 어느 쪽이 옳은지는
    # 여전히 정하지 않고 addr_other 로 보고만 한다 — 목록에서 빼지도 않는다.
    pairs = [(reg, x) for reg, v in by_region.items() for x in v]
    centers = market_centers(pairs)
    coord_cleared = []
    for reg, r in pairs:
        key = ((r.get("market") or "").strip(), reg)
        if key not in centers or r.get("lat") is None or r.get("lng") is None:
            continue
        d = haversine_km(float(r["lat"]), float(r["lng"]), *centers[key])
        if d <= COORD_FAR_KM:
            continue
        coord_cleared.append({"name": r["name"], "region": reg, "km": round(d, 1),
                              "market": r.get("market", ""), "addr": r.get("addr", ""),
                              "lat": r["lat"], "lng": r["lng"]})
        r["lat"] = None
        r["lng"] = None

    n_brands = sum(1 for c in candidates if c["verdict"] == "브랜드")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for region, code in REGION_FILE.items():
        items = sorted(by_region[region],
                       key=lambda x: (x["si"] or "", x["gu"] or "", x["dong"] or "", x["name"]))
        payload = {
            "meta": {
                "source": "온누리 가맹점찾기 비공식 API (온누리상품권 공식 홈페이지 〉 가맹점 찾기)",
                "source_url": "https://www.onnuri.gift/place",
                "api_endpoint": API_SEARCH,
                "collected_on": collected_on,
                "region": region,
                "scope": "전수 — 좌표 격자(2.8km) 순회로 전 페이지 수집 후 addrCd 로 시도·구 확정. v3 API 는 좌표가 필수이고 반경 2km 고정이라 구·군 순회가 불가능하다(2026-09-01)",
                "hierarchy": (["시도", "구", "동"] if region != "경기" else ["시도", "시", "구", "동"]),
                "cats": ["음식점", "편의점", "마트·슈퍼", "약국", "학원", "의류·신발",
                         "농축수산·식품", "생활·잡화", "생활서비스", "기타"],
                "brand": f"빈도≥{MIN_FREQ} AND 지점명비율≥{int(MIN_BRANCH_RATIO*100)}%로 자동탐지한 "
                         f"개별 브랜드명(확정 {n_brands}종) 또는 null. 표기변형 병합. "
                         "후보·판정근거: _workspace/13_brand_candidates.csv",
                "limitations": "비공식 API — 스키마 변경 가능. 전수라 누락 거의 없음. "
                               "구(서울·인천)·시(경기)는 API addrCd 원천으로 정확, 동은 주소 파싱 파생값(약 7% null). "
                               "brand는 상호명 정규화·지점명 비율 휴리스틱이라 경계 사례는 사람 검토 대상.",
                "note": "cat=업종 카테고리, brand=자동탐지 브랜드명(없으면 null). "
                        "집계·구/동/브랜드 목록은 저장하지 않음 — 렌더 시 items에서 계산. "
                        "규칙: _workspace/dev_scripts/build_region_full.py",
            },
            "items": items,
        }
        path = OUT_DIR / f"{code}.json"
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")
        print(f"{path}: {len(items)}건")

    # 후보 CSV
    with open(CAND_CSV, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["브랜드명", "총건수", "지점명비율", "표기변형목록", "대표주소예시", "판정", "판정근거", "경계사례"])
        for c in candidates:
            w.writerow([c["brand"], c["count"], f"{c['branch_ratio']:.2f}", c["variants"],
                        c["sample_addr"], c["verdict"], c["reason"], "Y" if c["borderline"] else ""])
    print(f"{CAND_CSV}: 후보 {len(candidates)}행 (확정 {n_brands} / 제외 {len(candidates)-n_brands})")

    # 요약(stderr)
    assigned = sum(1 for v in brand_of.values() if v)
    # 시·구 조합 점검(F18). 0 이면 한 줄로 끝내고, 있으면 표본을 보여 사람이 판단하게 한다.
    if si_gu_mismatch:
        print(f"\n⚠ 시·구 조합이 실재하지 않는 레코드 {len(si_gu_mismatch)}건 "
              f"— 값은 고치지 않았다(si 는 공식 API, gu 는 주소에서 온다)", file=sys.stderr)
        for m in si_gu_mismatch[:5]:
            print(f"    {m['si']} {m['gu']}  {m['name']}  ({m['addr']})", file=sys.stderr)
        if len(si_gu_mismatch) > 5:
            print(f"    … 그 밖 {len(si_gu_mismatch)-5}건", file=sys.stderr)
    else:
        print("\n시·구 조합 점검(경기): 어긋난 레코드 없음", file=sys.stderr)

    # 지역 대조 — 보고만 한다(2026-09-06). 0 이면 한 줄로 끝낸다.
    if addr_other:
        print(f"\n⚠ 주소가 배정 지역과 다른 레코드 {len(addr_other)}건 — 값은 고치지 않았다",
              file=sys.stderr)
        for m in addr_other[:5]:
            print(f"    {m['region']} 배정 · 주소는 {m['says']}  {m['name']}  "
                  f"(시장 {m['market']}) {m['addr']}", file=sys.stderr)
        if len(addr_other) > 5:
            print(f"    … 그 밖 {len(addr_other)-5}건", file=sys.stderr)
    else:
        print("지역 대조: 주소가 배정과 다른 레코드 없음", file=sys.stderr)

    if coord_cleared:
        print(f"\n⚠ 좌표를 비운 레코드 {len(coord_cleared)}건 — 자기 시장에서 "
              f"{COORD_FAR_KM:.0f}km 넘게 떨어져 지도에 그릴 근거가 없다(목록에는 남는다)",
              file=sys.stderr)
        for m in coord_cleared[:5]:
            print(f"    {m['region']} {m['name']}  {m['km']}km  (시장 {m['market']}) "
                  f"원좌표 {m['lat']},{m['lng']}", file=sys.stderr)
    else:
        print("좌표 점검: 시장에서 멀리 떨어진 좌표 없음", file=sys.stderr)

    print(f"\n고유 {len(uniq_rows)}건, brand 부여 {assigned}건 / null {len(uniq_rows)-assigned}", file=sys.stderr)
    print(f"확정 브랜드 {n_brands}종, 제외(7+·지점비율<40%) {len(candidates)-n_brands}종", file=sys.stderr)
    print("확정 상위:", ", ".join(f"{c['brand']}({c['count']})"
          for c in candidates if c["verdict"] == "브랜드")[:1] or "", file=sys.stderr)
    for c in [c for c in candidates if c["verdict"] == "브랜드"][:15]:
        print(f"  브랜드 {c['count']:5d} {c['branch_ratio']:.2f}  {c['brand']}", file=sys.stderr)
    print("제외 표본(7+·저지점비율):", file=sys.stderr)
    for c in [c for c in candidates if c["verdict"] == "제외"][:10]:
        print(f"  제외 {c['count']:5d} {c['branch_ratio']:.2f}  {c['brand']}", file=sys.stderr)


if __name__ == "__main__":
    main()
