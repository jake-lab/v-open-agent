# implementation plan - Task와 진행 방식

> 각 Task는 `01-product-requirements.md`의 REQ ID에서 도출했다. 산출물 경로, 이름, 동작은 `02-technical-specification.md`(v0.4)를 따른다.
> **한 owner는 한 번에 Task 하나만** 구현/검증하고, 끝나면 이 표의 상태를 `DONE`으로 바꾼다. 다중 개발자 병렬 작업은 `03-collaboration-architecture.md` §4~5를 따른다.
> Claude Code에서는 `/task`(다음 TODO 자동 선택) 또는 `/task TASK-07`. Codex에서는 §5의 프롬프트를 쓴다.

---

## 1. 진행 규칙

1. **선택**: 단일 owner는 선행 Task가 모두 `DONE`인 Task 중 번호가 가장 작은 것을 고른다. 팀 리더는 선행 Task가 모두 `DONE`이고 소유 파일이 겹치지 않는 Task를 여러 owner에게 병렬 배정할 수 있다.
2. **계획**: 수정할 파일, REQ/INV/T-R ID, 테스트 목록을 제시한다. 명백하고 낮은 위험의 로컬 작업은 보고 후 계속하고, 명세 변경, 비가역 작업, 사용자 선호에 따라 결과가 달라지는 경우에만 확인을 받는다. 테스트 목록에는 수용 테스트 전부와 성공, 실패, 결측/지연 각 1개 이상이 들어가야 한다.
3. **테스트 먼저**: 수용 기준을 테스트로 옮겨 실패하는 것을 확인한 뒤 구현한다.
4. **검증**: `scripts/check.sh`를 실제로 실행하고, 그 출력으로 보고한다.
5. **기록**: 이 표의 상태를 `DONE`으로 바꾼다. SPEC과 다르게 구현해야 했다면 SPEC을 먼저 고치자고 제안하고 확인을 받는다. 커밋 메시지는 `TASK-xx: <제목> (REQ ids)`로 쓴다.
6. **범위 엄수**: Task 범위 밖은 건드리지 않는다. 필요한 작업이 생기면 새 Task를 제안한다.

**완료 정의 (DoD)**: 수용 테스트 통과 / 기존 테스트 유지 / `scripts/check.sh` 통과 / 외부 네트워크/다운로드 없음 / 이 표 갱신.

---

## 2. Stage A — 판단 엔진 코어 (서버 없음) ★

