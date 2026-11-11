# 하네스 설계

이 저장소에서 AI 코딩 에이전트(Claude Code, Codex)가 지켜야 할 것을 어떻게 알려주고(가이드), 어떻게 검사하는지(센서) 정리한 문서.

## 참고한 자료와 가져온 것

| 자료 | 핵심 | 이 저장소에 가져온 것 |
|---|---|---|
| OpenAI, "하네스 엔지니어링: 에이전트 우선 세계에서 Codex 활용하기" (2026-02-11) https://openai.com/ko-KR/index/harness-engineering/ | AGENTS.md는 백과사전이 아니라 약 100줄짜리 목차. 지식은 `docs/`에 버전 관리. 실행 계획을 저장소에 둠. 커스텀 린트 오류 메시지에 수정 방법을 넣음. 문서 최신성을 CI로 검사 | 짧은 AGENTS.md + docs 구조, `exec-plans/`, doc-lint, ArchUnit 실패 메시지에 수정 방법 기재 |
| Anthropic, "Effective harnesses for long-running agents" (2025-11-26) https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents (현재 /research/ 경로로 이동) | 실패 패턴: 한 번에 다 하려다 중간에 끊김, 일부만 하고 끝났다고 선언, 테스트 없이 완료 표시. 해법: 기능 목록을 JSON으로 두고 `passes`만 바꾸게 함, 진행 기록 파일 + git 로그로 세션 시작, 한 세션 한 기능 | `feature_list.json`, `progress.md`, AGENTS.md의 세션 시작 순서 |
| Birgitta Böckeler, "Harness engineering for coding agent users" (martinfowler.com, 2026-04-02) https://martinfowler.com/articles/harness-engineering.html | 가이드(사전 제어)와 센서(사후 제어), 계산형(테스트, 린트, ArchUnit)과 추론형(AI 리뷰). 계산형 센서는 매 변경마다, 비싼 건 뒤로. 기능 동작 검증(behaviour harness)은 아직 AI가 만든 테스트에 너무 의존한다고 지적 | 센서를 계산형 위주로 구성. 시나리오 테스트(F-xx)는 사람이 쓴 기대 결과를 기준으로 함 |
| Claude Code 공식 Best practices https://code.claude.com/docs/en/best-practices | CLAUDE.md는 짧게, 예외 없는 규칙은 훅·deny 규칙으로. Stop 훅이 exit 2를 내면 종료를 막고 stderr를 Claude에게 돌려줌. 훅은 적게 유지 | Stop 훅 하나, deny 규칙, CLAUDE.md는 AGENTS.md import |
| 카카오모빌리티, "카카오 T 주차 하네스 엔지니어링 적용기" (2026-06-10) https://developers.kakaomobility.com/techblogs/harness-engineering.html | Context / Control / Observability 3요소. 수정 범위를 지시문으로 요구했더니 작업이 길어지면 무시됨 → 스크립트로 기계적으로 검증. 수정 범위 1회, 빌드·테스트 최대 2회, 리뷰 최대 3회 후 사람에게 알림 | `scope-check.sh` (실행 계획의 허용 경로 밖 수정 차단), 재시도 상한 |
| ECC (affaan-m/ECC, MIT, 2026-11 기준 약 30만 stars) https://github.com/affaan-m/ECC | 에이전트 71개, 스킬 314개, 훅, 메모리. Spring Boot용 `springboot-verification`, `springboot-tdd`, `jpa-patterns`, `java-reviewer` 포함. 설치 방식을 겹치지 말라고 경고, 훅 없는 minimal 프로필 제공 | 전체 설치는 하지 않음. Spring 검증 순서(빌드 → 정적 분석 → 테스트+커버리지 → 보안 스캔 → diff 리뷰)만 참고 |
| obra/superpowers (MIT) https://github.com/obra/superpowers | 대화로 스펙을 끌어낸 뒤 계획 → 서브에이전트 구현 → 리뷰. TDD, YAGNI 강조 | 계획을 먼저 쓰고 승인 후 구현하는 순서만 차용 |
| github/spec-kit (MIT) https://github.com/github/spec-kit | 스펙 → 계획 → 작업 분해 → 구현 | 설계 문서 → 실행 계획 → feature_list 구조가 같은 흐름 |

## ECC를 통째로 설치하지 않는 이유

- 스킬 수백 개는 대부분 이 프로젝트(Java/Spring 백엔드 하나)와 무관하다. 공식 Best practices도 훅과 규칙은 적게 유지하라고 한다.
- 면접에서 설명할 하네스는 "남이 만든 묶음"이 아니라 이 도메인의 실패(중복, 순서, 유실, 429)를 막는 장치여야 한다.
- 필요하면 ECC의 `java-reviewer` 에이전트나 `springboot-verification` 스킬만 골라 `.claude/`에 복사한다. 그때는 출처와 라이선스(MIT)를 이 문서에 기록한다.

## 구성

### 가이드 (작업 전에 알려주는 것)

| 무엇 | 파일 | 종류 |
|---|---|---|
| 저장소 지도, 세션 시작 순서, 금지 사항 | AGENTS.md (CLAUDE.md는 import만) | 추론형 |
| 모듈과 의존 방향 | ARCHITECTURE.md | 추론형 |
| 외부 API 제약 | docs/references/external-facts.md | 추론형 |
| 결정과 이유 | docs/adr/ | 추론형 |
| 이번 작업의 허용 경로와 완료 조건 | docs/exec-plans/active/*.md | 추론형 + scope-check가 읽음 |

### 센서 (작업 후에 검사하는 것)

| 무엇 | 언제 | 종류 | 상태 |
|---|---|---|---|
| doc-lint: 문서 링크, ADR 형식, feature_list 개수 감소 금지 | Stop 훅 | 계산형 | 작성 완료 |
| scope-check: 실행 계획 허용 경로 밖 변경 차단 | Stop 훅 | 계산형 | 작성 완료 |
| `./gradlew check` (컴파일, 단위·통합 테스트) | Stop 훅 (gradlew가 생긴 뒤부터) | 계산형 | 코드 생성 후 |
| ArchUnit: core 순수성, 실행 모듈 간 참조 금지, 샵바이 호출 단일 경로 | `gradlew check`에 포함 | 계산형 | 코드 생성 후 |
| 시나리오 테스트 F-01~F-14 (Testcontainers + mock 서버) | `gradlew check`에 포함 | 계산형 | 코드 생성 후 |
| 새 컨텍스트 코드 리뷰 (`/code-review`) | PR 전 | 추론형 | 수동 |

### 재시도 상한

Stop 훅이 같은 이유로 계속 막으면 Claude Code는 연속 8회 차단 후 종료를 허용한다(공식 문서 기준). 그 전에 같은 실패가 3회 반복되면 progress.md에 원인을 적고 사람에게 넘긴다.

## 하네스를 고치는 규칙

- 에이전트가 같은 실수를 두 번 하면 문서 한 줄을 늘리기 전에 센서로 막을 수 있는지 먼저 본다.
- 센서가 한 번도 안 걸리면 품질이 좋은 건지 검사가 약한 건지 확인한다 (예: 일부러 규칙을 어긴 커밋으로 테스트).
- 이 문서에 바뀐 이유를 남긴다.

## 변경 이력

- 2026-11-11: 최초 작성
