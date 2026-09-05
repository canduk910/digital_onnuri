#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_index.py — data/*.json + 03_content_spec.md(변경 7섹션) 을 index.html 번들에 주입.

전략:
  1. 원본(index.html.bak-20260806, pristine)에서 388행 __bundler/template JSON 문자열을 디코드.
  2. 디코드된 페이지(HTML+DC 앱) 안의 하드코딩 ONLINE/OFFLINE 배열을 data/*.json 으로 교체.
  3. D1~D7 동적 문구를 renderVals computed 로 교체(하드코딩 숫자·기준일 제거).
  4. 변경 7섹션 문구 반영(S1·S4·S9·S10신규·S11·S13·S14).
  5. 템플릿을 다시 JSON 인코딩해 388행에 재삽입. manifest/base64/loader 불변.

재현 가능: 항상 .bak 원본에서 시작해 index.html 을 생성한다(멱등).
"""
import json, sys, os, re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SRC_BUNDLE = os.path.join(ROOT, "index.html.bak-20260806")
OUT_BUNDLE = os.path.join(ROOT, "index.html")
ONLINE_JSON = os.path.join(ROOT, "data", "online_platforms.json")
OFFLINE_JSON = os.path.join(ROOT, "data", "offline_categories.json")

TEMPLATE_LINE_IDX = 387  # 0-based -> 388행

def jsstr(s):
    """파이썬 문자열 -> JS 더블쿼트 문자열 리터럴(유니코드 보존, 이스케이프 안전)."""
    return json.dumps(s, ensure_ascii=False)

def replace_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise SystemExit(f"[FAIL] {label}: 기대 1회, 실제 {n}회 매칭\n  --- old ---\n{old[:200]}")
    return text.replace(old, new, 1)

# ---------- 1. 데이터 로드 (주입 시점에 최신본 재읽기) ----------
with open(ONLINE_JSON, encoding="utf-8") as f:
    online = json.load(f)
with open(OFFLINE_JSON, encoding="utf-8") as f:
    offline = json.load(f)

on_meta = online["meta"]
off_meta = offline["meta"]

# ---------- 2. ONLINE / OFFLINE JS 배열 생성 ----------
on_lines = []
for it in online["items"]:
    if it.get("status") != "active":
        continue
    c = "배달" if it["kind"] == "delivery" else "쇼핑"
    on_lines.append(
        "    {c:%s, n:%s, d:%s, u:%s, m:%s, rl:%s, st:%s, co:%s}," % (
            jsstr(c), jsstr(it["name"]), jsstr(it.get("summary", "")),
            jsstr(it["url"]), jsstr(it.get("note", "")),
            "true" if it.get("region_limited") else "false",
            jsstr(it.get("status", "active")), jsstr(it.get("collected_on", "")),
        )
    )
on_lines[-1] = on_lines[-1].rstrip(",")  # 마지막 쉼표 제거
ONLINE_JS = (
    "  ONLINE_META = { collected_on: %s, pages_checked: %s };\n"
    "  ONLINE = [\n%s\n  ];"
) % (jsstr(on_meta.get("collected_on", "")), jsstr(on_meta.get("pages_checked", "")),
     "\n".join(on_lines))

GMAP = {"allowed": "ok", "conditional": "cond", "denied": "no"}
off_lines = []
for it in offline["items"]:
    off_lines.append(
        "    {t:%s, d:%s, g:%s, s:%s, p:%s, co:%s}," % (
            jsstr(it["type"]), jsstr(it.get("examples", "")),
            jsstr(GMAP[it["verdict"]]), jsstr(it["verdict_label"]),
            jsstr(it.get("check_point", "")), jsstr(it.get("collected_on", "")),
        )
    )
off_lines[-1] = off_lines[-1].rstrip(",")
OFFLINE_JS = (
    "  OFFLINE_META = { collected_on: %s };\n"
    "  OFFLINE = [\n%s\n  ];"
) % (jsstr(off_meta.get("collected_on", "")), "\n".join(off_lines))

# ---------- 3. 번들 로드 & 템플릿 디코드 ----------
with open(SRC_BUNDLE, encoding="utf-8") as f:
    bundle_lines = f.read().split("\n")
tpl = json.loads(bundle_lines[TEMPLATE_LINE_IDX])
orig_tpl = tpl

# ---------- 4a. JS 데이터 배열 교체 ----------
# ONLINE (구: ...].map((x, i) => Object.assign({}, x, { no: i + 1 }));)
m = re.search(r"  ONLINE = \[.*?\]\.map\(\(x, i\) => Object\.assign\(\{\}, x, \{ no: i \+ 1 \}\)\);",
              tpl, re.DOTALL)
if not m:
    raise SystemExit("[FAIL] ONLINE 배열 블록을 찾지 못함")
tpl = tpl[:m.start()] + ONLINE_JS + tpl[m.end():]

# OFFLINE (구: ...\n  ];\n\n  state = {)
m = re.search(r"  OFFLINE = \[.*?\n  \];\n\n  state = \{", tpl, re.DOTALL)
if not m:
    raise SystemExit("[FAIL] OFFLINE 배열 블록을 찾지 못함")
tpl = tpl[:m.start()] + OFFLINE_JS + "\n\n  state = {" + tpl[m.end():]

# ---------- 4b. state: mflowOpen 추가 ----------
tpl = replace_once(
    tpl,
    "    flowOpen: this.props.expandDetails ?? true,\n  };",
    "    flowOpen: this.props.expandDetails ?? true,\n"
    "    mflowOpen: this.props.expandDetails ?? true,\n  };",
    "state.mflowOpen",
)

# ---------- 4c. 메서드: toggleMFlow 추가 ----------
tpl = replace_once(
    tpl,
    "  toggleFlow = () => this.setState(s => ({ flowOpen: !s.flowOpen }));",
    "  toggleFlow = () => this.setState(s => ({ flowOpen: !s.flowOpen }));\n"
    "  toggleMFlow = () => this.setState(s => ({ mflowOpen: !s.mflowOpen }));",
    "method.toggleMFlow",
)

# ---------- 4d. D7: 연번을 정렬 후 부여 ----------
tpl = replace_once(
    tpl,
    "    onRows = onRows.map(r => ({ no: r.no, n: r.n, d: r.d, m: r.m || \"—\", u: r.u, isDelivery: r.c === \"배달\", isShopping: r.c === \"쇼핑\" }));",
    "    onRows = onRows.map((r, i) => ({ no: i + 1, n: r.n, d: r.d, m: r.m || \"—\", u: r.u, isDelivery: r.c === \"배달\", isShopping: r.c === \"쇼핑\" }));",
    "D7.onRows-no",
)

# ---------- 4e. renderVals: 동적 computed 삽입 (D1·D2·D3·D6) ----------
COMPUTE = (
    "    const onActive = this.ONLINE.filter(r => r.st === \"active\");\n"
    "    const onTotal = onActive.length;\n"
    "    const onShopping = onActive.filter(r => r.c === \"쇼핑\").length;\n"
    "    const onDelivery = onActive.filter(r => r.c === \"배달\").length;\n"
    "    const onTabText = \"공식 안내 \" + onTotal + \"곳 — 쇼핑 \" + onShopping + \" · 배달 \" + onDelivery;\n"
    "    const collectedOn = this.ONLINE_META.collected_on;\n"
    "    const pagesChecked = (this.ONLINE_META.pages_checked || \"\").replace(\"-\", \"~\");\n"
    "    const stampDates = [];\n"
    "    this.ONLINE.forEach(r => { if (r.co) stampDates.push(r.co); });\n"
    "    this.OFFLINE.forEach(r => { if (r.co) stampDates.push(r.co); });\n"
    "    if (this.ONLINE_META.collected_on) stampDates.push(this.ONLINE_META.collected_on);\n"
    "    if (this.OFFLINE_META.collected_on) stampDates.push(this.OFFLINE_META.collected_on);\n"
    "    const baseStamp = stampDates.slice().sort()[0] || \"\";\n"
    "    const baseMonth = baseStamp.slice(0, 7);\n"
    # 2026-09-05: `regionApps` 정의를 지운다. 이 값이 들어가던 각주는 2026-08-11(7g)에
    # terms.html 로 이관됐고 index 에는 남지 않는다 — terms.html 이 자기 몫을 따로 갖고 있다.
    # 지우고 재빌드해 **index.html 이 바이트 동일**함을 확인했다(죽은 값이었다는 증거).
    # 2026-09-04: 인트로가 목록 전체를 meta.collected_on(2026-08-06) 하나로 말하고 있었다.
    # 이후 확인분이 섞이면(권율로 2026-09-03) "그날 수집한 31곳 전체"라는 두 겹의 거짓이 된다.
    # 날짜는 항목의 실제 수집일에서 뽑고, 추가분이 있으면 "전체"라 말하지 않는다.
    "    const onDates = [];\n"
    "    onActive.forEach(r => { if (r.co) onDates.push(r.co); });\n"
    "    const onSorted = onDates.slice().sort();\n"
    "    const onOldest = onSorted[0] || collectedOn;\n"
    "    const onNewest = onSorted[onSorted.length - 1] || collectedOn;\n"
    "    const onLater = onActive.filter(r => r.co && r.co !== onOldest).length;\n"
    "    const onIntroMid = \"전용관·제휴 플랫폼은 계속 추가되는 단계라, 아래 목록(\" + onOldest + \" 수집\"\n"
    "      + (onLater ? \", \" + onLater + \"곳은 \" + onNewest + \"까지 추가 확인\" : \"\")\n"
    "      + \")에 없는 곳이 새로 생겼을 수 있습니다.\";\n"
    "    const onIntroTail = \"아래 \" + onTotal + \"곳은 온누리 공식 홈페이지 '온라인 전통시장관'(\" + pagesChecked\n"
    "      + \"페이지)에 안내된 가맹 플랫폼\" + (onLater ? \"과 이후 확인된 추가분입니다.\" : \" 전체입니다.\");\n\n"
)
tpl = replace_once(
    tpl,
    "    return {\n      isOff, isOn: !isOff,",
    COMPUTE + "    return {\n      isOff, isOn: !isOff,",
    "renderVals.compute",
)

# ---------- 4f. renderVals return: 새 키 노출 ----------
tpl = replace_once(
    tpl,
    "      tipsArrow: st.tipsOpen ? \"▼\" : \"▶\", flowArrow: st.flowOpen ? \"▼\" : \"▶\",\n    };",
    "      tipsArrow: st.tipsOpen ? \"▼\" : \"▶\", flowArrow: st.flowOpen ? \"▼\" : \"▶\",\n"
    "      mflowOpen: st.mflowOpen, toggleMFlow: this.toggleMFlow,\n"
    "      mflowArrow: st.mflowOpen ? \"▼\" : \"▶\",\n"
    "      baseMonth, onTotal, onShopping, onDelivery, onTabText,\n"
    "      collectedOn, pagesChecked, onIntroMid, onIntroTail,\n    };",
    "renderVals.return",
)

# ---------- 5. 템플릿 HTML 문구 교체 ----------
# D1 헤더 부제(기준월)
tpl = replace_once(
    tpl,
    "사용처 안내 · 2026-08 기준 · 온라인 목록 출처:",
    "사용처 안내 · <span>{{ baseMonth }}</span> 기준 · 온라인 목록 출처:",
    "D1.baseMonth",
)

# D2 온라인 탭 부제
tpl = replace_once(
    tpl,
    ">공식 안내 30곳 — 쇼핑 22 · 배달 8</span>",
    ">{{ onTabText }}</span>",
    "D2.onTabText",
)

# S4 요건 3
tpl = replace_once(
    tpl,
    "<strong style=\"color:#C4510F\">연매출 30억 원 이하</strong> 점포 — 2026.6.17부터 신규 등록·3년 주기 갱신 시 적용, 초과 확인 시 기존 가맹점도 등록 말소",
    "<strong style=\"color:#C4510F\">연매출 30억 원 이하</strong> 점포 — 2026.6.17부터 신규 등록·3년 주기 갱신 시 심사. 시행일 전에 등록된 기존 가맹점은 최초 갱신 전까지는 이 기준을 적용받지 않습니다(경과조치).",
    "S4.req3",
)

# S4 요건 4
tpl = replace_once(
    tpl,
    "<strong style=\"color:#C4510F\">제외 업종이 아닐 것</strong> — 대형마트·백화점·SSM, 유흥·사행성, 병·의원 등 보건업, 수의·법무·회계세무 (약국은 예외적으로 가맹 유지)",
    "<strong style=\"color:#C4510F\">제외 업종이 아닐 것</strong> — 대형마트·백화점·기업형슈퍼마켓(SSM) 직영점, 유흥·사행성, 병·의원 등 보건업, 수의·법무·회계세무(보건업 등은 2026.6.17부터 추가). 같은 브랜드 SSM이라도 전통시장·골목형상점가 안의 개인 가맹점주 점포는 가맹된 경우 사용 가능 — 앱 지도에서 개별 확인. 약국은 예외적으로 가맹이 유지됩니다 — 단, 연매출 30억 기준은 약국에도 똑같이 적용됩니다.",
    "S4.req4",
)

# S15(task #17): 요건2 '지도 검색' 안내 문장은 **유지**(요건 ② 본문 — 점포 단위 확인법 설명).
# 구 M7 '수도권 가맹점 검색 ↗' 진입 박스는 .bak 원본에 없고 이전 M7 스텝이 추가하던 것 → 추가 스텝을
# 두지 않으므로 별도 제거 불필요.
# team-lead 정정2: 검색 '진입'은 S15 서브탭으로 일원화했으니 요건2 지도박스의 인라인 onnuri.gift/place
# 링크만 텍스트로 전환한다(안내 문장 자체는 유지 — '가맹 시 가능' 매장 점포 단위 확인 안내가 핵심).
tpl = replace_once(
    tpl,
    "<a href=\"https://www.onnuri.gift/place\" target=\"_blank\" rel=\"noopener\" style=\"font-weight:700\">onnuri.gift/place 가맹점 지도 검색 ↗</a>",
    "<strong style=\"color:#26231F\">온누리 가맹점 지도</strong>",
    "S15.req2-delink",
)

# S9 4단계
tpl = replace_once(
    tpl,
    "가맹점이면 상품권 잔액에서 우선 차감, 부족분만 카드로 청구 · 미가맹 점포면 전액 일반 카드 결제",
    "가맹점이면 상품권 잔액에서 우선 차감 · 미가맹 점포면 차감 없이 전액 일반 카드 결제. 단, 결제액이 충전 잔액보다 크면 부족분만 카드로 청구될 수도, 차감 없이 전액 일반 카드로 결제될 수도 있습니다 — 잔액보다 큰 금액을 결제하기 전에는 앱에서 잔액을 먼저 확인하세요.",
    "S9.step4",
)

# S10 모바일(앱)형 결제 흐름 — 신규 (S9 흐름 카드 바로 아래, isOff 블록 내부)
S10 = (
    "\n    <div style=\"background:#FFFFFF;border:1.5px solid #E7E5E1;border-radius:14px;margin-top:12px;overflow:hidden\">\n"
    "      <button sc-camel-on-click=\"{{ toggleMFlow }}\" style=\"width:100%;box-sizing:border-box;font-family:inherit;cursor:pointer;background:none;border:none;padding:14px 18px;font-size:13.5px;font-weight:800;color:#171512;display:flex;align-items:center;gap:9px;text-align:left\" style-hover=\"background:#FAFAF9\">\n"
    "        <span style=\"flex:none;color:#F26B1D;font-size:10px\">{{ mflowArrow }}</span>모바일(앱)형 결제 흐름 — QR 방식<span style=\"font-weight:500;font-size:12px;color:#8A8580;margin-left:auto\">실물 카드 없이 앱만으로 — QR을 찍거나 보여주고 잔액에서 바로 차감</span>\n"
    "      </button>\n"
    "      <sc-if value=\"{{ mflowOpen }}\" hint-placeholder-val=\"{{ false }}\">\n"
    "        <div style=\"padding:4px 18px 18px\">\n"
    "          <div style=\"display:flex;gap:12px\">\n"
    "            <div style=\"display:flex;flex-direction:column;align-items:center\"><span style=\"flex:none;width:24px;height:24px;border-radius:50%;background:#F26B1D;color:#FFFFFF;font-size:12px;font-weight:800;display:inline-flex;align-items:center;justify-content:center\">1</span><span style=\"flex:1;width:1.5px;background:#F5D2B8;margin:4px 0\"></span></div>\n"
    "            <div style=\"padding-bottom:14px\"><div style=\"font-size:13px;font-weight:700;color:#171512\">앱 설치·가입</div><div style=\"font-size:12.5px;color:#6E6A64;line-height:1.55\">'디지털온누리' 통합앱 설치 후 본인 명의 휴대폰으로 가입 (2025.3.1부터 카드형·모바일형이 이 앱 하나로 통합 — 한국조폐공사 운영)</div></div>\n"
    "          </div>\n"
    "          <div style=\"display:flex;gap:12px\">\n"
    "            <div style=\"display:flex;flex-direction:column;align-items:center\"><span style=\"flex:none;width:24px;height:24px;border-radius:50%;background:#F26B1D;color:#FFFFFF;font-size:12px;font-weight:800;display:inline-flex;align-items:center;justify-content:center\">2</span><span style=\"flex:1;width:1.5px;background:#F5D2B8;margin:4px 0\"></span></div>\n"
    "            <div style=\"padding-bottom:14px\"><div style=\"font-size:13px;font-weight:700;color:#171512\">충전</div><div style=\"font-size:12.5px;color:#6E6A64;line-height:1.55\">앱에서 충전하면 곧 상품권 잔액 — 할인 적용, 월 한도 내 (카드형과 동일)</div></div>\n"
    "          </div>\n"
    "          <div style=\"display:flex;gap:12px\">\n"
    "            <div style=\"display:flex;flex-direction:column;align-items:center\"><span style=\"flex:none;width:24px;height:24px;border-radius:50%;background:#F26B1D;color:#FFFFFF;font-size:12px;font-weight:800;display:inline-flex;align-items:center;justify-content:center\">3</span><span style=\"flex:1;width:1.5px;background:#F5D2B8;margin:4px 0\"></span></div>\n"
    "            <div style=\"padding-bottom:14px\"><div style=\"font-size:13px;font-weight:700;color:#171512\">QR 결제</div><div style=\"font-size:12.5px;color:#6E6A64;line-height:1.55\">앱의 'QR 결제'에서 가맹점 QR을 스캔해 금액을 입력하거나, 내 QR을 만들어 점주에게 제시</div></div>\n"
    "          </div>\n"
    "          <div style=\"display:flex;gap:12px\">\n"
    "            <div style=\"display:flex;flex-direction:column;align-items:center\"><span style=\"flex:none;width:24px;height:24px;border-radius:50%;background:#171512;color:#FFFFFF;font-size:12px;font-weight:800;display:inline-flex;align-items:center;justify-content:center\">4</span><span style=\"flex:1;width:1.5px;background:#F5D2B8;margin:4px 0\"></span></div>\n"
    "            <div style=\"padding-bottom:14px\"><div style=\"font-size:13px;font-weight:700;color:#171512\">인증·차감</div><div style=\"font-size:12.5px;color:#6E6A64;line-height:1.55\">간편비밀번호·지문 등으로 인증하면 충전 잔액에서 차감</div></div>\n"
    "          </div>\n"
    "          <div style=\"display:flex;gap:12px\">\n"
    "            <div style=\"display:flex;flex-direction:column;align-items:center\"><span style=\"flex:none;width:24px;height:24px;border-radius:50%;background:#F26B1D;color:#FFFFFF;font-size:12px;font-weight:800;display:inline-flex;align-items:center;justify-content:center\">5</span></div>\n"
    "            <div><div style=\"font-size:13px;font-weight:700;color:#171512\">확인</div><div style=\"font-size:12.5px;color:#6E6A64;line-height:1.55\">앱 알림·잔액에서 차감 확인 — 카드형과 마찬가지로 차감이 없으면 미가맹 점포라는 뜻</div></div>\n"
    "          </div>\n"
    "          <p style=\"margin:12px 0 0;font-size:12.5px;color:#6E6A64;line-height:1.6;border-top:1px dashed #E7E5E1;padding-top:10px\">카드형은 등록해 둔 실물 카드만 내밀면 되고 결제 순간 앱을 켤 필요가 없지만, 모바일형은 결제할 때 앱을 열어 QR을 쓰는 방식입니다.</p>\n"
    "        </div>\n"
    "      </sc-if>\n"
    "    </div>\n"
)
tpl = replace_once(
    tpl,
    "\n  </sc-if>\n\n  <sc-if value=\"{{ isOn }}\" hint-placeholder-val=\"{{ false }}\">",
    S10 + "  </sc-if>\n\n  <sc-if value=\"{{ isOn }}\" hint-placeholder-val=\"{{ false }}\">",
    "S10.mobile-flow",
)

# S15(task #17, 최신 명세): '가맹점 찾기' 서브탭 — 오프라인 탭 **최상단**(메인 탭 바로 아래, 사용 요건 박스 앞).
# 서브탭 1 '가맹점 찾기'(merchants.html 내부) / 서브탭 2 '공식 지도 검색 ↗'(onnuri.gift/place 외부, 새 탭).
# 색만으로 구분 금지 → 성격 표지 배지(내부·서울·인천·경기·부산/외부·전국) + 외부 ↗ + aria-label "새 창에서 열림" + 포커스 링.
# 라벨에 "앱" 금지(웹 지도), 공식 지도는 전국 서비스로 지역 한정 서술 없음. 동적 문구 없음.
S15 = (
    "\n    <div style=\"background:#FFFFFF;border:1.5px solid #E7E5E1;border-radius:14px;margin-bottom:16px;padding:16px 18px\">\n"
    "      <div style=\"font-size:11px;font-weight:800;color:#C4510F;letter-spacing:0.06em;margin-bottom:5px\">가맹점 찾기</div>\n"
    "      <h2 style=\"margin:0 0 5px;font-size:15px;font-weight:800;letter-spacing:-0.01em;color:#171512\">가맹점을 직접 찾아보기</h2>\n"
    "      <p style=\"margin:0 0 12px;font-size:12.5px;color:#6E6A64;line-height:1.6\">코스콤·거래소 소재지(서울·인천·경기·부산)는 '가맹점 찾기'에서 지도·목록으로 — 그 외 전국 지역은 '공식 지도 검색'</p>\n"
    "      <div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:10px\">\n"
    "        <a href=\"merchants.html\" style=\"display:flex;flex-direction:column;gap:6px;background:#FAFAF9;border:1.5px solid #E7E5E1;border-radius:12px;padding:13px 15px;text-decoration:none;color:inherit\" style-hover=\"border-color:#F26B1D;background:#FFFFFF\" style-focus=\"border-color:#F26B1D;box-shadow:0 0 0 3px #FBD8BC\">\n"
    "          <span style=\"display:flex;align-items:center;gap:7px;font-size:13.5px;font-weight:800;color:#171512\">가맹점 찾기 <span style=\"font-size:11px;font-weight:700;color:#C4510F;background:#FDEEE3;border-radius:999px;padding:2px 8px\">내부 · 서울·인천·경기·부산</span></span>\n"
    "          <span style=\"font-size:12.5px;color:#6E6A64;line-height:1.55\">지도와 목록을 함께 보며 검색 — 지역(구·동)·업종·브랜드 필터, 상호·시장 이름 검색, '현 지도에서 재검색'까지. 브랜드 매장(편의점·마트·SSM·다이소) 포함, 온누리 가맹점찾기 수집본 기준.</span>\n"
    "        </a>\n"
    "        <a href=\"https://www.onnuri.gift/place\" target=\"_blank\" rel=\"noopener\" aria-label=\"공식 지도 검색 — 새 창에서 열림\" style=\"display:flex;flex-direction:column;gap:6px;background:#FAFAF9;border:1.5px solid #E7E5E1;border-radius:12px;padding:13px 15px;text-decoration:none;color:inherit\" style-hover=\"border-color:#F26B1D;background:#FFFFFF\" style-focus=\"border-color:#F26B1D;box-shadow:0 0 0 3px #FBD8BC\">\n"
    "          <span style=\"display:flex;align-items:center;gap:7px;font-size:13.5px;font-weight:800;color:#171512\">공식 지도 검색 ↗ <span style=\"font-size:11px;font-weight:700;color:#6E6A64;background:#F0EFED;border-radius:999px;padding:2px 8px\">외부 · 전국</span></span>\n"
    "          <span style=\"font-size:12.5px;color:#6E6A64;line-height:1.55\">서울·인천·경기·부산 외 지역의 가맹점은 온누리 공식 지도에서 확인하세요 — 전국 대상.</span>\n"
    "        </a>\n"
    "      </div>\n"
    "    </div>"
)
tpl = replace_once(
    tpl,
    "  <sc-if value=\"{{ isOff }}\" hint-placeholder-val=\"{{ true }}\">\n    <sc-if value=\"{{ showConcept }}\" hint-placeholder-val=\"{{ true }}\">",
    "  <sc-if value=\"{{ isOff }}\" hint-placeholder-val=\"{{ true }}\">"
    + S15
    + "\n    <sc-if value=\"{{ showConcept }}\" hint-placeholder-val=\"{{ true }}\">",
    "S15.subtabs-top",
)

# S11 온라인 인트로 (D3)
tpl = replace_once(
    tpl,
    "결제할 수 있습니다. 아래 30곳은 온누리 공식 홈페이지 '온라인 전통시장관'(1~3페이지)에 안내된 가맹 플랫폼 전체입니다. (2026-08-06 수집)",
    "결제할 수 있습니다. <span>{{ onIntroMid }}</span> <span>{{ onIntroTail }}</span>",
    "S11.intro",
)

# S13 각주 2
tpl = replace_once(
    tpl,
    "※ 연매출 30억 원 이하 기준은 전통시장법 개정에 따라 <strong style=\"color:#26231F\">2026.6.17 시행</strong> — 신규 등록과 3년 주기 갱신 시 심사하며, 초과가 확인되면 기존 가맹점도 등록이 말소됩니다. (병·의원 등 보건업, 수의·법무·회계세무업은 가맹 제외, 약국은 유지)",
    "※ 연매출 30억 원 이하 기준은 전통시장법 시행령 개정(대통령령 제36415호)에 따라 <strong style=\"color:#26231F\">2026.6.17 시행</strong> — 신규 등록과 3년 주기 갱신 시 심사합니다. 시행일 전에 등록된 기존 가맹점은 최초 갱신 전까지는 개정 기준을 적용받지 않으며, 갱신·등록 심사에서 초과가 확인되면 등록이 취소됩니다. (병·의원 등 보건업, 수의·법무·회계세무업은 가맹 제외 — 약국은 가맹이 유지되나 30억 기준은 약국에도 적용)",
    "S13.footnote2",
)

# S13 각주 3(신설) + 각주 4(동적 D6) — 기존 각주4 줄을 3+4로 교체
tpl = replace_once(
    tpl,
    "<p style=\"margin:0;font-size:12px;color:#8A8580;line-height:1.6\">※ 지역 기반 배달앱(대구로, 배달특급, 배달의 명수, 전주맛배달 등)은 해당 지역에서만 주문 가능합니다.</p>",
    "<p style=\"margin:0;font-size:12px;color:#8A8580;line-height:1.6\">※ 카드형 선차감에서 결제액이 충전 잔액보다 큰 경우 — 공식몰 안내로는 충전금 차감 없이 전액 일반 카드로 결제됩니다(부족분만 청구되는 방식이 아닐 수 있음). 안내가 갈리는 부분이므로 잔액보다 큰 결제 전에는 앱에서 잔액 확인이 필요합니다.</p>\n"
    # 2026-09-05: 지역 배달앱 각주 줄을 뺐다. `{{ regionApps }}` 정의를 143행에서 지웠는데
    # 이 줄만 남아 **없는 변수를 참조하는 자리표시자**가 됐다. 지금은 7g 가 이 블록째 지워
    # index 에 안 나가지만, 나중에 7g 를 손대면 화면에 `{{ regionApps }}` 가 그대로 찍힌다.
    # 이 각주의 실물은 2026-08-11 이관 이후 terms.html 이 갖고 있다.
    "    <p style=\"margin:0;font-size:12px;color:#8A8580;line-height:1.6\">※ 이 가이드의 안내와 챗봇 답변은 AI의 도움으로 작성·생성되어 <strong style=\"color:#26231F\">정확하지 않을 수 있습니다</strong> — 내부 참고용이며 공식 안내가 아닙니다. 결제·이용 전 공식 채널(디지털온누리 앱, 고객센터 1670-1600)에서 최종 확인하세요.</p>",
    "S13.footnote3+4+AI면책",
)

# S14 선차감 용어
tpl = replace_once(
    tpl,
    "<strong style=\"color:#26231F\">선차감</strong>: 결제하면 상품권 잔액이 먼저 빠지고 모자란 금액만 카드로 청구되는 방식",
    "<strong style=\"color:#26231F\">선차감</strong>: 등록 카드로 결제하면 상품권 충전 잔액이 먼저 빠지는 방식 (잔액이 모자랄 때의 처리는 각주 ③ 참고)",
    "S14.priorDeduction",
)

# S14 SSM 용어 (SSM 정정 — 직영점 기준)
tpl = replace_once(
    tpl,
    "<strong style=\"color:#26231F\">SSM</strong>(기업형 슈퍼마켓): 대기업이 운영하는 중형 슈퍼(이마트에브리데이, 홈플러스익스프레스 등)",
    "<strong style=\"color:#26231F\">SSM</strong>(기업형 슈퍼마켓): 대기업 계열회사가 직영하는 중형 슈퍼(이마트에브리데이, 홈플러스익스프레스 등)로, 가맹 제외 대상은 직영점 기준",
    "S14.ssm",
)

# ========== task #24: UX 대개편 2단계 — 사이드바 + 화이트 모노톤 확산 ==========
# merchants.html(1단계 확정)의 디자인 시스템(14_design_system.md)을 번들 index 에 확산.
# 스킨(색 토큰) + 좌측 사이드바 셸만 추가하고 기존 콘텐츠·DC 로직·탭 구조는 보존한다.
# 사이드바/오버레이/스크립트는 <x-dc> **밖**에 둔다 — DC(React) 재렌더가 정적 사이드바의
# active/open 클래스를 되돌리지 못하게(마운트는 document 전체 스왑이라 밖의 형제도 그대로 렌더).

# ---------- 7a. 베이스 <style> 교체 (따뜻한 톤 → 중립 모노톤 토큰 + 사이드바 셸 CSS) ----------
OLD_STYLE = (
    "body { margin:0; background:#F4F4F3; color:#26231F; font-family:\"Pretendard Variable\",Pretendard,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif; -webkit-font-smoothing:antialiased; text-rendering:optimizeLegibility; }\n"
    "a { color:#C4510F; text-decoration:none; }\n"
    "a:hover { color:#F26B1D; text-decoration:underline; }\n"
    "::selection { background:#FBD8BC; }\n"
    "input::placeholder { color:#A3A09B; }"
)
# 사이드바/셸 CSS 는 merchants.html 과 바이트 동일(명세 §3 복붙 일관성).
NEW_STYLE = (
    "  /* 공통 셸(토큰·폰트·사이드바·상단바·폭토글)은 shell.css — ADR-9. */\n"
    "  /* 이 <style>은 원본 최소 규칙 자리를 대체하는 플레이스홀더다. */"
)
tpl = replace_once(tpl, OLD_STYLE, NEW_STYLE, "24.style-tokens")

