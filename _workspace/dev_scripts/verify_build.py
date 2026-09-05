#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_build.py — 생성된 index.html 자체 무결성 점검(브라우저 검증은 verifier 담당)."""
import json, os, re, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SRC = os.path.join(ROOT, "index.html.bak-20260806")
OUT = os.path.join(ROOT, "index.html")
ONLINE_JSON = os.path.join(ROOT, "data", "online_platforms.json")
OFFLINE_JSON = os.path.join(ROOT, "data", "offline_categories.json")
TPL_IDX = 387

fails = []
def check(cond, msg):
    print(("  [PASS] " if cond else "  [FAIL] ") + msg)
    if not cond:
        fails.append(msg)

with open(OUT, encoding="utf-8") as f:
    out_lines = f.read().split("\n")
with open(SRC, encoding="utf-8") as f:
    src_lines = f.read().split("\n")

# 8단계(외곽 <head> 탭 제목·파비콘)는 템플릿 밖 정적 라인을 바꾸고 link 라인을 1줄 늘린다.
# (a) 무결성 비교는 그 차이를 되돌린 정규화본으로 수행한다 — 그러지 않으면 라인 인덱스가
# 통째로 밀려 이후 모든 검사가 무의미해진다(2026-08-12 파비콘 추가 이후 실제로 그랬다).
OUTER_TITLE_OLD = "  <title>디지털온누리상품권 가이드</title>"
OUTER_TITLE_NEW = "  <title>코스콤 디지털온누리 가이드</title>"
OUTER_FAVICON = '  <link rel="icon" href="favicon.svg?v=1" type="image/svg+xml">'
norm_lines = [OUTER_TITLE_OLD if ln == OUTER_TITLE_NEW else ln
              for ln in out_lines if ln != OUTER_FAVICON]

# (a) 로더 스크립트 / manifest / base64 blob 무결 — 388행(템플릿) 외 전부 원본과 동일
print("(a) 로더·manifest·base64 무결성")
check(len(norm_lines) == len(src_lines), f"라인 수 동일 ({len(norm_lines)} == {len(src_lines)}, 외곽 head 정규화 후)")
diff_lines = [i + 1 for i in range(min(len(norm_lines), len(src_lines))) if norm_lines[i] != src_lines[i]]
check(diff_lines == [TPL_IDX + 1], f"변경 라인은 388행(템플릿)뿐: {diff_lines}")
check(norm_lines[375] == src_lines[375], "manifest 라인(376) 불변")

# (b) 템플릿 JSON 파싱 가능
print("(b) 템플릿 JSON 파싱")
raw388 = norm_lines[TPL_IDX]
tpl = None
try:
    tpl = json.loads(raw388)
    check(True, f"388행 JSON 디코드 성공 ({len(tpl)} chars)")
except Exception as e:
    check(False, f"JSON 디코드 실패: {e}")
# HTML 파서 조기종료 방지 불변식: script 요소 textContent 안에 리터럴 </ 가 없어야 한다.
# (원본 번들 불변식과 동일 — json.loads/node --check 로는 못 잡는 파서 레벨 결함을 정적으로 포착)
check(raw388.count("</") == 0,
      f"388행 raw 에 리터럴 </ 없음 (실제 {raw388.count('</')}개 — 0 이어야 script 조기종료 안 함)")
check("<\\u002F" in raw388, "</ 가 <\\u002F 로 이스케이프됨")

