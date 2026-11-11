"""활성 실행 계획(docs/exec-plans/active/*.md)의 scope 블록 밖 파일이 바뀌었으면 실패.
카카오모빌리티 사례처럼 수정 범위를 지시문이 아니라 스크립트로 판정한다."""
import re, subprocess, sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[2]

def git(*args):
    r = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    return r.returncode, r.stdout

code, _ = git("rev-parse", "--is-inside-work-tree")
if code != 0:
    print("scope-check 건너뜀: git 저장소가 아님")
    sys.exit(0)

allowed = []
for plan in (ROOT / "docs" / "exec-plans" / "active").glob("*.md"):
    m = re.search(r"<!-- scope:start -->(.*?)<!-- scope:end -->", plan.read_text(encoding="utf-8"), re.S)
    if m:
        allowed += [l.strip()[2:].strip() for l in m.group(1).splitlines() if l.strip().startswith("- ")]
if not allowed:
    print("scope-check 건너뜀: 활성 실행 계획에 scope 블록이 없음")
    sys.exit(0)

changed = set()
has_head = git("rev-parse", "--verify", "HEAD")[0] == 0
if has_head:
    changed |= set(git("diff", "--name-only", "HEAD")[1].split())
changed |= set(git("ls-files", "--others", "--exclude-standard")[1].split())

bad = sorted(f for f in changed if not any(f == a or f.startswith(a.rstrip("/") + "/") for a in allowed))
if bad:
    print("scope-check 실패: 활성 실행 계획의 허용 경로 밖 파일이 바뀌었다.", file=sys.stderr)
    for f in bad:
        print(f"  - {f}", file=sys.stderr)
    print("필요 없는 변경이면 되돌린다. 필요한 변경이면 실행 계획의 scope 블록과 결정 로그를 먼저 고친다.", file=sys.stderr)
    sys.exit(1)
print(f"scope-check 통과 (변경 {len(changed)}개)")
