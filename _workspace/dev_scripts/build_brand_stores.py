#!/usr/bin/env python3
"""수도권 브랜드·생활매장 라이브 수집 스크립트 (온누리 가맹점찾기 비공식 API).

키워드 사전 × 시도(서울·인천·경기)로 POST /api/v2/onr/place/search를 전 페이지
순회하고, frCd로 중복 제거 후 브랜드 분류를 적용해
data/merchants/brand_stores.json 단일 파일을 생성한다.

재실행:
    python3 _workspace/dev_scripts/build_brand_stores.py --collected-on 2026-08-07

- 비공식 API — 요청 간 1초 스로틀 필수 (서버 예의).
- 집계는 저장하지 않는다 (파생 값 — 렌더 시 계산).
- 분류 불가 행은 출력에서 제외하고 stdout에 표본을 남긴다 (리포트 기록용).
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

KEYWORDS = {
    "convenience": ["CU", "씨유", "GS25", "지에스25", "세븐일레븐", "이마트24", "미니스톱", "편의점"],
    "ssm": ["더프레시", "이마트에브리데이", "홈플러스익스프레스", "롯데슈퍼", "노브랜드"],
    "daiso": ["다이소"],
    "mart": ["마트", "슈퍼", "수퍼", "식자재"],
}

# ---------------------------------------------------------------- 브랜드 분류
# 가맹점명 기준. 구체적 브랜드(SSM) → 편의점 → 다이소 → 일반 마트 순으로 첫 매치.
# build_merchants.py의 오탐 제거 경험 재사용: (?<!세탁)편의점, (?<!스)마트, 노브랜드(?!\s?버거).
BRAND_RULES = [
    # (brand_cat, brand, 정규식)
    ("ssm", "이마트에브리데이", re.compile(r"에브리\s?데이", re.I)),
    ("ssm", "홈플러스익스프레스", re.compile(r"홈플러스\s?익스프레스", re.I)),
    ("ssm", "롯데슈퍼", re.compile(r"롯데\s?슈퍼", re.I)),
    ("ssm", "GS더프레시", re.compile(r"더\s?프레시|THE\s?FRESH", re.I)),
    ("ssm", "노브랜드", re.compile(r"노브랜드(?!\s?버거)", re.I)),
    ("convenience", "CU", re.compile(r"씨유|(?:^|[^A-Za-z0-9])CU(?![A-Za-z0-9])", re.I)),
    ("convenience", "GS25", re.compile(r"GS\s?25|지에스\s?25", re.I)),
    ("convenience", "세븐일레븐", re.compile(r"세븐\s?일레븐|7\s?-?\s?일레븐|세븐\s?ELEVEN", re.I)),
    ("convenience", "이마트24", re.compile(r"이마트\s?24", re.I)),
    ("convenience", "미니스톱", re.compile(r"미니스톱", re.I)),
    ("convenience", "기타 편의점", re.compile(r"(?<!세탁)편의점")),
    ("daiso", "다이소", re.compile(r"다이소")),
    ("mart", "일반", re.compile(r"(?<!스)마트|슈퍼|수퍼|스토아|식자재")),
]


def classify(name: str):
    for cat, brand, rx in BRAND_RULES:
        if rx.search(name):
            return cat, brand
    return None, None


def post(url: str, body: dict) -> dict:
    req = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), headers=HEADERS, method="POST")
    with urllib.request.urlopen(req, timeout=30) as res:
        payload = json.loads(res.read().decode("utf-8"))
    time.sleep(THROTTLE_SEC)  # 비공식 API — 요청 간 1초 스로틀
    if payload.get("resCode") != "0000":
        raise RuntimeError(f"API 오류 {payload.get('resCode')}: {payload.get('resMsg')} ({url})")
    return payload["data"]


def region_of(addr_cd: str) -> str:
    return {"11": "서울", "28": "인천", "41": "경기"}.get((addr_cd or "")[:2], "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="data/merchants/brand_stores.json")
    ap.add_argument("--collected-on", default=date.today().isoformat())
    args = ap.parse_args()

    n_requests = 0

    # 1. 구·군 이름 매핑
    district_names = {}
    for sido in SIDO:
        data = post(API_ADDR.format(sido=sido), {})
        n_requests += 1
        for row in data["list"]:
            district_names[row["addrCd"]] = row["addrNm"]
    print(f"구·군 매핑 {len(district_names)}건 로드", file=sys.stderr)

    # 2. 키워드 × 시도 전 페이지 순회
    raw = {}          # frCd → (row, 최초 매치 키워드)
    kw_counts = {}    # (키워드, 시도명) → API totalCnt
    dup_hits = 0
    all_keywords = [kw for kws in KEYWORDS.values() for kw in kws]
    for kw in all_keywords:
        for sido, sido_nm in SIDO.items():
            page, total_page = 1, 1
            while page <= total_page:
                data = post(API_SEARCH, {"keyword": kw, "addrCd": sido, "currPage": page})
                n_requests += 1
                total_page = data.get("totalPage") or 1
                if page == 1:
                    kw_counts[(kw, sido_nm)] = data.get("totalCnt", 0)
                for row in data.get("list") or []:
                    if row["frCd"] in raw:
                        dup_hits += 1
                    else:
                        raw[row["frCd"]] = (row, kw)
                page += 1
            print(f"  {kw} × {sido_nm}: totalCnt {kw_counts[(kw, sido_nm)]}", file=sys.stderr)

    # 3. 브랜드 분류 (분류 불가 행은 제외하고 표본 기록)
    items, unclassified = [], []
    for fr_cd, (row, kw) in raw.items():
        cat, brand = classify(row["frcsNm"])
        if cat is None:
            unclassified.append((kw, row["frcsNm"], row.get("placeTypeNm", "")))
            continue
        addr_cd = row.get("addrCd", "")
        items.append({
            "id": fr_cd,
            "name": row["frcsNm"].strip(),
            "brand_cat": cat,
            "brand": brand,
            "addr": (row.get("frcsAddr") or "").strip(),
            "district": district_names.get(addr_cd, addr_cd),
            "region": region_of(addr_cd),
            "market": row.get("mrktNm", ""),
            "market_type": row.get("mrktType", ""),
            "paper": row.get("paperYn", ""),
            "card": row.get("cardYn", ""),
            "qr": row.get("qrYn", ""),
            "lat": row.get("latitude"),
            "lng": row.get("longitude"),
        })
    items.sort(key=lambda x: (x["region"], x["brand_cat"], x["brand"], x["name"]))

    payload = {
        "meta": {
            "source": "온누리 가맹점찾기 비공식 API (온누리상품권 공식 홈페이지 〉 가맹점 찾기)",
            "source_url": "https://www.onnuri.gift/place",
            "api_endpoint": API_SEARCH,
            "collected_on": args.collected_on,
            "keywords_used": KEYWORDS,
            "limitations": "비공식 API — 스키마 변경 가능. 가맹 등록 점포만 수록(직영 SSM 미포함). "
                           "키워드 검색 기반이라 상호에 키워드가 없는 점포는 누락될 수 있음.",
            "note": "브랜드 분류는 가맹점명 정규식(규칙: _workspace/dev_scripts/build_brand_stores.py). "
                    "집계는 저장하지 않음 — 렌더 시 items에서 계산할 것.",
        },
        "items": items,
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")

    # 4. 리포트용 통계 (stdout)
    print(f"\n총 요청 {n_requests}회 (스로틀 {THROTTLE_SEC}초)")
    print(f"원시 수집 {len(raw) + dup_hits}행 → 중복 제거 후 고유 {len(raw)}건 (키워드 간 중복 {dup_hits})")
    print(f"분류 완료 {len(items)}건 / 미분류 제외 {len(unclassified)}건 → {out}")

    from collections import Counter
    print("\n[키워드×시도 totalCnt]")
    for (kw, sido_nm), cnt in kw_counts.items():
        print(f"  {kw} × {sido_nm}: {cnt}")
    print("\n[브랜드 분류 집계 — 리포트용, JSON 미저장]")
    for cat in ("convenience", "ssm", "daiso", "mart"):
        sub = Counter(x["brand"] for x in items if x["brand_cat"] == cat)
        print(f"  {cat} {sum(sub.values())}: " + ", ".join(f"{b} {n}" for b, n in sub.most_common()))
    print("  시도별:", dict(Counter(x["region"] for x in items)))

    import random
    random.seed(20260807)
    print(f"\n[미분류 표본 (최대 20 / 전체 {len(unclassified)})]")
    for kw, nm, pt in random.sample(unclassified, min(20, len(unclassified))):
        print(f"  키워드'{kw}' → {nm} | {pt}")


if __name__ == "__main__":
    main()
