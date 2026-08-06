#!/usr/bin/env python3
"""수도권 온누리 가맹점 검색 데이터 빌드 스크립트.

공공데이터포털 "소상공인시장진흥공단_전국 온누리상품권 가맹점 현황" CSV를
서울·인천·경기로 필터링하고 카테고리를 부여해 data/merchants/*.json을 생성한다.

재실행 (예: 8/12 신규 데이터):
    python3 _workspace/dev_scripts/build_merchants.py \
        --csv _workspace/raw/onnuri_merchants.csv \
        --dataset-version 2025-07-31

- 개수·카테고리 집계는 저장하지 않는다 (파생 값 — 렌더 시 계산).
- id는 행 내용 해시라 순서가 바뀌어도 안정적. 동일 내용 중복 행은 -2, -3 접미사.
- 소재지는 시/도 단위뿐 (출처 한계) — meta.limitations에 명시.
"""

import argparse
import csv
import hashlib
import json
import re
from collections import Counter
from datetime import date
from pathlib import Path

REGIONS = {"서울": "seoul", "인천": "incheon", "경기": "gyeonggi"}

SOURCE = "공공데이터포털 〉 소상공인시장진흥공단_전국 온누리상품권 가맹점 현황"
SOURCE_URL = "https://www.data.go.kr/data/3060079/fileData.do"
LIMITATIONS = (
    "소재지가 시/도 단위뿐이라 상세 주소·좌표 없음 — 위치 상세는 소속 시장명(market)이 유일. "
    "출처 데이터 자체의 한계이며 결함이 아님. 점포 단위 위치 확인은 onnuri.gift/place 가맹점 지도 참조."
)

# ---------------------------------------------------------------- 카테고리 규칙
# 가맹점명+취급품목 결합 텍스트에 정규식 적용. 우선순위 순서대로 첫 매치가 카테고리.
# 브랜드명 나열은 편의점만 — 나머지는 업종 키워드로 일반화.
RULES = [
    ("편의점", re.compile(
        r"씨유|(?:^|[^A-Za-z0-9])CU(?![A-Za-z0-9])|GS\s?25|지에스\s?25"
        r"|세븐\s?일레븐|7\s?-?\s?일레븐|이마트\s?24|미니스톱|(?<!세탁)편의점", re.I)),
    ("약국", re.compile(r"약국")),
    # 학원(?!가): "평촌학원가점" 등 음식점 지점명의 '학원가' 오탐 제거
    ("학원", re.compile(r"학원(?!가)|교습소")),
    # (?<!스)마트: '스마트폰' 등 오탐 제거. 음식료품: 표준산업분류
    # "기타 음식료품 위주 종합 소매업"(소형 식료품점) — 음식점보다 먼저 매치시켜 소매로 분류
    ("마트·슈퍼", re.compile(r"(?<!스)마트|슈퍼|수퍼|스토아|음식료품")),
    ("음식점", re.compile(
        # 취급품목 계열: 업종·메뉴 키워드 (식음료 접객 위주 — 떡·반찬·정육 등 시장 소매는 제외)
        r"한식|중식|일식|양식|분식|음식|식당|반점|주점|포차|호프|맥주"
        r"|치킨|피자|족발|보쌈|국밥|김밥|초밥|스시|횟집|짜장|짬뽕"
        r"|국수|칼국수|냉면|막국수|수제비|우동|라멘|쌀국수|마라탕"
        r"|삼겹|갈비|곱창|막창|닭갈비|닭발|백숙|전골|찌개|해장국|설렁탕|감자탕"
        r"|백반|덮밥|비빔밥|돈까스|돈가스|카레|버거|샌드위치|토스트"
        r"|떡볶이|순대|튀김|도시락|만두|죽집"
        r"|커피|카페|베이커리|제과|제빵|빵집|케이크|디저트")),
]
OTHER = "기타"


def categorize(name: str, items: str) -> str:
    text = f"{name} {items}"
    for label, rx in RULES:
        if rx.search(text):
            return label
    return OTHER


def multi_matches(name: str, items: str) -> list:
    """오분류 위험 진단용 — 2개 이상 규칙에 걸리는 행의 매치 목록."""
    text = f"{name} {items}"
    return [label for label, rx in RULES if rx.search(text)]


def make_id(region_code: str, name: str, market: str, items: str, year: str, seen: dict) -> str:
    raw = "|".join((region_code, name, market, items, year))
    h = hashlib.sha1(raw.encode("utf-8")).hexdigest()[:10]
    n = seen.get(h, 0) + 1
    seen[h] = n
    return f"{region_code[:2]}-{h}" if n == 1 else f"{region_code[:2]}-{h}-{n}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", default="_workspace/raw/onnuri_merchants.csv")
    ap.add_argument("--out", default="data/merchants")
    ap.add_argument("--dataset-version", default="2025-07-31",
                    help="원본 데이터 기준일 (공공데이터포털 표기)")
    ap.add_argument("--generated-on", default=date.today().isoformat())
    ap.add_argument("--next-update", default="2026-08-12",
                    help="공공데이터포털 '차기 등록 예정일' — 페이지가 갱신 예정 안내에 사용")
    args = ap.parse_args()

    by_region = {code: [] for code in REGIONS.values()}
    seen_hashes = {code: {} for code in REGIONS.values()}
    counts = Counter()
    risk_rows = []

    with open(args.csv, encoding="utf-8-sig") as f:
        reader = csv.reader(f)
        next(reader)  # 헤더
        for row in reader:
            name, market, region, items, paper, digital, year = (x.strip() for x in row)
            code = REGIONS.get(region)
            if not code:
                continue
            cat = categorize(name, items)
            counts[(region, cat)] += 1
            matched = multi_matches(name, items)
            if len(matched) >= 2:
                risk_rows.append((region, name, items, matched, cat))
            by_region[code].append({
                "id": make_id(code, name, market, items, year, seen_hashes[code]),
                "name": name,
                "market": market,
                "category": cat,
                "items": items,
                "paper": paper,
                "digital": digital,
                "year": year,
            })

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    for region, code in REGIONS.items():
        payload = {
            "meta": {
                "source": SOURCE,
                "source_url": SOURCE_URL,
                "dataset_version": args.dataset_version,
                "generated_on": args.generated_on,
                "next_update": args.next_update,
                "region": region,
                "limitations": LIMITATIONS,
                "note": "카테고리는 가맹점명+취급품목 정규식 분류(규칙: _workspace/dev_scripts/build_merchants.py). "
                        "개수·카테고리 집계는 저장하지 않음 — 렌더 시 items에서 계산할 것.",
            },
            "items": by_region[code],
        }
        path = out_dir / f"{code}.json"
        with open(path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"{path}: {len(by_region[code])}건")

    print("\n[시도×카테고리 집계 — 리포트 기록용, JSON에는 저장 안 함]")
    cats = [label for label, _ in RULES] + [OTHER]
    for region in REGIONS:
        line = "  ".join(f"{c} {counts[(region, c)]}" for c in cats)
        print(f"  {region}: {line}")
    total = Counter()
    for (_, c), v in counts.items():
        total[c] += v
    print("  합계:", "  ".join(f"{c} {total[c]}" for c in cats))

    print(f"\n[복수 규칙 매치(오분류 위험) {len(risk_rows)}건 — 표본 20]")
    import random
    random.seed(20260806)
    for region, name, items, matched, cat in random.sample(risk_rows, min(20, len(risk_rows))):
        print(f"  [{region}] {name} | {items} | 매치={matched} → 판정={cat}")


if __name__ == "__main__":
    main()
