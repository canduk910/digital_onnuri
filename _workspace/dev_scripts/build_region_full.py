#!/usr/bin/env python3
"""수도권 전체 가맹점 재수집·통합 빌드 (온누리 가맹점찾기 비공식 API).

시도(서울·인천·경기)의 전 구·군 addrCd를 순회하며 빈 키워드로 전 페이지를
수집하고(전수), 주소 계층 파싱·업종 카테고리·브랜드 서브태그를 붙여
data/merchants/{seoul,incheon,gyeonggi}.json을 재생성한다. 공공데이터(CSV)
스냅샷과 키워드 기반 brand_stores.json을 이 통합 데이터가 대체·흡수한다.

멱등 재실행:
    python3 _workspace/dev_scripts/build_region_full.py                 # 캐시 있으면 재사용, 없으면 수집
    python3 _workspace/dev_scripts/build_region_full.py --refresh       # API 재수집(1초 스로틀)
    python3 _workspace/dev_scripts/build_region_full.py --collected-on 2026-08-08

계층 소스 원칙(구·군 addrCd 순회라 상위 레벨을 addrCd로 확정 — 주소 파싱 아티팩트 배제):
- 서울·인천: region → gu(쿼리 addrCd 확정) → dong(주소 괄호 파싱)
- 경기: region → si(쿼리 addrCd 확정) → gu(주소 파싱+화이트리스트) → dong(주소 파싱)

집계·계층 인덱스는 저장하지 않는다(파생 값 — 렌더 시 items에서 계산).
"""

import argparse
import json
import re
import sys
import time
import urllib.request
from datetime import date
from pathlib import Path

API_SEARCH = "https://www.onnuri.gift/api/v2/onr/place/search"
API_ADDR = "https://www.onnuri.gift/api/v2/onr/addr/{sido}"
HEADERS = {
    "Content-Type": "application/json",
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36",
    "Referer": "https://www.onnuri.gift/place",
}
THROTTLE_SEC = 1.0
SIDO = {"11000": "서울", "28000": "인천", "41000": "경기"}
CACHE = Path("_workspace/raw/capital_merchants_raw.json")
OUT_DIR = Path("data/merchants")
REGION_FILE = {"서울": "seoul", "인천": "incheon", "경기": "gyeonggi"}

# 경기 실제 일반구 화이트리스트 — 주소 파싱 아티팩트(타 시도 구 오등록) 차단
GYEONGGI_GU = {
    "장안구", "권선구", "팔달구", "영통구", "수정구", "중원구", "분당구",
    "만안구", "동안구", "상록구", "단원구", "덕양구", "일산동구", "일산서구",
    "처인구", "기흥구", "수지구", "원미구", "소사구", "오정구",
}

# ------------------------------------------------------------- 업종 카테고리
# 이름 규칙(오탐 가드 재사용) 우선 → placeTypeNm(업종) 매핑.
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

# ------------------------------------------------------- 브랜드 서브태그(체인)
# brand_stores.py 규칙 계승. cat과 별개로, 브랜드 체인 필터용. 해당 없으면 None.
BRAND_RULES = [
    # 이마트에브리데이만 SSM — 바레 "에브리데이"(골프·의류 등)는 제외
    ("ssm", re.compile(r"이마트\s?에브리\s?데이|홈플러스\s?익스프레스|롯데\s?슈퍼|더\s?프레시|THE\s?FRESH|노브랜드(?!\s?버거)", re.I)),
    ("convenience", re.compile(
        r"씨유|(?:^|[^A-Za-z0-9])CU(?![A-Za-z0-9])|GS\s?25|지에스\s?25"
        r"|세븐\s?일레븐|이마트\s?24|미니스톱|(?<!세탁)편의점", re.I)),
    ("daiso", re.compile(r"다이소")),
    ("mart", re.compile(r"(?<!스)마트|슈퍼|수퍼|스토아|식자재")),
]


def categorize(name, biz_type):
    for cat, rx in NAME_RULES:
        if rx.search(name):
            return cat
    return BIZ_MAP.get(biz_type, "기타")


def brand_tag(name):
    for tag, rx in BRAND_RULES:
        if rx.search(name):
            return tag
    return None


# --------------------------------------------------------------- 주소 파싱
def region_prefix(tok):
    for r in ("서울", "인천", "경기"):
        if tok.startswith(r):
            return r
    return None


def parse_addr(addr, region):
    """주소에서 (구, 동) 파싱."""
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


