#!/usr/bin/env python3
"""naver_shop_check.py — 온누리 온라인몰의 네이버쇼핑 입점 여부 확인 (2026-09-03, 20절 A10)

무엇을 하나: 네이버 검색 API(쇼핑)로 흔한 상품 낱말을 조회해 결과의 mallName 을 모으고,
data/online_platforms.json 의 쇼핑몰 이름과 대조한다. 입점해 있으면 그 몰의 상품명을
사이트를 건드리지 않고 네이버에서 얻을 수 있다(robots 와 무관한 경로).

왜 키가 필요한가: 네이버쇼핑 웹 검색은 봇을 차단(418)한다. 공식 API 는 developers.naver.com 에서
애플리케이션을 만들고 '검색' API 를 켜면 Client ID·Secret 이 나온다(일 25,000건 무료).

키는 서버 backend/deploy/.env 에 NAVER_API_KEY / NAVER_API_SECRET 로 둔다(NAVER_CLIENT_ID / NAVER_CLIENT_SECRET 도 인식) — 저장소·채팅에 적지 않는다.

사용:
  export NAVER_API_KEY=... NAVER_API_SECRET=...   # 또는 --env backend/deploy/.env
  python3 backend/tools/naver_shop_check.py [--env PATH] [--words 김치,사과,쌀] [--pages 3]
"""
import argparse, json, os, re, sys, time, urllib.parse, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
API = "https://openapi.naver.com/v1/search/shop.json"
DEFAULT_WORDS = ["김치", "사과", "쌀", "선물세트", "고등어", "청소기", "건어물", "한우", "반찬", "과일"]

def load_env(path):
    for ln in Path(path).read_text(encoding="utf-8").splitlines():
        m = re.match(r"^\s*(NAVER_API_KEY|NAVER_API_SECRET|NAVER_CLIENT_ID|NAVER_CLIENT_SECRET)\s*=\s*(.+?)\s*$", ln)
        if m: os.environ.setdefault(m.group(1), m.group(2).strip('"\''))

def norm(s):
    return re.sub(r"[\s·\-_()\[\]]", "", (s or "")).lower()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--env"); ap.add_argument("--words", default=",".join(DEFAULT_WORDS))
    ap.add_argument("--pages", type=int, default=3, help="낱말당 100건 페이지 수(최대 10)")
    a = ap.parse_args()
    if a.env: load_env(a.env)
    cid = os.environ.get("NAVER_API_KEY") or os.environ.get("NAVER_CLIENT_ID")
    sec = os.environ.get("NAVER_API_SECRET") or os.environ.get("NAVER_CLIENT_SECRET")
    if not cid or not sec:
        sys.exit("NAVER_API_KEY / NAVER_API_SECRET 이 없다 — backend/deploy/.env 에 넣고 --env 로 지정")
    items = json.loads((ROOT / "data" / "online_platforms.json").read_text(encoding="utf-8"))["items"]
    malls = {p["id"]: p["name"] for p in items if p.get("kind") == "shopping"}
    # 몰 이름의 흔한 변형(네이버쇼핑 mallName 은 운영사 등록명이라 우리 표기와 다를 수 있다)
    aliases = {pid: {norm(n)} for pid, n in malls.items()}
    aliases["onnuri-goodday"] |= {"온누리굿데이", "굿데이"}
    aliases["inthemarket-onnuri"] |= {"인더마켓", "inthemarket"}
    aliases["onnuri-paldo-sijang"] |= {"팔도시장", "온누리팔도시장", "e장터", "이장터"}
    seen = {}      # mallName → 건수
    hit = {}       # pid → set(mallName)
    calls = 0
    for w in [x.strip() for x in a.words.split(",") if x.strip()]:
        for pg in range(a.pages):
            q = urllib.parse.urlencode({"query": w, "display": 100, "start": 1 + pg * 100})
            req = urllib.request.Request(f"{API}?{q}", headers={"X-Naver-Client-Id": cid, "X-Naver-Client-Secret": sec})
            try:
                with urllib.request.urlopen(req, timeout=15) as r: d = json.load(r)
            except urllib.error.HTTPError as e:
                sys.exit(f"API 오류 {e.code}: {e.read()[:200]!r} (키·쿼터 확인)")
            calls += 1
            for it in d.get("items", []):
                mn = it.get("mallName", "")
                seen[mn] = seen.get(mn, 0) + 1
                nm = norm(mn)
                for pid, al in aliases.items():
                    if any(x and (x in nm or nm in x) for x in al):
                        hit.setdefault(pid, set()).add(mn)
            if len(d.get("items", [])) < 100: break
            time.sleep(0.2)
    print(f"호출 {calls}건 · 서로 다른 mallName {len(seen)}종")
    print("\n[입점 확인된 온누리몰]")
    for pid, names in sorted(hit.items()):
        print(f"  {malls[pid]} ({pid}) ← mallName: {', '.join(sorted(names))}")
    if not hit: print("  (없음 — 조회 낱말·페이지를 늘리거나 mallName 변형을 aliases 에 추가)")
    print("\n[참고: 상위 mallName 20]")
    for mn, c in sorted(seen.items(), key=lambda x: -x[1])[:20]: print(f"  {c:4d}  {mn}")

if __name__ == "__main__":
    main()
