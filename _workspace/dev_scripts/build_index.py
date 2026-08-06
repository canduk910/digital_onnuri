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
    "<strong style=\"color:#C4510F\">제외 업종이 아닐 것</strong> — 대형마트·백화점·SSM, 유흥·사행성, 병·의원 등 보건업, 수의·법무·회계세무(보건업 등은 2026.6.17부터 추가). 약국은 예외적으로 가맹이 유지됩니다 — 단, 연매출 30억 기준은 약국에도 똑같이 적용됩니다.",
    "S4.req4",
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
