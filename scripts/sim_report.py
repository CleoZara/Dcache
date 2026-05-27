#!/usr/bin/env python3
"""Generate an HTML report with selected DCacheTop VCD waveforms."""

from __future__ import annotations

import argparse
import html
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET


DUT_NAME = "DCacheTop"
INCLUDED_SUITE = "cache.DCacheTopSpec"
WAVE_EXTENSIONS = {".vcd", ".fst", ".lxt", ".ghw", ".wlf", ".vpd"}

TEST_SIGNAL_GROUPS = {
    "T01": ["clock", "io.addr", "io.memRen", "io.stall", "missFsm.io.mem.req.valid", "missFsm.io.mem.resp.valid", "missFsm.io.refillEn", "missFsm.io.refillDone", "io.rdata"],
    "T02": ["clock", "io.addr", "io.memRen", "io.stall", "io.rdata", "missFsm.io.mem.req.valid", "missFsm.state"],
    "T03": ["clock", "io.wen", "io.wdata", "io.stall", "missFsm.io.refillDone", "dataArray.io.hitWen", "io.rdata"],
    "T04": ["clock", "io.wen", "io.wmask", "io.wdata", "io.stall", "dataArray.io.hitWen", "io.rdata"],
    "T05": ["clock", "io.stall", "missFsm.io.mem.req.valid", "missFsm.io.mem.req.bits.wen", "missFsm.io.mem.req.bits.addr", "missFsm.io.mem.req.bits.wdata", "missFsm.io.refillDone", "missFsm.state"],
    "T06": ["clock", "io.flush", "io.stall", "io.addr", "tagArray.io.tagData_0_valid", "missFsm.io.refillDone"],
    "T07": ["clock", "io.addr", "io.wen", "io.memWd", "io.printChar.valid", "io.printChar.bits", "io.stall", "missFsm.io.mem.req.valid"],
    "T08": ["clock", "io.addr", "io.memRen", "io.mtimeLo", "io.mtimeHi", "io.rdata", "io.stall"],
    "T09": ["clock", "io.memWd", "io.signed", "io.addr", "loadExt.io.rawData_hitWay", "io.rdata", "io.stall"],
    "T10": ["clock", "io.memWd", "io.signed", "io.addr", "io.rdata", "io.stall"],
    "T11": ["clock", "io.addr", "missFsm.io.refillWord", "missFsm.io.refillEn", "missFsm.io.refillDone", "io.stall", "io.rdata"],
    "T12": ["clock", "io.stall", "missFsm.state", "missFsm.io.refillDone", "io.rdata", "io.memRen"],
    "T13": ["clock", "io.addr", "plru.io.evictWay", "plru.io.updateEn", "missFsm.io.refillDone", "io.stall"],
    "T14": ["clock", "missFsm.io.refillIsStore", "tagArray.io.refillDirty", "missFsm.io.mem.req.bits.wen", "missFsm.io.mem.req.bits.wdata", "missFsm.state", "io.stall"],
    "T15": ["clock", "io.addr", "io.memWd", "io.signed", "io.rdata", "io.stall"],
    "T16": ["clock", "io.addr", "io.memWd", "io.signed", "io.rdata", "io.stall"],
    "T17": ["clock", "missFsm.state", "missFsm.io.mem.req.bits.wen", "missFsm.io.mem.req.valid", "io.stall", "missFsm.io.refillDone"],
    "T18": ["clock", "io.addr", "io.wen", "io.stall", "missFsm.io.mem.req.valid", "io.mtimeLo", "io.rdata"],
    "T19": ["clock", "io.addr", "io.wen", "missFsm.io.refillWord", "dataArray.io.hitWen", "io.rdata"],
    "T20": ["clock", "missFsm.io.mem.req.bits.addr", "missFsm.io.mem.req.bits.wdata", "missFsm.io.mem.req.bits.wen", "missFsm.state", "missFsm.io.refillDone", "io.stall"],
    "T21": ["clock", "io.flush", "io.addr", "io.stall", "tagArray.io.tagData_0_valid", "missFsm.state"],
}

DEFAULT_SIGNALS = ["clock", "io.addr", "io.memRen", "io.wen", "io.stall", "io.rdata"]


