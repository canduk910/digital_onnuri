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
    "    const regionApps = this.ONLINE.filter(r => r.rl === true).map(r => r.n).join(\", \");\n"
    "    const onIntroMid = \"전용관·제휴 플랫폼은 계속 추가되는 단계라, 아래 목록(\" + collectedOn + \" 수집)에 없는 곳이 새로 생겼을 수 있습니다.\";\n"
    "    const onIntroTail = \"아래 \" + onTotal + \"곳은 온누리 공식 홈페이지 '온라인 전통시장관'(\" + pagesChecked + \"페이지)에 안내된 가맹 플랫폼 전체입니다.\";\n\n"
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
    "      collectedOn, pagesChecked, regionApps, onIntroMid, onIntroTail,\n    };",
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
    "      <p style=\"margin:0 0 12px;font-size:12.5px;color:#6E6A64;line-height:1.6\">상호·시장 이름으로 찾으려면 '가맹점 찾기', 지도에서 지역별로 훑으려면 '공식 지도 검색'</p>\n"
    "      <div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:10px\">\n"
    "        <a href=\"merchants.html\" style=\"display:flex;flex-direction:column;gap:6px;background:#FAFAF9;border:1.5px solid #E7E5E1;border-radius:12px;padding:13px 15px;text-decoration:none;color:inherit\" style-hover=\"border-color:#F26B1D;background:#FFFFFF\" style-focus=\"border-color:#F26B1D;box-shadow:0 0 0 3px #FBD8BC\">\n"
    "          <span style=\"display:flex;align-items:center;gap:7px;font-size:13.5px;font-weight:800;color:#171512\">가맹점 찾기 <span style=\"font-size:11px;font-weight:700;color:#C4510F;background:#FDEEE3;border-radius:999px;padding:2px 8px\">내부 · 서울·인천·경기·부산</span></span>\n"
    "          <span style=\"font-size:12.5px;color:#6E6A64;line-height:1.55\">코스콤·한국거래소 소재지 가맹점을 상호·소속 시장 이름으로 검색 — 시장·상점가 목록과 브랜드 매장(편의점·마트·SSM·다이소)을 함께 봅니다. 온누리 가맹점찾기 수집본 기준.</span>\n"
    "        </a>\n"
    "        <a href=\"https://www.onnuri.gift/place\" target=\"_blank\" rel=\"noopener\" aria-label=\"공식 지도 검색 — 새 창에서 열림\" style=\"display:flex;flex-direction:column;gap:6px;background:#FAFAF9;border:1.5px solid #E7E5E1;border-radius:12px;padding:13px 15px;text-decoration:none;color:inherit\" style-hover=\"border-color:#F26B1D;background:#FFFFFF\" style-focus=\"border-color:#F26B1D;box-shadow:0 0 0 3px #FBD8BC\">\n"
    "          <span style=\"display:flex;align-items:center;gap:7px;font-size:13.5px;font-weight:800;color:#171512\">공식 지도 검색 ↗ <span style=\"font-size:11px;font-weight:700;color:#6E6A64;background:#F0EFED;border-radius:999px;padding:2px 8px\">외부 · 전국</span></span>\n"
    "          <span style=\"font-size:12.5px;color:#6E6A64;line-height:1.55\">전국 가맹점을 지역별로 지도에서 검색 — 온누리 공식 서비스.</span>\n"
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
    "    <p style=\"margin:0;font-size:12px;color:#8A8580;line-height:1.6\">※ 지역 기반 배달앱(<span>{{ regionApps }}</span>)은 해당 지역에서만 주문 가능합니다.</p>",
    "S13.footnote3+4",
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
    "  /* ===== 디자인 시스템 (14): 화이트 중립 모노톤 + 오렌지 ===== */\n"
    "  :root{\n"
    "    --bg:#FFFFFF; --surface:#F7F7F7; --surface-2:#F0F0F0; --border:#E6E6E6; --border-2:#D6D6D6;\n"
    "    --text:#17181A; --text-sub:#6B6E73; --text-faint:#9CA0A6;\n"
    "    --accent:#F26B1D; --accent-hover:#DD5E12; --accent-press:#C4510F; --accent-soft:#FEF3EC; --accent-line:#F6C9A8;\n"
    "    --ok:#1F9D57; --ok-soft:#EAF7EF; --warn:#C77A16; --warn-soft:#FBF3E6; --no:#9CA0A6; --no-soft:#F2F2F2;\n"
    "    --r-sm:6px; --r-md:10px; --r-lg:14px; --sb-w:248px;\n"
    "    --shadow-sm:0 1px 2px rgba(20,22,26,.05); --shadow-md:0 6px 24px rgba(20,22,26,.09);\n"
    "  }\n"
    "  * { margin:0; padding:0; box-sizing:border-box; }\n"
    "  body { background:var(--bg); color:var(--text); font-family:\"Pretendard Variable\",Pretendard,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif; -webkit-font-smoothing:antialiased; text-rendering:optimizeLegibility; font-size:14px; line-height:1.6; letter-spacing:-0.005em; }\n"
    "  a { color:var(--accent-press); text-decoration:none; }\n"
    "  a:hover { color:var(--accent); text-decoration:underline; }\n"
    "  ::selection { background:var(--accent-soft); }\n"
    "  input::placeholder { color:var(--text-faint); }\n"
    "  :focus-visible { outline:2px solid var(--accent); outline-offset:2px; border-radius:2px; }\n"
    "  .logo-sym { flex:none; width:28px; height:28px; border-radius:var(--r-sm); background:var(--accent); display:inline-flex; align-items:center; justify-content:center; color:#fff; font-weight:800; font-size:14px; }\n"
    "  .sidebar { position:fixed; top:0; left:0; bottom:0; width:var(--sb-w); background:var(--bg); border-right:1px solid var(--border); display:flex; flex-direction:column; padding:18px 14px 14px; z-index:50; transition:transform .2s ease; }\n"
    "  .sb-logo { display:flex; align-items:center; gap:9px; padding:4px 6px 16px; color:var(--text); }\n"
    "  .sb-logo:hover { text-decoration:none; color:var(--text); }\n"
    "  .sb-logo-txt { font-size:13.5px; font-weight:700; letter-spacing:-0.01em; }\n"
    "  .sb-nav { display:flex; flex-direction:column; gap:1px; overflow-y:auto; flex:1; }\n"
    "  .sb-group { font-size:11.5px; font-weight:700; letter-spacing:0.06em; text-transform:uppercase; color:var(--text-faint); padding:14px 10px 6px; }\n"
    "  .sb-item { position:relative; display:block; padding:8px 12px; border-radius:var(--r-sm); font-size:13.5px; color:var(--text-sub); transition:background .15s ease,color .15s ease; }\n"
    "  .sb-item:hover { background:var(--surface); color:var(--text); text-decoration:none; }\n"
    "  .sb-item.active { background:var(--accent-soft); color:var(--text); font-weight:700; }\n"
    "  .sb-item.active::before { content:\"\"; position:absolute; left:0; top:6px; bottom:6px; width:3px; border-radius:0 3px 3px 0; background:var(--accent); }\n"
    "  .sb-ext { display:flex; align-items:center; gap:6px; margin-top:10px; padding:10px 12px; border-top:1px solid var(--border); color:var(--text-sub); font-size:13px; font-weight:600; }\n"
    "  .sb-ext:hover { color:var(--accent-press); text-decoration:none; }\n"
    "  .topbar { display:none; position:fixed; top:0; left:0; right:0; height:52px; align-items:center; gap:10px; padding:0 14px; background:var(--bg); border-bottom:1px solid var(--border); z-index:40; }\n"
    "  .topbar-brand { display:flex; align-items:center; gap:8px; font-size:13.5px; font-weight:700; }\n"
    "  .hamburger { flex:none; width:38px; height:38px; border:1px solid var(--border); background:var(--bg); border-radius:var(--r-sm); font-size:16px; color:var(--text); cursor:pointer; display:flex; align-items:center; justify-content:center; }\n"
    "  .hamburger:hover { background:var(--surface); }\n"
    "  .overlay { position:fixed; inset:0; background:rgba(20,22,26,.38); z-index:45; }\n"
    "  .content { margin-left:var(--sb-w); }\n"
    "  .content-inner { max-width:1080px; margin:0 auto; padding:40px 32px 72px; }\n"
    "  @media (max-width:959px){\n"
    "    .sidebar { transform:translateX(-100%); box-shadow:var(--shadow-md); }\n"
    "    .sidebar.open { transform:translateX(0); }\n"
    "    .topbar { display:flex; }\n"
    "    .content { margin-left:0; padding-top:52px; }\n"
    "    .content-inner { padding:24px 20px 56px; }\n"
    "  }\n"
    "  @media (min-width:960px){ .overlay { display:none !important; } }\n"
    "  @media (prefers-reduced-motion:reduce){ * { transition:none !important; } }"
)
tpl = replace_once(tpl, OLD_STYLE, NEW_STYLE, "24.style-tokens")