# ---------- 7a-2. 페이지 타이틀 극대화 (24b: 극단 타이포 — 본문 대비 큰 대비) ----------
tpl = replace_once(
    tpl,
    "<h1 style=\"margin:0 0 10px;font-size:32px;font-weight:800;letter-spacing:-0.03em;color:#171512;line-height:1.2\">",
    "<h1 style=\"margin:0 0 12px;font-family:var(--font-display);font-size:clamp(32px,4.4vw,48px);font-weight:700;letter-spacing:-0.035em;color:#171512;line-height:1.1\">",
    "24b.h1-display",
)

# ---------- 7b. 사이드바 셸 마크업 (<x-dc> 밖 — 정적, DC 비관여) ----------
# 가이드 항목=현재 페이지 인페이지 앵커(#offline/#online/#payment/#terms, 해시 라우터가 탭 전환).
# 검색 항목=merchants.html#앵커(크로스 페이지). 기본 활성=오프라인 사용처.
SHELL = (
    "<link rel=\"stylesheet\" href=\"shell.css?v=8\">\n"
    "<script src=\"shell.js?v=6\"></script>\n"
    "<link rel=\"stylesheet\" href=\"chat-widget.css?v=12\">\n"
    "<script src=\"config.js?v=1\"></script>\n"
    "<script src=\"chat-widget.js?v=14\" defer></script>\n"
    # 크리티컬 인라인 스타일: 번들(4MB) 마운트 직후 셸 CSS 적용 전 한 프레임에
    # topbar CI+사이드바 CI가 세로로 겹쳐 보이는 FOUC 방지. img는 width/height 명시(시프트 방지).
    "<header class=\"topbar\" style=\"display:none\">\n"
    "  <button class=\"hamburger\" id=\"navToggle\" aria-label=\"메뉴 열기\" aria-expanded=\"false\" aria-controls=\"sidebar\">☰</button>\n"
    "  <span class=\"topbar-brand\"><img class=\"ci-img ci-img-sm\" src=\"assets/koscom_ci.png\" alt=\"koscom\" width=\"69\" height=\"14\"> 코스콤 디지털온누리 가이드</span>\n"
    "</header>\n"
    "<aside class=\"sidebar\" id=\"sidebar\" aria-label=\"주요 메뉴\" style=\"position:fixed;top:0;left:0;bottom:0;width:248px\">\n"
    "  <a class=\"sb-logo\" href=\"index.html\"><img class=\"ci-img\" src=\"assets/koscom_ci.png\" alt=\"koscom\" width=\"93\" height=\"19\"><span class=\"sb-logo-txt\">코스콤 디지털온누리 가이드</span></a>\n"
    "  <nav class=\"sb-nav\" aria-label=\"사이트 메뉴\">\n"
    "    <div class=\"sb-group\">가맹점 스마트 검색</div>\n"
    "    <a class=\"sb-item\" href=\"merchants.html#sidoTabs\">오프라인 가맹점 찾기</a>\n"
    "    <a class=\"sb-item\" href=\"online.html\">온라인 사용처 찾기</a>\n"
    "    <div class=\"sb-group\">사용 가이드</div>\n"
    "    <a class=\"sb-item active\" href=\"#offline\" aria-current=\"page\">오프라인 사용처</a>\n"
    "    <a class=\"sb-item\" href=\"#online\">온라인 가맹 플랫폼</a>\n"
    "    <a class=\"sb-item\" href=\"payment.html\">결제 방법</a>\n"
    "    <a class=\"sb-item\" href=\"terms.html\">용어·유의사항</a>\n"
    "    <div class=\"sb-group\">소식</div>\n"
    "    <a class=\"sb-item\" href=\"news.html\">온누리 뉴스</a>\n"
    "    <div class=\"sb-group\">피드백</div>\n"
    "    <a class=\"sb-item\" href=\"report.html\">버그 제보</a>\n"
    "  </nav>\n"
    "  <div class=\"sb-width\" role=\"group\" aria-label=\"화면 폭 조절\">\n"
    "    <span class=\"sb-width-label\">화면 폭</span>\n"
    "    <button type=\"button\" data-pw=\"narrow\">좁게</button>\n"
    "    <button type=\"button\" data-pw=\"\">표준</button>\n"
    "    <button type=\"button\" data-pw=\"wide\">넓게</button>\n"
    "  </div>\n"
    "  <a class=\"sb-ext\" href=\"https://www.onnuri.gift/place\" target=\"_blank\" rel=\"noopener\">공식 가맹점 지도 <span aria-hidden=\"true\">↗</span></a>\n"
    "</aside>\n"
    "<div class=\"overlay\" id=\"navOverlay\" hidden></div>"
)
tpl = replace_once(tpl, "<body>\n<x-dc>", "<body>\n" + SHELL + "\n<x-dc>", "24.shell-markup")

