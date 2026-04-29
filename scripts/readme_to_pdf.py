"""
将仓库根目录 README.md 转为 README.pdf。

优先使用 Edge / Chrome「无头打印」，由系统字体渲染中文（无乱码）。
若本机无可用浏览器或打印失败，回退到 xhtml2pdf，并修补其 DEFAULT_CSS 中的
Helvetica/Courier（否则默认样式会覆盖中文字体）。

依赖:
  pip install markdown xhtml2pdf Pillow reportlab
  （无头打印无需额外 Python 包，需已安装 Microsoft Edge 或 Google Chrome）
"""
from __future__ import annotations

import base64
import io
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import markdown
from PIL import Image
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from xhtml2pdf import pisa

try:
    from xhtml2pdf.default import DEFAULT_CSS as XHTML2PDF_DEFAULT_CSS
except ImportError:
    XHTML2PDF_DEFAULT_CSS = ""

ROOT = Path(__file__).resolve().parent.parent
MD = ROOT / "README.md"
OUT = ROOT / "README.pdf"
OUT_FALLBACK = ROOT / "README_export.pdf"

FONT_CANDIDATES = [
    (r"C:\Windows\Fonts\msyh.ttc", 0),
    (r"C:\Windows\Fonts\msyhbd.ttc", 0),
    (r"C:\Windows\Fonts\simhei.ttf", None),
    (r"C:\Windows\Fonts\simsun.ttc", 0),
]

MAX_GIF_PREVIEW_WIDTH = 880

# 追加在 xhtml2pdf 内置 DEFAULT_CSS 之后，提高优先级以覆盖 html { Helvetica } 与 Courier New
XHTML2PDF_FONT_OVERRIDE = """
/* readme_to_pdf: 覆盖内置拉丁字体，否则中文会变成乱码或 tofu */
html {
    font-family: CJK, sans-serif !important;
}
body {
    font-family: CJK, sans-serif !important;
}
pre, code, kbd, samp, tt {
    font-family: CJK, "Courier New", monospace !important;
}
"""


def register_cjk_font() -> str:
    """为 xhtml2pdf 回退路径注册 Windows 中文字体（逻辑名 CJK）。"""
    for path, sub in FONT_CANDIDATES:
        p = Path(path)
        if not p.is_file():
            continue
        try:
            if sub is not None:
                pdfmetrics.registerFont(TTFont("CJK", str(p), subfontIndex=sub))
            else:
                pdfmetrics.registerFont(TTFont("CJK", str(p)))
            pdfmetrics.registerFontFamily(
                "CJK",
                normal="CJK",
                bold="CJK",
                italic="CJK",
                boldItalic="CJK",
            )
            print(f"[readme_to_pdf] xhtml2pdf 中文字体: {p}")
            return "CJK"
        except Exception as e:
            print(f"[readme_to_pdf] 跳过字体 {p}: {e}")
            continue
    print("[readme_to_pdf] 警告: 未注册中文字体（仅影响 xhtml2pdf 回退）")
    return "Helvetica"


def local_image_to_data_uri(path: Path) -> str | None:
    """将本地图片转为 data URI；GIF 仅取首帧并缩小宽度。"""
    suf = path.suffix.lower()
    try:
        if suf == ".gif":
            im = Image.open(path)
            im.seek(0)
            im = im.convert("RGB")
            w, h = im.size
            if w > MAX_GIF_PREVIEW_WIDTH:
                ratio = MAX_GIF_PREVIEW_WIDTH / w
                resample = getattr(getattr(Image, "Resampling", None), "LANCZOS", Image.LANCZOS)
                im = im.resize((MAX_GIF_PREVIEW_WIDTH, int(h * ratio)), resample)
            buf = io.BytesIO()
            im.save(buf, format="JPEG", quality=72)
            return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()
        raw = path.read_bytes()
        mime = {
            ".png": "image/png",
            ".jpg": "image/jpeg",
            ".jpeg": "image/jpeg",
            ".webp": "image/webp",
        }.get(suf, "application/octet-stream")
        return f"data:{mime};base64," + base64.b64encode(raw).decode()
    except Exception:
        return None


