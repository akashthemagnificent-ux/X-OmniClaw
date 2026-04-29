# 将仓库根目录 README.md 转为带基本样式的 HTML，供 Prince 转 PDF。需: pip install markdown
import markdown
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
md_path = ROOT / "README.md"
html_path = ROOT / "README_preview.html"

body = markdown.markdown(
    md_path.read_text(encoding="utf-8"),
    extensions=["tables", "fenced_code", "nl2br"],
)
css = r"""
body { font-family: "Segoe UI", "Microsoft YaHei", sans-serif; max-width: 920px; margin: 24px auto; padding: 0 16px; line-height: 1.65; color: #222; }
table { border-collapse: collapse; width: 100%; margin: 1em 0; font-size: 0.92em; word-break: break-word; }
td, th { border: 1px solid #ddd; padding: 8px; vertical-align: top; }
code, pre { font-family: Consolas, "Courier New", monospace; background: #f5f5f5; border-radius: 4px; }
pre { padding: 12px; overflow: auto; white-space: pre-wrap; }
img { max-width: 100%; height: auto; }
blockquote { border-left: 4px solid #ccc; margin-left: 0; padding-left: 12px; color: #444; }
"""
full = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>X-OmniClaw README</title><style>{css}</style></head><body>{body}</body></html>"""
html_path.write_text(full, encoding="utf-8")
print(html_path)
