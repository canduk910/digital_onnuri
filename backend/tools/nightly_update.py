#!/usr/bin/env python3
"""야간 배치(서버 cron 00:30) — 가맹점·온라인 플랫폼 데이터 자동 갱신.

여섯 단계가 서로 독립적으로 fail-open 한다(ADR-14, D는 ADR-16, E는 ADR-17, F는 ADR-18):
  A 가맹점  — 공식 API 전수 재수집 → stage 테이블 적재 → ±20% 가드 → 무중단 stage-swap
  B 온라인  — 공식 e-commerce API 순회 → upsert(post_no/이름 매칭, 큐레이션 필드 보존)
  C RAG     — OPENAI_API_KEY 있을 때만 코퍼스 재빌드
  D 채록    — 온라인 취급품목·브랜드 변화 **탐지만**(하루 3~4곳 순환, 자동 반영 없음)
  F 색인    — 실시간 조회가 닿지 않는 2곳의 **상품명·주소만** 수집해 몰 단위로 교체 적재
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

# robots.txt 감시 — 우리가 **실제로 두드리는 호스트**를 매일 다시 본다.
#
# ADR-19 가 robots 를 대상 선정 기준에서 빼면서 내건 균형점이 "정책이 강해지면 즉시 끈다"이고,
# 그 방아쇠가 이 감시다. 그러니 관측점이 어긋나면 근거가 반만 선다.
#
# 2026-09-03 개편(3건). 그 전에는 이랬다 —
#   ① 호스트를 `search_url_template or url` 에서 뽑았다. 그건 **이용자에게 줄 링크의 호스트**이지
#      우리가 두드리는 호스트가 아니다. 11번가는 감시가 search.11st.co.kr 인데 조회는 apis.11st.co.kr,
#      롯데ON 은 감시가 단축주소 s.lotteon.com(301 루프)인데 조회는 www.lotteon.com 이었다.
#   ② 키가 몰 id 였다. robots.txt 는 **호스트 단위 파일**이라 한 몰이 호스트를 둘 쓰면(인어교주)
#      표현되지 않고, 두 몰이 한 호스트를 나눠 쓰면 같은 파일을 두 번 조회했다.
#   ③ 기준선이 손으로 관리하는 상수(ROBOTS_BLOCKED_AT_SURVEY)였다. 몰을 편입하면서 거기 넣는 걸
#      잊으면 조용히 거짓 통과가 난다 — 2026-08-31 굿데이 도메인 오기와 같은 자리다.
#
# 이제 호스트의 출처는 셋이고 **어느 것도 손으로 적지 않는다**:
#   1) 앱 셀프테스트 응답의 판정(있으면 최우선 — ProbeTargets 를 아는 건 앱뿐이고 판정도 앱이 한다)
#   2) 색인 크롤러의 RECIPES.hosts (`node index_nightly.js --print-hosts`)
#      — 놀장·인어교주는 ProbeTargets 에 없어서 앱 응답만으로는 관측 밖이 된다
#   3) **어제 리포트**의 조회 대상(폴백) — 앱이 안 뜬 회차에만. "어제까지 우리가 두드리던 곳"이다.
# 기준선도 상수가 아니라 어제 리포트다(응답 길이 비교가 이미 쓰는 패턴). 첫 회차는 기준선만 남긴다.
#
# 2026-09-03 추가: 폴백 목록도 손으로 적지 않는다. 처음엔 id 20개를 상수로 적었는데, 조회 대상이
# 늘 때 거기 넣는 걸 잊으면 **폴백 회차에서만 조용히 빠진다** — 방금 없앤 ROBOTS_BLOCKED_AT_SURVEY 와
# 정확히 같은 노후화다. 리포트가 하나도 없는 첫 실행에서는 **아무것도 관측하지 않고 사유를 남긴다**
# (손으로 적은 목록으로 흉내 내는 것보다 "확인하지 않았다"가 정직하다).
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
        if r.returncode == 4:
            # 수집기가 스스로 막았다 — 같은 날 두 번째 재수집(2026-09-06 가드).
            # **공식 API 는 실패하지 않았다.** 이것을 다른 실패와 같이 다루면 화면에
            # "온누리 공식 시스템이 자동 수집을 차단했습니다" 라는 **틀린 사유**가 뜬다.
            # 가드가 걸렸다는 것은 오늘 이미 수집이 끝났다는 뜻이므로 데이터는 낡지 않았다.
            log("A 건너뜀: 오늘 이미 재수집했다(수집기의 같은 날 가드). "
                "중단 표시를 세우지 않는다 — 공식 API 실패가 아니고 데이터도 오늘 것이다.")
            return False
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


def _repo_db_drift(db_active, repo_active):
    """저장소 JSON 과 DB 의 **활성 온라인몰 목록**이 갈라졌는지 본다(2026-09-03 신설).

    왜 필요한가: 단계 B 는 공식 목록에서 새 몰을 받아 **DB 에만** 넣는다. 저장소 JSON 은
    사람이 손으로 따라와야 하는데, 그걸 잊으면 **백엔드가 멈춰 폴백으로 도는 날 이용자가
    그 몰을 못 본다.** 2026-09-03 에 실제로 그랬다 — 라이브 31곳 / 저장소 30곳,
    빠진 곳은 배달앱 `ec-35 온누리 권율로` 였다. 그날 로그에는 `신규 1` 이라는 숫자만
    남아 있었고 **어느 몰인지도, 저장소에 반영하라는 말도 없었다.**

    건수가 아니라 **id 집합**을 비교한다 — 같은 날 하나가 들어오고 하나가 빠지면
    건수는 그대로다. 반환은 (저장소에 없는 것, DB 에 없는 것) 두 집합이다.
    """
    return sorted(set(db_active) - set(repo_active)), sorted(set(repo_active) - set(db_active))


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

    # 저장소↔DB 드리프트 — 위 UPDATE 는 **저장소에 있는 id 만** 훑으므로 DB 에만 있는 몰은
    # 이 동기화로 영영 드러나지 않는다. 그래서 여기서 따로 본다.
    repo_active = [it["id"] for it in items if (it.get("status") or "active") == "active"]
    with conn.cursor() as cur:
        cur.execute("SELECT id, name, kind FROM online_platform WHERE status='active'")
        rows = cur.fetchall()
    db_meta = {r[0]: (r[1], r[2]) for r in rows}
    only_db, only_repo = _repo_db_drift(db_meta.keys(), repo_active)
    if only_db:
        log(f"  ! 저장소에 없는 몰 {len(only_db)}곳 — 백엔드가 멈춘 날 이용자가 이 곳들을 못 본다."
            f" data/online_platforms.json 에 반영할 것")
        for pid in only_db:
            nm, kd = db_meta.get(pid, ("?", "?"))
            log(f"      {pid} · {nm}({kd})")
    if only_repo:
        log(f"  ! DB 에 없는 몰 {len(only_repo)}곳 — 공식 목록에서 빠졌거나 id 가 어긋났다:"
            f" {', '.join(only_repo)}")
    if not only_db and not only_repo:
        log(f"  · 저장소↔DB 목록 일치({len(repo_active)}곳)")


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


def _index_recipe_ids():
    """색인 크롤러가 도는 몰 id. 크롤러가 직접 말한다(`--print-hosts`) — 배치가 적지 않는다."""
    script = ROOT / "backend" / "tools" / "index_nightly.js"
    node = shutil.which("node")
    if not script.exists() or not node:
        return []
    try:
        r = subprocess.run([node, str(script), "--print-hosts"], cwd=str(ROOT),
                           capture_output=True, text=True, timeout=30)
        if r.returncode != 0:
            return []
        return [row["id"] for row in json.loads(r.stdout) if row.get("id")]
    except Exception:                              # noqa: BLE001
        return []


def _index_ids_to_crawl(recipe_ids, realtime_ids):
    """이번 회차에 크롤할 몰과 건너뛸 몰을 가른다.

    실시간 조회 대상이 된 몰은 앱이 색인 행을 아예 읽지 않으므로(ADR-18) 걷어도 헛일이다.
    **앱 응답이 없어 realtime_ids 가 비면 아무것도 건너뛰지 않는다** — 모르면 하던 대로다.
    여기서 건너뛰면 앱이 잠깐 안 뜬 날 색인이 통째로 비어 50% 가드에 걸린다.

    반환: (크롤할 id 목록 또는 None=전부, 건너뛸 id 목록)
    """
    if not realtime_ids:
        return None, []
    drop = [i for i in recipe_ids if i in realtime_ids]
    if not drop:
        return None, []
    return [i for i in recipe_ids if i not in realtime_ids], drop


def _index_table_exists(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT to_regclass('public.online_product_index') IS NOT NULL")
        return bool(cur.fetchone()[0])


def stage_f_index(conn, out_dir, today, app_rep=None):
    """실시간 조회가 닿지 않는 2곳(놀장·인어교주해적단)의 상품명 색인을 몰 단위로 교체 적재한다(ADR-18).

    둘 다 화면이 그려져야 상품이 보여 브라우저가 필요하다. playwright 가 없으면
    크롤러가 종료코드 2 로 끝나고 이 단계는 스킵된다(어제 색인은 그대로 남는다).

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
    if not _index_table_exists(conn):
        log("F 스킵: online_product_index 테이블 없음 — 마이그레이션(V8) 적용 후 동작한다.")
        return

    # 실시간 조회 대상이 된 몰은 **크롤하지 않는다.**
    # 앱의 IndexJudge 가 색인 층에서 실시간 대상을 빼므로(ADR-18 — 한 몰이 두 층에서 다른 말을
    # 하지 않게), 그 몰을 걷어 적재해도 **앱이 그 행을 아예 읽지 않는다.** 몰당 수십~수백
    # 페이지를 걷어 상대 사이트에 부담만 주고 얻는 것이 0이고, 로그는 정상으로 찍혀 조용하다.
    # 실제로 이틀 사이 넷이 색인→실시간으로 옮겨 갔다(지니어스몰·11번가·공영쇼핑·롯데ON).
    #
    # 저장소에서는 `RECIPES ∩ ProbeTargets = ∅` 를 테스트가 지키지만 그건 **저장소 상태**다.
    # 배치는 git pull 한 클론에서 돌고 앱은 CD 로 따로 배포되니, pull 이 실패한 날이나 배포
    # 시차에는 앱이 앞서고 배치가 뒤처진 상태가 실재한다(2026-09-01 에 pull 실패로 배치가 죽었다).
    #
    # **앱 응답이 없으면 평소대로 크롤한다** — 모르면 하던 대로다. 여기서 건너뛰면 앱이 잠깐
    # 안 뜬 날 색인이 통째로 비어 50% 가드에 걸린다.
    only_ids, drop = _index_ids_to_crawl(_index_recipe_ids(), _realtime_ids(app_rep))
    for pid in drop:
        log(f"  ! 색인 건너뜀: {pid} 는 실시간 조회 대상이다(앱이 색인 행을 읽지 않는다). "
            f"레시피에서 지울 것.")
    if drop and not only_ids:
        log("F 스킵: 레시피의 몰이 전부 실시간 조회 대상이 됐다 — 걷어도 화면에 닿지 않는다.")
        return

    t0 = time.time()
    # 리포트를 반드시 읽어야 하므로 --out 은 항상 준다(지정이 없으면 임시 디렉터리).
    tmp = None
    target_dir = out_dir
    if not target_dir:
        tmp = tempfile.mkdtemp(prefix="onnuri-index-")
        target_dir = tmp
    try:
        cmd = [node, str(script), "--out", target_dir]
        if only_ids:
            cmd += ["--ids", ",".join(only_ids)]
        r = subprocess.run(cmd, cwd=str(ROOT))
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
def _norm_host(value):
    """URL 이든 호스트든 소문자 호스트명만 남긴다. 읽을 수 없으면 None."""
    if not isinstance(value, str) or not value.strip():
        return None
    v = value.strip()
    m = re.match(r"https?://([^/:\s]+)", v, re.I)
    if m:
        return m.group(1).lower()
    if re.fullmatch(r"[A-Za-z0-9.\-]+\.[A-Za-z]{2,}", v):
        return v.lower()
    return None