# ---------- 7c. main → content 셸 (max-width 인라인 제거, 클래스 전환) ----------
tpl = replace_once(
    tpl,
    "</helmet>\n<main style=\"max-width:1060px;margin:0 auto;padding:44px 28px 72px\">",
    "</helmet>\n<main class=\"content\">\n <div class=\"content-inner\">",
    "24.content-open",
)

# ---------- 7d. 섹션 앵커 id (탭 버튼 / 결제 / 온라인 / 용어) ----------
tpl = replace_once(
    tpl,
    "<button sc-camel-on-click=\"{{ goOff }}\" style=\"{{ tabOffStyle }}\">오프라인 사용처",
    "<button id=\"tabOff\" sc-camel-on-click=\"{{ goOff }}\" style=\"{{ tabOffStyle }}\">오프라인 사용처",
    "24.id.tabOff",
)
tpl = replace_once(
    tpl,
    "<button sc-camel-on-click=\"{{ goOn }}\" style=\"{{ tabOnStyle }}\">온라인 가맹 플랫폼",
    "<button id=\"tabOn\" sc-camel-on-click=\"{{ goOn }}\" style=\"{{ tabOnStyle }}\">온라인 가맹 플랫폼",
    "24.id.tabOn",
)
# 결제 흐름 카드(원본) — S10 이 같은 div 스타일을 쓰므로 toggleFlow 로 유일하게 앵커링.
tpl = replace_once(
    tpl,
    "<div style=\"background:#FFFFFF;border:1.5px solid #E7E5E1;border-radius:14px;margin-top:12px;overflow:hidden\">\n      <button sc-camel-on-click=\"{{ toggleFlow }}\"",
    "<div id=\"payment\" style=\"background:#FFFFFF;border:1.5px solid #E7E5E1;border-radius:14px;margin-top:12px;overflow:hidden\">\n      <button sc-camel-on-click=\"{{ toggleFlow }}\"",
    "24.id.payment",
)
tpl = replace_once(
    tpl,
    "<div style=\"background:#FDF3EA;border:1px dashed #EFC5A3;border-radius:12px;padding:12px 16px;font-size:12.5px;color:#6E6A64;line-height:1.6;margin-bottom:14px\">",
    "<div id=\"online\" style=\"background:#FDF3EA;border:1px dashed #EFC5A3;border-radius:12px;padding:12px 16px;font-size:12.5px;color:#6E6A64;line-height:1.6;margin-bottom:14px\">",
    "24.id.online",
)

