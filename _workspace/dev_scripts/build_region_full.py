#!/usr/bin/env python3
"""수도권 전체 가맹점 재수집·통합 빌드 + 빈도·지점명 기반 브랜드 자동탐지.

시도(서울·인천·경기)의 전 구·군 addrCd를 순회하며 빈 키워드로 전 페이지를
수집하고(전수), 주소 계층 파싱·업종 카테고리·브랜드 자동탐지를 붙여
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
import re
import sys
import time
import urllib.request
from collections import Counter, defaultdict
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
                "scope": "전수 — 시도 전 구·군 addrCd 순회, 빈 키워드로 전 페이지 수집",
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