def _hosts_from_index():
    """색인 크롤러가 두드리는 호스트. `--print-hosts` 로 크롤러가 직접 말한다.

    놀장·인어교주는 ProbeTargets 에 없어 앱 응답만으로는 관측 밖이 된다.
    여기서도 도메인을 손으로 적지 않는다 — RECIPES 가 유일한 출처다.
    """
    out = {}
    script = ROOT / "backend" / "tools" / "index_nightly.js"
    node = shutil.which("node")
    if not script.exists() or not node:
        return out
    try:
        r = subprocess.run([node, str(script), "--print-hosts"], cwd=str(ROOT),
                           capture_output=True, text=True, timeout=30)
        if r.returncode != 0:
            log(f"  · robots 색인 호스트 조회 실패(exit={r.returncode}) — 폴백만 쓴다")
            return out
        for row in json.loads(r.stdout):
            for h in row.get("hosts", []):
                nh = _norm_host(h)
                if nh:
                    out.setdefault(nh, set()).add(row.get("id", "?"))
    except Exception as e:                         # noqa: BLE001
        log(f"  · robots 색인 호스트 조회 실패: {str(e)[:100]} — 폴백만 쓴다")
    return out


def _hosts_from_prev(prev_doc):
    """폴백 — **어제 리포트의 조회 대상**에서 호스트를 뽑는다.

    앱이 안 뜬 회차에만 쓴다. "어제까지 우리가 두드리던 곳"이라는 뜻이 정확하고,
    무엇보다 **손으로 적는 목록이 없다** — 조회 대상이 늘면 다음 날 리포트에 자동으로 들어온다.

    반환: (호스트→몰id 목록, 어느 날짜 리포트를 썼나)
    데이터의 링크 주소를 쓰지 않는 이유: 그건 *이용자에게 줄 링크*의 호스트이지 우리가 두드리는
    호스트가 아니다(11번가·5일장·롯데ON 이 실제로 갈린다 — 그게 이번 라운드가 고친 병이다).
    """
    out = {}
    if not isinstance(prev_doc, dict):
        return out, None
    for pid, r in (prev_doc.get("probe") or {}).items():
        h = _norm_host((r or {}).get("host"))
        if h:
            out.setdefault(h, set()).add(pid)
    return out, prev_doc.get("date")


