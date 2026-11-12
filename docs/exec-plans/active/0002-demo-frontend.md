# 실행 계획 0002: 데모 화면 (CS 어드민 + 장애 데모 대시보드)

- 상태: 진행 중
- 시작: 2026-11-12

## 왜

포트폴리오에서 시스템이 푸는 문제를 화면으로 보여 주려는 것이다. 보여 줄 것은 두 가지다.
- 장애를 넣어도 불일치가 0으로 수렴하는 과정
- 사람이 봐야 하는 건만 CS 화면에 남는다는 것

0001에서는 프론트엔드 화면을 범위 밖으로 두었다. 이번에 사용자 요청으로 범위를 넓힌다 (설계 문서 2절 범위 표 수정).

## 허용 경로

<!-- scope:start -->
- AGENTS.md
- ARCHITECTURE.md
- README.md
- .gitignore
- progress.md
- feature_list.json
- docs/
- scripts/
- frontend/
- admin-api/
- infra/
- core/
- worker/
- reconciler/
- mock-servers/
- scenario-tests/
- docker-compose.yml
- settings.gradle
- build.gradle
<!-- scope:end -->

## 단계

| 단계 | 할 일 | 관련 기능 |
|---|---|---|
| 1 | 범위 변경 문서, 계획, feature_list 추가 | - |
| 2 | admin 현황 API, mock 데모용 일괄 조작 API | F-16 |
| 3 | 데모 실행 설정(짧은 주기)과 실행 스크립트 | F-16 |
| 4 | React 프론트: CS 어드민 화면, 데모 대시보드 | F-15, F-16 |
| 5 | 실제로 띄워 브라우저로 데모 흐름 확인, 리뷰, 커밋 | F-15, F-16 |

## 완료 조건

- feature_list F-15, F-16 `passes:true`
- 시나리오 테스트(기존 31개 + 추가분) 통과, 프론트 빌드·단위 테스트 통과
- README에 데모 실행 방법과 화면 캡처

## 결정 로그

- 2026-11-12: 사용자 결정에 따라 범위를 넓힌다.
  - 화면은 CS 어드민과 장애 데모 대시보드를 함께 만든다.
  - 구현은 React + TypeScript + Vite로 별도 모듈 `frontend/`에 둔다.
- 2026-11-12: 실행 계획 0001과 kickoff 문서를 `docs/exec-plans/completed/`로 옮겼다.
- 2026-11-12: 데모 중 가짜 conflict(`PREPARE_PRODUCT_REJECTED`)를 발견했다.
  - 같은 옵션을 여러 경로가 동시에 처리해 상품준비중 변경을 두 번 호출한 것이다.
  - 출고 행 잠금으로 직렬화했다 ([ADR-0010](../../adr/0010-prepare-product-under-row-lock.md)).
- 2026-11-12: 데모 실행 스크립트에 시작 전 포트 검사를 넣었다.
  - 이 PC에서는 8090을 다른 프로그램이 쓰고 있었다. 스크립트는 그 프로그램의 응답을 mock 준비 완료로 오인했다.
  - mock 포트는 `MOCK_PORT`로 바꿀 수 있다.
- 2026-11-12: F-16은 `passes:false`로 둔다.
  - 출고 후 취소의 클레임 철회 웹훅이 유실되면 대사의 `CLAIM_MISMATCH`가 0으로 돌아오지 않는다. ADR-0009에 미해결로 적힌 경우다.
  - 화면에서 그대로 재현된다. 해결 방법은 사용자와 정한다.
- 2026-11-12: 처음 화면이 "AI 티가 난다"는 지적을 받아 전면 수정했다.
  - 개발자가 만든 운영 콘솔(Sidekiq, Hangfire, Kafka UI)을 참고했다 ([demo-ui-references.md](../../design-docs/demo-ui-references.md)).
  - 카드, 설명 문장, 번호 단계, 아이콘 알약을 뺐다.
  - 대신 지표 줄, 도구 줄, 폭 전체 그래프, 왼쪽 목록과 일괄 재처리를 넣었다.
- 2026-11-12: 리뷰(Sonnet, 새 컨텍스트) 지적을 반영했다.
  - 503을 모든 요청에서 성공으로 읽던 문제
  - 비ASCII 처리자 헤더
  - 송장 대기·완료의 단위 불일치
  - 초기화 중 FK 검사 복구와 큐 비우기 60초 제한
  - demo.sh의 SIGPIPE 오판, 죽은 앱 대기, 포트 재검사
  - DemoApiScenarioTest에 429 주입 추가
- 2026-11-12: 운영 콘솔풍 2차 화면도 "개별로"라는 평을 받아 다시 만들었다 (3차).
  - 디자이너가 만든 실물을 레퍼런스로 삼았다: Tremor 템플릿, Vercel Observability, Anthropic frontend-design 스킬.
  - 2차 화면은 스킬이 꼽은 "AI 티" 목록(물든 검정, 고정폭 라벨, 가운뎃점 메타)에 해당했다.
  - 바꾼 것
    - 왼쪽 사이드바
    - 복구 타임라인(장애 시점 세로선, 불일치 구간 음영, 요약 문장)
    - 카드 없는 지표
    - 장애 시나리오 오른쪽 패널(프리셋)
    - 상세 표 스타일 목록
    - Pretendard
  - 이 PC에서는 5173을 다른 프로젝트가 쓰고 있어 `FRONT_PORT`로 바꿀 수 있게 했다.
