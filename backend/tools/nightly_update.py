#!/usr/bin/env python3
"""야간 배치(서버 cron 00:30) — 가맹점·온라인 플랫폼 데이터 자동 갱신.

네 단계가 서로 독립적으로 fail-open 한다(ADR-14, D는 ADR-16):
  A 가맹점  — 공식 API 전수 재수집 → stage 테이블 적재 → ±20% 가드 → 무중단 stage-swap
  B 온라인  — 공식 e-commerce API 순회 → upsert(post_no/이름 매칭, 큐레이션 필드 보존)
  C RAG     — OPENAI_API_KEY 있을 때만 코퍼스 재빌드
  D 채록    — 온라인 취급품목·브랜드 변화 **탐지만**(하루 3~4곳 순환, 자동 반영 없음)

배치 전체 실패(exit≠0)로 치는 것은 **A 단계 실패뿐**이다. B·C 실패는 로그만 남기고 기존 데이터를 유지한다.

  python3 backend/tools/nightly_update.py                        # 전체
  python3 backend/tools/nightly_update.py --skip-online --skip-rag
  python3 backend/tools/nightly_update.py --no-collect           # 재수집 생략(기존 JSON으로 스왑만 — 로컬 검증용)

접속정보는 DB_DSN env(기본은 docker-compose와 동일). 비밀값 하드코딩 없음.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
import urllib.error
from datetime import date, datetime
from pathlib import Path

try:
    import psycopg
    from psycopg import sql
except ImportError:
    sys.exit("psycopg 필요: pip install 'psycopg[binary]'")

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parents[1]                          # 리포 루트(backend/tools → 루트)
sys.path.insert(0, str(TOOLS))
import load_merchants as lm                       # COLS·FILES·rows_from 재사용

DSN = os.environ.get("DB_DSN",
    "host=localhost port=5432 dbname=onnuri user=onnuri password=onnuri")

REGIONS = ["서울", "인천", "경기", "부산"]
GUARD_TOLERANCE = 0.20          # 지역별 ±20%
GUARD_MIN_TOTAL = 50_000        # 총계 하한

ONLINE_API = "https://www.onnuri.gift/api/v2/onr/e-commerce/platform"
ONLINE_REFERER = "https://www.onnuri.gift/visit/market"
ONLINE_SOURCE_URL = "https://www.onnuri.gift/visit/market"
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/125.0 Safari/537.36")


def log(msg):
    print(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {msg}", flush=True)


# ---------------------------------------------------------------- 단계 A: 가맹점
def stage_a_merchants(conn, today, no_collect):
    t0 = time.time()
    log("=== 단계 A: 가맹점 (stage-swap) ===")

    # 1) 공식 API 전수 재수집(--no-collect면 기존 data/merchants/*.json 그대로 사용)
    if no_collect:
        log("A1 재수집 생략(--no-collect) — 기존 data/merchants/*.json으로 스왑 검증")
    else:
        log(f"A1 build_region_full.py --refresh --collected-on {today} 실행")
        r = subprocess.run(
            [sys.executable, "_workspace/dev_scripts/build_region_full.py",
             "--refresh", "--collected-on", today],
            cwd=str(ROOT))
        if r.returncode != 0:
            log(f"A 실패: 재수집 비정상 종료(exit={r.returncode}). 기존 데이터 유지.")
            return False
        log("A1 재수집 완료")

    with conn.cursor() as cur:
        # 2) stage 테이블 생성(멱등: 이전 실패 잔재 제거) + 적재
        cur.execute("DROP TABLE IF EXISTS merchant_stage")
        cur.execute("CREATE TABLE merchant_stage (LIKE merchant INCLUDING ALL)")
        placeholders = "(" + ",".join(["%s"] * len(lm.COLS)) + ")"
        insert = (f"INSERT INTO merchant_stage ({','.join(lm.COLS)}) VALUES {placeholders} "
                  f"ON CONFLICT (id) DO NOTHING")
        stage_counts = {}
        for region in REGIONS:
            path = lm.FILES[region]
            batch = list(lm.rows_from(region, path))
            cur.executemany(insert, batch)
            stage_counts[region] = len(batch)
        cur.execute("SELECT region, count(*) FROM merchant_stage GROUP BY region")
        stage_db = dict(cur.fetchall())
        total = sum(stage_db.values())
        log(f"A2 stage 적재: {stage_db} | 총 {total}")

        # 3) 가드: 지역별 ±20% + 총계 하한
        cur.execute("SELECT region, count(*) FROM merchant GROUP BY region")
        cur_db = dict(cur.fetchall())
        reasons = []
        if total < GUARD_MIN_TOTAL:
            reasons.append(f"총계 {total} < {GUARD_MIN_TOTAL}")
        for region in REGIONS:
            new = stage_db.get(region, 0)
            old = cur_db.get(region, 0)
            if old > 0:
                delta = abs(new - old) / old
                if delta > GUARD_TOLERANCE:
                    reasons.append(f"{region} {old}→{new} ({delta:+.1%})")
        if reasons:
            cur.execute("DROP TABLE merchant_stage")
            conn.commit()
            log(f"A 실패: 가드 위반 — {'; '.join(reasons)}. 스왑 중단, 기존 유지.")
            return False
        log(f"A3 가드 통과(기존 {cur_db} 대비 ±{GUARD_TOLERANCE:.0%} 이내)")
    conn.commit()

    # 4) 무중단 스왑(한 트랜잭션)
    with conn.transaction():
        with conn.cursor() as cur:
            cur.execute("ALTER TABLE merchant RENAME TO merchant_old")
            cur.execute("ALTER TABLE merchant_stage RENAME TO merchant")
    log("A4 스왑 완료(merchant ← merchant_stage)")

    # 5) 정리: old 제거 + 인덱스/제약 이름 정규화(merchant_stage_* → merchant_*) + ANALYZE
    with conn.cursor() as cur:
        cur.execute("DROP TABLE merchant_old")
        _canonicalize_index_names(cur)
    conn.commit()
    with conn.cursor() as cur:
        cur.execute("ANALYZE merchant")
    conn.commit()
    # 수집일 스탬프 기록 — 프론트가 API 모드에서 이 값으로 "○○ 수집"을 표시한다.
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO app_meta(k, v) VALUES('merchants_collected_on', %s) "
            "ON CONFLICT (k) DO UPDATE SET v = EXCLUDED.v", (today,))
    conn.commit()
    log(f"A5 정리 완료(old drop·인덱스명 정규화·ANALYZE·수집일 {today} 기록) — 소요 {time.time()-t0:.1f}s")
    log("A 판정: OK")
    return True


def _canonicalize_index_names(cur):
    """LIKE INCLUDING ALL이 만든 merchant_stage_* 인덱스/제약 이름을 merchant_* 로 되돌린다.

    스왑 후에도 이름이 merchant_stage_* 로 남으면 다음날 stage 생성이 이름 충돌한다.
    접두어만 치환하므로 멱등(정규화 후엔 대상이 없어 no-op).
    """
    cur.execute("SELECT indexname FROM pg_indexes "
                "WHERE tablename='merchant' AND indexname LIKE 'merchant\\_stage\\_%'")
    for (name,) in cur.fetchall():
        new = "merchant_" + name[len("merchant_stage_"):]
        cur.execute(sql.SQL("ALTER INDEX {} RENAME TO {}").format(
            sql.Identifier(name), sql.Identifier(new)))
    cur.execute("SELECT conname FROM pg_constraint "
                "WHERE conrelid='merchant'::regclass AND conname LIKE 'merchant\\_stage\\_%'")
    for (name,) in cur.fetchall():
        new = "merchant_" + name[len("merchant_stage_"):]
        cur.execute(sql.SQL("ALTER TABLE merchant RENAME CONSTRAINT {} TO {}").format(
            sql.Identifier(name), sql.Identifier(new)))


# --------------------------------------------------------------- 단계 B: 온라인
def _norm_name(s):
    return re.sub(r"\s+", "", s or "").lower()


def _fetch_online_page(page):
    body = json.dumps({"currPage": page, "mobYn": "N", "categoryNm": ""}).encode("utf-8")
    req = urllib.request.Request(ONLINE_API, data=body, method="POST", headers={
        "Content-Type": "application/json",
        "User-Agent": UA,
        "Referer": ONLINE_REFERER,
        "Accept": "application/json",
    })
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _collect_online():
    """공식 API 전 페이지 순회 → 정규화 레코드 리스트. 가드 실패 시 (None, 사유)."""
    try:
        first = _fetch_online_page(1)
    except (urllib.error.URLError, TimeoutError, ValueError) as e:
        return None, f"요청 실패: {e}"
    if first.get("resCode") != "0000":
        return None, f"resCode={first.get('resCode')}"
    data = first.get("data", {})
    total_cnt = data.get("totalCnt", 0)
    total_page = data.get("totalPage", 1)
    if total_cnt < 10:
        return None, f"totalCnt={total_cnt} < 10"

    raw = list(data.get("list", []))
    for p in range(2, total_page + 1):
        time.sleep(1)                            # 1초 스로틀
        try:
            page = _fetch_online_page(p)
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            return None, f"{p}페이지 요청 실패: {e}"
        if page.get("resCode") != "0000":
            return None, f"{p}페이지 resCode={page.get('resCode')}"
        raw.extend(page.get("data", {}).get("list", []))

    recs = []
    for it in raw:
        cat = (it.get("ecommerceCategoryNm") or "").strip()
        if cat == "쇼핑":
            kind = "shopping"
        elif cat == "배달":
            kind = "delivery"
        else:
            kind = cat.lower()
            log(f"  B 미지정 카테고리 '{cat}' → kind='{kind}' 보존")
        c1 = (it.get("ecommerceIntroCn1") or "").strip()
        c2 = (it.get("ecommerceIntroCn2") or "").strip()
        summary = ", ".join([x for x in (c1, c2) if x]) or None
        recs.append({
            "post_no": it.get("postNo"),
            "ord": it.get("ordNo"),
            "kind": kind,
            "name": (it.get("ecommerceMgtNm") or "").strip(),
            "summary": summary,
            "url": it.get("ecommerceUrl"),
        })
    return recs, None


def stage_b_online(conn, today):
    t0 = time.time()
    log("=== 단계 B: 온라인 플랫폼 ===")
    recs, reason = _collect_online()
    if recs is None:
        log(f"B 스킵: {reason}. 기존 유지(배치 실패 아님).")
        return
    log(f"B1 수집 {len(recs)}건")

    # upsert + removed 마킹 전체를 한 트랜잭션으로 — 부분 갱신 후 crash 시에도 원자성 보장.
    with conn.transaction(), conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM online_platform")
        before = cur.fetchone()[0]
        cur.execute("SELECT id, post_no, name, status FROM online_platform")
        db_rows = cur.fetchall()
        by_post = {r[1]: r[0] for r in db_rows if r[1] is not None}
        by_name = {_norm_name(r[2]): r[0] for r in db_rows}

        matched_ids = set()
        inserted = updated = 0
        for r in recs:
            # 매칭: post_no 우선, 없으면 정규화 이름
            mid = by_post.get(r["post_no"]) if r["post_no"] is not None else None
            if mid is None:
                mid = by_name.get(_norm_name(r["name"]))
            if mid is not None:
                # 수집 가능 필드만 갱신 — note·region_limited·id·source_url·search_url_template 은
                # 절대 미변경(큐레이션 보존). 갱신 컬럼을 명시적으로 나열하는 방식이라
                # 새 큐레이션 컬럼은 자동으로 보존된다 — 여기에 컬럼을 함부로 더하지 말 것.
                cur.execute(
                    "UPDATE online_platform SET name=%s, kind=%s, summary=%s, url=%s, "
                    "ord=%s, post_no=%s, collected_on=%s, status='active' WHERE id=%s",
                    (r["name"], r["kind"], r["summary"], r["url"],
                     r["ord"], r["post_no"], today, mid))
                matched_ids.add(mid)
                updated += 1
            else:
                new_id = f"ec-{r['post_no']}"
                cur.execute(
                    "INSERT INTO online_platform "
                    "(id, post_no, ord, kind, name, summary, note, url, "
                    " region_limited, source_url, collected_on, status) "
                    "VALUES (%s,%s,%s,%s,%s,%s,'',%s,FALSE,%s,%s,'active') "
                    "ON CONFLICT (id) DO UPDATE SET "
                    "  post_no=EXCLUDED.post_no, ord=EXCLUDED.ord, kind=EXCLUDED.kind, "
                    "  name=EXCLUDED.name, summary=EXCLUDED.summary, url=EXCLUDED.url, "
                    "  collected_on=EXCLUDED.collected_on, status='active'",
                    (new_id, r["post_no"], r["ord"], r["kind"], r["name"],
                     r["summary"], r["url"], ONLINE_SOURCE_URL, today))
                matched_ids.add(new_id)
                inserted += 1

        # 수집에 없는 기존 active 행 → removed(삭제 금지, collected_on 유지)
        removed = 0
        for rid, _pno, _nm, status in db_rows:
            if rid not in matched_ids and status != "removed":
                cur.execute("UPDATE online_platform SET status='removed' WHERE id=%s", (rid,))
                removed += 1
        cur.execute("SELECT count(*) FROM online_platform")
        after = cur.fetchone()[0]
    log(f"B2 upsert: 신규 {inserted}·갱신 {updated}·removed {removed} "
        f"(행 {before}→{after}) — 소요 {time.time()-t0:.1f}s")
    log("B 판정: OK")


# ------------------------------------------------------------------ 단계 C: RAG
def stage_c_rag():
    log("=== 단계 C: RAG ===")
    if not os.environ.get("OPENAI_API_KEY"):
        log("C 스킵: OPENAI_API_KEY 없음.")
        return
    t0 = time.time()
    r = subprocess.run([sys.executable, "_workspace/dev_scripts/build_rag_corpus.py"],
                       cwd=str(ROOT))
    if r.returncode != 0:
        log(f"C 실패(로그만): build_rag_corpus exit={r.returncode}. 배치 실패 아님.")
        return
    log(f"C 판정: OK — 소요 {time.time()-t0:.1f}s")


def stage_d_survey(out_dir):
    """온라인 취급품목·브랜드 변화 **탐지**(자동 반영 없음).

    단계 B(온라인 플랫폼)는 공식 API 라 계약이 안정적이지만 이 단계는 HTML 스크래핑이다.
    사이트 개편이나 지연 로드로 절반만 걷힌 회차를 자동 반영하면 데이터가 조용히 나빠지므로,
    변화 후보만 리포트로 남기고 반영 판단은 사람이 한다(2026-08-22 결정).

    하루 3~4곳씩 순환해 일주일에 22곳을 한 바퀴 돈다 — 매일 전수 스크래핑은 상대 사이트
    부담에 견줘 얻는 게 적다(취급품목은 가맹점 목록만큼 자주 바뀌지 않는다).
    """
    log("=== 단계 D: 온라인 취급품목·브랜드 변화 탐지 ===")
    script = ROOT / "backend" / "tools" / "survey_nightly.js"
    if not script.exists():
        log("D 스킵: survey_nightly.js 없음.")
        return
    node = shutil.which("node")
    if not node:
        log("D 스킵: node 없음(설치: nodejs).")
        return
    t0 = time.time()
    cmd = [node, str(script)]
    if out_dir:
        cmd += ["--out", out_dir]
    r = subprocess.run(cmd, cwd=str(ROOT))
    if r.returncode == 2:
        log("D 스킵: playwright 미설치 — npm i playwright && npx playwright install --with-deps chromium")
        return
    if r.returncode != 0:
        log(f"D 실패(로그만): survey_nightly exit={r.returncode}. 배치 실패 아님.")
        return
    log(f"D 판정: OK — 소요 {time.time()-t0:.1f}s (데이터 자동 반영 없음, 리포트만)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-merchants", action="store_true")
    ap.add_argument("--skip-online", action="store_true")
    ap.add_argument("--skip-rag", action="store_true")
    ap.add_argument("--skip-survey", action="store_true")
    ap.add_argument("--survey-out", default=os.environ.get("SURVEY_OUT_DIR"),
                    help="변화 탐지 리포트를 남길 디렉터리(미지정 시 로그로만)")
    ap.add_argument("--no-collect", action="store_true",
                    help="가맹점 재수집 생략(기존 JSON으로 스왑만 — 로컬 검증)")
    ap.add_argument("--collected-on", default=date.today().isoformat())
    args = ap.parse_args()

    t0 = time.time()
    today = args.collected_on
    log(f"야간 배치 시작 (collected_on={today}, DSN 호스트={DSN.split()[0]})")

    merchant_failed = False
    # A·B 만 DB 를 쓴다. 둘 다 스킵이면 연결하지 않는다 —
    # 단계 D(채록 탐지)는 DB 가 필요 없어서, 이 가드가 없으면 D 만 돌려보는 것이 불가능하다.
    if args.skip_merchants and args.skip_online:
        log("단계 A·B 스킵 — DB 연결 생략")
    else:
        with psycopg.connect(DSN, autocommit=True) as conn:
            if args.skip_merchants:
                log("단계 A 스킵(--skip-merchants)")
            else:
                ok = stage_a_merchants(conn, today, args.no_collect)
                merchant_failed = not ok

            if args.skip_online:
                log("단계 B 스킵(--skip-online)")
            else:
                stage_b_online(conn, today)

    if args.skip_rag:
        log("단계 C 스킵(--skip-rag)")
    else:
        stage_c_rag()

    if args.skip_survey:
        log("단계 D 스킵(--skip-survey)")
    else:
        try:
            stage_d_survey(args.survey_out)
        except Exception as e:                     # noqa: BLE001 — D 는 배치를 죽이지 않는다
            log(f"D 실패(로그만): {e}. 배치 실패 아님.")

    log(f"야간 배치 종료 — 총 소요 {time.time()-t0:.1f}s, "
        f"판정 {'FAIL(가맹점)' if merchant_failed else 'OK'}")
    sys.exit(1 if merchant_failed else 0)


if __name__ == "__main__":
    main()