if tpl:
    # (c) 주입 데이터 개수
    print("(c) 주입 데이터 개수")
    on_items = re.findall(r"\{c:\"(?:쇼핑|배달)\",", tpl)
    off_items = re.findall(r"\{t:\"[^\"]+\", d:", tpl)
    with open(ONLINE_JSON, encoding="utf-8") as f:
        on_json = [x for x in json.load(f)["items"] if x.get("status") == "active"]
    with open(OFFLINE_JSON, encoding="utf-8") as f:
        off_json = json.load(f)["items"]
    # 2026-09-04: 기대치를 30 으로 **손으로 적어 두어** 공식 목록에 몰이 하나 늘자(권율로) 실패했다.
    # 이 검사가 지켜야 하는 것은 "몇 곳인가"가 아니라 **"템플릿과 데이터가 같은가"** 다.
    # 숫자를 적으면 데이터가 바뀔 때마다 검증기가 거짓 경보를 낸다(페이지에 숫자를 손으로
    # 쓰지 않는다는 이 저장소의 원칙이 검증기에도 그대로 적용된다).
    check(len(on_items) == len(on_json), f"ONLINE 템플릿 {len(on_items)} = JSON {len(on_json)}")
    check(len(off_items) == 12, f"OFFLINE 12개 (실제 {len(off_items)}, JSON {len(off_json)})")
    ship = len(re.findall(r"\{c:\"쇼핑\",", tpl))
    deli = len(re.findall(r"\{c:\"배달\",", tpl))
    j_ship = sum(1 for x in on_json if x.get("kind") == "shopping")
    j_deli = sum(1 for x in on_json if x.get("kind") == "delivery")
    check(ship == j_ship and deli == j_deli,
          f"쇼핑 {ship}=JSON {j_ship} · 배달 {deli}=JSON {j_deli}")

    # (d) 하드코딩 잔재 없음
    print("(d) 하드코딩 제거 확인")
    for bad in ["공식 안내 30곳", "2026-08 기준", "쇼핑 22 · 배달 8",
                "아래 30곳은", "1~3페이지)에 안내",
                "대구로, 배달특급, 배달의 명수, 전주맛배달",
                # SSM 정정: 구 blanket 문구 잔재 없어야 함
                "대형마트·백화점·SSM, 유흥",
                "SSM</strong>(기업형 슈퍼마켓): 대기업이 운영하는",
                "최종 확인은 앱 지도",
                # S15(task #17): 구 M7 진입 박스 제거 + 요건2 인라인 지도 링크만 제거(문장은 유지)
                "수도권 가맹점 검색 ↗",
                "최종 확인은 온누리 가맹점 지도/앱",
                "onnuri.gift/place 가맹점 지도 검색 ↗"]:
        check(bad not in tpl, f"하드코딩/구문구 '{bad[:30]}' 제거됨")

    # (e) 동적 바인딩·신규 섹션 존재
    # 2026-08-19 정리: payment.html/terms.html 로 이관된 토큰은 여기서 뺐다(이관 무결성은 (i)).
    #   {{ regionApps }}·각주 ③·대통령령 제36415호·가맹 제외 대상은 직영점 기준 → terms.html
    #   {{ mflowArrow }}·모바일(앱)형 결제 흐름 → payment.html(앱(QR)형으로 재작성)
    # 문구 갱신: "내부 · 수도권" → 부산 추가(2026-08-10), 공식 지도 카드 설명문 개정.
    print("(e) 동적 바인딩·신규 섹션")
    for tok in ["{{ baseMonth }}", "{{ onTabText }}", "{{ onIntroMid }}",
                "{{ onIntroTail }}", "경과조치",
                "href=\"merchants.html\"",
                # S15 서브탭 + 접근성
                "가맹점을 직접 찾아보기", "공식 지도 검색 ↗",
                "서울·인천·경기·부산 외 지역의 가맹점은 온누리 공식 지도에서 확인하세요",
                "href=\"https://www.onnuri.gift/place\"",
                "새 창에서 열림", "내부 · 서울·인천·경기·부산", "외부 · 전국",
                # 요건2 지도 검색 안내 문장 유지(인라인 링크만 제거) 확인 — hex 는 shell.css 팔레트(#0B0C0E, 2026-08-19 통일)
                "가맹 여부는 <strong style=\"color:#0B0C0E\">온누리 가맹점 지도</strong>에서 점포 단위로 확인할 수 있습니다",
                "기업형슈퍼마켓(SSM) 직영점",
                "GS더프레시 직영", "직영점은 가맹 제외 — 단, 같은 브랜드라도"]:
        check(tok in tpl, f"토큰 '{tok}' 존재")

    # (f) renderVals computed 정의 존재
    print("(f) renderVals computed 정의")
    # 2026-09-05: `const regionApps =` 를 뺐다. 그 값이 들어가던 각주가 terms.html 로
    # 이관돼(7g) index 에는 렌더되지 않으므로 정의만 남아 있었다 — (i) 이관 무결성이
    # terms.html 쪽 실물을 계속 지킨다. 지운 뒤 재빌드에서 index.html 바이트 동일 확인.
    for tok in ["const onTabText =", "const baseMonth =",
                "const onIntroTail =", "no: i + 1", "ONLINE_META =", "OFFLINE_META ="]:
        check(tok in tpl, f"'{tok}' 정의됨")

    # (g) task #24: 사이드바 셸 + 화이트 모노톤 확산
    print("(g) task #24 사이드바 + 모노톤")
    # 디자인 토큰·셸 CSS 는 2026-08-10 셸 공통화(ADR)로 shell.css 로 나갔다 —
    # 템플릿에 없는 것이 정상이므로 shell.css 를 대상으로 검사한다(값은 현행 팔레트).
    with open(os.path.join(ROOT, "shell.css"), encoding="utf-8") as _f:
        shell_css = _f.read()
    for tok in ["--accent:#F26B1D", "--text:#0B0C0E", "--surface:#F6F6F7",
                "--border:#E5E6E8", "--sb-w:248px",
                ".sb-item.active::before"]:
        check(tok in shell_css, f"shell.css 토큰/CSS '{tok}' 존재")
    check(not re.search(r"--[a-z-]+\s*:\s*#[0-9A-Fa-f]{6}", tpl),
          "템플릿에 CSS 변수 정의 잔존 0 (셸 외부화 완료)")
    # 사이드바 마크업(<x-dc> 밖)
    for tok in ["class=\"sidebar\" id=\"sidebar\"", "id=\"navToggle\"", "id=\"navOverlay\"",
                "class=\"sb-item active\" href=\"#offline\" aria-current=\"page\"",
                # #catChips 는 2026-08-10 '가맹점 찾기' 메뉴 통합으로 제거됨
                "href=\"merchants.html#sidoTabs\"",
                "공식 가맹점 지도"]:
        check(tok in tpl, f"사이드바 '{tok}' 존재")
    # 셸 구조 + 섹션 앵커 id
    # id="payment" 는 2026-08-11 payment.html 분리로 제거됨(포인터 링크 검사는 (i)).
    for tok in ["<main class=\"content\">", "class=\"content-inner\"",
                "id=\"tabOff\"", "id=\"tabOn\"", "id=\"online\"", "id=\"terms\""]:
        check(tok in tpl, f"셸/앵커 '{tok}' 존재")
    # 드로어 + 해시 라우터 스크립트(resize 리셋 = task #23 낮음 관찰 처리)
    for tok in ["function applyHash", "window.addEventListener(\"hashchange\""]:
        check(tok in tpl, f"스크립트 '{tok}' 존재")
    # 드로어·리사이즈 처리도 shell.js 로 이관(2026-08-10)
    with open(os.path.join(ROOT, "shell.js"), encoding="utf-8") as _f:
        shell_js = _f.read()
    for tok in ["window.addEventListener(\"resize\"", "matchMedia(\"(max-width:959px)\")"]:
        check(tok in shell_js, f"shell.js 스크립트 '{tok}' 존재")
    # 모노톤 불변식: 웜톤 hex·pill 잔존 0 (오렌지 #F26B1D·#C4510F 는 유지)
    warm = sorted(set(re.findall(
        r"#(?:E7E5E1|EFEEEC|8A8580|6E6A64|171512|26231F|FAFAF9|F0EFED|FDEEE3|FDF3EA|FBD8BC|F5D2B8|EFC5A3|F4F4F3|A3A09B|9A968F)", tpl)))
    check(not warm, f"웜톤 hex 잔존 0 (실제 {warm})")
    check("border-radius:999px" not in tpl, "pill(999px) 잔존 0")
    check("#F26B1D" in tpl and "#C4510F" in tpl, "오렌지 포인트 유지(#F26B1D·#C4510F)")
    # 토큰 출처 단일화(2026-08-19): index 인라인 색이 shell.css 팔레트와 같은 값이어야 한다.
    # 구 모노톤이 되살아나면 여기서 잡힌다 — 한쪽만 고치고 나머지를 잊는 것이 이 저장소의 상습 결함이다.
    for _old, _new in [("#17181A", "#0B0C0E"), ("#6B6E73", "#585D64"), ("#E6E6E6", "#E5E6E8"),
                       ("#F7F7F7", "#F6F6F7"), ("#F0F0F0", "#EFF0F2")]:
        check(_old not in tpl, f"구 모노톤 {_old} 잔존 0 (→ {_new})")
    for _tok in ["#0B0C0E", "#585D64", "#E5E6E8"]:
        check(_tok in shell_css and _tok in tpl, f"{_tok} 가 shell.css·템플릿 양쪽에 존재(출처 일치)")

    # (h) 탭 제목·파비콘: 외곽과 템플릿 내부 양쪽 (2026-08-18)
    # 로더는 document.documentElement.replaceWith(doc.documentElement) 로 문서 루트를 통째로
    # 교체한다 → 외곽 <head> 의 <title>·<link icon> 은 그 순간 사라진다. 템플릿 내부에도
    # 있어야 실제 탭에 남는다. 외곽만 검사하면 이 결함을 놓친다(2026-08-12~08-18 실제 사고).
    print("(h) 탭 제목·파비콘 (외곽 + 템플릿 내부)")
    check(OUTER_TITLE_NEW in out_lines, "외곽 <title> = 코스콤 디지털온누리 가이드")
    check(OUTER_FAVICON in out_lines, "외곽 <link rel=icon> 존재")
    check("<title>코스콤 디지털온누리 가이드</title>" in tpl,
          "템플릿 내부 <title> 존재 (replaceWith 후에도 탭 제목 유지)")
    check('<link rel="icon" href="favicon.svg?v=1" type="image/svg+xml">' in tpl,
          "템플릿 내부 <link rel=icon> 존재 (replaceWith 후에도 파비콘 유지)")
    _head_m = re.search(r"<head[^>]*>", tpl, re.I)
    check(bool(_head_m) and tpl.find("<title>", _head_m.end()) < tpl.lower().find("</head>"),
          "템플릿 <title> 이 <head> 안에 위치")

    # (i) 이관 무결성 (2026-08-19)
    # index 에서 뺀 콘텐츠는 payment.html·terms.html 로 옮겨졌다. index 검사에서 토큰을
    # 지우기만 하면 "이관됨"과 "소실됨"을 구분할 수 없으므로, 목적지에 실물이 있는지와
    # index 에 그쪽으로 가는 포인터가 있는지를 함께 본다.
    print("(i) 이관 무결성 (payment.html · terms.html)")
    check('href="payment.html"' in tpl, "index 에 payment.html 포인터 존재")
    check('href="terms.html"' in tpl, "index 에 terms.html 포인터 존재")
    _moved = [
        ("payment.html", ["앱(QR)형", "카드형", "선차감"],
         "결제 흐름(2026-08-11 분리, index 의 mflow 블록을 대체)"),
        ("terms.html", ["대통령령 제36415호", "가맹 제외 대상은 직영점 기준", "regionApps"],
         "용어·유의사항·각주(2026-08-11 분리)"),
    ]
    for _fn, _toks, _why in _moved:
        _path = os.path.join(ROOT, _fn)
        if not os.path.exists(_path):
            check(False, f"{_fn} 존재 — {_why}")
            continue
        with open(_path, encoding="utf-8") as _f:
            _c = _f.read()
        for _t in _toks:
            check(_t in _c, f"{_fn}: '{_t}' 존재 — {_why}")

print()
if fails:
    print(f"자체 확인 실패: {len(fails)}건")
    sys.exit(1)
print("자체 확인 전체 통과")