| Task | 제목 | REQ | 산출물 (SPEC 절) | 수용 테스트 | 선행 | 상태 |
|---|---|---|---|---|---|---|
| TASK-01 | 프로젝트 스캐폴드 | NFR-01, DAT-03 | `git init`, `apps/server/pyproject.toml`(§1 도구/의존성), `apps/server/src/voa/` 하위 패키지(§1 트리, 빈 `__init__.py`), `scripts/check.sh`, `.gitignore`(`.venv/`, `var/`, `apps/server/config/devices.yaml`) | Docker 개발 이미지 빌드 후 `scripts/check.sh` 통과, `import voa` 테스트 1개 | — | DONE |
| TASK-02 | Observation 계약 | CON-01, CON-02, ENG-01 | `apps/server/src/voa/contracts/enums.py`, `apps/server/src/voa/contracts/observation.py` (§3.1–3.3, Draft 포함) | BBox/Score 경계값, payload kind 판별, extra 필드 거부, 빈 detections 허용, JSON Schema 스냅샷 | 01 | DONE |
| TASK-03 | RiskDecision/Alert 계약 | CON-01, ENG-11, ENG-13 | `apps/server/src/voa/contracts/decision.py` (§3.4, `ReasonCode` §4.8) | `contracts/fixtures/risk_event.json` 통과, 검증기 1–4 각각 위반 시 거부, 미등록 reason_code 거부, `contracts/schemas` 스냅샷 | 02 | DONE |
| TASK-04 | Policy Profile | ENG-15, INV-10 | `apps/server/src/voa/engine/profile.py`(모델/검증), `apps/server/src/voa/config/profile_loader.py`(YAML → 모델 / `ProfileError`), `apps/server/config/profiles/indoor_research_v0.yaml`, `apps/server/tests/fixtures/profiles/` | 정상 로드, §4.9 검증 항목마다 실패 케이스 1개 이상, `file_not_found`/`yaml_invalid`/`profile_invalid` 구분, detail 비밀 데이터 미포함 | 02 | DONE |
| TASK-05 | 포트/테스트 도구/순수성 검사 | ENG-14, ENG-17, INV-06 | `apps/server/src/voa/engine/ports.py`(`IdFactory`, `UtcClock`, `SequentialIdFactory`, `FixedEpochClock`), `apps/server/tests/builders.py`(관측/프레임 빌더), `apps/server/tests/engine/test_purity.py`(AST import 허용 목록) | risk/alert/obs/sess/notice prefix별 ID 순번 결정론, clock 매핑, 금지 모듈 import 시 테스트 실패 확인 | 02 | DONE |
| TASK-06 | 단계 1 Admission | ENG-01, ENG-02, INV-07 | `apps/server/src/voa/engine/admission.py` (§4.2) | `AdmissionStatus` 6종 각각 발생, 검사 순서, 처음 받은 key는 3/4번 통과 (T-R04) | 03, 04, 05 | DONE |
| TASK-07 | 단계 2/3 Window/Freshness/Frame Assembly | ENG-03, ENG-12 | `apps/server/src/voa/engine/window.py` (§4.3, §4.4) | fresh 경계값(=staleness 포함), `window_max_items`/`window_ms` 축출 시 인덱스 동기화, sensor_down 갱신, 프레임 완성 1회, 늦은 중복 frame 무시, 완성 시 stale이면 폐기 | 06 | DONE |
| TASK-08 | 단계 4 Scenario Evaluator | ENG-04, ENG-05, ENG-16 | `apps/server/src/voa/engine/evaluator.py` (§4.5) | 시나리오 2종 × met/absent/indeterminate, 판정 순서(차단 flag 우선), `overlap` 계산/경계, label/score/motion 필터, track lost의 `roi_min_overlap` 경계 (T-R02, T-R12, T-R15) | 07 | TODO |
| TASK-09 | 단계 5 State Machine | ENG-06–10, ENG-18, INV-03, INV-04 | `apps/server/src/voa/engine/state_machine.py` (§4.6) | 전이표 행 1–10 각각 1개 이상, 표에 없는 전이 미발생, 호출당 전이 1회, unknown 진입 시 카운터 초기화, risk_event_id 발급/유지/종료 규칙 (T-R01, T-R03) | 08 | TODO |
| TASK-10 | 단계 6 + `DecisionEngine` 통합 | ENG-10–15, INV-08–10 | `apps/server/src/voa/engine/engine.py` (§4.1, §4.7) | `step`/`tick` 적용 범위(시간 규칙 행 1/2/4), 결정 필드(§4.7), warning 진입당 alert 1개, `now` 역행 시 ValueError, `ProfileError` 시 config_error/결정 0개, `get_observation`/`is_fresh` 읽기 전용 계약 | 09 | TODO |
| TASK-11 | 불변식 테스트 묶음 | G1, G2 | `apps/server/tests/engine/test_invariants.py` | INV-01–10 전부. 고정 시드 `random.Random(seed)`로 무작위 관측/tick 시퀀스 200개를 생성해 매 결정마다 INV 검사(테스트 코드에서만 random 사용) | 10 | TODO |
| TASK-12 | Fixture 재생기/Mock Provider | INP-01, CON-03 | `apps/server/src/voa/providers/base.py`, `apps/server/src/voa/providers/mock.py`, `apps/server/src/voa/simulator/fixture.py`(§5 형식 파서/재생), provider contract suite, `contracts/fixtures/scenarios/` UC-01–04 + `.expected.jsonl` | 4개 fixture의 결정이 기대와 일치, mock과 독립 test double이 같은 provider contract suite 통과 | 10 | TODO |
| TASK-13 | Replay CLI | INP-02, ENG-14, NFR-02 | `apps/server/src/voa/replay/runner.py`, `apps/server/src/voa/replay/__main__.py` (§5) | 2회 실행 출력 바이트 동일(INV-05), 기대 불일치 시 줄/필드 출력 + exit 1, stderr에 p50/p95 | 12 | TODO |
| TASK-14 | 근거 검증/설명 폴백/행동 제한 | ENG-12, EXP-01, EXP-02 | `apps/server/src/voa/explain/repository.py`, `apps/server/src/voa/explain/validator.py`, `apps/server/src/voa/explain/template.py`, `apps/server/src/voa/explain/actions.py` (§6) | 위반 코드 6종 각각 → 템플릿 폴백 (T-R08), 금지 행동 → `ForbiddenActionError`, idempotency_key 결정론 | 10 | TODO |
| TASK-15 | SQLite 메타데이터 저장 | DAT-01, DAT-02 | `apps/server/src/voa/storage/sqlite.py` (§8.1), 로그 포매터 (§8.2) | 4개 테이블 CRUD, `PRAGMA table_info`로 BLOB/허용 외 컬럼 없음 검사, 보존 기간 삭제, 로그 허용 키 외 필드 제거 | 03 | DONE |
| TASK-15A | 의존성 라이선스 등록부 검증 | DAT-05, NFR-04 | `apps/server/tests/test_dependency_registry.py`, `02-technical-specification.md` §9 | `apps/server/pyproject.toml` 직접 의존성이 등록부에 누락되면 실패, AGPL/NC/비공개 API 의무 항목이 기본 설치에 있으면 실패 | 01 | DONE |

