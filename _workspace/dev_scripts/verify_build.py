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

# (a) 로더 스크립트 / manifest / base64 blob 무결 — 388행(템플릿) 외 전부 원본과 동일
print("(a) 로더·manifest·base64 무결성")
check(len(out_lines) == len(src_lines), f"라인 수 동일 ({len(out_lines)} == {len(src_lines)})")
diff_lines = [i + 1 for i in range(min(len(out_lines), len(src_lines))) if out_lines[i] != src_lines[i]]
check(diff_lines == [TPL_IDX + 1], f"변경 라인은 388행(템플릿)뿐: {diff_lines}")
check(out_lines[375] == src_lines[375], "manifest 라인(376) 불변")

# (b) 템플릿 JSON 파싱 가능
print("(b) 템플릿 JSON 파싱")
raw388 = out_lines[TPL_IDX]
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
    check(len(on_items) == 30, f"ONLINE 30개 (실제 {len(on_items)}, JSON {len(on_json)})")
    check(len(off_items) == 12, f"OFFLINE 12개 (실제 {len(off_items)}, JSON {len(off_json)})")
    ship = len(re.findall(r"\{c:\"쇼핑\",", tpl))
    deli = len(re.findall(r"\{c:\"배달\",", tpl))
    check(ship == 22 and deli == 8, f"쇼핑 {ship} · 배달 {deli}")

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
    print("(e) 동적 바인딩·신규 섹션")
    for tok in ["{{ baseMonth }}", "{{ onTabText }}", "{{ onIntroMid }}",
                "{{ onIntroTail }}", "{{ regionApps }}", "{{ mflowArrow }}",
                "모바일(앱)형 결제 흐름 — QR 방식", "각주 ③ 참고",
                "대통령령 제36415호", "경과조치",
                "href=\"merchants.html\"",
                # S15 서브탭 + 접근성
                "가맹점을 직접 찾아보기", "공식 지도 검색 ↗", "전국 가맹점을 지역별로",
                "href=\"https://www.onnuri.gift/place\"",
                "새 창에서 열림", "내부 · 수도권", "외부 · 전국",
                # 요건2 지도 검색 안내 문장 유지(인라인 링크만 제거) 확인 — hex 는 task #24 모노톤(#17181A)
                "가맹 여부는 <strong style=\"color:#17181A\">온누리 가맹점 지도</strong>에서 점포 단위로 확인할 수 있습니다",
                "기업형슈퍼마켓(SSM) 직영점", "가맹 제외 대상은 직영점 기준",
                "GS더프레시 직영", "직영점은 가맹 제외 — 단, 같은 브랜드라도"]:
        check(tok in tpl, f"토큰 '{tok}' 존재")

    # (f) renderVals computed 정의 존재
    print("(f) renderVals computed 정의")
    for tok in ["const onTabText =", "const baseMonth =", "const regionApps =",
                "const onIntroTail =", "no: i + 1", "ONLINE_META =", "OFFLINE_META ="]:
        check(tok in tpl, f"'{tok}' 정의됨")

    # (g) task #24: 사이드바 셸 + 화이트 모노톤 확산
    print("(g) task #24 사이드바 + 모노톤")
    # 디자인 토큰
    for tok in ["--accent:#F26B1D", "--text:#17181A", "--surface:#F7F7F7",
                "--border:#E6E6E6", "--sb-w:248px",
                ".sb-item.active::before"]:
        check(tok in tpl, f"토큰/CSS '{tok}' 존재")
    # 사이드바 마크업(<x-dc> 밖)
    for tok in ["class=\"sidebar\" id=\"sidebar\"", "id=\"navToggle\"", "id=\"navOverlay\"",
                "class=\"sb-item active\" href=\"#offline\" aria-current=\"page\"",
                "href=\"merchants.html#sidoTabs\"", "href=\"merchants.html#catChips\"",
                "공식 가맹점 지도"]:
        check(tok in tpl, f"사이드바 '{tok}' 존재")
    # 셸 구조 + 섹션 앵커 id
    for tok in ["<main class=\"content\">", "class=\"content-inner\"",
                "id=\"tabOff\"", "id=\"tabOn\"", "id=\"payment\"", "id=\"online\"", "id=\"terms\""]:
        check(tok in tpl, f"셸/앵커 '{tok}' 존재")
    # 드로어 + 해시 라우터 스크립트(resize 리셋 = task #23 낮음 관찰 처리)
    for tok in ["function applyHash", "window.addEventListener(\"hashchange\"",
                "window.addEventListener(\"resize\"", "matchMedia(\"(max-width:959px)\")"]:
        check(tok in tpl, f"스크립트 '{tok}' 존재")
    # 모노톤 불변식: 웜톤 hex·pill 잔존 0 (오렌지 #F26B1D·#C4510F 는 유지)
    warm = sorted(set(re.findall(
        r"#(?:E7E5E1|EFEEEC|8A8580|6E6A64|171512|26231F|FAFAF9|F0EFED|FDEEE3|FDF3EA|FBD8BC|F5D2B8|EFC5A3|F4F4F3|A3A09B)", tpl)))
    check(not warm, f"웜톤 hex 잔존 0 (실제 {warm})")
    check("border-radius:999px" not in tpl, "pill(999px) 잔존 0")
    check("#F26B1D" in tpl and "#C4510F" in tpl, "오렌지 포인트 유지(#F26B1D·#C4510F)")

print()
if fails:
    print(f"자체 확인 실패: {len(fails)}건")
    sys.exit(1)
print("자체 확인 전체 통과")
