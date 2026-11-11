"""문서 하네스 검사: 깨진 상대 링크, ADR 형식, feature_list.json 규칙.
실패 메시지에는 고치는 방법을 함께 적는다 (에이전트가 읽고 스스로 고치도록)."""
import json, re, subprocess, sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parents[2]
SKIP = {".git", "build", ".gradle", "node_modules", "out"}
errors = []

def md_files():
    for p in ROOT.rglob("*.md"):
        if not SKIP.intersection(p.relative_to(ROOT).parts):
            yield p

# 1. 상대 링크
link_re = re.compile(r"\]\(([^)\s]+)\)")
for f in md_files():
    text = f.read_text(encoding="utf-8")
    text = re.sub(r"```.*?```", "", text, flags=re.S)
    for target in link_re.findall(text):
        if target.startswith(("http://", "https://", "#", "mailto:")):
            continue
        path = target.split("#")[0]
        if path and not (f.parent / path).exists():
            errors.append(f"[link] {f.relative_to(ROOT)} -> {target} 가 없음. 경로를 고치거나 대상 문서를 만든다.")

# 2. ADR 형식
for f in sorted((ROOT / "docs" / "adr").glob("[0-9][0-9][0-9][0-9]-*.md")):
    t = f.read_text(encoding="utf-8")
    if not re.search(r"^- 상태: (제안|채택|대체됨)", t, re.M):
        errors.append(f"[adr] {f.name}: '- 상태: 제안|채택|대체됨' 줄이 필요하다.")
    for sec in ("## 맥락", "## 결정", "## 검토한 대안", "## 결과"):
        if sec not in t:
            errors.append(f"[adr] {f.name}: '{sec}' 섹션이 없다. docs/adr/README.md 형식을 따른다.")
    if f.name not in (ROOT / "docs" / "adr" / "README.md").read_text(encoding="utf-8"):
        errors.append(f"[adr] {f.name} 가 docs/adr/README.md 목록에 없다. 표에 한 줄 추가한다.")

# 3. feature_list.json
fl = ROOT / "feature_list.json"
try:
    items = json.loads(fl.read_text(encoding="utf-8"))
except Exception as e:
    items = None
    errors.append(f"[features] feature_list.json 파싱 실패: {e}")
if items is not None:
    ids = [i.get("id") for i in items]
    if len(ids) != len(set(ids)):
        errors.append("[features] id가 중복된다.")
    for i in items:
        missing = {"id", "description", "verify", "passes"} - i.keys()
        if missing:
            errors.append(f"[features] {i.get('id')}: {sorted(missing)} 필드가 없다.")
        if not isinstance(i.get("passes"), bool):
            errors.append(f"[features] {i.get('id')}: passes는 true/false 여야 한다.")
    # 마지막 커밋과 비교: 항목 삭제·설명 변경 금지 (passes만 바꿀 수 있다)
    try:
        old = subprocess.run(["git", "show", "HEAD:feature_list.json"], cwd=ROOT,
                             capture_output=True, text=True, encoding="utf-8")
        if old.returncode == 0:
            prev = {i["id"]: i for i in json.loads(old.stdout)}
            now = {i.get("id"): i for i in items}
            for k, v in prev.items():
                if k not in now:
                    errors.append(f"[features] {k} 가 삭제됐다. 항목은 지우지 않는다. 되돌린다.")
                elif now[k].get("description") != v.get("description") or now[k].get("verify") != v.get("verify"):
                    errors.append(f"[features] {k} 의 description/verify가 바뀌었다. passes만 바꿀 수 있다. 되돌린다.")
    except FileNotFoundError:
        pass

if errors:
    print("doc-lint 실패:", *errors, sep="\n", file=sys.stderr)
    sys.exit(1)
print("doc-lint 통과")
