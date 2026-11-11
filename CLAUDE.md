@AGENTS.md

## Claude Code 전용

- 훅 설정은 `.claude/settings.json`에 있다. Stop 훅이 doc-lint, scope-check, `./gradlew check`를 돌리고
  실패하면 stderr 내용과 함께 종료를 막는다. 막히면 출력된 원인을 고친다. 훅을 끄거나 우회하지 않는다.
- 코드 리뷰는 구현한 대화가 아닌 새 컨텍스트에서 받는다 (`/code-review`).
