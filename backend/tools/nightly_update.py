#!/usr/bin/env python3
"""야간 배치(서버 cron 00:30) — 가맹점·온라인 플랫폼 데이터 자동 갱신.

여섯 단계가 서로 독립적으로 fail-open 한다(ADR-14, D는 ADR-16, E는 ADR-17, F는 ADR-18):
  A 가맹점  — 공식 API 전수 재수집 → stage 테이블 적재 → ±20% 가드 → 무중단 stage-swap
  B 온라인  — 공식 e-commerce API 순회 → upsert(post_no/이름 매칭, 큐레이션 필드 보존)
  C RAG     — OPENAI_API_KEY 있을 때만 코퍼스 재빌드
  D 채록    — 온라인 취급품목·브랜드 변화 **탐지만**(하루 3~4곳 순환, 자동 반영 없음)
  F 색인    — 실시간 조회가 닿지 않는 5곳의 **상품명·주소만** 수집해 몰 단위로 교체 적재
  E 카나리아 — 실시간 조회 판정 규칙이 아직 맞는지 앱에 물어보고 리포트만(자동 비활성화 없음)

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
import tempfile
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

# 단계 E — 실시간 조회 카나리아(ADR-17)
APP_BASE_URL = os.environ.get("APP_BASE_URL", "http://localhost:8080")
APP_ADMIN_KEY = os.environ.get("APP_ADMIN_KEY", "")
CANARY_TIMEOUT = 120            # 가장 느린 몰이 8초 × 12건이라 넉넉히 잡는다
BODY_DELTA_ALERT = 0.5          # 응답 길이가 ±50% 넘게 변하면 개편 신호로 본다

# robots.txt 재조회 대상 8곳 — 2026-08-31 실현성 조사에서 정적 HTTP 로 결과를 얻은 전부다.
# 조회 대상 6곳 + 그때 `Disallow: /` 라 **제외한** 2곳. 제외한 곳도 계속 본다 —
# 허용으로 바뀌면 커버리지를 넓힐 수 있고, 그 사실을 아무도 다시 확인하지 않으면 영영 모른다.
#
# 도메인은 손으로 쓰지 않고 data/online_platforms.json 에서 뽑는다. 처음엔 상수로 적었다가
# 2026-08-31 검증에서 굿데이를 onnurigoodday.com(실제는 onnurigood.com)으로 잘못 적었고,
# **그 엉뚱한 도메인이 마침 Disallow: / 라 기대와 맞아떨어져 통과까지 했다.**
# 감시 대상이 조용히 다른 사이트가 되는 것을 막으려면 주소의 출처가 하나여야 한다.
#
# 2026-09-02: 단계 F(전일 색인, ADR-18)가 여는 2곳과, 같은 날 실시간 조회 대상이 된
# 지니어스몰을 감시에 넣는다. 조사 시점 상태는
#   놀장 `Allow: /` · 인어교주해적단 robots.txt 없음(요청이 SPA 화면으로 넘어간다) ·
#   지니어스몰 `Allow: /` + `Disallow: /ko_mall/`.
# 셋 다 전면 차단이 아니므로 ROBOTS_BLOCKED_AT_SURVEY 에는 넣지 않는다 —
# 어느 한 곳이 `Disallow: /` 로 바뀌면 그날 로그에 경고가 뜨고 사람이 그 몰을 재검토한다.
ROBOTS_BLOCKED_AT_SURVEY = ("onnuri-goodday", "inthemarket-onnuri")   # 2026-08-31 조사 시점 전면 차단
ROBOTS_WATCH_IDS = ("onnuri-hotdeal", "onnuri-chance", "onnuri-sijang", "onnuri-market",
                    "onnuri-gonggong-mall", "epost-mall", "genius-mall",
                    "onnuri-noljang", "tpirates") + ROBOTS_BLOCKED_AT_SURVEY

# 단계 F — 전일 상품명 색인(ADR-18)
INDEX_GUARD_MIN_RATIO = 0.5     # 몰별 새 건수가 기존의 50% 미만이면 그 몰은 유지

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
            _mark_stale(conn, today, f"공식 가맹점 API 재수집 실패(exit={r.returncode})")
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
            _mark_stale(conn, today, "수집 결과가 가드에 걸려 반영하지 않음")
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
    _clear_stale(conn)          # 갱신이 되돌아왔다 — 화면 경고를 내린다
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
    _sync_curation(conn)
    log("B 판정: OK")


def _mark_stale(conn, today, reason):
    """가맹점 갱신이 멈춘 사실을 app_meta 에 남긴다 — /api/meta 가 그대로 노출하고
    merchants.html 이 화면에 띄운다.

    2026-08-29 온누리가 가맹점 API 를 v2→v3 로 옮기며 v2 를 닫았고, 배치는 설계대로
    fail-open 해 기존 데이터를 지켰다. 그런데 **나흘 동안 아무도 몰랐다** — 로그에만
    남았고 화면은 그동안 "매일 00:30 자동 최신화"라고 말하고 있었다.
    멈춘 사실은 운영자의 로그가 아니라 이용자가 보는 화면에 드러나야 한다.

    since 는 **첫 실패일을 유지**한다(매일 덮어쓰면 "어제부터"가 되어 4일째인지 알 수 없다).
    """
    try:
        with conn.cursor() as cur:
            cur.execute("INSERT INTO app_meta(k, v) VALUES('merchants_stale_since', %s) "
                        "ON CONFLICT (k) DO NOTHING", (today,))
            cur.execute("INSERT INTO app_meta(k, v) VALUES('merchants_stale_reason', %s) "
                        "ON CONFLICT (k) DO UPDATE SET v = EXCLUDED.v", (reason[:200],))
        conn.commit()
        log(f"A 중단 기록: since(첫 실패일 유지)·reason={reason[:60]}")
    except Exception as e:                         # noqa: BLE001 — 기록 실패가 배치를 죽이지 않는다
        log(f"A 중단 기록 실패(무시): {e}")


def _clear_stale(conn):
    """갱신이 되돌아왔으면 지운다. 남겨 두면 정상인데도 화면이 경고를 띄운다."""
    try:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM app_meta WHERE k IN "
                        "('merchants_stale_since','merchants_stale_reason')")
        conn.commit()
    except Exception as e:                         # noqa: BLE001
        log(f"A 중단 기록 해제 실패(무시): {e}")


def _sync_curation(conn):
    """저장소 data/online_platforms.json 의 **큐레이션 필드**를 DB 에 맞춘다.

    note·region_limited·search_url_template 은 공식 API 가 주지 않는, 우리가 손으로 정한 값이다.
    단계 B 의 upsert 는 이 컬럼을 건드리지 않아 잘 보존되지만 — **새 값이 들어갈 길도 없다.**
    지금까지는 사람이 load_online_platforms.py 를 따로 돌려야 했고, 2026-09-02 에 실제로
    그걸 잊어 어제 추가한 검색 링크 5곳이 DB 에 없었다(화면은 그 몰들을 홈으로 보내고 있었다).

    저장소가 큐레이션의 SSOT 이므로 DB 가 매일 따라오게 한다. 배치는 이미 git pull 을 하니
    커밋만 하면 다음 날 반영된다.
    """
    src = ROOT / "data" / "online_platforms.json"
    if not src.exists():
        log("B 큐레이션 동기화 생략: data/online_platforms.json 없음")
        return
    try:
        items = json.loads(src.read_text(encoding="utf-8"))["items"]
    except Exception as e:                         # noqa: BLE001
        log(f"B 큐레이션 동기화 실패(무시): {e}")
        return
    n = 0
    with conn.cursor() as cur:
        for it in items:
            cur.execute(
                "UPDATE online_platform SET note=%s, region_limited=%s, search_url_template=%s "
                "WHERE id=%s AND (note IS DISTINCT FROM %s OR region_limited IS DISTINCT FROM %s "
                "               OR search_url_template IS DISTINCT FROM %s)",
                (it.get("note", ""), bool(it.get("region_limited", False)),
                 it.get("search_url_template") or None, it["id"],
                 it.get("note", ""), bool(it.get("region_limited", False)),
                 it.get("search_url_template") or None))
            n += cur.rowcount
    conn.commit()
    log(f"B 큐레이션 동기화: {n}건 갱신(note·region_limited·search_url_template)")


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


# ---------------------------------------------------------------- 단계 F: 색인
def _index_guard(prev_count, new_count, min_ratio=INDEX_GUARD_MIN_RATIO):
    """이 회차의 수집분으로 그 몰의 색인을 덮어도 되는가.

    단계 A 의 ±20% 가드와 같은 논리다 — **반쯤 걷힌 회차로 멀쩡한 색인을 지우지 않는다.**
    크롤은 HTML·화면 응답에 기대므로 사이트가 느리거나 개편 중이면 절반만 걷힌다.
    그런 회차를 그대로 반영하면 이용자에게는 "어제까지 있던 상품이 사라진" 것으로 보인다.

    반대로 **처음 적재(prev=0)는 언제나 통과**시킨다. 기준이 없으면 비교할 것도 없다.

    반환: (반영해도 되는가, 사유 또는 None)
    """
    if new_count <= 0:
        return False, "새 수집 0건"
    if prev_count and new_count < prev_count * min_ratio:
        return False, (f"{prev_count}→{new_count}건 "
                       f"(기존의 {new_count / prev_count:.0%}, 하한 {min_ratio:.0%})")
    return True, None


def _index_table_exists(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT to_regclass('public.online_product_index') IS NOT NULL")
        return bool(cur.fetchone()[0])


def stage_f_index(conn, out_dir, today):
    """실시간 조회가 닿지 않는 5곳의 상품명 색인을 몰 단위로 교체 적재한다(ADR-18).

    대상: 놀장·인어교주해적단(브라우저) · 11번가 온누리마켓·롯데ON 상생스토어·공영쇼핑(정적 fetch).
    뒤 3곳은 기획전·스토어가 자기 상품을 정적 요청으로 주므로 브라우저가 필요 없다 —
    playwright 가 없어도 그 3곳은 걷힌다(index_nightly.js 가 브라우저를 필요할 때만 띄운다).

    단계 D 와 달리 **이 단계는 DB 를 고친다.** 그래도 성격은 같다 — 수집은 크롤이고,
    크롤은 조용히 절반만 성공한다. 그래서 몰마다 건수 가드를 걸고(_index_guard),
    걸린 몰은 **어제 색인을 그대로 둔다**(빈 색인보다 하루 지난 색인이 낫다).

    ok=false 로 온 몰도 손대지 않는다 — 크롤러가 스스로 "이 회차는 못 믿는다"고 말한 것이다.
    """
    log("=== 단계 F: 온라인 상품명 색인 ===")
    script = ROOT / "backend" / "tools" / "index_nightly.js"
    if not script.exists():
        log("F 스킵: index_nightly.js 없음.")
        return
    node = shutil.which("node")
    if not node:
        log("F 스킵: node 없음(설치: nodejs).")
        return
    # playwright 는 여기서 확인하지 않는다 — 없으면 브라우저 레시피만 실패하고
    # 정적 레시피 3곳은 그대로 걷힌다(크롤러가 몰별로 fail-open 한다).
    if not _index_table_exists(conn):
        log("F 스킵: online_product_index 테이블 없음 — 마이그레이션(V8) 적용 후 동작한다.")
        return

    t0 = time.time()
    # 리포트를 반드시 읽어야 하므로 --out 은 항상 준다(지정이 없으면 임시 디렉터리).
    tmp = None
    target_dir = out_dir
    if not target_dir:
        tmp = tempfile.mkdtemp(prefix="onnuri-index-")
        target_dir = tmp
    try:
        r = subprocess.run([node, str(script), "--out", target_dir], cwd=str(ROOT))
        if r.returncode == 2:
            log("F 스킵: playwright 미설치 — npm i playwright && npx playwright install --with-deps chromium")
            return
        if r.returncode != 0:
            log(f"F 실패(로그만): index_nightly exit={r.returncode}. 배치 실패 아님.")
            return

        # 파일명은 크롤러의 **로컬 날짜**로 붙는다(배치 로그와 같은 기준). --collected-on 으로
        # 다른 날짜를 주더라도 파일은 오늘 것이므로, 없으면 가장 최근 파일로 되짚는다.
        d = Path(target_dir)
        f = d / f"product-index-{date.today().isoformat()}.json"
        if not f.exists():
            files = sorted(d.glob("product-index-*.json"))
            if not files:
                log("F 실패(로그만): 색인 리포트를 찾지 못했습니다.")
                return
            f = files[-1]
            log(f"  · 오늘자 파일이 없어 최근 파일을 씁니다: {f.name}")
        report = json.loads(f.read_text(encoding="utf-8"))
    except Exception as e:                         # noqa: BLE001 — F 는 배치를 죽이지 않는다
        log(f"F 실패(로그만): {e}. 배치 실패 아님.")
        return
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)

    loaded = kept = 0
    for p in report.get("platforms", []):
        pid = p.get("id")
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM online_product_index WHERE platform_id=%s", (pid,))
            prev = cur.fetchone()[0]

        if not p.get("ok"):
            kept += 1
            log(f"  · {pid}: 수집 실패로 유지({prev}건) — {p.get('error') or '사유 없음'}")
            continue

        items = p.get("items") or []
        ok, reason = _index_guard(prev, len(items))
        if not ok:
            kept += 1
            log(f"  ! {pid}: 가드에 걸려 유지({prev}건) — {reason}")
            continue

        # 몰 단위 원자적 교체. 지우고 넣는 사이에 죽어도 그 몰만 비지 않는다.
        # 한 몰이 실패해도(외래키 위반·컬럼 초과 등) 나머지 몰은 계속한다 — 단계 F 는 fail-open 이다.
        rows = [(pid, it["url"], it["name"], today) for it in items
                if it.get("url") and it.get("name")]
        try:
            with conn.transaction(), conn.cursor() as cur:
                cur.execute("DELETE FROM online_product_index WHERE platform_id=%s", (pid,))
                cur.executemany(
                    "INSERT INTO online_product_index (platform_id, url, name, collected_on) "
                    "VALUES (%s,%s,%s,%s) ON CONFLICT (platform_id, url) DO NOTHING", rows)
                cur.execute("SELECT count(*) FROM online_product_index WHERE platform_id=%s", (pid,))
                after = cur.fetchone()[0]
        except Exception as e:                     # noqa: BLE001
            kept += 1
            log(f"  ! {pid}: 적재 실패로 유지({prev}건) — {str(e)[:140]}")
            continue
        loaded += 1
        log(f"  · {pid}: {prev}→{after}건 (수집 {len(items)}건 · {p.get('pages')}페이지 "
            f"· {p.get('seconds')}s)")

    log(f"F 판정: OK — 적재 {loaded}곳 · 유지 {kept}곳 / 소요 {time.time()-t0:.1f}s")


# ------------------------------------------------------------- 단계 E: 카나리아
def _prev_canary(out_dir, today_name):
    """어제까지의 리포트 중 가장 최근 것. 응답 길이 비교의 기준이 된다."""
    if not out_dir:
        return None
    d = Path(out_dir)
    files = sorted(f for f in d.glob("probe-canary-*.json") if f.name != today_name)
    if not files:
        return None
    try:
        return json.loads(files[-1].read_text(encoding="utf-8"))
    except Exception:                              # noqa: BLE001
        return None


def _robots_targets():
    """감시 대상 id → robots.txt 주소. 주소의 출처는 데이터 한 곳뿐이다."""
    src = ROOT / "data" / "online_platforms.json"
    try:
        items = {i["id"]: i for i in json.loads(src.read_text(encoding="utf-8"))["items"]}
    except Exception as e:                         # noqa: BLE001
        log(f"  · robots 대상 로드 실패: {e}")
        return {}
    out = {}
    for pid in ROBOTS_WATCH_IDS:
        it = items.get(pid)
        if not it:
            log(f"  ! robots {pid}: data/online_platforms.json 에 없다 — id 확인 필요")
            continue
        base = it.get("search_url_template") or it.get("url") or ""
        m = re.match(r"(https?://[^/]+)", base)
        if not m:
            log(f"  ! robots {pid}: 주소를 읽을 수 없다 ({base!r})")
            continue
        out[pid] = m.group(1) + "/robots.txt"
    return out


def _robots_scan():
    """robots.txt 8곳 재조회. 전면 차단 여부만 본다 — 세부 경로 해석은 사람이 한다."""
    out = {}
    for pid, url in _robots_targets().items():
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=10) as r:
                body = r.read(20000).decode("utf-8", "replace")
            # `Disallow: /` 한 줄(뒤에 경로가 더 없는 것)이 전면 차단이다.
            blocked = any(re.fullmatch(r"disallow:\s*/\s*", ln.strip(), re.I)
                          for ln in body.splitlines())
            out[pid] = {"status": r.status, "blocked_all": blocked, "bytes": len(body)}
        except urllib.error.HTTPError as e:
            # 404 는 금지 없음이다(온누리시장이 그렇다). 오류로 취급하지 않는다.
            out[pid] = {"status": e.code, "blocked_all": False, "bytes": 0}
        except Exception as e:                     # noqa: BLE001
            out[pid] = {"status": None, "error": str(e)[:120]}
    return out


def stage_e_canary(out_dir):
    """실시간 조회(ADR-17)의 판정 규칙이 아직 맞는지 앱에 물어본다.

    **자동으로 아무것도 끄지 않는다.** 규칙이 깨졌다는 판단은 사람이 하고, 배치는
    깨졌다는 사실만 남긴다 — 조용한 축소는 ADR-16 이 채록 자동 반영을 기각한 논리와 같다.
    배치는 파서를 갖지 않는다. 판정은 앱의 실제 조회 경로가 그대로 한다.
    """
    log("=== 단계 E: 실시간 조회 판정 카나리아 ===")
    if not APP_ADMIN_KEY:
        log("E 스킵: APP_ADMIN_KEY 없음(.env 에 설정하면 켜진다).")
        return
    t0 = time.time()
    url = APP_BASE_URL.rstrip("/") + "/api/online/search/selftest"
    try:
        req = urllib.request.Request(url, headers={"X-Admin-Key": APP_ADMIN_KEY, "User-Agent": UA})
        with urllib.request.urlopen(req, timeout=CANARY_TIMEOUT) as r:
            rep = json.loads(r.read().decode("utf-8"))
    except Exception as e:                         # noqa: BLE001
        log(f"E 실패(로그만): 셀프테스트 호출 실패 {e}. 배치 실패 아님.")
        return

    if not rep.get("probeEnabled"):
        # 꺼져 있으면 "이상 없음"이 아니라 "확인하지 않았다"다.
        log("E 판정: 확인 안 함 — 실시간 조회 킬 스위치가 꺼져 있다.")
        return

    for c in rep.get("cases", []):
        if not c.get("ok"):
            log(f"  ✗ {c['platformId']} [{c['kind']}] 기대={c['expected'] or '(없음)'} "
                f"실제={c['actual']} 샘플={c['sampleCount']} — {c.get('note') or c.get('reason')}")
        elif c.get("note"):
            log(f"  · {c['platformId']} [{c['kind']}] {c['note']}")

    # 응답 길이 급변은 몰 개편의 조기 신호다. 판정이 아직 맞더라도 알린다.
    today_name = f"probe-canary-{datetime.now().strftime('%Y-%m-%d')}.json"
    prev = _prev_canary(out_dir, today_name)
    if prev:
        before = {(c["platformId"], c["kind"]): c.get("bodyLength", 0)
                  for c in prev.get("cases", [])}
        for c in rep.get("cases", []):
            b = before.get((c["platformId"], c["kind"]))
            now_len = c.get("bodyLength", 0)
            if b and now_len and abs(now_len - b) / b > BODY_DELTA_ALERT:
                log(f"  · {c['platformId']} [{c['kind']}] 응답 길이 {b}→{now_len} "
                    f"({(now_len-b)/b*100:+.0f}%) — 개편 여부 확인")

    rep["robots"] = _robots_scan()
    for pid, r in rep["robots"].items():
        was_blocked = pid in ROBOTS_BLOCKED_AT_SURVEY
        if r.get("error"):
            log(f"  · robots {pid}: 조회 실패 {r['error']}")
        elif r.get("blocked_all") != was_blocked:
            log(f"  ! robots {pid}: 전면 차단 {was_blocked} → {r['blocked_all']} — 조회 대상 재검토")

    if out_dir:
        d = Path(out_dir)
        d.mkdir(parents=True, exist_ok=True)
        (d / today_name).write_text(json.dumps(rep, ensure_ascii=False, indent=1), encoding="utf-8")
        log(f"E 리포트: {d / today_name}")
    log(f"E 판정: {'OK' if rep.get('failed', 0) == 0 else 'FAIL ' + str(rep['failed']) + '건'} "
        f"— 통과 {rep.get('passed')} / 기대치없음 {rep.get('skipped')} "
        f"/ 소요 {time.time()-t0:.1f}s (자동 반영·비활성화 없음)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-merchants", action="store_true")
    ap.add_argument("--skip-online", action="store_true")
    ap.add_argument("--skip-rag", action="store_true")
    ap.add_argument("--skip-survey", action="store_true")
    ap.add_argument("--skip-index", action="store_true",
                    help="단계 F(상품명 색인) 생략 — ADR-18 의 롤백 수단")
    ap.add_argument("--skip-canary", action="store_true")
    ap.add_argument("--survey-out", default=os.environ.get("SURVEY_OUT_DIR"),
                    help="변화 탐지·색인 리포트를 남길 디렉터리(미지정 시 로그로만)")
    ap.add_argument("--no-collect", action="store_true",
                    help="가맹점 재수집 생략(기존 JSON으로 스왑만 — 로컬 검증)")
    ap.add_argument("--collected-on", default=date.today().isoformat())
    args = ap.parse_args()

    t0 = time.time()
    today = args.collected_on
    log(f"야간 배치 시작 (collected_on={today}, DSN 호스트={DSN.split()[0]})")

    merchant_failed = False
    # A·B·F 가 DB 를 쓴다. 셋 다 스킵이면 연결하지 않는다 —
    # 단계 D(채록 탐지)는 DB 가 필요 없어서, 이 가드가 없으면 D 만 돌려보는 것이 불가능하다.
    # F 가 D 와 E 사이에 있어 연결을 그 구간까지 열어 둔다(연결 하나로 A~F 를 관통).
    needs_db = not (args.skip_merchants and args.skip_online and args.skip_index)
    conn = None
    if not needs_db:
        log("단계 A·B·F 스킵 — DB 연결 생략")
    else:
        conn = psycopg.connect(DSN, autocommit=True)

    try:
        if conn is not None:
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
            except Exception as e:                 # noqa: BLE001 — D 는 배치를 죽이지 않는다
                log(f"D 실패(로그만): {e}. 배치 실패 아님.")

        if args.skip_index:
            log("단계 F 스킵(--skip-index)")
        elif conn is None:
            log("단계 F 스킵: DB 연결 없음.")
        else:
            try:
                stage_f_index(conn, args.survey_out, today)
            except Exception as e:                 # noqa: BLE001 — F 도 배치를 죽이지 않는다
                log(f"F 실패(로그만): {e}. 배치 실패 아님.")

        if args.skip_canary:
            log("단계 E 스킵(--skip-canary)")
        else:
            try:
                stage_e_canary(args.survey_out)
            except Exception as e:                 # noqa: BLE001 — E 도 배치를 죽이지 않는다
                log(f"E 실패(로그만): {e}. 배치 실패 아님.")
    finally:
        if conn is not None:
            conn.close()

    log(f"야간 배치 종료 — 총 소요 {time.time()-t0:.1f}s, "
        f"판정 {'FAIL(가맹점)' if merchant_failed else 'OK'}")
    sys.exit(1 if merchant_failed else 0)


if __name__ == "__main__":
    main()
