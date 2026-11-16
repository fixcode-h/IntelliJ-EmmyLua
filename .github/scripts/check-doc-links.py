"""检查仓库里 Markdown 的相对链接指向的文件存在（不查网址和锚点）。"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
# archive 是历史记录，里面引用的旧代码已经删了
SKIP = {"build", "node_modules", ".git", "third-party", ".gradle", "archive"}
LINK = re.compile(r"\]\(([^)\s]+)\)")

bad = []
for md in ROOT.rglob("*.md"):
    if any(part in SKIP for part in md.relative_to(ROOT).parts):
        continue
    text = md.read_text(encoding="utf-8", errors="replace")
    for target in LINK.findall(text):
        if re.match(r"^[a-z][a-z0-9+.-]*:", target) or target.startswith("#"):
            continue
        path = target.split("#", 1)[0]
        if not path:
            continue
        resolved = (ROOT / path.lstrip("/")) if path.startswith("/") else (md.parent / path)
        if not resolved.exists():
            bad.append(f"{md.relative_to(ROOT)}: {target}")

if bad:
    print("这些相对链接指向的文件不存在：")
    print("\n".join(bad))
    sys.exit(1)
print("文档链接检查通过")