def _robots_scan(hosts):
    """호스트별 robots.txt 재조회. 전면 차단 여부만 본다 — 경로 단위 해석은 아직 사람이 한다."""
    out = {}
    for host in hosts:
        url = f"https://{host}/robots.txt"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=10) as r:
                body = r.read(20000).decode("utf-8", "replace")
                final = getattr(r, "url", None) or url
            # **다른 호스트에서 온 것은 그 몰의 robots.txt 가 아니다.** urlopen 은
            # 리다이렉트를 따라가므로 200 이라고 그 몰의 파일인 것은 아니다 —
            # 2026-09-05 현대이지웰이 점검에 들어가며 robots.txt 요청을 다른 도메인의
            # **HTML 안내 페이지**로 보냈고, 앱은 그것을 robots 로 파싱해 '허용'이라
            # 적고 있었다(같은 날 앱 쪽 가드로 잡았다). 배치도 같은 함정에 있다.
            # 관측 실패로 남긴다 — '금지 없음'으로도 '전면 차단'으로도 적지 않는다.
            fh = final.split("//", 1)[-1].split("/", 1)[0]
            if fh != host:
                out[host] = {"status": getattr(r, "status", None),
                             "error": f"redirect to other host: {fh}"[:120]}
                continue
            # `Disallow: /` 한 줄(뒤에 경로가 더 없는 것)이 전면 차단이다.
            blocked = any(re.fullmatch(r"disallow:\s*/\s*", ln.strip(), re.I)
                          for ln in body.splitlines())
            out[host] = {"status": r.status, "blocked_all": blocked, "bytes": len(body)}
        except urllib.error.HTTPError as e:
            # 404·410 은 **파일이 없다** = 금지 없음이다(온누리시장이 그렇다).
            # 그 밖의 응답 코드는 판정이 아니라 **관측 실패**로 남긴다 — 403 을 "금지 없음"으로,
            # 리다이렉트 루프(단축주소 호스트가 그렇다)를 "허용"으로 적으면 없는 사실을 만든다.
            if e.code in (404, 410):
                out[host] = {"status": e.code, "blocked_all": False, "bytes": 0}
            else:
                out[host] = {"status": e.code, "error": f"HTTP {e.code}"}
        except Exception as e:                     # noqa: BLE001
            out[host] = {"status": None, "error": str(e)[:120]}
    return out