TASK-15 검증 (2026-11-10): 네트워크 차단 Docker 개발 컨테이너에서 `pytest -q tests/storage` 44 passed. `src/voa/storage`, `tests/storage` 범위의 ruff lint/format 통과, mypy는 None 검사 보완 후 동일 대상 1회 재검사 통과. 이후 사용자가 전체 회귀 검증을 요청하여 `scripts/check.sh`를 120초 상한으로 1회 실행했고, ruff lint/format, mypy 35개 파일, pytest 216개가 모두 통과했다. 실제 서버 연결 검증은 `voa.server`에 API 앱과 실행 진입점이 아직 없어 수행할 수 없다. 서버 시작 시/시간별 `purge_expired` 호출과 `JsonLogFormatter` 연결은 Stage B 서버 조립 시 수행한다.

TASK-07 검증 (2026-11-10): `tests/engine/test_window.py`의 freshness와 `window_ms` 경계, item/time 축출과 ID 인덱스 동기화, sensor down/복구, 시나리오별 frame 조립, 결측 kind, 완료 frame 중복, stale 완성 폐기, 미완성 frame 만료 테스트 10개가 통과했다. 이어서 `scripts/check.sh`를 실행해 ruff lint/format, mypy 39개 파일, pytest 242개가 모두 통과했다. SPEC, schema, 상태 전이 변경은 없다. Admission의 중복 ID 기억과 window 축출 ID 연결은 TASK-10의 `DecisionEngine` 조립에서 수행한다.

**Stage A 게이트**: TASK-01–15A가 모두 DONE이고, T-R01/02/03/04/08/12/15와 INV-01–10이 통과해야 한다. `python -m voa.replay contracts/fixtures/scenarios/uc01_obstacle.jsonl --expected ...`가 exit 0이어야 한다.

---

## 3. Stage B — 서버/전송 (MVP-1 완성)

