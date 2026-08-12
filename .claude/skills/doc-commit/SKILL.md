---
name: doc-commit
description: "수정 내역을 커밋·푸시하기 전에 프로젝트 문서(CLAUDE.md 변경이력, _workspace 명세, DEPLOY.md 등)를 먼저 갱신하고, 비밀값·D-F1·브랜치 분리 안전 점검을 거쳐 커밋/푸시하는 이 저장소의 표준 반영 절차. '커밋푸시하자', '커밋하자', '푸시하자', '반영하고 올려줘', '변경사항 올리자', 'doc-commit' 등 커밋·푸시 요청이 오면 반드시 이 스킬을 사용할 것. 코드·데이터·문서 어떤 변경이든 저장소에 반영하는 마지막 단계는 전부 이 절차를 따른다. 단, 커밋 없이 파일만 수정하는 작업이나 배포 인프라 조작(terraform apply·서버 기동)은 이 스킬 범위가 아니다."
---

# doc-commit — 문서 갱신 후 커밋·푸시

커밋은 코드만 남기는 행위가 아니다. 이 저장소의 문서(변경이력·명세)는 다음 세션의 델타 기준이므로, **문서를 먼저 갱신하고 나서** 커밋한다. 문서가 낡으면 다음 갱신 때 페이지가 거짓말을 시작한다.

## 1. 변경 파악

```bash
git branch --show-current   # main 단일 브랜치 — 프론트(Pages) + 백엔드(CI/CD) 통합
git status --short
git diff --stat
```

- 어떤 변경이 **사용자 가시 동작**을 바꿨는지(페이지 문구·숫자·기능), 어떤 것이 내부 정비인지 구분한다. 문서 갱신 대상은 전자다.

## 2. 문서 갱신 (커밋 전에)

| 변경 유형 | 갱신할 문서 |
|----------|------------|
| 모든 유의미한 변경 | `CLAUDE.md` 변경 이력 테이블 — `\| 날짜 \| 변경 내용 \| 대상 \| 사유 \|` 형식. 사용자 제보로 고친 결함은 "사용자 결함 제보", 요청 기능은 "사용자 요청"을 사유에 명시 |
| 페이지 문구·구조 변경 | `_workspace/03_content_spec.md` — 명세와 산출물이 어긋나면 안 된다(명세가 진실) |
| 데이터 갱신·수집 규칙 변경 | 해당 `data/*.json`의 meta는 수집 스크립트가 쓴다 — 스크립트(`build_region_full.py`) 주석·`_workspace` 보고서에 규칙 변경을 남긴다 |
| 배포 절차·인프라 변경 | `backend/DEPLOY.md`, `backend/deploy/terraform/README.md` |
| 하네스(에이전트·스킬) 변경 | `CLAUDE.md` 변경 이력 + 해당 SKILL.md |

- 날짜는 상대 표현 없이 절대 날짜(YYYY-MM-DD)로 쓴다.
- index.html에 반영될 문구를 고쳤다면 **소스는 `build_index.py`/명세**다 — 산출물만 고치지 말고 소스를 고친 뒤 재빌드한다(아래 3).

## 3. 산출물·안전 점검

**index.html을 만졌다면** (직접 수정 금지 — 항상 재생성):
```bash
python3 _workspace/dev_scripts/build_index.py   # 출력의 "리터럴 </ = 0" 확인(D-F1 불변)
```

**비밀값 스캔** (스테이징 후, 커밋 전 필수):
```bash
git add <대상 파일들>       # git add -A 는 지양 — 의도한 파일만
git diff --cached --name-only | grep -iE '\.env$|tfvars$|\.tfstate|\.terraform/|\.pem$' && echo "차단!" || echo "OK"
git diff --cached | grep -iE 'ncp_iam_[A-Za-z0-9]|secret[_-]?key\s*[:=]\s*"[A-Za-z0-9]{16,}|api[_-]?key\s*[:=]\s*"[A-Za-z0-9]{20,}' && echo "차단!" || echo "OK"
```
비밀값이 걸리면 커밋을 멈추고 사용자에게 알린다. `.env`/`tfvars`/`.pem`은 예외 없이 커밋 금지.

**브랜치 규칙** (2026-08-12 단일 브랜치 통합 — 프론트·백엔드 모두 `main`):
- `main` = 라이브(프론트 GitHub Pages + 백엔드 CI/CD). `feat/backend-scaffold`는 폐기됐다. 브랜치 간 동기화는 더 이상 필요 없다.
- `backend/`는 소스만 추적한다. `backend/build/`·`backend/.gradle/`·`.env`·`*.tfvars`·`*.tfstate`는 `.gitignore`로 차단된다 — 스테이징 목록에 나타나면 안 된다.
- `config.js`의 `dataMode`는 `"auto"`(API 우선·JSON 폴백)로 단일하다. 데이터(`data/merchants/*.json`) 갱신 커밋에는 `dataVersion`을 수집일로 올렸는지 확인(브라우저 캐시 무력화).

## 4. 커밋

- 논리 단위로 나눈다(기능 vs 문서-only가 크면 분리, 작으면 한 커밋).
- 메시지: 첫 줄 한국어 요약(무엇을), 본문에 왜·검증 결과. 푸터 필수:

```
Co-Authored-By: Claude <사용 모델명> <noreply@anthropic.com>
Claude-Session: <세션 링크>
```

## 5. 푸시

```bash
git push origin main
```

단일 브랜치라 브랜치 간 동기화는 없다. 대신 **프론트 계약과 백엔드를 한 커밋에 함께** 담는다 — 프론트가 새 API 파라미터·필터 규칙을 쓰면 `SearchQuery`/`MerchantSpecs`(및 JSON 폴백)도 같은 커밋에 넣어 경계면이 갈라지지 않게 한다. `backend/**`를 건드린 푸시는 backend-ci(CD)를 발동시키므로, 서버 반영이 의도된 것인지 확인한다.

## 6. 마무리 보고

커밋 해시·브랜치·푸시 결과를 표로 보고하고, 라이브 반영 조건(GitHub Pages 재배포, 캐시 시 `?v=` 새로고침)을 한 줄 덧붙인다. 검증하지 않은 것이 있으면 명시한다.

## 에러 핸들링

- push 거부(non-fast-forward): `git pull --rebase` 후 재푸시. 충돌 시 사용자에게 보고.
- D-F1 검증 실패(리터럴 `</` > 0): index.html을 커밋하지 말고 build_index.py의 이스케이프 처리를 점검.
- 비밀값 검출: 해당 파일 unstage → `.gitignore` 보강 → 사용자에게 키 폐기·재발급 안내.

## 테스트 시나리오

- 정상: merchants.html 기능 수정 → CLAUDE.md 이력 추가 → 스캔 OK → main 커밋·푸시 → feat 동기화 → 복귀·보고.
- 에러: 스테이징에 `deploy/.env` 발견 → 커밋 중단, unstage, 사용자 보고 후 재시도.
