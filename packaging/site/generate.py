#!/usr/bin/env python3
"""Generates the ClashFest landing page (GitHub Pages).

Run by .github/workflows/site.yml on every release publish/edit. Pulls the recent
releases via `gh` (GH_TOKEN provided by the workflow) so the page always shows the
latest version, release notes and the current dev build without manual edits.
Self-contained output: inline CSS/JS, no external assets except the logo copied
alongside.

Usage: python3 packaging/site/generate.py --out site
"""

import argparse
import html
import json
import re
import shutil
import subprocess
from datetime import datetime
from pathlib import Path

REPO = "Nemu-x/ClashFest"
DL = f"https://github.com/{REPO}/releases/latest/download"
DEV_TAG = "dev-latest"
DEV_PAGE = f"https://github.com/{REPO}/releases/tag/{DEV_TAG}"


def gh_json(*args):
    out = subprocess.run(["gh", *args], capture_output=True, text=True, check=True,
                         encoding="utf-8", errors="replace").stdout
    return json.loads(out)


def gh_releases(limit=5):
    try:
        rels = gh_json("release", "list", "--repo", REPO, "--limit", str(limit),
                       "--exclude-pre-releases", "--exclude-drafts",
                       "--json", "tagName,publishedAt,name")
    except Exception:
        return []
    for r in rels:
        # One broken release must not blank the whole feed.
        try:
            r["body"] = gh_json("release", "view", r["tagName"], "--repo", REPO,
                                "--json", "body").get("body", "")
        except Exception:
            r["body"] = ""
    return rels


def gh_dev_build():
    """The rolling dev pre-release: asset names carry the commit sha, so resolve them live."""
    try:
        rel = gh_json("release", "view", DEV_TAG, "--repo", REPO,
                      "--json", "assets,publishedAt,body")
    except Exception:
        return None
    assets = {}
    dates = []
    for a in rel.get("assets", []):
        name = a.get("name", "")
        m = re.search(r"-alpha-(arm64-v8a|armeabi-v7a|universal|x86_64|x86)\.apk$", name)
        if m:
            assets[m.group(1)] = a.get("url") or a.get("browserDownloadUrl") or \
                f"https://github.com/{REPO}/releases/download/{DEV_TAG}/{name}"
            dates.append(a.get("updatedAt") or a.get("createdAt") or "")
    sha = re.search(r"\*\*Commit:\*\* `([0-9a-f]{7,})`", rel.get("body") or "")
    # The rolling release is edited in place, so its publishedAt is the day it was first
    # created; the assets' timestamps are the real build date.
    date = max(dates) if any(dates) else rel.get("publishedAt", "")
    return {"assets": assets, "sha": sha.group(1) if sha else "", "date": date}


def md_lite(text):
    """Tiny, safe markdown subset for release notes: headings, bullets, links, code."""
    out = []
    in_list = False
    in_code = False
    for raw in text.splitlines():
        if raw.strip().startswith("```"):
            if in_code:
                out.append("</pre>")
            else:
                out.append("<pre>")
            in_code = not in_code
            continue
        if in_code:
            out.append(html.escape(raw))
            continue
        line = html.escape(raw.rstrip())
        line = re.sub(r"`([^`]+)`", r"<code>\1</code>", line)
        line = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", line)
        line = re.sub(r"\[([^\]]+)\]\((https?://[^)]+)\)", r'<a href="\2">\1</a>', line)
        line = re.sub(r"(?<!href=\")(https?://github\.com/\S+)", r'<a href="\1">\1</a>', line)
        line = re.sub(r"(?<![\w/])#(\d{2,5})\b",
                      rf'<a href="https://github.com/{REPO}/issues/\1">#\1</a>', line)
        if re.match(r"^\s*[-*] ", line):
            if not in_list:
                out.append("<ul>")
                in_list = True
            out.append("<li>" + re.sub(r"^\s*[-*] ", "", line) + "</li>")
            continue
        if in_list:
            out.append("</ul>")
            in_list = False
        if line.startswith("### "):
            out.append(f"<h4>{line[4:]}</h4>")
        elif line.startswith("## "):
            out.append(f"<h4>{line[3:]}</h4>")
        elif line.strip():
            out.append(f"<p>{line}</p>")
    if in_list:
        out.append("</ul>")
    if in_code:
        out.append("</pre>")
    return "\n".join(out)


def fmt_date(iso):
    try:
        return datetime.fromisoformat(iso.replace("Z", "+00:00")).strftime("%d %b %Y")
    except Exception:
        return ""


def copy_logo(src: Path, dst: Path):
    """Ship a small logo: the repo PNG is print-sized, the page needs ~256 px."""
    try:
        from PIL import Image  # optional; the workflow installs pillow
        with Image.open(src) as im:
            im = im.convert("RGBA")
            im.thumbnail((256, 256))
            im.save(dst, optimize=True)
            return
    except Exception:
        shutil.copyfile(src, dst)


