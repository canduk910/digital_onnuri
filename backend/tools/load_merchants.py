#!/usr/bin/env python3
"""data/merchants/{seoul,incheon,gyeonggi}.json → Postgres merchant 테이블 적재.

Flyway가 스키마를 만든 뒤(백엔드 1회 부팅) 실행한다. 멱등: TRUNCATE 후 재적재.
psycopg 사용. 접속정보는 환경변수 또는 기본값(docker-compose와 동일).

  python3 backend/tools/load_merchants.py
"""
import json, os, sys
from pathlib import Path

try:
    import psycopg  # psycopg3
except ImportError:
    sys.exit("psycopg 필요: pip install 'psycopg[binary]'")

ROOT = Path(__file__).resolve().parents[2]
FILES = {
    "서울": ROOT / "data/merchants/seoul.json",
    "인천": ROOT / "data/merchants/incheon.json",
    "경기": ROOT / "data/merchants/gyeonggi.json",
    "부산": ROOT / "data/merchants/busan.json",
}
DSN = os.environ.get("DB_DSN",
    "host=localhost port=5432 dbname=onnuri user=onnuri password=onnuri")

COLS = ["id","name","cat","brand","region","si","gu","dong","addr",
        "market","market_type","paper","card","qr","lat","lng"]

def rows_from(region, path):
    d = json.loads(path.read_text(encoding="utf-8"))
    for it in d.get("items", []):
        yield (
            it.get("id"), it.get("name"), it.get("cat"), it.get("brand"),
            region, it.get("si"), it.get("gu"), it.get("dong"), it.get("addr"),
            it.get("market"), it.get("market_type"), it.get("paper"),
            it.get("card"), it.get("qr"), it.get("lat"), it.get("lng"),
        )

def collected_on_of(path):
    """파일 meta.collected_on — 없으면 None."""
    try:
        return (json.loads(path.read_text(encoding="utf-8")).get("meta") or {}).get("collected_on")
    except Exception:
        return None

def main():
    with psycopg.connect(DSN) as conn, conn.cursor() as cur:
        cur.execute("TRUNCATE merchant")
        total = 0
        placeholders = "(" + ",".join(["%s"] * len(COLS)) + ")"
        sql = f"INSERT INTO merchant ({','.join(COLS)}) VALUES {placeholders} " \
              f"ON CONFLICT (id) DO NOTHING"
        for region, path in FILES.items():
            batch = list(rows_from(region, path))
            cur.executemany(sql, batch)
            print(f"  {region}: {len(batch)}건")
            total += len(batch)
        # 수집일 스탬프 — 확인 안 한 항목 날짜를 올리지 않도록 min(collected_on).
        dates = [d for d in (collected_on_of(p) for p in FILES.values()) if d]
        if dates:
            cur.execute(
                "INSERT INTO app_meta(k, v) VALUES('merchants_collected_on', %s) "
                "ON CONFLICT (k) DO UPDATE SET v = EXCLUDED.v", (min(dates),))
            print(f"  merchants_collected_on = {min(dates)}")
        conn.commit()
        cur.execute("SELECT region, count(*) FROM merchant GROUP BY region ORDER BY region")
        print("적재 검증:", dict(cur.fetchall()), "| 총", total)

if __name__ == "__main__":
    main()