def _robots_delta(prev, now, skipped=()):
    """어제 대비 무엇이 달라졌나. 기준선 상수 대신 어제 리포트를 쓴다.

    상수는 몰을 편입하면서 갱신을 잊는 순간 조용히 거짓 통과가 난다(굿데이 사고 자리).
    어제와 비교하면 잊을 것이 없다 — 유지비가 0 이다.

    첫 회차(prev 없음)는 변화 없음으로 본다. 비교 대상이 없는 것을 "바뀌었다"고 말하면
    매번 새 서버에서 거짓 경고가 난다.

    `skipped` 는 **오늘 일부러 안 본 호스트**다(그 층이 꺼져 있었다). 오늘 없다고 해서
    "감시에서 빠졌다"고 말하면 거짓이 된다 — 빠진 게 아니라 확인하지 않은 것이다.
    반환: (차단여부 변화, 새로 감시 시작한 호스트, 감시에서 빠진 호스트)
    """
    changed, added, removed = [], [], []
    if not isinstance(prev, dict) or not prev:
        return changed, added, removed
    for host, r in now.items():
        p = prev.get(host)
        if p is None:
            added.append(host)
            continue
        if r.get("error") or p.get("error"):
            continue                               # 어느 한쪽이 관측 실패면 비교하지 않는다
        if r.get("blocked_all") != p.get("blocked_all"):
            changed.append((host, p.get("blocked_all"), r.get("blocked_all")))
    removed = [h for h in prev if h not in now and h not in set(skipped)]
    return changed, added, removed


# 카나리아 재시도 추세(2026-09-03)
# 무작위 질의가 우연히 상품을 물면 앱이 새 말로 한 번 다시 묻는다. 그 재시도는 우연을 걸러 주지만
# **가리기도 한다** — 어느 몰의 검색이 점점 느슨해져 무작위 낱말을 자주 물기 시작하면 재시도가
# 매번 통과시켜 주면서 FAIL 이 영영 안 뜬다. 그래서 통과한 재시도에도 note 가 붙고, 배치는
# 그 note 가 **잦아지는 것**을 본다. 한 회차의 1건은 정상이고 문제는 추세다.
RETRY_WINDOW = 7          # 최근 몇 회차를 보나(일주일)
RETRY_ALERT_MIN = 3       # 그중 몇 회차에서 걸리면 알리나
RETRY_MIN_ROUNDS = 3      # 회차가 이보다 적으면 판단하지 않는다(표본이 없으면 추세도 없다)
# note 문구로 추론할 때 쓰는 표지. **구조화된 플래그(`retried`)가 있으면 그쪽이 우선**이다 —
# 문구 매칭은 앱이 말을 바꾸면 조용히 0건이 된다. 그래서 리포트에 어느 방법으로 셌는지 남긴다.
RETRY_NOTE_MARKS = ("재시도", "다시 물었", "다시 물음")


def _retry_ids(rep):
    """그 회차에 **재시도가 있었던** 몰 id 집합과, 무엇으로 셌는지.

    반환: (id 집합, "flag" 또는 "note" 또는 None)
    """
    if not isinstance(rep, dict):
        return set(), None
    by, out = None, set()
    for c in rep.get("cases") or []:
        if not isinstance(c, dict) or not c.get("platformId"):
            continue
        if "retried" in c:
            by = by or "flag"
            if c["retried"]:
                out.add(c["platformId"])
            continue
        note = c.get("note") or ""
        if any(m in note for m in RETRY_NOTE_MARKS):
            by = by or "note"
            out.add(c["platformId"])
    return out, by


def _retry_trend(rounds):
    """회차별 재시도 몰 목록을 받아 몰마다 (걸린 회차 수, 본 회차 수)를 센다.

    `rounds` 는 최근 것부터든 오래된 것부터든 상관없다 — 세는 것은 횟수뿐이다.
    회차가 RETRY_MIN_ROUNDS 보다 적으면 **빈 결과**를 돌려준다. 표본이 없는데 추세를 말하면
    새 서버에서 첫 주 내내 거짓 경고가 난다.
    """
    rounds = [r for r in rounds if r is not None]
    n = len(rounds)
    if n < RETRY_MIN_ROUNDS:
        return {}, n
    hits = {}
    for ids in rounds:
        for pid in ids:
            hits[pid] = hits.get(pid, 0) + 1
    return hits, n