def rel(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def test_id(text: str) -> str | None:
    match = re.search(r"\b(T(?:0[1-9]|1[0-9]|2[0-1]))\b", text)
    return match.group(1) if match else None


def parse_junit(report_dir: Path) -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    suites: list[dict[str, object]] = []
    cases: list[dict[str, object]] = []
    xml_path = report_dir / "TEST-cache.DCacheTopSpec.xml"
    if not xml_path.exists():
        return suites, cases

    root = ET.parse(xml_path).getroot()
    suite = {
        "name": root.attrib.get("name", INCLUDED_SUITE),
        "tests": int(root.attrib.get("tests", "0")),
        "failures": int(root.attrib.get("failures", "0")),
        "errors": int(root.attrib.get("errors", "0")),
        "skipped": int(root.attrib.get("skipped", "0")),
        "time": float(root.attrib.get("time", "0")),
    }
    suites.append(suite)

    for node in root.findall("testcase"):
        failed = node.find("failure") is not None or node.find("error") is not None
        skipped = node.find("skipped") is not None
        cases.append({
            "suite": suite["name"],
            "name": node.attrib.get("name", ""),
            "time": float(node.attrib.get("time", "0")),
            "status": "SKIPPED" if skipped else ("FAILED" if failed else "PASSED"),
        })
    return suites, sorted(cases, key=lambda item: test_id(str(item["name"])) or str(item["name"]))


def find_waveforms(root: Path) -> list[Path]:
    matches: list[Path] = []
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            path = Path(dirpath) / name
            if path.suffix.lower() in WAVE_EXTENSIONS and "dcachecore" not in path.as_posix().lower():
                matches.append(path)
    return sorted(matches)


def run_dir_for(test_run_dir: Path, tid: str) -> Path | None:
    if not test_run_dir.exists():
        return None
    matches = [
        path for path in test_run_dir.iterdir()
        if path.is_dir() and f"_{tid.lower()}_" in f"_{path.name.lower()}_" and "dcachecore" not in path.name.lower()
    ]
    return sorted(matches)[0] if matches else None


def vcd_for(run_dir: Path | None) -> Path | None:
    if run_dir is None:
        return None
    preferred = run_dir / f"{DUT_NAME}.vcd"
    if preferred.exists():
        return preferred
    candidates = sorted(path for path in run_dir.rglob("*.vcd") if "dcachecore" not in path.as_posix().lower())
    return candidates[0] if candidates else None


def parse_vcd(path: Path) -> tuple[dict[str, dict[str, object]], dict[str, list[tuple[int, str]]], int]:
    signals: dict[str, dict[str, object]] = {}
    changes: dict[str, list[tuple[int, str]]] = {}
    scopes: list[str] = []
    now = 0
    max_time = 0
    header = True

    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.strip()
            if not line:
                continue
            if header:
                if line.startswith("$scope"):
                    parts = line.split()
                    if len(parts) >= 3:
                        scopes.append(parts[2])
                elif line.startswith("$upscope"):
                    if scopes:
                        scopes.pop()
                elif line.startswith("$var"):
                    parts = line.split()
                    if len(parts) >= 5:
                        width = int(parts[2])
                        code = parts[3]
                        name = parts[4]
                        full = ".".join(scopes + [name])
                        signals.setdefault(code, {"width": width, "names": []})
                        signals[code]["names"].append(full)
                        changes.setdefault(code, [])
                elif line.startswith("$enddefinitions"):
                    header = False
                continue
            if line.startswith("#"):
                now = int(line[1:])
                max_time = max(max_time, now)
            elif line[0] in "01xz":
                code = line[1:]
                if code in changes:
                    changes[code].append((now, line[0]))
            elif line[0] in "bBrR":
                parts = line.split()
                if len(parts) == 2 and parts[1] in changes:
                    changes[parts[1]].append((now, parts[0][1:]))
    return signals, changes, max_time


def canon(name: str) -> str:
    name = name.strip().strip("\\").replace("[", "_").replace("]", "")
    return re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_").lower()


def aliases(name: str) -> set[str]:
    flat = name.replace(".", "_")
    items = {name, flat, f"TOP.{name}", f"TOP.{flat}", f"TOP.{DUT_NAME}.{name}", f"TOP.{DUT_NAME}.{flat}"}
    if name.startswith("io."):
        io_flat = "io_" + name[3:].replace(".", "_")
        items |= {io_flat, f"TOP.{DUT_NAME}.{io_flat}"}
    return {canon(item) for item in items}


def resolve(signals: dict[str, dict[str, object]], name: str) -> tuple[str, dict[str, object]] | None:
    wanted = aliases(name)
    candidates: list[tuple[int, str, dict[str, object]]] = []
    for code, meta in signals.items():
        for signal_name in meta["names"]:
            c = canon(str(signal_name))
            if c in wanted or any(c.endswith("_" + item) or c.endswith(item) for item in wanted):
                candidates.append((len(str(signal_name)), code, meta))
                break
    if not candidates:
        return None
    _, code, meta = sorted(candidates, key=lambda item: item[0])[0]
    return code, meta


def value_at(changes: list[tuple[int, str]], time: int) -> str | None:
    value: str | None = None
    for t, v in changes:
        if t > time:
            break
        value = v
    return value


def fmt(value: str | None, width: int) -> str:
    if value is None:
        return "x"
    if any(ch in value.lower() for ch in "xz"):
        return value.lower()
    if width == 1:
        return value[-1]
    return f"0x{int(value, 2):X}"


def segments(changes: list[tuple[int, str]], end_time: int) -> list[tuple[int, int, str]]:
    if not changes:
        return [(0, end_time, "x")]
    out: list[tuple[int, int, str]] = []
    last_t = 0
    last_v = changes[0][1] if changes[0][0] == 0 else "x"
    for t, v in changes:
        if t > last_t:
            out.append((last_t, t, last_v))
        last_t, last_v = t, v
    out.append((last_t, end_time, last_v))
    return [(a, b, v) for a, b, v in out if b >= a]


def draw_signal(changes: list[tuple[int, str]], width: int, max_time: int, x0: int, y0: int, plot_w: int) -> str:
    scale = plot_w / max(max_time, 1)
    if width == 1:
        pts: list[str] = []
        prev_y = y0 + 18
        for start, end, value in segments(changes, max_time):
            xs, xe = x0 + start * scale, x0 + end * scale
            y = y0 + 4 if value == "1" else y0 + 18 if value == "0" else y0 + 11
            if pts:
                pts += [f"{xs:.1f},{prev_y:.1f}", f"{xs:.1f},{y:.1f}"]
            else:
                pts.append(f"{xs:.1f},{y:.1f}")
            pts.append(f"{xe:.1f},{y:.1f}")
            prev_y = y
        return f'<polyline points="{" ".join(pts)}" class="wave-line" />'

    parts: list[str] = []
    palette = ["#eff6ff", "#f0fdf4", "#fff7ed", "#fdf2f8", "#f5f3ff"]
    for idx, (start, end, value) in enumerate(segments(changes, max_time)):
        xs, xe = x0 + start * scale, x0 + end * scale
        w = max(1, xe - xs)
        label = html.escape(fmt(value, width))
        fill = "#f3f4f6" if any(ch in label.lower() for ch in "xz") else palette[idx % len(palette)]
        parts.append(f'<rect x="{xs:.1f}" y="{y0 + 3}" width="{w:.1f}" height="18" fill="{fill}" stroke="#94a3b8" />')
        if w > 36:
            parts.append(f'<text x="{xs + 4:.1f}" y="{y0 + 16}" class="wave-value">{label}</text>')
    return "".join(parts)


def render_wave(vcd: Path, signal_names: list[str]) -> str:
    signals, changes, max_time = parse_vcd(vcd)
    selected: list[tuple[str, str, int, list[tuple[int, str]]]] = []
    missing: list[str] = []
    for name in signal_names:
        found = resolve(signals, name)
        if found is None:
            missing.append(name)
        else:
            code, meta = found
            selected.append((name, code, int(meta["width"]), changes.get(code, [])))

    missing_html = ""
    if missing:
        missing_html = '<p class="missing">Missing selected signals: ' + ", ".join(f"<code>{html.escape(x)}</code>" for x in missing) + "</p>"
    if not selected:
        return missing_html + '<p class="muted">No selected signals were found in this VCD.</p>'

    label_w, plot_w, row_h, top = 220, 980, 30, 30
    height = top + row_h * len(selected) + 30
    width = label_w + plot_w + 70
    parts = [
        missing_html,
        f'<svg class="waveform" viewBox="0 0 {width} {height}">',
        f'<text x="{label_w}" y="16" class="wave-title">0 to {max_time} ps</text>',
        f'<line x1="{label_w}" y1="{top - 8}" x2="{label_w + plot_w}" y2="{top - 8}" class="axis" />',
    ]
    for tick in range(6):
        x = label_w + plot_w * tick / 5
        t = int(max_time * tick / 5)
        parts.append(f'<line x1="{x:.1f}" y1="{top - 12}" x2="{x:.1f}" y2="{height - 20}" class="grid" />')
        parts.append(f'<text x="{x + 2:.1f}" y="{height - 6}" class="tick">{t}</text>')
    for idx, (label, _, bits, change_list) in enumerate(selected):
        y = top + idx * row_h
        parts.append(f'<text x="8" y="{y + 17}" class="signal-label">{html.escape(label)}</text>')
        parts.append(f'<line x1="{label_w}" y1="{y + 22}" x2="{label_w + plot_w}" y2="{y + 22}" class="row-line" />')
        parts.append(draw_signal(change_list, bits, max_time, label_w, y, plot_w))
        parts.append(f'<text x="{label_w + plot_w + 8}" y="{y + 17}" class="final-value">{html.escape(fmt(value_at(change_list, max_time), bits))}</text>')
    parts.append("</svg>")
    return "".join(parts)


def bar(value: float, max_value: float) -> str:
    width = 0 if max_value <= 0 else max(2, int((value / max_value) * 260))
    return f'<span class="bar"><span style="width:{width}px"></span></span>'


def render(root: Path, output: Path) -> None:
    suites, cases = parse_junit(root / "target" / "test-reports")
    waves = find_waveforms(root)
    max_case_time = max((float(case["time"]) for case in cases), default=0.0)
    test_run_dir = root / "test_run_dir"

    rows: list[str] = []
    wave_sections: list[str] = []
    for case in cases:
        tid = test_id(str(case["name"]))
        run_dir = run_dir_for(test_run_dir, tid) if tid else None
        vcd = vcd_for(run_dir)
        link = ""
        if tid and vcd:
            link = f'<a href="#wave-{tid}">wave</a>'
            wave_sections.append(
                '<details class="wave-card" open>'
                f'<summary id="wave-{tid}">{html.escape(tid)} - {html.escape(str(case["name"]))}</summary>'
                f'<div class="wave-path">{html.escape(rel(vcd, root))}</div>'
                f'{render_wave(vcd, TEST_SIGNAL_GROUPS.get(tid, DEFAULT_SIGNALS))}'
                '</details>'
            )
        elif tid:
            link = '<span class="muted">no DCacheTop VCD</span>'
        status = html.escape(str(case["status"]))
        rows.append(
            "<tr>"
            f'<td><span class="status {status.lower()}">{status}</span></td>'
            f'<td>{html.escape(str(case["name"]))}</td>'
            f'<td>{html.escape(str(case["suite"]))}</td>'
            f'<td>{float(case["time"]):.3f}s</td>'
            f'<td>{bar(float(case["time"]), max_case_time)}</td>'
            f'<td>{link}</td>'
            "</tr>"
        )
    if not rows:
        rows.append('<tr><td colspan="6" class="muted">No DCacheTopSpec JUnit test cases were found.</td></tr>')

    wave_rows = "".join(f"<tr><td>{html.escape(rel(w, root))}</td><td>{w.stat().st_size:,}</td></tr>" for w in waves)
    if not wave_rows:
        wave_rows = '<tr><td colspan="2" class="muted">No DCacheTop waveform files were found.</td></tr>'

    runs = sorted(path for path in test_run_dir.iterdir() if test_run_dir.exists() and path.is_dir() and "dcachecore" not in path.name.lower())
    run_rows = "".join(
        f"<tr><td>{html.escape(path.name)}</td><td>{'VCD' if (path / f'{DUT_NAME}.vcd').exists() else '-'}</td><td>{sum(1 for p in path.rglob('*') if p.is_file())}</td></tr>"
        for path in runs
    )

    total_tests = sum(int(s["tests"]) for s in suites)
    failures = sum(int(s["failures"]) for s in suites)
    errors = sum(int(s["errors"]) for s in suites)
    skipped = sum(int(s["skipped"]) for s in suites)
    total_time = sum(float(s["time"]) for s in suites)

    output.write_text(f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>DCacheTop Simulation Report</title>
  <style>
    body {{ font-family: Arial, sans-serif; margin: 32px; color: #1f2937; }}
    h1 {{ margin-bottom: 4px; }}
    h2 {{ margin-top: 32px; }}
    .summary {{ display: flex; gap: 12px; flex-wrap: wrap; margin: 20px 0; }}
    .card {{ border: 1px solid #d1d5db; border-radius: 6px; padding: 12px 14px; min-width: 120px; }}
    .metric {{ font-size: 24px; font-weight: 700; }}
    .label, .muted {{ color: #6b7280; }}
    table {{ border-collapse: collapse; width: 100%; margin-top: 12px; }}
    th, td {{ border-bottom: 1px solid #e5e7eb; padding: 8px; text-align: left; vertical-align: top; }}
    th {{ background: #f9fafb; }}
    .status {{ font-size: 12px; font-weight: 700; padding: 2px 6px; border-radius: 4px; }}
    .passed {{ background: #dcfce7; color: #166534; }}
    .failed {{ background: #fee2e2; color: #991b1b; }}
    .skipped {{ background: #fef3c7; color: #92400e; }}
    .bar {{ display: inline-block; width: 260px; height: 10px; background: #e5e7eb; border-radius: 999px; }}
    .bar span {{ display: block; height: 10px; background: #2563eb; border-radius: 999px; }}
    code {{ background: #f3f4f6; padding: 2px 4px; border-radius: 4px; }}
    a {{ color: #2563eb; }}
    .missing {{ color: #92400e; background: #fffbeb; border: 1px solid #fde68a; border-radius: 6px; padding: 8px; }}
    .wave-card {{ border: 1px solid #d1d5db; border-radius: 6px; margin: 14px 0; padding: 10px 12px; }}
    .wave-card summary {{ cursor: pointer; font-weight: 700; }}
    .wave-path {{ color: #6b7280; font-size: 12px; margin: 6px 0 10px; }}
    .waveform {{ display: block; width: 100%; max-width: 1280px; background: #ffffff; border: 1px solid #e5e7eb; }}
    .wave-line {{ fill: none; stroke: #111827; stroke-width: 2; }}
    .axis {{ stroke: #475569; stroke-width: 1; }}
    .grid {{ stroke: #e5e7eb; stroke-width: 1; }}
    .row-line {{ stroke: #f1f5f9; stroke-width: 1; }}
    .signal-label, .wave-value, .final-value, .wave-title, .tick {{ font-family: Consolas, monospace; font-size: 11px; fill: #111827; }}
    .final-value, .wave-title, .tick {{ fill: #64748b; }}
  </style>
</head>
<body>
  <h1>DCacheTop Simulation Report</h1>
  <p>Generated from <code>target/test-reports/TEST-cache.DCacheTopSpec.xml</code> and <code>test_run_dir</code>.</p>
  <div class="summary">
    <div class="card"><div class="metric">{total_tests}</div><div class="label">Tests</div></div>
    <div class="card"><div class="metric">{failures}</div><div class="label">Failures</div></div>
    <div class="card"><div class="metric">{errors}</div><div class="label">Errors</div></div>
    <div class="card"><div class="metric">{skipped}</div><div class="label">Skipped</div></div>
    <div class="card"><div class="metric">{total_time:.1f}s</div><div class="label">Reported Time</div></div>
    <div class="card"><div class="metric">{len(waves)}</div><div class="label">Waveforms</div></div>
  </div>
  <h2>Test Cases</h2>
  <table><thead><tr><th>Status</th><th>Test</th><th>Suite</th><th>Time</th><th>Relative Time</th><th>Wave</th></tr></thead><tbody>{''.join(rows)}</tbody></table>
  <h2>Selected Waveforms</h2>
  {''.join(wave_sections) if wave_sections else '<p class="muted">No per-test DCacheTop VCD waveforms were found.</p>'}
  <h2>Waveform Files</h2>
  <table><thead><tr><th>Path</th><th>Bytes</th></tr></thead><tbody>{wave_rows}</tbody></table>
  <h2>Run Directories</h2>
  <table><thead><tr><th>Directory</th><th>Artifacts</th><th>Files</th></tr></thead><tbody>{run_rows}</tbody></table>
</body>
</html>
""", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".", help="Workspace root")
    parser.add_argument("--out", "--output", default="sim_report.html", help="Output HTML path")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    output = Path(args.out)
    if not output.is_absolute():
        output = root / output
    render(root, output)
    print(output)


if __name__ == "__main__":
    main()
