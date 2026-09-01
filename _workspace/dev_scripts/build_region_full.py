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

# 경기 실제 일반구 화이트리스트 — 주소 파싱 아티팩트(타 시도 구 오등록) 차단
GYEONGGI_GU = {
    "장안구", "권선구", "팔달구", "영통구", "수정구", "중원구", "분당구",
    "만안구", "동안구", "상록구", "단원구", "덕양구", "일산동구", "일산서구",
    "처인구", "기흥구", "수지구", "원미구", "소사구", "오정구",
}

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
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--collected-on", default=date.today().isoformat())
    ap.add_argument("--refresh", action="store_true", help="API 재수집(캐시 무시)")
    args = ap.parse_args()

    if args.refresh or not CACHE.exists():
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
        else:
            si = None
            gu = query_nm
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