def rewrite_img_tags(html: str, root: Path) -> str:
    """嵌入本地图；shields.io 改为文本块。"""

    def one_img(match: re.Match) -> str:
        tag = match.group(0)
        src_m = re.search(r'\bsrc\s*=\s*("|\')([^"\']+)\1', tag, re.I)
        if not src_m:
            return tag
        quote, src = src_m.group(1), src_m.group(2)

        if "img.shields.io" in src:
            alt_m = re.search(r'\balt\s*=\s*("|\')([^"\']*)\1', tag, re.I)
            label = alt_m.group(2) if alt_m else "badge"
            return (
                f'<span style="border:1px solid #ccc;border-radius:4px;padding:2px 8px;'
                f'font-size:9pt;margin:0 4px;display:inline-block;">{label}</span>'
            )

        if src.startswith(("http://", "https://", "data:")):
            return tag

        path = (root / src).resolve()
        if not path.is_file():
            return tag
        data_uri = local_image_to_data_uri(path)
        if not data_uri:
            return tag
        return re.sub(
            r'\bsrc\s*=\s*("|\')([^"\']+)\1',
            f"src={quote}{data_uri}{quote}",
            tag,
            count=1,
            flags=re.I,
        )

    return re.sub(r"<img\b[^>]*>", one_img, html, flags=re.I)


def _browser_exe_candidates() -> list[Path]:
    """Windows 常见 Edge / Chrome 安装路径。"""
    pf = os.environ.get("PROGRAMFILES", r"C:\Program Files")
    pfx86 = os.environ.get("PROGRAMFILES(X86)", r"C:\Program Files (x86)")
    local = os.environ.get("LOCALAPPDATA", "")
    return [
        Path(pf) / "Microsoft" / "Edge" / "Application" / "msedge.exe",
        Path(pfx86) / "Microsoft" / "Edge" / "Application" / "msedge.exe",
        Path(local) / "Google" / "Chrome" / "Application" / "chrome.exe",
        Path(pf) / "Google" / "Chrome" / "Application" / "chrome.exe",
    ]


def print_pdf_via_browser(html_file: Path, pdf_out: Path) -> bool:
    """
    使用 Chromium 系无头「打印为 PDF」。由操作系统与浏览器完成中文排版，避免 ReportLab 字库问题。
    """
    uri = html_file.resolve().as_uri()
    pdf_arg = str(pdf_out.resolve())
    # 内置图较多时 HTML 体积大；先尝试经典 --headless（多数环境更快结束），再试 --headless=new
    headless_flags = ("--headless", "--headless=new")
    for exe in _browser_exe_candidates():
        if not exe.is_file():
            continue
        for headless in headless_flags:
            cmd = [
                str(exe),
                headless,
                "--disable-gpu",
                "--no-first-run",
                "--disable-extensions",
                f"--print-to-pdf={pdf_arg}",
                uri,
            ]
            try:
                subprocess.run(cmd, capture_output=True, text=True, timeout=180, check=False)
            except subprocess.TimeoutExpired:
                print(f"[readme_to_pdf] {exe.name} {headless} 超时，换下一参数重试…")
                continue
            except Exception as e:
                print(f"[readme_to_pdf] 浏览器打印异常 {exe.name}: {e}")
                continue
            if pdf_out.is_file() and pdf_out.stat().st_size > 2000:
                print(f"[readme_to_pdf] 已用 {exe.name} 无头打印生成 PDF")
                return True
    return False


def build_html_body() -> str:
    text = MD.read_text(encoding="utf-8")
    text = re.sub(r"\$\s*\\rightarrow\s*\$", "\u2192", text)
    html_body = markdown.markdown(
        text,
        extensions=[
            "markdown.extensions.tables",
            "markdown.extensions.fenced_code",
            "markdown.extensions.nl2br",
            "markdown.extensions.extra",
        ],
    )
    return rewrite_img_tags(html_body, ROOT)


def wrap_html_for_browser(html_body: str) -> str:
    """无头打印路径：使用系统已安装的黑体/雅黑字体名（不依赖 ReportLab 注册名）。"""
    return f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"/>