# ---------- S16: 온라인 탭 최상단 '온라인 사용처 찾기' 진입 카드 (online.html) ----------
# 24.id.online이 부여한 앵커 직전에 삽입. 동적 숫자 없음(D-F1 단순화). S15 서브탭과 동일한 카드 문법.
tpl = replace_once(
    tpl,
    "<div id=\"online\" style=\"background:#FDF3EA;",
    "<a href=\"online.html\" style=\"display:flex;flex-direction:column;gap:6px;background:#FAFAF9;border:1.5px solid #E7E5E1;border-radius:12px;padding:13px 15px;margin-bottom:14px;text-decoration:none;color:inherit\" style-hover=\"border-color:#F26B1D;background:#FFFFFF\" style-focus=\"border-color:#F26B1D;box-shadow:0 0 0 3px #FBD8BC\">\n"
    "          <span style=\"display:flex;align-items:center;gap:7px;font-size:13.5px;font-weight:800;color:#171512\">온라인 사용처 찾기 <span style=\"font-size:11px;font-weight:700;color:#C4510F;background:#FDEEE3;border-radius:999px;padding:2px 8px\">내부</span></span>\n"
    "          <span style=\"font-size:12.5px;color:#6E6A64;line-height:1.55\">쇼핑몰·배달앱을 물품종류(농·수·축산물, 가전, 의류 등)와 브랜드로 골라 찾습니다. 각 몰 실측 태그 기준.</span>\n"
    "          <span style=\"font-size:11.5px;color:#585D64;line-height:1.5\">이 안내 페이지의 목록은 갱신 시점에 고정됩니다 — 최신 플랫폼 목록은 위 '온라인 사용처 찾기'에서 확인하세요.</span>\n"
    "        </a>\n<div id=\"online\" style=\"background:#FDF3EA;",
    "S16.online-search-entry",
)
tpl = replace_once(
    tpl,
    "<div style=\"margin-top:26px;padding-top:16px;border-top:1px solid #E7E5E1;display:flex;flex-direction:column;gap:6px\">",
    "<div id=\"terms\" style=\"margin-top:26px;padding-top:16px;border-top:1px solid #E7E5E1;display:flex;flex-direction:column;gap:6px\">",
    "24.id.terms",
)