# ---------- 7b. 사이드바 셸 마크업 (<x-dc> 밖 — 정적, DC 비관여) ----------
# 가이드 항목=현재 페이지 인페이지 앵커(#offline/#online/#payment/#terms, 해시 라우터가 탭 전환).
# 검색 항목=merchants.html#앵커(크로스 페이지). 기본 활성=오프라인 사용처.
SHELL = (
    "<header class=\"topbar\">\n"
    "  <button class=\"hamburger\" id=\"navToggle\" aria-label=\"메뉴 열기\" aria-expanded=\"false\" aria-controls=\"sidebar\">☰</button>\n"
    "  <span class=\"topbar-brand\"><span class=\"logo-sym\">온</span> 디지털온누리 가이드</span>\n"
    "</header>\n"
    "<aside class=\"sidebar\" id=\"sidebar\" aria-label=\"주요 메뉴\">\n"
    "  <a class=\"sb-logo\" href=\"index.html\"><span class=\"logo-sym\">온</span><span class=\"sb-logo-txt\">디지털온누리 가이드</span></a>\n"
    "  <nav class=\"sb-nav\" aria-label=\"사이트 메뉴\">\n"
    "    <div class=\"sb-group\">사용 가이드</div>\n"
    "    <a class=\"sb-item active\" href=\"#offline\" aria-current=\"page\">오프라인 사용처</a>\n"
    "    <a class=\"sb-item\" href=\"#online\">온라인 가맹 플랫폼</a>\n"
    "    <a class=\"sb-item\" href=\"#payment\">결제 방법</a>\n"
    "    <a class=\"sb-item\" href=\"#terms\">용어·유의사항</a>\n"
    "    <div class=\"sb-group\">가맹점 검색</div>\n"
    "    <a class=\"sb-item\" href=\"merchants.html#sidoTabs\">지역별 찾기</a>\n"
    "    <a class=\"sb-item\" href=\"merchants.html#catChips\">업종·브랜드별</a>\n"
    "  </nav>\n"
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
tpl = replace_once(
    tpl,
    "<div style=\"margin-top:26px;padding-top:16px;border-top:1px solid #E7E5E1;display:flex;flex-direction:column;gap:6px\">",
    "<div id=\"terms\" style=\"margin-top:26px;padding-top:16px;border-top:1px solid #E7E5E1;display:flex;flex-direction:column;gap:6px\">",
    "24.id.terms",
)

# ---------- 7e. content 닫기 + 드로어/해시 라우터 스크립트 (<x-dc> 밖) ----------
# 이벤트 위임(타이밍 무관) + resize 닫힘 리셋(task #23 낮음 관찰) + 해시→탭 라우터.
NAV_SCRIPT = (
    "<script>\n"
    "(function(){\n"
    "  \"use strict\";\n"
    "  var mq = function(){ return window.matchMedia(\"(max-width:959px)\").matches; };\n"
    "  var sb = function(){ return document.getElementById(\"sidebar\"); };\n"
    "  var ov = function(){ return document.getElementById(\"navOverlay\"); };\n"
    "  var tg = function(){ return document.getElementById(\"navToggle\"); };\n"
    "  function openNav(){ var s=sb(),o=ov(),t=tg(); if(s)s.classList.add(\"open\"); if(o)o.hidden=false; if(t)t.setAttribute(\"aria-expanded\",\"true\"); }\n"
    "  function closeNav(){ var s=sb(),o=ov(),t=tg(); if(s)s.classList.remove(\"open\"); if(o)o.hidden=true; if(t)t.setAttribute(\"aria-expanded\",\"false\"); }\n"
    "  document.addEventListener(\"click\", function(e){\n"
    "    var t=e.target; if(!t||!t.closest) return;\n"
    "    if(t.closest(\"#navToggle\")){ e.preventDefault(); (sb()&&sb().classList.contains(\"open\"))?closeNav():openNav(); return; }\n"
    "    if(t.closest(\"#navOverlay\")){ closeNav(); return; }\n"
    "    if(t.closest(\".sidebar a\") && mq()) closeNav();\n"
    "  });\n"
    "  document.addEventListener(\"keydown\", function(e){ if(e.key===\"Escape\") closeNav(); });\n"
    "  window.addEventListener(\"resize\", function(){ if(!mq()) closeNav(); });\n"
    "  // ---- 해시 → 탭 라우터 (가이드 항목 앵커 동작: 오프라인/온라인 탭 전환 + 스크롤) ----\n"
    "  function scrollToId(id){ var el=document.getElementById(id); if(el) window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - 64, behavior:\"smooth\" }); }\n"
    "  function setActive(hash){ var items=document.querySelectorAll(\".sidebar .sb-nav a.sb-item\"); Array.prototype.forEach.call(items, function(a){ var on=a.getAttribute(\"href\")===hash; a.classList.toggle(\"active\", on); if(on) a.setAttribute(\"aria-current\",\"page\"); else a.removeAttribute(\"aria-current\"); }); }\n"
    "  function applyHash(){\n"
    "    var h=(location.hash||\"\").replace(/^#/, \"\");\n"
    "    if(h===\"online\"){ var a=document.getElementById(\"tabOn\"); if(a)a.click(); setActive(\"#online\"); setTimeout(function(){ scrollToId(\"online\"); }, 70); }\n"
    "    else if(h===\"payment\"){ var b=document.getElementById(\"tabOff\"); if(b)b.click(); setActive(\"#payment\"); setTimeout(function(){ scrollToId(\"payment\"); }, 70); }\n"
    "    else if(h===\"terms\"){ setActive(\"#terms\"); setTimeout(function(){ scrollToId(\"terms\"); }, 20); }\n"
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
# 원본 + 이번에 추가된 S10/S15 등 블록 모두에 적용되도록 마지막에 한 번 수행.
COLOR_MAP = [
    ("#E7E5E1", "#E6E6E6"),  # border
    ("#EFEEEC", "#E6E6E6"),  # border(옅음)
    ("#8A8580", "#6B6E73"),  # text-sub
    ("#6E6A64", "#6B6E73"),  # text-sub
    ("#171512", "#17181A"),  # text
    ("#26231F", "#17181A"),  # text(strong)
    ("#FAFAF9", "#F7F7F7"),  # surface
    ("#F0EFED", "#F0F0F0"),  # surface-2
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
_leftover = sorted(set(_re.findall(r"#(?:E7E5E1|EFEEEC|8A8580|6E6A64|171512|26231F|FAFAF9|F0EFED|FDEEE3|FDF3EA|FBD8BC|F5D2B8|EFC5A3|F4F4F3|A3A09B)", tpl)))
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
# json.dumps 는 '/' 를 이스케이프하지 않으므로 </ 가 리터럴로 남는다. 원본 번들과 동일하게
# 모든 </ 를 </ 로 복구해 리터럴 </ 를 제거한다(JSON 에서 / 는 '/' 로 디코드되어 내용 불변).
encoded = json.dumps(tpl, ensure_ascii=False).replace("</", "<\\u002F")
assert encoded.count("</") == 0, "재인코딩 후에도 리터럴 </ 가 남아 있음 — 스크립트 조기종료 위험"
bundle_lines[TEMPLATE_LINE_IDX] = encoded
with open(OUT_BUNDLE, "w", encoding="utf-8") as f:
    f.write("\n".join(bundle_lines))

print("[OK] index.html 생성 완료")
print(f"  템플릿 크기: {len(orig_tpl)} -> {len(tpl)} chars")
print(f"  이스케이프: 리터럴 </ = {encoded.count('</')} (0 이어야 함), <\\u002F = {encoded.count(chr(92)+'u002F')}개")
