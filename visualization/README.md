# 시스템 구성도

이 폴더의 HTML은 현재 저장소의 제품 요구사항, 기술 명세, 협업 아키텍처, 구현 계획,
Android 계획과 accepted ADR을 근거로 생성한 standalone 구성도다. 한 장에 현재와 후속
단계를 섞어 왜곡하지 않도록 전체 구조와 Stage별 흐름을 분리한다.

| 파일 | 범위 | 핵심 질문 |
|---|---|---|
| `architecture.html` | 전체 시스템 개요 | 어떤 구성요소가 있고 판단 책임은 어디에 있는가 |
| `stage-a-engine.html` | Stage A dataflow | 서버와 장치 없이 fixture/mock으로 엔진을 어떻게 검증하는가 |
| `stage-b-simulator.html` | Stage B sequence | Python Simulator가 세션, WS, alert, ACK, 재접속을 어떻게 검증하는가 |
| `stage-c-android-vision.html` | Stage C dataflow | Android 휴대폰 camera/IMU가 서버 Provider를 거쳐 어떻게 관측이 되는가 |

구성도는 다음을 구분한다.

- 현재 구현된 Android 계약, Admission, Window/Freshness, Frame Assembly, SQLite metadata
- TASK-08부터 TASK-10까지 이어지는 DecisionEngine 단계
- Stage B에서 구현할 Gateway와 MVP-1 기준 Python Simulator
- Stage B 계약을 보완 검증하는 Android 휴대폰/에뮬레이터 참조 앱
- Stage C Android camera/IMU 입력과 Stage F 스마트글래스 adapter 교체 경계
- `DecisionEngine`만 위험 상태를 확정하는 판단 경계
- 원본 미디어와 민감정보를 저장하지 않는 데이터 경계

`architecture.html` 헤더에는 Stage A/B/C 상세 HTML로 이동하는 상대경로 링크가 있다.
Archify 재생성 후 `postprocess.mjs`를 실행하면 링크를 다시 삽입하고 모든 HTML에서
U+00B7 문자를 `/`로 치환한다.

```bash
node visualization/postprocess.mjs
```

Archify의 한국어 catalog와 viewer template은 U+00B7 문자를 포함한다. 이 저장소는 해당
문자를 금지하므로 candidate 번역은 `/`로 재정의하고, 생성된 HTML도 후처리한다. 재생성
후에는 아래 검사를 다시 수행해야 한다.

```bash
rg -n $'\u00b7' visualization
```