# ---------- 7e. content 닫기 + 해시 라우터 스크립트 (<x-dc> 밖) ----------
# 드로어·폭토글은 shell.js 공통(ADR-9, 위임 방식이라 DC 재렌더 내성) — 여기는 index 전용 해시 라우터만.
NAV_SCRIPT = (
    "<script>\n"
    "(function(){\n"
    "  \"use strict\";\n"
    "  // ---- 해시 → 탭 라우터 (가이드 항목 앵커 동작: 오프라인/온라인 탭 전환 + 스크롤) ----\n"
    "  function scrollToId(id){ var el=document.getElementById(id); if(el) window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - 64, behavior:\"smooth\" }); }\n"
    "  function setActive(hash){ var items=document.querySelectorAll(\".sidebar .sb-nav a.sb-item\"); Array.prototype.forEach.call(items, function(a){ var on=a.getAttribute(\"href\")===hash; a.classList.toggle(\"active\", on); if(on) a.setAttribute(\"aria-current\",\"page\"); else a.removeAttribute(\"aria-current\"); }); }\n"
    "  function applyHash(){\n"
    "    var h=(location.hash||\"\").replace(/^#/, \"\");\n"
    "    if(h===\"online\"){ var a=document.getElementById(\"tabOn\"); if(a)a.click(); setActive(\"#online\"); setTimeout(function(){ scrollToId(\"online\"); }, 70); }\n"
    "    else if(h===\"payment\"){ location.replace(\"payment.html\"); }   // 결제 방법은 전용 페이지로 분리(2026-08-11)\n"
    "    else if(h===\"terms\"){ location.replace(\"terms.html\"); }        // 용어·유의사항도 전용 페이지\n"
    "    else if(h===\"offline\"){ var c=document.getElementById(\"tabOff\"); if(c)c.click(); setActive(\"#offline\"); window.scrollTo({ top:0, behavior:\"smooth\" }); }\n"
    "  }\n"
    "  window.addEventListener(\"hashchange\", applyHash);\n"
    "  setTimeout(applyHash, 0);\n"
    "})();\n"
    "</script>"
)
tpl = replace_once(
    tpl,
    "</main>\n</x-dc>",
    "  </div>\n</main>\n</x-dc>\n" + NAV_SCRIPT,
    "24.content-close+script",
)