def _recent_reports(out_dir, prefix, limit, today_name):
    """오늘을 뺀 최근 리포트 몇 개(오래된 것 → 최신 순)."""
    if not out_dir:
        return []
    files = sorted(f for f in Path(out_dir).glob(f"{prefix}-*.json") if f.name != today_name)
    out = []
    for f in files[-limit:]:
        try:
            out.append(json.loads(f.read_text(encoding="utf-8")))
        except Exception:                          # noqa: BLE001
            continue
    return out


def _prev_report(out_dir, prefix, today_name):
    """어제까지의 리포트 중 가장 최근 것."""
    if not out_dir:
        return None
    files = sorted(f for f in Path(out_dir).glob(f"{prefix}-*.json") if f.name != today_name)
    if not files:
        return None
    try:
        return json.loads(files[-1].read_text(encoding="utf-8"))
    except Exception:                              # noqa: BLE001
        return None


def _fetch_selftest():
    """셀프테스트를 **한 회차에 한 번만** 부른다.

    이 호출은 앱이 몰들에 실제 조회를 보내는 카나리아다 — 두 번 부르면 상대 사이트에 가는
    요청이 두 배가 된다. 그래서 단계 F(색인)와 단계 E(카나리아)가 같은 응답을 나눠 쓴다.
    반환: (응답 또는 None, 못 받은 사유 또는 None)
    """
    if not APP_ADMIN_KEY:
        return None, "APP_ADMIN_KEY 없음(.env 에 설정하면 켜진다)"
    url = APP_BASE_URL.rstrip("/") + "/api/online/search/selftest"
    try:
        req = urllib.request.Request(url, headers={"X-Admin-Key": APP_ADMIN_KEY, "User-Agent": UA})
        with urllib.request.urlopen(req, timeout=CANARY_TIMEOUT) as r:
            return json.loads(r.read().decode("utf-8")), None
    except Exception as e:                         # noqa: BLE001
        return None, f"셀프테스트 호출 실패 {e}"


def _realtime_ids(rep):
    """앱이 아는 **실시간 조회 대상** id 집합. `robots[]`·`probeEndpoints[]` 어느 쪽이 와도 읽는다.

    새로 실을 것이 없다 — 앱이 `ProbeTargets` 에서 파생시킨 값이라 대상이 늘거나 줄면 따라온다.
    응답이 없으면 **빈 집합**을 돌려 호출자가 "모르면 하던 대로" 하게 한다.
    """
    if not isinstance(rep, dict):
        return set()
    out = set()
    for key in ("robots", "probeEndpoints"):
        for row in rep.get(key) or []:
            if isinstance(row, dict) and row.get("platformId"):
                out.add(row["platformId"])
    return out


def _probe_robots_from_app(rep):
    """앱이 내린 조회 대상 robots 판정을 그대로 옮긴다. **배치는 판정하지 않는다.**

    파서를 한 곳에만 둔다 — 앱은 `ProbeTargets` 옆에 있어 우리가 두드리는 정확한 경로와
    쿼리를 알고, 경로별 허용(`Disallow: /` + `Allow: /plan/front/`)을 표준 규칙으로 읽는다.
    배치가 자기 판정을 따로 하면 같은 몰을 두고 앱은 허용, 배치는 전면 차단이라고 말하게 된다.

    계약(2026-09-03, backend):
      · `probeEndpoints[]` = {platformId, host, path} — **실제 조회 주소**(조회가 꺼져도 온다)
      · `robots[]`         = {platformId, allowed, rule, group, error}
    `allowed` 는 전면 차단 여부가 아니라 **그 경로가 허용되는가** 다. 그래서 키는 호스트가 아니라
    엔드포인트(platformId)다 — 한 호스트라도 경로가 다르면 답이 다를 수 있다. 호스트는 함께 적는다.

    `error` 가 채워진 행은 robots 를 못 읽은 것이고 그때 `allowed` 는 거짓이다. 모르는 것을
    허용으로 적지 않으려는 값이므로 **차단과 구분해서** 다룬다.
    """
    out = {}
    if not isinstance(rep, dict):
        return out
    ep = {}
    for e in rep.get("probeEndpoints") or []:
        if isinstance(e, dict) and e.get("platformId"):
            ep[e["platformId"]] = (_norm_host(e.get("host")), e.get("path"))
    for r in rep.get("robots") or []:
        if not isinstance(r, dict) or not r.get("platformId"):
            continue
        pid = r["platformId"]
        host, path = ep.get(pid, (None, None))
        out[pid] = {"grade": "app", "host": host, "path": path,
                    "allowed": bool(r.get("allowed")), "rule": r.get("rule"),
                    "group": r.get("group"), "error": r.get("error")}
    return out


def _probe_delta(prev, now):
    """조회 대상 판정의 어제 대비 변화. `allowed` 뿐 아니라 **`rule` 이 바뀌는 것도 신호**다.

    등급이 다른 판정끼리는 비교하지 않는다 — 앱의 경로 단위 판정과 배치의 거친 판정을
    맞대면 매번 거짓 변화가 난다.
    """
    changed, added, removed = [], [], []
    if not isinstance(prev, dict) or not prev:
        return changed, added, removed
    for pid, r in now.items():
        p = prev.get(pid)
        if p is None:
            added.append(pid)
            continue
        if p.get("grade") != r.get("grade"):
            continue                               # 등급이 다르면 비교하지 않는다
        if r.get("error") or p.get("error"):
            if bool(r.get("error")) != bool(p.get("error")):
                changed.append((pid, f"관측 {'실패' if r.get('error') else '복구'}",
                                p.get("error") or "정상", r.get("error") or "정상"))
            continue
        if bool(p.get("allowed")) != bool(r.get("allowed")):
            changed.append((pid, "허용", p.get("allowed"), r.get("allowed")))
        elif (p.get("rule") or None) != (r.get("rule") or None):
            changed.append((pid, "근거 규칙", p.get("rule") or "(없음)", r.get("rule") or "(없음)"))
    removed = [pid for pid in prev if pid not in now]
    return changed, added, removed