CSS = """
:root{--bg:#14120f;--panel:#1e1c1a;--panel2:#181614;--text:#f2efe8;--muted:#a39e96;
--accent:#c9a86c;--accent-dim:rgba(201,168,108,.18);--line:rgba(255,255,255,.09);--radius:14px}
*{box-sizing:border-box}body{margin:0;background:
radial-gradient(1200px 500px at 70% -10%,rgba(201,168,108,.08),transparent 60%),var(--bg);
color:var(--text);font:16px/1.6 system-ui,'Segoe UI',sans-serif}
.wrap{max-width:960px;margin:0 auto;padding:48px 20px 80px}
header{display:flex;align-items:center;gap:18px;margin-bottom:8px;position:relative}
.hdrlinks{position:absolute;top:0;right:0;display:flex;gap:10px}
.hdrlinks a{display:flex;align-items:center;justify-content:center;width:40px;height:40px;
border-radius:12px;background:var(--panel);border:1px solid var(--line);color:var(--accent)}
.hdrlinks a:hover{background:var(--accent-dim);border-color:var(--accent)}
.hdrlinks svg{width:20px;height:20px;fill:currentColor}
header img{width:72px;height:72px;border-radius:18px}
h1{margin:0;font-size:2rem}h1 small{display:block;font-size:.95rem;color:var(--muted);font-weight:400}
.badge{display:inline-flex;gap:8px;align-items:center;background:var(--accent-dim);
border:1px solid var(--accent);color:var(--accent);border-radius:999px;padding:4px 14px;
font-weight:600;margin:14px 0 4px}
h2{margin:44px 0 14px;font-size:1.25rem;border-bottom:1px solid var(--line);padding-bottom:8px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:14px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);padding:18px}
.card h3{margin:0 0 6px;font-size:1.02rem;display:flex;gap:8px;align-items:center}
.card p{margin:0 0 10px;color:var(--muted);font-size:.88rem}
.card a{display:flex;align-items:center;gap:8px;color:var(--accent);text-decoration:none;padding:3px 0;font-size:.92rem}
.card a:hover{text-decoration:underline}
.card.primary{border-color:var(--accent);box-shadow:0 0 0 1px var(--accent-dim) inset}
.btn{display:inline-flex;align-items:center;gap:8px;background:var(--accent);color:#14120f;
border-radius:10px;padding:8px 14px;font-weight:600;text-decoration:none;margin:4px 0 8px}
.btn:hover{filter:brightness(1.08)}
pre{position:relative;background:var(--panel2);border:1px solid var(--line);border-radius:10px;
padding:14px;overflow-x:auto;font-size:.85rem;line-height:1.55;color:#e8e2d6}
.rel{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);
padding:18px 20px;margin-bottom:14px}
.rel h3{margin:0;display:flex;justify-content:space-between;align-items:baseline;font-size:1.05rem}
.rel h3 span{color:var(--muted);font-size:.85rem;font-weight:400}
.rel .notes{color:#d9d4c9;font-size:.92rem}.rel .notes h4{margin:12px 0 4px;color:var(--text)}
.rel .notes ul{margin:6px 0;padding-left:22px}.rel .notes code{background:var(--panel2);
padding:1px 6px;border-radius:6px;font-size:.85em}
.rel .notes a{color:var(--accent);word-break:break-all}
footer{margin-top:56px;color:var(--muted);font-size:.9rem;border-top:1px solid var(--line);padding-top:18px}
footer a{color:var(--accent);text-decoration:none}.muted{color:var(--muted)}
@media(max-width:560px){header{flex-direction:column;text-align:center}.hdrlinks{position:static;justify-content:center}}
"""

ICON_WIKI = ('<svg viewBox="0 0 24 24"><path d="M6 2h11a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 '
             '2 0 0 1 2-2zm0 2v16h11V4H6zm2 3h7v2H8V7zm0 4h7v2H8v-2z"/></svg>')
ICON_TG = ('<svg viewBox="0 0 24 24"><path d="M9.78 18.65l.28-4.23 7.68-6.92c.34-.31-.07-.46-.52-.19L7.74 '
           '13.3 3.64 12c-.88-.25-.89-.86.2-1.3l15.97-6.16c.73-.33 1.43.18 1.15 1.3l-2.72 12.81c-.19.91-.74 '
           '1.13-1.5.71L12.6 16.3l-1.99 1.93c-.23.23-.42.42-.83.42z"/></svg>')