# ---------- 7f. 전역 색상 매핑: 따뜻한 톤 → 중립 모노톤 (오렌지 #F26B1D·#C4510F 유지) ----------
# 인라인 스타일에 흩어진 웜톤 hex 를 중립 토큰 값으로 일괄 치환.
# 2026-08-19: 목표값을 shell.css 팔레트와 일치시켰다(토큰 출처 단일화).
#   --text #0B0C0E · --text-sub #585D64 · --border #E5E6E8 · --surface #F6F6F7 · --surface-2 #EFF0F2
# 이전에는 index 인라인만 구 모노톤(#17181A 등)을 써서, shell.css 한쪽만 고치면 나머지가 조용히 어긋났다.
# 원본 + 이번에 추가된 S10/S15 등 블록 모두에 적용되도록 마지막에 한 번 수행.
COLOR_MAP = [
    ("#E7E5E1", "#E5E6E8"),  # border
    ("#EFEEEC", "#E5E6E8"),  # border(옅음)
    ("#8A8580", "#585D64"),  # text-sub
    ("#6E6A64", "#585D64"),  # text-sub
    ("#171512", "#0B0C0E"),  # text
    ("#26231F", "#0B0C0E"),  # text(strong)
    ("#FAFAF9", "#F6F6F7"),  # surface
    ("#F0EFED", "#EFF0F2"),  # surface-2
    ("#FDEEE3", "#FEF3EC"),  # accent-soft
    ("#FDF3EA", "#FEF3EC"),  # accent-soft
    ("#FBD8BC", "#FEF3EC"),  # accent-soft(포커스 링)
    ("#F5D2B8", "#F6C9A8"),  # accent-line
    ("#EFC5A3", "#F6C9A8"),  # accent-line
]
_before_map = tpl
for _old_c, _new_c in COLOR_MAP:
    tpl = tpl.replace(_old_c, _new_c)