<style>
body {{ margin: 22px; font-size: 10pt; line-height: 1.45;
  font-family: "Microsoft YaHei","微软雅黑","PingFang SC",SimHei,sans-serif;
}}
html, body, div, span, table, thead, tbody, tr, th, td,
p, blockquote, li, h1, h2, h3, h4, h5, h6, strong, em {{
  font-family: "Microsoft YaHei","微软雅黑","PingFang SC",SimHei,sans-serif !important;
}}
h1 {{ font-size: 18pt; }}
h2 {{ font-size: 13pt; margin-top: 1.2em; }}
h3 {{ font-size: 11pt; }}
table {{ border-collapse: collapse; width: 100%; margin: 10px 0; font-size: 9pt; }}
th, td {{ border: 1px solid #ccc; padding: 6px 8px; vertical-align: top; }}
pre, code, kbd, samp {{
  font-family: "Microsoft YaHei","微软雅黑",Consolas,"Courier New",monospace !important;
  font-size: 8.5pt;
}}
pre {{ background: #f6f8fa; padding: 8px; white-space: pre-wrap; word-break: break-word; }}
img {{ max-width: 100%; height: auto; }}
blockquote {{ margin-left: 0; padding-left: 12px; border-left: 3px solid #ddd; color: #444; }}
a {{ color: #0969da; }}
hr {{ margin: 16px 0; border: none; border-top: 1px solid #eee; }}
</style></head><body>{html_body}</body></html>"""


def wrap_html_for_xhtml2pdf(html_body: str, font_family: str) -> str:
    """xhtml2pdf 回退：使用已注册的 CJK 逻辑名；CreatePDF 必须传 str 勿传 bytes。"""
    return f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"/>
<style>
body {{ margin: 22px; font-size: 10pt; line-height: 1.45; }}
html, body, div, span, table, thead, tbody, tr, th, td,
p, blockquote, li, dd, dt, strong, em, u, small,
h1, h2, h3, h4, h5, h6 {{
  font-family: "{font_family}", sans-serif !important;
}}
h1 {{ font-size: 18pt; }}
h2 {{ font-size: 13pt; margin-top: 1.2em; }}
h3 {{ font-size: 11pt; }}
table {{ border-collapse: collapse; width: 100%; margin: 10px 0; font-size: 9pt; }}
th, td {{ border: 1px solid #ccc; padding: 6px 8px; vertical-align: top; }}
pre, code, kbd, samp {{
  font-family: "{font_family}", Consolas, "Courier New", monospace !important;
  font-size: 8.5pt;
}}
pre {{ background: #f6f8fa; padding: 8px; white-space: pre-wrap; word-break: break-word; }}
img {{ max-width: 100%; height: auto; }}
blockquote {{ margin-left: 0; padding-left: 12px; border-left: 3px solid #ddd; color: #444; }}
a {{ color: #0969da; }}
hr {{ margin: 16px 0; border: none; border-top: 1px solid #eee; }}
</style></head><body>{html_body}</body></html>"""


def merge_xhtml2pdf_default_css() -> str:
    """内置 DEFAULT_CSS 强制 html 使用 Helvetica，必须在其后追加覆盖规则。"""
    return (XHTML2PDF_DEFAULT_CSS or "") + "\n" + XHTML2PDF_FONT_OVERRIDE


def resolve_pdf_output_path() -> Path:
    """优先写入 README.pdf；若被占用则改用 README_export.pdf。"""
    try:
        OUT.parent.mkdir(parents=True, exist_ok=True)
    except Exception:
        pass
    try:
        if OUT.exists():
            OUT.unlink()
        return OUT
    except PermissionError:
        print("[readme_to_pdf] README.pdf 可能被占用，改用 README_export.pdf")
        OUT_FALLBACK.unlink(missing_ok=True)
        return OUT_FALLBACK


def main() -> int:
    html_body = build_html_body()

    # 1) 优先：临时 HTML + Edge/Chrome 无头打印（中文由系统字体渲染）
    fd, tmp_path = tempfile.mkstemp(prefix="readme_to_pdf_", suffix=".html", dir=str(ROOT))
    os.close(fd)
    tmp_file = Path(tmp_path)
    try:
        tmp_file.write_text(wrap_html_for_browser(html_body), encoding="utf-8")
        out_path = resolve_pdf_output_path()
        if print_pdf_via_browser(tmp_file, out_path):
            print(f"Wrote: {out_path} ({out_path.stat().st_size} bytes)")
            return 0
    finally:
        try:
            tmp_file.unlink(missing_ok=True)
        except OSError:
            pass

    # 2) 回退：xhtml2pdf + 修补 DEFAULT_CSS（覆盖内置 Helvetica）+ 注册 TTF
    print("[readme_to_pdf] 浏览器打印不可用，使用 xhtml2pdf 回退…")
    out_path = resolve_pdf_output_path()
    font_family = register_cjk_font()
    html = wrap_html_for_xhtml2pdf(html_body, font_family)
    default_css = merge_xhtml2pdf_default_css()

    out_f = out_path.open("wb")
    status = pisa.CreatePDF(
        html,
        dest=out_f,
        encoding="utf-8",
        default_css=default_css,
    )
    out_f.close()
    if getattr(status, "err", None):
        print(f"xhtml2pdf err flag: {status.err}")
    print(f"Wrote: {out_path} ({out_path.stat().st_size} bytes)")
    return 0 if not status.err else 1


if __name__ == "__main__":
    sys.exit(main())