| Task | 제목 | REQ | 산출물 (SPEC 절) | 수용 테스트 | 선행 | 상태 |
|---|---|---|---|---|---|---|
| TASK-16 | 설정/앱 골격 | COM-06, DAT-03, DAT-04 | 의존성 추가(fastapi, uvicorn, httpx-dev, §9 등록부 갱신), `apps/server/src/voa/server/settings.py`, `apps/server/src/voa/server/runtime.py`, `apps/server/src/voa/server/app.py`, `apps/server/src/voa/server/__main__.py`, `/v1/healthz`, `/v1/readyz` | 기본값, 숫자 교차 검증, pilot cert/key 없음 시작 거부, pilot uvicorn SSL 인자 전달, RAW_MEDIA=true 시작 거부, profile 무효 시 readyz 503, `SecureIdFactory` ID 형식, `AnchoredUtcClock` monotonic-to-UTC 매핑 | A 게이트 | TODO |
| TASK-17 | 장치 등록/세션/capability | COM-01, DEV-01 | `apps/server/src/voa/server/auth.py`, `apps/server/config/devices.example.yaml`, `POST /v1/sessions`, `POST /v1/sessions/{id}/resume` (§7.1, §7.4 enum) | scrypt 형식/비교, 정상 발급, access/resume 만료, resume 회전 후 이전 token 폐기, 서버 재시작 후 resume 401, 잘못된 secret/미등록 장치 401, capability 범위 밖 400, outputs 빈 목록 400, insecure dev는 127.0.0.1에서만 | 16 | TODO |
| TASK-18 | WS 스트림 ↔ 엔진 | COM-02 | `apps/server/src/voa/contracts/messages.py`, JSON Schema 스냅샷, `apps/server/src/voa/server/ws.py`, 세션별 엔진, tick 루프 (§7.2) | 5초 안에 auth 없음/token 오류 → 4401, Draft → 관측 → `decision`/`alert` push, 입력 끊김 → unknown push, non-accepted → `admission`, heartbeat 주기, 미선언 입력은 health down 주입 (T-R17), `resume` message는 auth 후 `last_alert_seq`만 수용, 예약 메시지 → `unsupported_message_type` | 17 | TODO |
| TASK-19 | Alert 전달/ACK/notice | COM-03, DEV-03 | `apps/server/src/voa/server/dispatch.py`, `POST /v1/alerts/{id}/ack`, WS `ack`, `notice` 송신 (§7.3, §7.4) | queued→expired, sent→unknown_delivery, received 유지, received→played, 역행 없음, 중복 ACK 무시, 늦은 ACK 상태 복구와 `late_ack` 로그 (T-R11), notice 조건 (T-R18) | 18 | TODO |
| TASK-20 | 재접속/중복 방지 | COM-04 | REST token 회전 후 WS `auth` 및 `resume(last_alert_seq)` 처리 (§7.1, §7.3) | 이전 resume token 재사용 거부, 재전송 조건 1/2/3 각각 위반 시 미전송, 같은 alert_id 유지, 시뮬레이터 측 alert_id 1회 재생 (T-R10, UC-05) | 19 | TODO |
| TASK-21 | Backpressure/status | COM-05 | 입력 큐, drop 계측, stream별 `frame_dropped` 부착, 제어 경로 분리, `GET /v1/sessions/{id}/status` (§7.5) | 큐 포화 시 오래된 영상 폐기, 폐기 대상 없음 시 신규 영상 거부, health/ACK 보존, 같은 stream의 다음 영상에만 flag, status 응답 필드 | 18 | TODO |
| TASK-22 | 시뮬레이터 = 글래스 클라이언트 참조 구현 | INP-01, COM-02, DEV-02, DEV-04, DEV-05 | `apps/server/src/voa/simulator/adapter.py`(세션/WS/전송), `apps/server/src/voa/simulator/outputs.py`(가상 HUD/speaker/haptic 출력 로그), `apps/server/src/voa/simulator/watchdog.py`, `apps/server/src/voa/simulator/__main__.py` (`--fixture --server --device --secret --outputs hud,speaker`) (§7.4) | uvicorn을 띄운 통합 테스트로 UC-01–05/08/09: 출력 로그로 렌더링/1회 재생/ACK 시점 검증, 서버 중단 시 watchdog 고지와 backoff 재접속 (T-R16) | 20, 21 | TODO |
| TASK-23 | 프라이버시 누출 검사 | DAT-01, DAT-02 | `apps/server/tests/integration/test_privacy.py` | E2E 실행 후 DB/캡처 로그/`tmp_path`에서 token/secret/payload 텍스트 검출 0건 | 22, 15 | TODO |

**Stage B 게이트 = MVP-1**: T-R10/T-R11/T-R16/T-R17/T-R18, 인증 거부, 재접속 시나리오가 통과하고, 시뮬레이터 → 서버 → 엔진 → alert → ACK까지 시연할 수 있어야 한다.

