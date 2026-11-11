"""gradle 테스트 실패 내용을 JUnit XML 리포트에서 읽어 UTF-8로 출력한다.

Windows의 Java 17에서 Gradle 콘솔 출력은 MS949로 나와 한글 테스트명과
'고치는 방법' 안내문이 Stop 훅 출력에서 깨진다. XML 리포트는 UTF-8이므로 여기서 다시 읽는다.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_MESSAGE = 1500

# 인자로 받은 시각(epoch 초) 이후에 쓰인 리포트만 읽는다. 컴파일 실패로 테스트가 안 돈 경우
# 이전 실행의 리포트를 현재 실패로 착각하지 않게 한다
since = float(sys.argv[1]) if len(sys.argv) > 1 else 0.0

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

found = 0
for path in sorted(glob.glob("*/build/test-results/*/*.xml")):
    if os.path.getmtime(path) < since:
        continue
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        for problem in list(case.findall("failure")) + list(case.findall("error")):
            found += 1
            message = (problem.get("message") or problem.text or "").strip()
            print(f"[테스트 실패] {case.get('classname')} > {case.get('name')}", file=sys.stderr)
            print(message[:MAX_MESSAGE], file=sys.stderr)
            print(file=sys.stderr)

if found:
    print(f"테스트 실패 {found}건 (위 메시지는 build/test-results의 XML 리포트에서 읽음)", file=sys.stderr)