# 매핑 후 잔존 웜톤 검사(오렌지 계열 제외 — 남으면 누락)
import re as _re
_leftover = sorted(set(_re.findall(r"#(?:E7E5E1|EFEEEC|8A8580|6E6A64|171512|26231F|FAFAF9|F0EFED|FDEEE3|FDF3EA|FBD8BC|F5D2B8|EFC5A3|F4F4F3|A3A09B|9A968F)", tpl)))
if _leftover:
    raise SystemExit(f"[FAIL] 24.color-map: 웜톤 잔존 {_leftover}")
print(f"[OK] 24.color-map: 웜톤 치환 (변경 {sum(1 for a,b in COLOR_MAP)} 규칙, 길이 {len(_before_map)}->{len(tpl)})")

# ---------- 7g. pill(999px) → 샤프 라운드(6px) ----------
# 디자인 시스템: pill 금지, 6~14px 샤프 라운드. 알약 배지(판정/구분 태그)만 대상.
# 단계 번호 원형(border-radius:50%)은 아이콘이라 유지.
_pill_n = tpl.count("border-radius:999px")
tpl = tpl.replace("border-radius:999px", "border-radius:6px")
if "border-radius:999px" in tpl:
    raise SystemExit("[FAIL] 24.radius: pill 잔존")
print(f"[OK] 24.radius: pill 999px -> 6px ({_pill_n}개)")

# ---------- 6. 재삽입 & 저장 ----------
# 중요: 이 JSON 문자열은 <script type="__bundler/template"> 요소의 textContent 로 들어간다.
# HTML 파서는 script 요소 안에서 첫 리터럴 </script(더 넓게는 </) 를 만나면 요소를 조기 종료하고
# textContent 를 그 자리에서 잘라버린다 → 로더의 JSON.parse 가 미완결 문자열로 실패한다.
# ---------- 7g. 결제·용어 섹션 분리 (2026-08-11) ----------
# 결제 흐름 아코디언(payment.html로 이동)과 용어·각주(terms.html로 이동)를 index에서 제거.
# 모든 콘텐츠 스텝(S9·S10·S13·S14 등) 이후에 실행해야 한다 — 스텝들이 이 블록 안을 참조한다.
_i = tpl.find('<div id="payment"')
if _i < 0:
    raise SystemExit("[FAIL] 7g: payment 블록을 찾지 못함")
