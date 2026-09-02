#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_rag_corpus.py — 챗봇 RAG 지식베이스 청킹·임베딩·적재 (ADR-12).

원천 (검증된 것만 — 추측·미확인 내용 금지):
  1. _workspace/rag_corpus/*.md          — 공식 사이트 채록 정제본 (frontmatter: source/url/collected_on)
  2. _workspace/rag_corpus/_raw/faq_by_type.json — onnuri.gift FAQ API 수집본 (축제·공연 카테고리 제외)
  3. _workspace/01_policy_analysis.md    — 실측 검증된 내부 정책 분석
  4. data/offline_categories.json        — 오프라인 업종 판정표 (SSOT)
  5. data/online_platforms.json          — 온라인 공식 플랫폼 목록 (SSOT)
  6. data/online_catalog.json            — 온라인 물품종류×브랜드 실측 태깅 (SSOT)

사용:
  python3 build_rag_corpus.py --dry-run    # 청킹만 (임베딩·DB 없음, 통계 출력)
  python3 build_rag_corpus.py --selftest   # 청킹 로직 단위 테스트
  OPENAI_API_KEY=... python3 build_rag_corpus.py   # 임베딩 + rag_chunk 전체 교체 적재

임베딩: OpenAI text-embedding-3-small (1536차원). 재실행 = 전체 교체(멱등).
DB: DB_URL 환경변수 (기본 localhost:5432/onnuri, onnuri/onnuri) — V2 마이그레이션 선행 필요.
"""
import json
import re, os, re, sys, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CORPUS_DIR = os.path.join(ROOT, "_workspace", "rag_corpus")
RAW_FAQ = os.path.join(CORPUS_DIR, "_raw", "faq_complete.json")

CHUNK_MIN, CHUNK_MAX = 200, 900   # 문자 기준. 섹션이 크면 문단 단위 분할, 작으면 이웃과 병합.

# ---------- 청킹 ----------

def parse_frontmatter(text):
    m = re.match(r"^---\n(.*?)\n---\n(.*)$", text, re.DOTALL)
    if not m:
        return {}, text
    meta = {}
    for line in m.group(1).splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            meta[k.strip()] = v.strip().strip('"')
    return meta, m.group(2)


def split_sections(md):
    """헤딩 기준 (섹션경로, 본문) 목록. 헤딩 레벨 1~3."""
    lines = md.splitlines()
    sections, path, buf = [], [], []

    def flush():
        body = "\n".join(buf).strip()
        if body:
            sections.append((" > ".join(path) if path else "", body))
        buf.clear()

    for ln in lines:
        m = re.match(r"^(#{1,3})\s+(.*)$", ln)
        if m:
            flush()
            level = len(m.group(1))
            path[:] = path[:level - 1]
            path.append(m.group(2).strip())
        else:
            buf.append(ln)
    flush()
    return sections


def split_long(p):
    """CHUNK_MAX 초과 단일 문단을 줄·문장 경계에서 분할 (내용 소실 금지)."""
    if len(p) <= CHUNK_MAX:
        return [p]
    units = re.split(r"(?<=[.다요음])\s+|\n", p)
    out, cur = [], ""
    for u in units:
        cand = (cur + " " + u).strip() if cur else u
        if len(cand) <= CHUNK_MAX:
            cur = cand
        else:
            if cur:
                out.append(cur)
            # 경계 없는 초장문 단위는 고정폭 분할
            while len(u) > CHUNK_MAX:
                out.append(u[:CHUNK_MAX])
                u = u[CHUNK_MAX:]
            cur = u
    if cur:
        out.append(cur)
    return out


def chunk_section(section, body):
    """긴 섹션은 문단 경계로 CHUNK_MAX 이하 분할, 짧은 문단은 병합."""
    paras = [p.strip() for p in re.split(r"\n\s*\n", body) if p.strip()]
    chunks, cur = [], ""
    for p0 in paras:
        for p in split_long(p0):
            cand = (cur + "\n\n" + p).strip() if cur else p
            if len(cand) <= CHUNK_MAX:
                cur = cand
            else:
                if cur:
                    chunks.append(cur)
                cur = p
    if cur:
        chunks.append(cur)
    # 미세 청크는 앞 청크에 병합
    merged = []
    for c in chunks:
        if merged and len(c) < CHUNK_MIN and len(merged[-1]) + len(c) < CHUNK_MAX + 200:
            merged[-1] = merged[-1] + "\n\n" + c
        else:
            merged.append(c)
    return [(section, c) for c in merged]


def chunk_markdown(meta, md):
    out = []
    for section, body in split_sections(md):
        for sec, content in chunk_section(section, body):
            out.append({
                "source": meta.get("source", "unknown"),
                "section": sec[:250],
                "content": content,
                "url": meta.get("url"),
                "collected_on": meta.get("collected_on"),
            })
    return out

# ---------- 원천별 로더 ----------

def load_curated():
    chunks = []
    for fn in sorted(os.listdir(CORPUS_DIR)):
        if not fn.endswith(".md"):
            continue
        meta, body = parse_frontmatter(open(os.path.join(CORPUS_DIR, fn), encoding="utf-8").read())
        chunks += chunk_markdown(meta, body)
    return chunks


# 원본 채록은 손대지 않는다(공식 FAQ가 실제로 그렇게 말한다는 것 자체가 사실). 다만 상위 출처인
# 정책 변경 공지와 어긋나는 항목은 청크에 정정을 덧붙인다 — 붙이지 않으면 그 청크만 검색됐을 때
# 챗봇이 낡은 값을 그대로 답한다(2026-08-27: FAQ '월 최대 200만 원 충전'이 정책 변경 전 값).
# 키는 질문 문자열의 부분 일치.
FAQ_CORRECTIONS = {
    "충전 한도가 있나요": (
        "※ 정정(2026-08-27 확인) — 위 FAQ 답변은 2026-01-01 정책 변경 이전 기준으로 갱신되지 않았다. "
        "공식 공지 「2026년 온누리상품권 할인 판매 및 정책 변경 안내」(2025-12-31 게시, 2026-01-01 시행)에 따르면 "
        "개인·디지털 기준 **구매(충전)한도는 월 100만 원**이고, **200만 원은 보유한도**(잔액으로 갖고 있을 수 있는 상한)다. "
        "충전 한도를 묻는 질문에는 월 100만 원으로 답하고, 200만 원은 보유한도로 구분해 안내한다."
    ),
}


def load_faq():
    d = json.load(open(RAW_FAQ, encoding="utf-8"))
    chunks = []
    applied = set()
    for it in d["items"]:
        if it["cat"] == "축제·공연정보":   # 챗봇 범위 밖(행사 홍보)
            continue
        body = f"Q. {it['q']}\nA. {it['a'].strip()}"
        for key, note in FAQ_CORRECTIONS.items():
            if key in it["q"]:
                body += "\n\n" + note
                applied.add(key)
        chunks.append({
            "source": "onnuri_faq",
            "section": f"공식 FAQ({it['cat']}) > {it['q'][:150]}",
            "content": body,
            "url": "https://www.onnuri.gift/faq",
            "collected_on": "2026-08-11",
        })
    missed = set(FAQ_CORRECTIONS) - applied
    if missed:   # FAQ 재수집으로 질문 문구가 바뀌면 정정이 조용히 사라진다 — 즉시 드러낸다.
        raise SystemExit(f"[FAIL] FAQ 정정 대상을 찾지 못했습니다: {sorted(missed)} "
                         f"— 질문 문구가 바뀌었는지 확인하고 FAQ_CORRECTIONS 키를 갱신하세요.")
    return chunks


def load_policy_analysis():
    p = os.path.join(ROOT, "_workspace", "01_policy_analysis.md")
    if not os.path.exists(p):
        return []
    meta = {"source": "internal_policy_analysis", "url": None,
            "collected_on": "2026-08-06"}
    return chunk_markdown(meta, open(p, encoding="utf-8").read())


def load_offline_categories():
    d = json.load(open(os.path.join(ROOT, "data", "offline_categories.json"), encoding="utf-8"))
    rows = []
    for it in d["items"]:
        rows.append(f"- {it['type']} (예: {it.get('examples','')}) → {it['verdict_label']}."
                    + (f" 확인 포인트: {it['check_point']}" if it.get("check_point") else ""))
    content = ("디지털온누리상품권 오프라인 업종별 사용 가능 판정표 (실측 검증 기준):\n" + "\n".join(rows))
    co = d.get("meta", {}).get("collected_on", "")
    # 표가 길면 분할
    out = []
    for sec, c in chunk_section("오프라인 업종별 판정표", content):
        out.append({"source": "offline_categories", "section": sec, "content": c,
                    "url": None, "collected_on": co})
    return out


def load_online_platforms():
    d = json.load(open(os.path.join(ROOT, "data", "online_platforms.json"), encoding="utf-8"))
    act = [it for it in d["items"] if it.get("status") == "active"]
    shopping = [it for it in act if it["kind"] != "delivery"]
    delivery = [it for it in act if it["kind"] == "delivery"]
    co = d.get("meta", {}).get("collected_on", "")

    def fmt(items):
        return "\n".join(f"- {it['name']}: {it.get('summary','')}"
                         + (f" (참고: {it['note']})" if it.get("note") else "")
                         + (" [지역 한정]" if it.get("region_limited") else "")
                         for it in items)
    chunks = []
    head = (f"디지털온누리 온라인 사용처 공식 플랫폼 총 {len(act)}곳 — 쇼핑 {len(shopping)}곳, 배달 {len(delivery)}곳. "
            f"(공식 '온라인 전통시장관' 안내 기준, {co} 수집)")
    for sec, c in chunk_section("온라인 공식 플랫폼 > 쇼핑", head + "\n[쇼핑]\n" + fmt(shopping)):
        chunks.append({"source": "online_platforms", "section": sec, "content": c, "url": None, "collected_on": co})
    for sec, c in chunk_section("온라인 공식 플랫폼 > 배달", "[배달 앱]\n" + fmt(delivery)):
        chunks.append({"source": "online_platforms", "section": sec, "content": c, "url": None, "collected_on": co})
    return chunks


def _cat_terms():
    """카테고리 id → 그 품목을 가리키는 말들. data/cat_rules.json(채록 규칙 사본)에서 뽑는다.

    왜 필요한가: 코퍼스 청크에는 '가전·디지털/생활·주방가전' 같은 **라벨만** 있었다.
    이용자는 "로봇청소기 어디서 사?"라고 묻는데 그 말이 어디에도 없으니 검색이 닿지 않는다
    (2026-09-02 화면 검색에서 겪은 것과 같은 문제 — 거기서는 같은 규칙을 재사용해 풀었다).

    정규식에서 사람이 읽을 수 있는 낱말만 추린다. 문법 기호가 섞이면 임베딩에 잡음이 된다.
    """
    path = os.path.join(ROOT, "data", "cat_rules.json")
    if not os.path.exists(path):
        return {}
    out = {}
    for r in json.load(open(path, encoding="utf-8")).get("rules", []):
        words = []
        for w in r["re"].split("|"):
            w = re.sub(r"\(\?[<!=][^)]*\)", "", w)      # lookahead/lookbehind 제거
            w = w.replace("\\/", "/").replace("\\b", "").replace("\\", "").strip()
            if w and not re.search(r"[()\[\]?*+{}^$]", w):
                words.append(w)
        if words:
            out.setdefault(r["cat"], []).extend(words)
    return {k: list(dict.fromkeys(v)) for k, v in out.items()}


def load_online_catalog():
    d = json.load(open(os.path.join(ROOT, "data", "online_catalog.json"), encoding="utf-8"))
    plat = json.load(open(os.path.join(ROOT, "data", "online_platforms.json"), encoding="utf-8"))
    plat_names = {p["id"]: p["name"] for p in plat["items"]}
    co = d.get("meta", {}).get("collected_on", "")
    names = {}
    for t in d.get("taxonomy", []):
        names[t["id"]] = t["label"]
        for s in t.get("subs", []):
            names[s["id"]] = f"{t['label']}/{s['label']}"
    chunks = []
    for it in d["items"]:
        name = plat_names.get(it["id"], it["id"])
        cats = ", ".join(names.get(c, c) for c in it.get("cats", []))
        brands = ", ".join(it.get("brands", []))
        body = f"온라인몰 '{name}' 취급 물품종류(실측): {cats or '미확인'}."
        if brands:
            body += f" 확인된 브랜드: {brands}."
        if it.get("evidence"):
            body += f" (근거: {it['evidence']})"
        chunks.append({"source": "online_catalog", "section": f"온라인몰 취급품목 > {name}",
                       "content": body, "url": it.get("survey_url"),
                       "collected_on": it.get("surveyed_on", co)})

    # 품목별 청크 — "로봇청소기 어디서 사?" 처럼 **상품 이름으로 묻는** 질문이 닿을 자리다.
    # 몰별 청크는 '그 몰이 무엇을 파는가'에 답하고, 이쪽은 '이 품목을 파는 몰이 어디인가'에 답한다.
    terms = _cat_terms()
    by_cat = {}
    for it in d["items"]:
        for c in it.get("cats", []):
            by_cat.setdefault(c, []).append(plat_names.get(it["id"], it["id"]))
    for cat, malls in sorted(by_cat.items()):
        label = names.get(cat, cat)
        ex = terms.get(cat, [])
        body = f"온라인 물품종류 '{label}'"
        if ex:
            body += f" — {', '.join(ex[:14])} 등이 여기에 속한다"
        body += (f". 이 품목을 취급하는 것으로 확인된 온라인몰 {len(malls)}곳: "
                 f"{', '.join(sorted(malls))}.")
        chunks.append({"source": "online_catalog", "section": f"온라인 물품종류 > {label}",
                       "content": body, "url": None, "collected_on": co})
    return chunks


def build_all():
    chunks = (load_curated() + load_faq() + load_policy_analysis()
              + load_offline_categories() + load_online_platforms() + load_online_catalog())
    # 방어: 빈/중복 제거
    seen, out = set(), []
    for c in chunks:
        key = (c["source"], c["content"][:200])
        if c["content"] and key not in seen:
            seen.add(key)
            out.append(c)
    return out

# ---------- 임베딩·적재 ----------

def embed_batch(texts, api_key):
    req = urllib.request.Request(
        "https://api.openai.com/v1/embeddings",
        data=json.dumps({"model": "text-embedding-3-small", "input": texts}).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"})
    with urllib.request.urlopen(req, timeout=60) as r:
        d = json.load(r)
    return [e["embedding"] for e in sorted(d["data"], key=lambda x: x["index"])]


# collected_on 은 DB 가 varchar(10) — 형식이 어긋나면 임베딩을 다 태운 뒤 INSERT 에서 터진다.
# (실제 사고 2026-08-27: frontmatter 에 인라인 주석을 달았더니 파서가 값에 붙여 읽어 145건 임베딩 후 실패.
#  parse_frontmatter 는 YAML 이 아니라 단순 split 이므로 주석을 잘라주지 않는다.)
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def validate_chunks(chunks):
    bad = [(c["source"], repr(c.get("collected_on"))) for c in chunks
           if c.get("collected_on") and not DATE_RE.match(str(c["collected_on"]))]
    if bad:
        raise SystemExit("[FAIL] collected_on 형식 오류(YYYY-MM-DD 여야 함): "
                         + ", ".join(f"{s} -> {v}" for s, v in sorted(set(bad)))
                         + "\n  frontmatter 에 인라인 주석(#)을 달지 마세요 — 값에 딸려 들어갑니다.")


def load_db(chunks, api_key):
    import psycopg
    validate_chunks(chunks)
    url = os.environ.get("DB_URL", "postgresql://onnuri:onnuri@localhost:5432/onnuri")
    vecs = []
    B = 64
    for i in range(0, len(chunks), B):
        batch = [c["content"] for c in chunks[i:i + B]]
        vecs += embed_batch(batch, api_key)
        print(f"  임베딩 {min(i + B, len(chunks))}/{len(chunks)}")
    with psycopg.connect(url) as conn, conn.cursor() as cur:
        cur.execute("TRUNCATE rag_chunk RESTART IDENTITY")
        for c, v in zip(chunks, vecs):
            cur.execute(
                "INSERT INTO rag_chunk (source, section, content, url, collected_on, embedding) "
                "VALUES (%s, %s, %s, %s, %s, %s::vector)",
                (c["source"], c["section"], c["content"], c["url"], c["collected_on"], json.dumps(v)))
        conn.commit()
        cur.execute("SELECT count(*) FROM rag_chunk")
        print(f"[OK] rag_chunk 적재: {cur.fetchone()[0]}건")

# ---------- 셀프테스트 ----------

def selftest():
    md = "# A\n\n" + "가" * 100 + "\n\n## B\n\n" + "나" * 1200 + "\n\n짧은 문단.\n\n## C\n\n표 한 줄."
    meta = {"source": "t", "url": "u", "collected_on": "2026-01-01"}
    ch = chunk_markdown(meta, md)
    assert all(len(c["content"]) <= CHUNK_MAX + 250 for c in ch), "청크 상한 초과"

    # collected_on 형식 가드(2026-08-27 사고) — frontmatter 인라인 주석이 값에 딸려 들어가면
    # 임베딩을 다 태운 뒤 DB varchar(10) 에서 터진다. 적재 전에 잡혀야 한다.
    validate_chunks([{"source": "t", "collected_on": "2026-01-01"}])   # 정상은 통과
    validate_chunks([{"source": "t", "collected_on": ""}])             # 빈 값은 허용(url 없는 소스)
    try:
        validate_chunks([{"source": "t", "collected_on": '2026-08-11"   # 주석'}])
    except SystemExit:
        pass
    else:
        raise AssertionError("collected_on 형식 오류를 잡지 못했다")
    assert any(c["section"] == "A" for c in ch), "섹션 경로 소실"
    assert any(c["section"] == "A > B" for c in ch), "하위 섹션 경로 소실"
    big = [c for c in ch if c["section"] == "A > B"]
    assert len(big) >= 2, "장문 섹션 미분할"
    fm, body = parse_frontmatter("---\nsource: x\nurl: y\n---\n본문")
    assert fm["source"] == "x" and body.strip() == "본문", "frontmatter 파싱 실패"
    print("[OK] selftest 통과")


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
        sys.exit(0)
    chunks = build_all()
    stats = {}
    for c in chunks:
        stats[c["source"]] = stats.get(c["source"], 0) + 1
    print(f"총 청크: {len(chunks)}")
    for k, v in sorted(stats.items()):
        print(f"  {k}: {v}")
    lens = sorted(len(c["content"]) for c in chunks)
    print(f"  길이 min/중앙/max: {lens[0]}/{lens[len(lens)//2]}/{lens[-1]}")
    validate_chunks(chunks)   # dry-run 에서도 잡아 준다 — 서버에서 임베딩 후 터지기 전에
    if "--dry-run" in sys.argv:
        sys.exit(0)
    key = os.environ.get("OPENAI_API_KEY")
    if not key:
        sys.exit("[FAIL] OPENAI_API_KEY 미설정 — 적재하려면 키가 필요하다 (--dry-run은 키 불필요)")
    load_db(chunks, key)