def collect(collected_on):
    n_req = 0
    districts = []
    for sido, sido_nm in SIDO.items():
        data = post(API_ADDR.format(sido=sido), {})
        n_req += 1
        for row in data["list"]:
            districts.append((row["addrCd"], row["addrNm"], sido_nm))
    print(f"구·군 {len(districts)}개 로드", file=sys.stderr)
    rows, district_counts = [], {}
    for addr_cd, addr_nm, sido_nm in districts:
        page, total_page, got = 1, 1, 0
        while page <= total_page:
            data = post(API_SEARCH, {"keyword": "", "addrCd": addr_cd, "currPage": page})
            n_req += 1
            total_page = data.get("totalPage") or 1
            for r in data.get("list") or []:
                r["_query_addrNm"] = addr_nm
                r["_query_sido"] = sido_nm
                rows.append(r)
                got += 1
            page += 1
        district_counts[f"{sido_nm} {addr_nm}"] = data.get("totalCnt", got)
        print(f"  {sido_nm} {addr_nm}({addr_cd}): {got}건", file=sys.stderr)
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    with open(CACHE, "w", encoding="utf-8") as f:
        json.dump({"collected_on": collected_on, "n_requests": n_req,
                   "district_counts": district_counts, "rows": rows}, f, ensure_ascii=False)
    print(f"총 요청 {n_req}회, 원시 {len(rows)}행 → {CACHE}", file=sys.stderr)
    return {"collected_on": collected_on, "rows": rows, "district_counts": district_counts}


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

    by_region = {"서울": [], "인천": [], "경기": []}
    from collections import Counter
    cat_counter, brand_counter = Counter(), Counter()
    seen = set()
    dong_missing = 0

    for r in rows:
        fr = r["frCd"]
        if fr in seen:
            continue
        seen.add(fr)
        region = r["_query_sido"]
        query_nm = r["_query_addrNm"]
        name = (r["frcsNm"] or "").strip()
        cat = categorize(name, r.get("placeTypeNm", ""))
        btag = brand_tag(name)
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
            "id": fr,
            "name": name,
            "cat": cat,
            "brand": btag,
            "si": si,
            "gu": gu,
            "dong": dong,
            "addr": (r.get("frcsAddr") or "").strip(),
            "market": r.get("mrktNm", ""),
            "market_type": r.get("mrktType", ""),
            "paper": r.get("paperYn", ""),
            "card": r.get("cardYn", ""),
            "qr": r.get("qrYn", ""),
            "lat": r.get("latitude"),
            "lng": r.get("longitude"),
        })
        cat_counter[(region, cat)] += 1
        if btag:
            brand_counter[(region, btag)] += 1

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
                "scope": "수도권 전수 — 시도 전 구·군 addrCd 순회, 빈 키워드로 전 페이지 수집",
                "hierarchy": (["시도", "구", "동"] if region != "경기" else ["시도", "시", "구", "동"]),
                "cats": ["음식점", "편의점", "마트·슈퍼", "약국", "학원", "의류·신발",
                         "농축수산·식품", "생활·잡화", "생활서비스", "기타"],
                "brands": ["convenience", "mart", "ssm", "daiso"],
                "limitations": "비공식 API — 스키마 변경 가능. 전수 수집이라 누락은 거의 없음. "
                               "행정구역 중 구(서울·인천)·시(경기)는 API addrCd 원천으로 정확, "
                               "동은 도로명주소 괄호 파싱 파생값이라 약 7%가 null(법정동 미표기), "
                               "경기 일반구도 주소 파싱(화이트리스트 검증).",
                "note": "cat=업종 카테고리(가맹점명+placeTypeNm 규칙), brand=브랜드 체인 서브태그(없으면 null). "
                        "집계·구/동 목록은 저장하지 않음 — 렌더 시 items에서 계산. "
                        "규칙: _workspace/dev_scripts/build_region_full.py",
            },
            "items": items,
        }
        path = OUT_DIR / f"{code}.json"
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")
        print(f"{path}: {len(items)}건")

    # ---- 리포트용 통계(stderr) ----
    print(f"\n고유 {len(seen)}건, 동 결측 {dong_missing} ({dong_missing/len(seen)*100:.1f}%)", file=sys.stderr)
    cats = ["음식점", "편의점", "마트·슈퍼", "약국", "학원", "의류·신발",
            "농축수산·식품", "생활·잡화", "생활서비스", "기타"]
    print("[시도×카테고리]", file=sys.stderr)
    for region in ("서울", "인천", "경기"):
        print(f"  {region}: " + " ".join(f"{c}{cat_counter[(region, c)]}" for c in cats), file=sys.stderr)
    print("[브랜드 서브태그]", file=sys.stderr)
    for region in ("서울", "인천", "경기"):
        print(f"  {region}: " + " ".join(f"{b}{brand_counter[(region, b)]}"
              for b in ("convenience", "mart", "ssm", "daiso")), file=sys.stderr)


if __name__ == "__main__":
    main()