---

## 4. Stage C~F — 개략 (해당 단계에 들어갈 때 SPEC을 먼저 확장한 뒤 Task로 세분화)

| Stage | 범위 | 핵심 REQ | 진입 전 결정 |
|---|---|---|---|
| C 실 Vision | detector 어댑터 + model manifest → tracker(+`camera_motion_uncompensated` 판정) → 평가 러너(B0 프레임 단위 규칙 vs B2 엔진: recall, FP/hour, p95) | INP-03, NFR-02/03, T-R14 | OQ-01, OQ-04, OQ-06 |
| D Audio/OCR | SED 시나리오 추가(SPEC §4.9 확장), PTT ASR, partial/final, OCR 비신뢰 처리 | INP-04, T-R05/06/07/13 | OQ-05 |
| E VLM 설명 | 확정된 결정만 입력, claim+evidence_ids 구조화 출력, §6 validator, 타임아웃 폴백, unsupported claim rate 측정 | EXP-03, T-R09 | OQ-04 |
| F Android 클라이언트 및 실기기 | `05-android-client-plan.md`의 Android 참조 클라이언트, 기종별 어댑터, TLS 및 등록 운영, 배터리, 열, 끊김 측정 | COM, DEV, DAT-04 | OQ-02, OQ-03 |

평가 보고 형식(Stage C~): `run_id, code_commit, profile, model_artifacts{id,revision,sha256}, hardware, dataset, metrics{event_recall, false_alerts_per_hour, e2e_p95_ms, unknown_rate, unsupported_claim_rate}, limitations`. 측정하지 않은 값은 `TBD`로 둔다.

---

## 5. 에이전트 프롬프트

### 5.1 공통 (Codex는 매번 앞에 붙임. Claude Code는 `/task`가 자동 적용)
```text
AGENTS.md, docs/01-product-requirements.md, docs/02-technical-specification.md, docs/03-collaboration-architecture.md, docs/04-implementation-plan.md 중 이번 Task와 관련된 부분을 먼저 읽어라.
04-implementation-plan.md 에서 Task 하나만 구현한다. 경로/이름/스키마/상태/전이/reason_code 는 02-technical-specification.md 그대로 쓴다.
기본 실행은 로컬/mock. 외부 API 호출, 가중치 다운로드, 음성/영상 파일 저장 금지.
시작 전 수정 파일, REQ/INV/T-R ID, 테스트 계획을 짧게 보고하라.
끝나면 scripts/check.sh 결과와 함께 아래 보고 템플릿으로 보고하고 04-implementation-plan.md 상태를 갱신하라.
```

### 5.2 Task 실행 (Codex)
```text
04-implementation-plan.md 의 <TASK-ID> 를 구현하라. 선행 Task 가 DONE 이 아니면 중단하고 보고하라.
```

### 5.3 감사 (구현 후 수시)
```text
01-product-requirements.md 의 M 우선순위 REQ 와 02-technical-specification.md §4.0 INV 기준으로 compliance audit 를 하라. 코드는 수정하지 않는다.
결함은 위험도순으로, 각 항목에 파일:줄 근거를 붙인다. 확인하지 못한 항목은 PASS 가 아니라 UNVERIFIED.
중점: 근거 없는 warning, stale 근거, unknown→clear 오판, 중복 alert, raw media/토큰 로그, 비신뢰 텍스트의 명령 승격, 라이선스 미등록.
```

### 5.4 보고 템플릿
```md
### Task
- ID / REQ / INV / T-R:
- Files changed:
### 검증
- Commands + 결과 (passed / failed / skipped):
### 설계 영향
- 스키마/전이/SPEC 변경 여부:
### 미완료/위험
- NOT TESTED 항목과 이유, 후속 Task 제안:
```

### 5.5 금지 문구
"환각 0%", "사고 예방 보장", "모든 글래스 지원", "8GB GPU면 충분", 측정 근거가 없는 latency/accuracy 수치, "LLM이 경고를 직접 판단하도록 변경", "성능 테스트를 위해 원본 저장".