def _probe_skip_reason(app_rep):
    """조회 대상 판정을 왜 못 받았나 — 성격이 다른 셋을 구분해 적는다.

    킬 스위치는 **의도된 중단**이고, 나머지 둘은 우리 쪽 사정이다. 한 낱말로 뭉뚱그리면
    "운영사 요청으로 껐다"와 "배치 설정을 안 했다"가 로그에서 같아 보인다.
    """
    if not APP_ADMIN_KEY:
        return "no-admin-key(배치 설정 미비 — .env 에 APP_ADMIN_KEY)"
    if app_rep is None:
        return "app-unreachable(앱 응답 없음)"
    return "kill-switch-off(실시간 조회가 꺼져 있다 — 꺼진 몰은 두드리지 않는다)"


def _robots_watch(out_dir, app_rep, probe_on, index_on):
    """robots 감시 — 판정은 앱에서 받고, 앱이 모르는 호스트만 배치가 거칠게 본다.

    2026-09-03 정리. 두 가지를 고쳤다.
      ① **판정이 두 곳에서 나던 것**을 하나로. 조회 대상은 앱의 `robots[]` 를 그대로 옮기고
         배치는 그 호스트를 **다시 가져오지 않는다**(같은 파일을 하루 두 번 두드리지 않는다).
      ② **꺼진 층은 두드리지 않는다.** 킬 스위치를 켜는 대표적 상황이 운영사 항의를 받아 끄는
         것인데, 그 상태에서 그 몰의 robots.txt 를 계속 긁으면 요청을 받고도 계속 두드리는 셈이다.

    등급이 둘이다 — 섞어 읽으면 안 된다.
      · `app`    : 경로·쿼리까지 본 판정. `allowed`(그 경로가 허용되는가) + 근거 규칙.
      · `coarse` : 배치의 한 줄 스캐너. **전면 차단 여부만** 본다. 색인 몰과 폴백 회차에 쓴다.
    """
    # ── 1) 조회 대상: 앱 판정을 그대로 옮긴다(우리는 두드리지 않는다)
    probe, probe_skip = {}, None
    if probe_on:
        probe = _probe_robots_from_app(app_rep)
        if not probe:
            probe_skip = "app-no-robots(앱이 robots 판정을 아직 안 실어 준다)"
    else:
        probe_skip = _probe_skip_reason(app_rep)

    today_name = f"robots-{datetime.now().strftime('%Y-%m-%d')}.json"
    prev_doc = _prev_report(out_dir, "robots", today_name) or {}

    # 앱을 못 부른 회차만 **어제 리포트의 조회 대상**을 거칠게 본다. 손으로 적은 목록은 없다.
    fallback, fallback_date = {}, None
    if probe_skip and probe_skip.startswith(("no-admin-key", "app-unreachable")):
        raw, fallback_date = _hosts_from_prev(prev_doc)
        fallback = {h: sorted(ids) for h, ids in raw.items()}
        if not fallback:
            probe_skip += " · 폴백 없음(어제 리포트가 없다 — 첫 실행이면 정상)"

    # ── 2) 색인 몰: 앱이 모르는 호스트다(ProbeTargets 에 없다). 여기만 배치가 조회한다.
    index_hosts = {h: sorted(ids) for h, ids in _hosts_from_index().items()}
    coarse_targets = dict(fallback)
    if index_on:
        for h, ids in index_hosts.items():
            coarse_targets.setdefault(h, []).extend(i for i in ids if i not in coarse_targets[h])

    # **앱이 이미 판정한 호스트는 빼고 조회한다** — 같은 파일을 하루 두 번 가져오지 않는다.
    # 오늘 데이터로는 겹침이 0이지만 그건 성질이 아니라 우연이다(두 집합이 마침 안 겹칠 뿐).
    # 서로 다른 몰이 한 도메인을 쓰면(한 운영사가 두 몰을 올리는 경우) 그날부터 겹친다.
    app_judged = {v["host"] for v in probe.values() if v.get("host")}
    for h in sorted(set(coarse_targets) & app_judged):
        coarse_targets.pop(h)

    coarse = {}
    if coarse_targets:
        for h, r in _robots_scan(sorted(coarse_targets)).items():
            coarse[h] = {**r, "grade": "coarse", "owners": coarse_targets[h]}

    skipped = sorted((set() if index_on else set(index_hosts)) | app_judged)

    # ── 3) 어제와 비교(같은 등급끼리만)
    p_ch, p_add, p_rm = _probe_delta(prev_doc.get("probe", {}), probe)
    c_ch, c_add, c_rm = _robots_delta(
        {h: v for h, v in (prev_doc.get("coarse") or {}).items() if v.get("grade") == "coarse"},
        coarse, skipped)

    # ── 4) 로그. 요약은 늘 남긴다 — 침묵이 "변화 없음"으로 읽히면 안 된다.
    blocked = sorted(pid for pid, r in probe.items() if not r["allowed"] and not r["error"])
    failed = sorted(pid for pid, r in probe.items() if r["error"])
    if probe:
        prev_blocked = sorted(pid for pid, r in (prev_doc.get("probe") or {}).items()
                              if not r.get("allowed") and not r.get("error"))
        same = "어제와 같음" if prev_doc.get("probe") and prev_blocked == blocked else (
            "첫 회차" if not prev_doc.get("probe") else "**어제와 다름**")
        log(f"  · robots 조회 대상 {len(probe)}곳(앱 판정) — 차단 {len(blocked)}곳({same})"
            + (f" · 관측 실패 {len(failed)}곳" if failed else ""))
        if blocked:
            log(f"    차단: {', '.join(blocked)}")
        for pid in failed:
            log(f"    관측 실패: {pid} — {probe[pid]['error']} (차단이 아니라 못 읽은 것)")
    if probe_skip:
        log(f"  · robots 조회 대상 확인 안 함({probe_skip})"
            + (f" — 폴백으로 {len(fallback)}곳을 거칠게 본다(이용자 링크 호스트라 "
               f"11번가·5일장·롯데ON 은 실제 조회 호스트와 다른 사이트를 본다)" if fallback else "")
            + ". 변화 없음이 아니라 **확인하지 않았다**")
    log(f"  · robots 거친 판정 {len(coarse)}곳(색인 {len(index_hosts) if index_on else 0}"
        + (f" · 폴백 {len(fallback)}[{fallback_date} 회차 기준]" if fallback else "") + ")"
        + (f" · 단계 F 스킵으로 {len(index_hosts)}곳 확인 안 함" if not index_on else ""))

    for pid, what, before, after in p_ch:
        log(f"  ! robots {pid}: {what} {before} → {after} — 조회 대상 재검토")
    for pid in p_add:
        log(f"  · robots {pid} 판정 시작 — 허용 {probe[pid]['allowed']}")
    for pid in p_rm:
        log(f"  · robots {pid} 판정에서 빠짐(조회 대상이 아니게 됐다)")
    for host, before, after in c_ch:
        log(f"  ! robots {host}: 전면 차단 {before} → {after} "
            f"(사용 몰: {', '.join(coarse[host]['owners'])}) — 색인 대상 재검토")
    for host in c_add:
        log(f"  · robots {host} 감시 시작 — 전면 차단 {coarse[host].get('blocked_all')}")
    for host in c_rm:
        log(f"  · robots {host} 감시에서 빠짐(대상 몰이 없어졌다)")
    for host, r in coarse.items():
        if r.get("error"):
            log(f"  · robots {host}: 조회 실패 {r['error']}")

    # ── 5) 리포트. 등급을 함께 남긴다 — 다음 사람이 두 값을 같은 것으로 읽으면 안 된다.
    if out_dir:
        try:
            d = Path(out_dir)
            d.mkdir(parents=True, exist_ok=True)
            (d / today_name).write_text(json.dumps({
                "date": datetime.now().strftime("%Y-%m-%d"),
                "robotsUserAgent": (app_rep or {}).get("robotsUserAgent"),
                "probe": probe, "probeSkipped": probe_skip,
                "coarse": coarse, "coarseSkipped": skipped,
                "fallbackFrom": fallback_date,
                "grades": {"app": "경로·쿼리까지 본 앱 판정", "coarse": "전면 차단 여부만 본 배치 판정"},
            }, ensure_ascii=False, indent=1), encoding="utf-8")
            log(f"  · robots 리포트: {d / today_name}")
        except Exception as e:                     # noqa: BLE001
            log(f"  · robots 리포트 저장 실패(무시): {e}")
    return {"probe": probe, "coarse": coarse}