ICON_GH = ('<svg viewBox="0 0 24 24"><path d="M12 0c-6.626 0-12 5.373-12 12 0 5.302 3.438 9.8 8.207 '
           '11.387.599.111.793-.261.793-.577v-2.234c-3.338.726-4.033-1.416-4.033-1.416-.546-1.387-1.333-1.756'
           '-1.333-1.756-1.089-.745.083-.729.083-.729 1.205.084 1.839 1.237 1.839 1.237 1.07 1.834 2.807 '
           '1.304 3.492.997.107-.775.418-1.305.762-1.604-2.665-.305-5.467-1.334-5.467-5.931 0-1.311.469'
           '-2.381 1.236-3.221-.124-.303-.535-1.524.117-3.176 0 0 1.008-.322 3.301 1.23.957-.266 1.983-.399 '
           '3.003-.404 1.02.005 2.047.138 3.006.404 2.291-1.552 3.297-1.23 3.297-1.23.653 1.653.242 2.874.118 '
           '3.176.77.84 1.235 1.911 1.235 3.221 0 4.609-2.807 5.624-5.479 5.921.43.372.823 1.102.823 '
           '2.222v3.293c0 .319.192.694.801.576 4.765-1.589 8.199-6.086 8.199-11.386 0-6.627-5.373-12-12-12z"/></svg>')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="site")
    ap.add_argument("--logo", default="design/ClashFest.png")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    copy_logo(Path(args.logo), out / "logo.png")

    rels = gh_releases()
    latest = rels[0] if rels else None
    ver = latest["tagName"].lstrip("v") if latest else "—"
    date = fmt_date(latest["publishedAt"]) if latest else ""
    dev = gh_dev_build()

    releases_html = "".join(
        f'<div class="rel"><h3>{html.escape(r.get("name") or r["tagName"])}'
        f'<span>{fmt_date(r["publishedAt"])}</span></h3>'
        f'<div class="notes">{md_lite(r.get("body") or "")}</div></div>'
        for r in rels
    ) or '<p class="muted">Release feed unavailable.</p>'

    if dev and dev["assets"]:
        sha = f" · {html.escape(dev['sha'])}" if dev["sha"] else ""
        dev_links = "".join(
            f'<a href="{html.escape(dev["assets"][k])}">{label}</a>'
            for k, label in (("arm64-v8a", "arm64-v8a"), ("armeabi-v7a", "armeabi-v7a"),
                             ("universal", "universal"), ("x86_64", "x86_64"), ("x86", "x86"))
            if k in dev["assets"]
        )
        dev_html = (f'<div class="card"><h3>🧪 Dev build</h3>'
                    f'<p>Automatic build from <code>dev</code>{sha} · {fmt_date(dev["date"])}. '
                    f'Debug-signed: install alongside, not over, the release.</p>{dev_links}'
                    f'<a href="{DEV_PAGE}">What changed →</a></div>')
    else:
        dev_html = ""

    page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ClashFest — downloads</title>
<meta name="description" content="ClashFest — Clash Meta (Mihomo) client for Android phones and Android TV. Downloads and release notes.">
<link rel="icon" href="logo.png">
<style>{CSS}</style></head><body><div class="wrap">
<header>
<div class="hdrlinks">
<a href="https://github.com/{REPO}/wiki" title="Wiki / Docs" aria-label="Wiki / Docs">{ICON_WIKI}</a>
<a href="https://t.me/nemux_dev" title="Telegram group" aria-label="Telegram group">{ICON_TG}</a>
<a href="https://github.com/{REPO}" title="GitHub" aria-label="GitHub">{ICON_GH}</a>
</div>
<img src="logo.png" alt="ClashFest">
<div><h1>ClashFest<small>Clash Meta (Mihomo) client for Android — phone &amp; Android TV</small></h1>
<div class="badge">🦥 latest: v{html.escape(ver)}{' · ' + date if date else ''}</div></div></header>

<h2>Downloads</h2>
<div class="grid">
<div class="card primary"><h3>📱 Phone &amp; tablet</h3>
<p>Every Android phone made in the last decade is 64-bit ARM. Not sure? Take this one.</p>
<a class="btn" href="{DL}/clashfest-alpha-arm64-v8a.apk">⬇ Download APK (arm64-v8a)</a>
<a href="{DL}/clashfest-alpha-armeabi-v7a.apk">32-bit ARM (armeabi-v7a) — very old phones, some TV boxes</a></div>
<div class="card"><h3>📺 Android TV, emulators, Chromebooks</h3>
<p>Pick by CPU, or take the universal build that carries all of them.</p>
<a href="{DL}/clashfest-alpha-universal.apk">Universal (all ABIs, largest)</a>
<a href="{DL}/clashfest-alpha-x86_64.apk">x86_64 — emulators, Chromebooks, x86 TV boxes</a>
<a href="{DL}/clashfest-alpha-x86.apk">x86 — 32-bit Intel</a></div>
{dev_html}
</div>
<p class="muted">Links always point at the newest release. Minimum Android 5.0; the app checks for updates itself under <b>Settings → About &amp; updates</b>.</p>

<h2>Releases</h2>
{releases_html}

<footer>🦥 <a href="https://github.com/{REPO}">GitHub</a> ·
<a href="https://github.com/{REPO}/wiki">Wiki</a> ·
<a href="https://t.me/nemux_dev">Telegram</a> ·
<a href="https://github.com/{REPO}/releases">All releases</a> · GPL-3.0</footer>
</div></body></html>
"""
    (out / "index.html").write_text(page, encoding="utf-8")
    (out / ".nojekyll").write_text("", encoding="utf-8")
    print(f"wrote {out / 'index.html'} ({len(page)} bytes, {len(rels)} releases, dev={'yes' if dev_html else 'no'})")


if __name__ == "__main__":
    main()
