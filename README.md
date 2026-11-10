# v-open-agent

관측한 것만 근거로 판단하고, 확신할 수 없으면 솔직하게 `unknown`이라고 말하는 로컬 위험 판단 엔진입니다.

스마트글라스나 시뮬레이터가 보낸 관측을 시간 순서대로 모아 위험 상태를 판단합니다. 경고에는 항상 어떤 관측을 근거로 삼았는지 기록합니다. 입력이 끊기거나 오래됐거나 가려진 경우에는 안전하다고 추측하지 않습니다.

> 아직 제품이 아니라 구현 중인 연구용 프로젝트입니다. 산업안전 보호장치나 응급 시스템을 대신하지 않습니다. 현재 진행 상황은 [구현 계획](docs/04-implementation-plan.md)에서 확인할 수 있습니다.

## 왜 이 프로젝트를 만드는가

센서 기반 경고 시스템에서 가장 위험한 실수는 보지 못한 것을 봤다고 말하는 것입니다. 이 프로젝트는 그 반대에서 출발합니다.

- 근거가 충분하면 승인된 문구로 경고합니다.
- 근거가 부족하면 `unknown`을 반환합니다.
- 생성 모델의 출력은 관측 후보나 설명일 뿐, 위험 상태를 직접 결정하지 않습니다.
- 거리, 속도, 충돌 확률, 신원처럼 측정하지 않은 값은 만들어내지 않습니다.
- 경고 판단은 VLM, OCR, STT를 기다리지 않습니다.
- 기본 설정에서는 외부 API를 호출하거나 원본 미디어를 저장하지 않습니다.
- GPU, 모델 가중치, 네트워크가 없어도 테스트할 수 있어야 합니다.

핵심은 화려한 모델이 아니라 작고 결정론적인 `DecisionEngine`입니다. 같은 정책과 같은 관측을 다시 재생하면 같은 결정을 내리는 엔진을 먼저 만들고, 실제 센서와 모델은 그 뒤에 연결합니다.

## 동작 방식

```text
시뮬레이터 또는 장치
        |
        v
관측 Observation
        |
        v
판단 엔진 DecisionEngine
        |
        v
결정 RiskDecision
        |
        +---- 위험 조건 확인됨 --> warning
        |
        +---- 위험 조건 없음 ----> clear / resolved
        |
        +---- 근거가 부족함 -----> unknown
```

모든 결정에는 정책 버전과 모델 버전, `reason_codes`, `evidence_ids`가 함께 남습니다.

## 지금 어디까지 왔나

현재 `main` 브랜치는 Stage A의 기반을 구현하고 있습니다.

완료된 부분:

- Observation, RiskDecision, Alert 데이터 계약과 JSON Schema 스냅샷
- 정책 프로파일 모델, YAML 로더, 안전한 오류 처리
- 리플레이를 위한 결정론적 ID와 UTC 시계
- 엔진과 계약 모듈이 금지된 라이브러리를 import하지 않는지 확인하는 검사
- 직접 의존성의 라이선스 등록부 검사

아직 남은 부분:

- 판단 엔진의 나머지 단계와 전체 통합
- fixture 재생기와 mock provider
- API 서버, WebSocket 전송, ACK와 재접속
- Android 참조 클라이언트와 실제 센서 provider

성능 수치는 아직 측정하지 않았습니다. 측정 로그가 생기기 전까지 정확도와 지연 시간은 `TBD`로 둡니다.

## 직접 확인하기

필요한 것은 Git, Docker, Docker Compose v2와 Bash 실행 환경입니다. Windows에서는 Git Bash나 WSL을 사용할 수 있습니다. Python과 개발 도구는 컨테이너 안에 설치되므로 호스트에 별도로 맞출 필요가 없습니다.

```bash
git clone https://github.com/virnect-corp/v-open-agent.git
cd v-open-agent
scripts/dev.sh
```

`scripts/dev.sh`는 개발 이미지를 만들고 Ruff, 포맷 검사, strict mypy, pytest를 차례로 실행합니다. 첫 실행은 이미지를 만드는 데 시간이 조금 더 걸릴 수 있습니다.

특정 테스트만 실행할 수도 있습니다.

```bash
scripts/dev.sh pytest -q apps/server/tests/contracts
```

컨테이너 안에서 직접 살펴보고 싶다면 다음 명령을 사용합니다.

```bash
scripts/dev.sh bash
```

API 서버 실행은 아직 준비되지 않았습니다. 서버 골격과 `/v1/healthz`는 구현 계획의 TASK-16에서 추가됩니다.

## 저장소 둘러보기

```text
apps/server/     Python 판단 엔진과 향후 API 서버
apps/android/    Android 참조 클라이언트
contracts/       Python과 Android가 공유하는 Schema와 fixture
docs/            요구사항, 기술 명세, 작업 계획, ADR
docker/          개발 및 서버 컨테이너 정의
scripts/         빌드와 검증 명령
```

처음 읽는다면 아래 순서가 가장 빠릅니다.

1. [제품 요구사항](docs/01-product-requirements.md)에서 무엇을 만들고 무엇을 만들지 않는지 확인합니다.
2. [기술 명세](docs/02-technical-specification.md)에서 계약, 불변식, 엔진 단계와 전이표를 확인합니다.
3. [구현 계획](docs/04-implementation-plan.md)에서 현재 완료된 작업과 다음 작업을 확인합니다.
4. 병렬 작업을 시작하기 전에는 [협업 아키텍처](docs/03-collaboration-architecture.md)의 Lane 소유권을 확인합니다.

그 밖의 문서:

- [인터랙티브 시스템 구성도와 Stage별 흐름](visualization/README.md)
- [Android 클라이언트 계획](docs/05-android-client-plan.md)
- [Architecture Decision Records](docs/adr/)
- [Codex와 Claude Code 작업 규칙](AGENTS.md)
- [공유 RiskDecision fixture](contracts/fixtures/risk_event.json)

## 작업에 참여하려면

이 저장소는 작은 작업을 순서대로 쌓는 방식을 사용합니다.

1. [구현 계획](docs/04-implementation-plan.md)에서 선행 작업이 모두 `DONE`인 Task를 하나 고릅니다.
2. 관련 REQ, INV, T-R과 기술 명세를 먼저 읽습니다.
3. 수용 기준을 테스트로 고정한 뒤 구현합니다.
4. `scripts/dev.sh`로 전체 검사를 통과시킵니다.
5. Task 하나를 commit 하나와 PR 하나로 제출합니다.

공유 계약이나 상태 전이를 바꿔야 한다면 코드를 먼저 고치지 말고 기술 명세와 ADR 변경부터 제안해 주세요.

## 기술 기반

- Python 3.11+
- Pydantic v2
- pytest, Ruff, mypy strict mode
- FastAPI와 WebSocket은 Stage B에서 연결 예정
- SQLite는 원본 미디어가 아닌 메타데이터 저장에만 사용 예정

직접 의존성과 라이선스는 [기술 명세의 등록부](docs/02-technical-specification.md#91-python-dependency-registry)에서 관리합니다.

## 라이선스

프로젝트 라이선스는 Apache-2.0을 제안한 상태이며 아직 소유자 확인이 남아 있습니다. 공개 배포 전에는 `LICENSE`와 `NOTICE`를 확정해야 합니다. 타사 코드, 모델, 데이터에는 각각의 원래 라이선스가 적용됩니다.
