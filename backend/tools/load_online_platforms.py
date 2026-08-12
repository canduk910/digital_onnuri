#!/usr/bin/env python3
"""data/online_platforms.json → Postgres online_platform 테이블 초기 적재(멱등 upsert).

Flyway가 V5로 스키마를 만든 뒤 실행한다. id 기준 upsert라 여러 번 돌려도 안전.
- ord = 배열 순서(0-based)
- post_no = null(야간 배치 첫 실행이 name 매칭으로 채운다)
- note·region_limited 등 큐레이션 필드 포함 전체 적재

  python3 backend/tools/load_online_platforms.py
"""
import json, os, sys
from pathlib import Path

try:
    import psycopg  # psycopg3
except ImportError:
    sys.exit("psycopg 필요: pip install 'psycopg[binary]'")

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "data/online_platforms.json"
DSN = os.environ.get("DB_DSN",
    "host=localhost port=5432 dbname=onnuri user=onnuri password=onnuri")

UPSERT = """
INSERT INTO online_platform
    (id, post_no, ord, kind, name, summary, note, url,
     region_limited, source_url, collected_on, status)
VALUES (%s, NULL, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
ON CONFLICT (id) DO UPDATE SET
    ord = EXCLUDED.ord, kind = EXCLUDED.kind, name = EXCLUDED.name,
    summary = EXCLUDED.summary, note = EXCLUDED.note, url = EXCLUDED.url,
    region_limited = EXCLUDED.region_limited, source_url = EXCLUDED.source_url,
    collected_on = EXCLUDED.collected_on, status = EXCLUDED.status
"""


def main():
    d = json.loads(SRC.read_text(encoding="utf-8"))
    meta = d.get("meta", {})
    default_src = meta.get("source_url")
    default_date = meta.get("collected_on")
    items = d.get("items", [])
    with psycopg.connect(DSN) as conn, conn.cursor() as cur:
        rows = []
        for ord_, it in enumerate(items):
            rows.append((
                it["id"], ord_, it["kind"], it["name"],
                it.get("summary"), it.get("note", ""), it.get("url"),
                bool(it.get("region_limited", False)),
                it.get("source_url", default_src),
                it.get("collected_on", default_date),
                it.get("status", "active"),
            ))
        cur.executemany(UPSERT, rows)
        conn.commit()
        cur.execute("SELECT kind, count(*) FROM online_platform GROUP BY kind ORDER BY kind")
        print("적재 검증:", dict(cur.fetchall()), "| 총", len(rows))


if __name__ == "__main__":
    main()