# ------------------------------------------------------------- 단계 E: 카나리아
def stage_e_canary(out_dir, index_on=True, app_rep=None, fetch_error=None):
    """실시간 조회(ADR-17)의 판정 규칙이 아직 맞는지 앱에 물어본다.

    **자동으로 아무것도 끄지 않는다.** 규칙이 깨졌다는 판단은 사람이 하고, 배치는
    깨졌다는 사실만 남긴다 — 조용한 축소는 ADR-16 이 채록 자동 반영을 기각한 논리와 같다.
    배치는 파서를 갖지 않는다. 판정은 앱의 실제 조회 경로가 그대로 한다.
    """
    log("=== 단계 E: 실시간 조회 판정 카나리아 ===")
    t0 = time.time()

    # 1) 셀프테스트 응답은 main 이 한 번만 받아 넘겨 준다(단계 F 와 공유 — 두 번 부르면
    #    앱이 몰들에 보내는 조회가 두 배가 된다). 못 받았어도 여기서 끝내지 않는다 —
    #    robots 감시는 그 사실을 사유로 남겨야 하기 때문이다.
    rep = app_rep
    if rep is None and fetch_error is None:
        # 단독 호출(수동 점검·테스트)일 때만 여기서 받는다. main 은 이미 넘겨 준다.
        rep, fetch_error = _fetch_selftest()
    if rep is None:
        log(f"  · 카나리아 스킵: {fetch_error or '앱 응답 없음'}. 배치 실패 아님.")

    # 2) robots 감시. **켜져 있는 층만** 본다 — 꺼진 몰을 두드리지 않는 것이 킬 스위치의 정의다.
    #    안 본 층은 리포트와 로그에 "확인하지 않았다"로 남는다(침묵이 정상으로 읽히면 안 된다).
    probe_on = bool(rep and rep.get("probeEnabled"))
    robots = _robots_watch(out_dir, rep, probe_on, index_on)
    _n = len(robots["probe"]) + len(robots["coarse"])

    # 3) 여기부터가 카나리아. 앱 응답이 없으면 할 수 있는 일이 없다.
    if rep is None:
        log(f"E 판정: 카나리아 확인 안 함(robots 판정 {_n}건) — 소요 {time.time()-t0:.1f}s")
        return
    if not rep.get("probeEnabled"):
        # 꺼져 있으면 "이상 없음"이 아니라 "확인하지 않았다"다.
        log(f"E 판정: 확인 안 함 — 실시간 조회 킬 스위치가 꺼져 있다(robots 판정 {_n}건).")
        return

    for c in rep.get("cases", []):
        if not c.get("ok"):
            log(f"  ✗ {c['platformId']} [{c['kind']}] 기대={c['expected'] or '(없음)'} "
                f"실제={c['actual']} 샘플={c['sampleCount']} — {c.get('note') or c.get('reason')}")
        elif c.get("note"):
            log(f"  · {c['platformId']} [{c['kind']}] {c['note']}")

    # 응답 길이 급변은 몰 개편의 조기 신호다. 판정이 아직 맞더라도 알린다.
    today_name = f"probe-canary-{datetime.now().strftime('%Y-%m-%d')}.json"
    prev = _prev_report(out_dir, "probe-canary", today_name)
    if prev:
        before = {(c["platformId"], c["kind"]): c.get("bodyLength", 0)
                  for c in prev.get("cases", [])}
        for c in rep.get("cases", []):
            b = before.get((c["platformId"], c["kind"]))
            now_len = c.get("bodyLength", 0)
            if b and now_len and abs(now_len - b) / b > BODY_DELTA_ALERT:
                log(f"  · {c['platformId']} [{c['kind']}] 응답 길이 {b}→{now_len} "
                    f"({(now_len-b)/b*100:+.0f}%) — 개편 여부 확인")

    # ── 재시도 추세. **자동으로 아무것도 끄지 않는다** — 리포트·로그까지다(ADR-17 의 조용한 축소 금지).
    today_retry, retry_by = _retry_ids(rep)
    prev_rounds = _recent_reports(out_dir, "probe-canary", RETRY_WINDOW - 1, today_name)
    rounds = [_retry_ids(r)[0] for r in prev_rounds] + [today_retry]
    hits, seen = _retry_trend(rounds)
    frequent = sorted(pid for pid, n in hits.items() if n >= RETRY_ALERT_MIN)
    for pid in frequent:
        log(f"  ! 재시도 잦음: {pid} — 최근 {seen}회차 중 {hits[pid]}번 무작위 질의가 걸려 "
            f"다시 물었다. 그 몰 검색이 느슨해지는 중일 수 있다(재시도가 FAIL 을 가린다) — "
            f"absent 질의 규칙 재검토")
    if today_retry and not frequent:
        log(f"  · 재시도 {len(today_retry)}곳({', '.join(sorted(today_retry))}) — "
            f"최근 {seen}회차 기준 아직 추세 아님(알림 기준 {RETRY_ALERT_MIN}회)")
    # 판단 근거를 리포트에 남긴다 — 몇 회차 중 몇 번이었는지 봐야 사람이 규칙을 고칠지 정한다.
    rep["retryTrend"] = {"window": RETRY_WINDOW, "roundsSeen": seen, "alertMin": RETRY_ALERT_MIN,
                         "countedBy": retry_by, "today": sorted(today_retry),
                         "hits": dict(sorted(hits.items())), "frequent": frequent}

    # robots 결과를 카나리아 리포트에도 실어 둔다(한 파일로 읽을 수 있게).
    # 비교의 기준이 되는 정본은 별도 파일 robots-YYYY-MM-DD.json 이다 — 조회가 꺼진 날에도 쌓인다.
    rep["robots"] = robots

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

        # 셀프테스트는 한 회차에 한 번만 — 단계 F(색인 대상 판단)와 단계 E(카나리아)가 나눠 쓴다.
        # 두 번 부르면 앱이 몰들에 보내는 조회가 두 배가 된다.
        selftest, selftest_err = (None, None)
        if not (args.skip_index and args.skip_canary):
            selftest, selftest_err = _fetch_selftest()

        if args.skip_index:
            log("단계 F 스킵(--skip-index)")
        elif conn is None:
            log("단계 F 스킵: DB 연결 없음.")
        else:
            try:
                stage_f_index(conn, args.survey_out, today, selftest)
            except Exception as e:                 # noqa: BLE001 — F 도 배치를 죽이지 않는다
                log(f"F 실패(로그만): {e}. 배치 실패 아님.")

        if args.skip_canary:
            log("단계 E 스킵(--skip-canary)")
        else:
            try:
                stage_e_canary(args.survey_out, index_on=not args.skip_index,
                               app_rep=selftest, fetch_error=selftest_err)
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