_p = tpl.find('<sc-if value="{{ isOn }}"', _i)
_q = tpl.rfind('</sc-if>', _i, _p)          # isOff 탭 닫힘(유지)
_r = tpl.rfind('</div>', _i, _q)            # #payment div 닫힘(제거 범위 끝)
if _p < 0 or _q < 0 or _r < 0:
    raise SystemExit("[FAIL] 7g: payment 닫힘 구조 불일치")
tpl = tpl[:_i] + tpl[_r + len('</div>'):]
print("[OK] 7g. payment 블록 제거 (payment.html로 분리)")

_j = tpl.find('<div id="terms"')
if _j < 0:
    raise SystemExit("[FAIL] 7g: terms 블록을 찾지 못함")
_e = tpl.find('</div>', _j)
TERMS_POINTER = (
    '<div id="terms" style="margin-top:26px;padding-top:16px;border-top:1px solid #E5E6E8">'
    '<p style="margin:0;font-size:12px;color:#585D64;line-height:1.7">'
    '용어 풀이와 유의사항 각주는 <a href="terms.html" style="color:#C4510F;font-weight:700">용어·유의사항</a>, '
    '결제 단계·카드 실적 안내는 <a href="payment.html" style="color:#C4510F;font-weight:700">결제 방법</a> 페이지로 옮겼습니다. '
    '※ 이 가이드의 안내와 챗봇 답변은 AI의 도움으로 작성·생성되어 <strong style="color:#0B0C0E">정확하지 않을 수 있습니다</strong> — '
    '내부 참고용이며 공식 안내가 아닙니다. 결제·이용 전 공식 채널(디지털온누리 앱, 고객센터 1670-1600)에서 최종 확인하세요.</p></div>'
)
tpl = tpl[:_j] + TERMS_POINTER + tpl[_e + len('</div>'):]
print("[OK] 7g. terms 블록 → 포인터·면책 치환 (terms.html로 분리)")

# ---------- 7g-guard. 7f 이후 삽입 블록의 웜톤 재검사 (2026-08-19) ----------
# 7f(색상 매핑)는 "마지막에 한 번" 도는 전제로 쓰였으나, 이후 7g 가 그 뒤에 추가되면서
# 7g 가 삽입하는 TERMS_POINTER 는 매핑을 우회했다(실제로 #8A8580·#26231F 가 되살아났다).
# 7f 뒤에 문자열을 삽입하는 스텝이 또 생겨도 잡히도록 여기서 한 번 더 검사한다.
_leftover2 = sorted(set(_re.findall(
    r"#(?:E7E5E1|EFEEEC|8A8580|6E6A64|171512|26231F|FAFAF9|F0EFED|FDEEE3|FDF3EA|FBD8BC|F5D2B8|EFC5A3|F4F4F3|A3A09B|9A968F)", tpl)))
if _leftover2:
    raise SystemExit(f"[FAIL] 7g-guard: 7f 이후 삽입 블록에 웜톤 잔존 {_leftover2} — COLOR_MAP 목표값으로 직접 쓸 것")
print("[OK] 7g-guard: 7f 이후 웜톤 잔존 0")

# ---------- 7h. 템플릿 내부 <head> 탭 제목·파비콘 (2026-08-18) ----------
# 로더는 template 을 DOMParser 로 파싱해
#   document.documentElement.replaceWith(doc.documentElement)
# 로 문서 루트를 통째로 교체한다. 그 순간 외곽 <head>(8단계에서 심는 <title>·<link icon>)는
# 통째로 사라지고, 템플릿 내부 <head> 에는 meta 2개와 로더 script 뿐이라 탭 제목과 파비콘이
# 함께 소실된다(실측: 로드 완료 후 document.title === "" , link[rel=icon] 0개).
# 따라서 템플릿 내부에도 심어야 한다. 인코딩(json.dumps) 앞에 두어야 반영된다 —
# 뒤에 두면 조용히 무반영된다(2026-08-11 7g 와 같은 함정).
_m_head = re.search(r"<head[^>]*>", tpl, re.I)
if not _m_head:
    raise SystemExit("[FAIL] 7h: 템플릿 <head> 여는 태그를 찾지 못함")
HEAD_INJECT = ("\n<title>코스콤 디지털온누리 가이드</title>"
               '\n<link rel="icon" href="favicon.svg?v=1" type="image/svg+xml">')
for _tok in ("<title>", "rel=\"icon\""):
    if _tok in tpl[:tpl.lower().find("</head>")]:
        raise SystemExit(f"[FAIL] 7h: 템플릿 head 에 '{_tok}' 가 이미 있음 — 중복 주입 방지")
tpl = tpl[:_m_head.end()] + HEAD_INJECT + tpl[_m_head.end():]
print("[OK] 7h. 템플릿 내부 head 에 탭 제목·파비콘 주입")

# json.dumps 는 '/' 를 이스케이프하지 않으므로 </ 가 리터럴로 남는다. 원본 번들과 동일하게
# 모든 </ 를 </ 로 복구해 리터럴 </ 를 제거한다(JSON 에서 / 는 '/' 로 디코드되어 내용 불변).
encoded = json.dumps(tpl, ensure_ascii=False).replace("</", "<\\u002F")
assert encoded.count("</") == 0, "재인코딩 후에도 리터럴 </ 가 남아 있음 — 스크립트 조기종료 위험"
bundle_lines[TEMPLATE_LINE_IDX] = encoded

# ---------- 8. 브라우저 탭 제목 (외곽 <title> — 템플릿 밖 정적 라인) ----------
# 사용자 요청(2026-08-11): 탭 제목을 "코스콤 디지털온누리 가이드"로.
TITLE_OLD = "  <title>디지털온누리상품권 가이드</title>"
TITLE_NEW = ("  <title>코스콤 디지털온누리 가이드</title>\n"
             '  <link rel="icon" href="favicon.svg?v=1" type="image/svg+xml">')
n_title = sum(1 for ln in bundle_lines if ln == TITLE_OLD)
if n_title != 1:
    raise SystemExit(f"[FAIL] 외곽 <title> 라인: 기대 1회, 실제 {n_title}회")
bundle_lines = [TITLE_NEW if ln == TITLE_OLD else ln for ln in bundle_lines]

with open(OUT_BUNDLE, "w", encoding="utf-8") as f:
    f.write("\n".join(bundle_lines))

print("[OK] index.html 생성 완료")
print(f"  템플릿 크기: {len(orig_tpl)} -> {len(tpl)} chars")
print(f"  이스케이프: 리터럴 </ = {encoded.count('</')} (0 이어야 함), <\\u002F = {encoded.count(chr(92)+'u002F')}개")
