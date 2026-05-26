#!/usr/bin/env python3
"""Generate a small HTML report from ChiselTest/ScalaTest simulation artifacts."""

from __future__ import annotations

import argparse
import html
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET


WAVE_EXTENSIONS = {".vcd", ".fst", ".lxt", ".ghw", ".wlf", ".vpd"}
EXCLUDED_SUITES = {"cache.DCacheCoreComplexTest", "cache.DCacheCoreTest"}
WAVEFORM_EXTENSIONS = {".vcd"}

TEST_SIGNAL_GROUPS = {
    "T01": ["clock", "io_flush", "io_memRen", "io_missOut", "io_missValid", "tagArray_io_flush", "tagArray_io_tagData_0_valid"],
    "T02": ["clock", "io_addr", "io_memRen", "io_memWd", "io_signed", "hitTest_io_isHit", "hitTest_io_hitWay", "loadExt_io_rdata", "io_rdata"],
    "T03": ["clock", "io_wen", "io_wmask", "io_wdata", "dataArray_io_hitWen", "dataArray_io_wdata", "io_rdata"],
    "T04": ["clock", "io_wen", "tagArray_io_setDirtyEn", "io_flush", "io_missOut", "io_evictWay", "io_evictDirty"],
    "T05": ["clock", "io_memRen", "io_missOut", "io_refillEn", "io_refillWord", "io_refillDone", "tagArray_io_refillTagEn", "hitTest_io_isHit"],
    "T06": ["clock", "io_refillEn", "io_refillWord", "io_refillData", "io_refillDone", "io_memRen", "io_rdata"],
    "T07": ["clock", "io_wen", "io_missOut", "io_refillIsStore", "io_refillDone", "tagArray_io_refillDirty", "io_evictDirty"],
    "T08": ["clock", "io_wen", "dataArray_io_hitWen", "io_missOut", "io_evictWay", "io_evictDirty", "io_evictTag", "io_evictLine_0", "io_evictLine_3"],
    "T09": ["clock", "io_refillDone", "io_refillWay", "plru_io_updateEn", "plru_io_updateWay", "plru_io_evictWay", "io_evictWay"],
    "T10": ["clock", "io_memRen", "io_wen", "plru_io_updateEn", "plru_io_updateWay", "plru_io_evictWay", "io_evictWay"],
    "T11": ["clock", "io_refillEn", "io_refillWord", "io_refillDone", "plru_io_updateEn", "plru_io_updateWay", "plru_io_evictWay"],
    "T12": ["clock", "io_addr", "io_memRen", "io_wen", "hitTest_io_missValid", "io_missOut", "io_missValid"],
    "T13": ["clock", "io_addr", "io_refillIdx", "io_refillDone", "hitTest_io_hitWay", "io_rdata"],
    "T14": ["clock", "io_addr", "hitTest_io_isHit", "hitTest_io_hitWay", "dataArray_io_rawData_0", "dataArray_io_rawData_1", "dataArray_io_rawData_2", "dataArray_io_rawData_3", "io_rdata"],
    "T15": ["clock", "io_memRen", "io_missOut", "io_evictWay", "io_evictLine_0", "io_evictLine_1", "io_evictLine_15"],
    "T16": ["clock", "io_addr", "io_wen", "io_memWd", "isBypass", "isPrintf", "io_printChar_valid", "io_printChar_bits", "io_missOut"],
    "T17": ["clock", "io_addr", "io_wen", "io_memWd", "isBypass", "isPrintf", "io_printChar_valid", "io_missOut"],
    "T18": ["clock", "io_addr", "io_memRen", "io_memWd", "isBypass", "addrIsPrintf", "io_printChar_valid", "io_rdata", "io_missOut"],
    "T19": ["clock", "io_addr", "io_memRen", "isBypass", "isMtimeLo", "isMtimeHi", "io_mtimeLo", "io_mtimeHi", "io_rdata", "io_missOut"],
    "T20": ["clock", "io_addr", "io_wen", "io_wdata", "isBypass", "io_missOut", "io_missValid", "io_mtimeLo", "io_rdata"],
    "T21": ["clock", "io_addr", "isBypass", "io_printChar_valid", "io_mtimeLo", "plru_io_updateEn", "io_evictWay", "io_rdata"],
}

DEFAULT_SIGNALS = ["clock", "io_addr", "io_memRen", "io_wen", "io_missOut", "io_missValid", "io_rdata"]


def find_files(root: Path, extensions: set[str]) -> list[Path]:
    matches: list[Path] = []
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            path = Path(dirpath) / name
            if path.suffix.lower() in extensions:
                matches.append(path)
    return sorted(matches)


def parse_junit_reports(report_dir: Path) -> tuple[list[dict[str, object]], list[dict[str, object]]]:
    suites: list[dict[str, object]] = []
    cases: list[dict[str, object]] = []

    for xml_path in sorted(report_dir.glob("TEST-*.xml")):
        root = ET.parse(xml_path).getroot()
        suite = {
            "name": root.attrib.get("name", xml_path.stem),
            "tests": int(root.attrib.get("tests", "0")),
            "failures": int(root.attrib.get("failures", "0")),
            "errors": int(root.attrib.get("errors", "0")),
            "skipped": int(root.attrib.get("skipped", "0")),
            "time": float(root.attrib.get("time", "0")),
            "path": xml_path,
        }
        if suite["name"] in EXCLUDED_SUITES:
            continue

        suites.append(suite)

        for test_case in root.findall("testcase"):
            failed = test_case.find("failure") is not None or test_case.find("error") is not None
            skipped = test_case.find("skipped") is not None
            cases.append(
                {
                    "suite": suite["name"],
                    "name": test_case.attrib.get("name", ""),
                    "classname": test_case.attrib.get("classname", ""),
                    "time": float(test_case.attrib.get("time", "0")),
                    "status": "SKIPPED" if skipped else ("FAILED" if failed else "PASSED"),
                    "path": xml_path,
                }
            )

    return suites, sorted(cases, key=lambda item: float(item["time"]), reverse=True)


def collect_run_dirs(test_run_dir: Path) -> list[dict[str, object]]:
    if not test_run_dir.exists():
        return []

    runs: list[dict[str, object]] = []
    for path in sorted(p for p in test_run_dir.iterdir() if p.is_dir()):
        files = list(path.rglob("*"))
        runs.append(
            {
                "name": path.name,
                "path": path,
                "has_sv": (path / "DCacheCore.sv").exists(),
                "has_fir": (path / "DCacheCore.lo.fir").exists(),
                "has_coverage": (path / "coverage.dat").exists(),
                "file_count": sum(1 for file_path in files if file_path.is_file()),
            }
        )
    return runs


def rel(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return path.as_posix()


def render_bar(value: float, max_value: float) -> str:
    width = 0 if max_value <= 0 else max(2, int((value / max_value) * 260))
    return f'<span class="bar"><span style="width:{width}px"></span></span>'


def extract_test_id(text: str) -> str | None:
    match = re.search(r"\b(T(?:0[1-9]|1[0-9]|2[0-1]))\b", text)
    return match.group(1) if match else None


def format_value(value: str | None, width: int) -> str:
    if value is None:
        return "x"
    if any(ch in value.lower() for ch in "xz"):
        return value.lower()
    if width == 1:
        return value[-1]
    try:
        return f"0x{int(value, 2):X}"
    except ValueError:
        return value


def parse_vcd(vcd_path: Path) -> tuple[dict[str, dict[str, object]], dict[str, list[tuple[int, str]]], int]:
    signals_by_code: dict[str, dict[str, object]] = {}
    signal_codes_by_name: dict[str, str] = {}
    changes: dict[str, list[tuple[int, str]]] = {}
    scopes: list[str] = []
    current_time = 0
    max_time = 0
    in_header = True

    with vcd_path.open("r", encoding="utf-8", errors="replace") as handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line:
                continue

            if in_header:
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
                        full_name = ".".join(scopes + [name])
                        if code not in signals_by_code:
                            signals_by_code[code] = {"width": width, "names": []}
                            changes[code] = []
                        signals_by_code[code]["names"].append(full_name)
                        signal_codes_by_name.setdefault(full_name, code)
                elif line.startswith("$enddefinitions"):
                    in_header = False
                continue

            if line.startswith("#"):
                current_time = int(line[1:])
                max_time = max(max_time, current_time)
            elif line[0] in "01xz":
                code = line[1:]
                if code in changes:
                    changes[code].append((current_time, line[0]))
            elif line[0] in "bBrR":
                parts = line.split()
                if len(parts) == 2:
                    value = parts[0][1:]
                    code = parts[1]
                    if code in changes:
                        changes[code].append((current_time, value))

    return signals_by_code, changes, max_time


def resolve_signal_code(signals_by_code: dict[str, dict[str, object]], signal_name: str) -> tuple[str, dict[str, object]] | None:
    exact_candidates = [
        f"TOP.{signal_name}",
        f"TOP.DCacheCore.{signal_name}",
        signal_name,
    ]
    for code, meta in signals_by_code.items():
        names = meta["names"]
        for candidate in exact_candidates:
            if candidate in names:
                return code, meta
    for code, meta in signals_by_code.items():
        names = meta["names"]
        if any(name.endswith(f".{signal_name}") for name in names):
            return code, meta
    return None


def value_at(changes: list[tuple[int, str]], time: int) -> str | None:
    result: str | None = None
    for change_time, value in changes:
        if change_time > time:
            break
        result = value
    return result


def segment_changes(changes: list[tuple[int, str]], end_time: int) -> list[tuple[int, int, str]]:
    if not changes:
        return [(0, end_time, "x")]
    segments: list[tuple[int, int, str]] = []
    last_time = 0
    last_value = changes[0][1] if changes[0][0] == 0 else "x"
    for change_time, value in changes:
        if change_time > last_time:
            segments.append((last_time, change_time, last_value))
        last_time = change_time
        last_value = value
    segments.append((last_time, end_time, last_value))
    return [(start, end, value) for start, end, value in segments if end >= start]


def draw_digital_signal(changes: list[tuple[int, str]], width: int, max_time: int, x0: int, y0: int, plot_width: int) -> str:
    high_y = y0 + 4
    low_y = y0 + 18
    mid_y = y0 + 11
    scale = plot_width / max(max_time, 1)
    parts: list[str] = []

    if width == 1:
        points: list[str] = []
        previous_x = x0
        previous_y = low_y
        for start, end, value in segment_changes(changes, max_time):
            x_start = x0 + start * scale
            x_end = x0 + end * scale
            y = high_y if value == "1" else low_y if value == "0" else mid_y
            if not points:
                points.append(f"{x_start:.1f},{y:.1f}")
            else:
                points.append(f"{x_start:.1f},{previous_y:.1f}")
                points.append(f"{x_start:.1f},{y:.1f}")
            points.append(f"{x_end:.1f},{y:.1f}")
            previous_x = x_end
            previous_y = y
        parts.append(f'<polyline points="{" ".join(points)}" class="wave-line" />')
        return "".join(parts)

    palette = ["#eff6ff", "#f0fdf4", "#fff7ed", "#fdf2f8", "#f5f3ff"]
    for idx, (start, end, value) in enumerate(segment_changes(changes, max_time)):
        x_start = x0 + start * scale
        x_end = x0 + end * scale
        width_px = max(1, x_end - x_start)
        label = html.escape(format_value(value, width))
        fill = "#f3f4f6" if any(ch in label.lower() for ch in "xz") else palette[idx % len(palette)]
        parts.append(
            f'<rect x="{x_start:.1f}" y="{y0 + 3}" width="{width_px:.1f}" height="18" '
            f'fill="{fill}" stroke="#94a3b8" stroke-width="1" />'
        )
        if width_px > 36:
            parts.append(
                f'<text x="{x_start + 4:.1f}" y="{y0 + 16}" class="wave-value">{label}</text>'
            )
    return "".join(parts)


def render_waveform_svg(vcd_path: Path, signal_names: list[str]) -> str:
    signals_by_code, changes_by_code, max_time = parse_vcd(vcd_path)
    selected: list[tuple[str, str, int, list[tuple[int, str]]]] = []
    for signal_name in signal_names:
        resolved = resolve_signal_code(signals_by_code, signal_name)
        if resolved is None:
            continue
        code, meta = resolved
        selected.append((signal_name, code, int(meta["width"]), changes_by_code.get(code, [])))

    if not selected:
        return '<p class="muted">No selected signals were found in this VCD.</p>'

    label_width = 190
    plot_width = 980
    row_height = 30
    top = 30
    height = top + row_height * len(selected) + 30
    width = label_width + plot_width + 30
    time_label = f"0 to {max_time} ps"
    parts = [
        f'<svg class="waveform" viewBox="0 0 {width} {height}" role="img" '
        f'aria-label="Waveform {html.escape(vcd_path.parent.name)}">',
        f'<text x="{label_width}" y="16" class="wave-title">{html.escape(time_label)}</text>',
        f'<line x1="{label_width}" y1="{top - 8}" x2="{label_width + plot_width}" y2="{top - 8}" class="axis" />',
    ]

    for tick in range(0, 6):
        x = label_width + plot_width * tick / 5
        value = int(max_time * tick / 5)
        parts.append(f'<line x1="{x:.1f}" y1="{top - 12}" x2="{x:.1f}" y2="{height - 20}" class="grid" />')
        parts.append(f'<text x="{x + 2:.1f}" y="{height - 6}" class="tick">{value}</text>')

    for idx, (label, _, width_bits, changes) in enumerate(selected):
        y = top + idx * row_height
        parts.append(f'<text x="8" y="{y + 17}" class="signal-label">{html.escape(label)}</text>')
        parts.append(f'<line x1="{label_width}" y1="{y + 22}" x2="{label_width + plot_width}" y2="{y + 22}" class="row-line" />')
        parts.append(draw_digital_signal(changes, width_bits, max_time, label_width, y, plot_width))
        final_value = format_value(value_at(changes, max_time), width_bits)
        parts.append(f'<text x="{label_width + plot_width + 8}" y="{y + 17}" class="final-value">{html.escape(final_value)}</text>')

    parts.append("</svg>")
    return "".join(parts)


def run_dir_for_test(test_run_dir: Path, test_id: str) -> Path | None:
    if not test_run_dir.exists():
        return None
    matches = sorted(path for path in test_run_dir.iterdir() if path.is_dir() and f"_{test_id}_" in f"_{path.name}_")
    return matches[0] if matches else None


def render_report(root: Path, output: Path) -> None:
    suites, cases = parse_junit_reports(root / "target" / "test-reports")
    test_run_dir = root / "test_run_dir"
    runs = collect_run_dirs(test_run_dir)
    waveforms = find_files(root, WAVE_EXTENSIONS)

    total_tests = sum(int(suite["tests"]) for suite in suites)
    failures = sum(int(suite["failures"]) for suite in suites)
    errors = sum(int(suite["errors"]) for suite in suites)
    skipped = sum(int(suite["skipped"]) for suite in suites)
    total_time = sum(float(suite["time"]) for suite in suites)
    max_case_time = max((float(case["time"]) for case in cases), default=0.0)

    rows = []
    waveform_sections = []
    for case in cases:
        status = html.escape(str(case["status"]))
        status_class = status.lower()
        case_name = html.escape(str(case["name"]))
        suite_name = html.escape(str(case["suite"]))
        time = float(case["time"])
        test_id = extract_test_id(str(case["name"]))
        run_dir = run_dir_for_test(test_run_dir, test_id) if test_id else None
        vcd_path = (run_dir / "DCacheCore.vcd") if run_dir else None
        waveform_link = ""
        if test_id and vcd_path and vcd_path.exists():
            waveform_link = f'<a href="#wave-{test_id}">wave</a>'
            selected_signals = TEST_SIGNAL_GROUPS.get(test_id, DEFAULT_SIGNALS)
            waveform_sections.append(
                "<details class=\"wave-card\" open>"
                f'<summary id="wave-{test_id}">{html.escape(test_id)} - {case_name}</summary>'
                f'<div class="wave-path">{html.escape(rel(vcd_path, root))}</div>'
                f'{render_waveform_svg(vcd_path, selected_signals)}'
                "</details>"
            )
        rows.append(
            "<tr>"
            f'<td><span class="status {status_class}">{status}</span></td>'
            f"<td>{case_name}</td>"
            f"<td>{suite_name}</td>"
            f"<td>{time:.3f}s</td>"
            f"<td>{render_bar(time, max_case_time)}</td>"
            f"<td>{waveform_link}</td>"
            "</tr>"
        )

    wave_rows = []
    if waveforms:
        for wave in waveforms:
            wave_rows.append(
                "<tr>"
                f"<td>{html.escape(rel(wave, root))}</td>"
                f"<td>{wave.stat().st_size:,}</td>"
                "</tr>"
            )
    else:
        wave_rows.append(
            '<tr><td colspan="2">No VCD/FST waveform files were found in this workspace.</td></tr>'
        )

    run_rows = []
    for run in runs:
        flags = []
        if run["has_sv"]:
            flags.append("SV")
        if run["has_fir"]:
            flags.append("FIR")
        if run["has_coverage"]:
            flags.append("coverage")
        run_rows.append(
            "<tr>"
            f"<td>{html.escape(str(run['name']))}</td>"
            f"<td>{', '.join(flags) if flags else '-'}</td>"
            f"<td>{run['file_count']}</td>"
            "</tr>"
        )

    content = f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>DCache Simulation Report</title>
  <style>
    body {{ font-family: Arial, sans-serif; margin: 32px; color: #1f2937; }}
    h1 {{ margin-bottom: 4px; }}
    h2 {{ margin-top: 32px; }}
    .summary {{ display: flex; gap: 12px; flex-wrap: wrap; margin: 20px 0; }}
    .card {{ border: 1px solid #d1d5db; border-radius: 6px; padding: 12px 14px; min-width: 120px; }}
    .metric {{ font-size: 24px; font-weight: 700; }}
    .label {{ color: #6b7280; font-size: 12px; }}
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
    .muted {{ color: #6b7280; }}
    .wave-card {{ border: 1px solid #d1d5db; border-radius: 6px; margin: 14px 0; padding: 10px 12px; }}
    .wave-card summary {{ cursor: pointer; font-weight: 700; }}
    .wave-path {{ color: #6b7280; font-size: 12px; margin: 6px 0 10px; }}
    .waveform {{ display: block; width: 100%; max-width: 1240px; background: #ffffff; border: 1px solid #e5e7eb; }}
    .wave-line {{ fill: none; stroke: #111827; stroke-width: 2; }}
    .axis {{ stroke: #475569; stroke-width: 1; }}
    .grid {{ stroke: #e5e7eb; stroke-width: 1; }}
    .row-line {{ stroke: #f1f5f9; stroke-width: 1; }}
    .signal-label {{ font-family: Consolas, monospace; font-size: 12px; fill: #111827; }}
    .wave-value {{ font-family: Consolas, monospace; font-size: 11px; fill: #111827; }}
    .final-value {{ font-family: Consolas, monospace; font-size: 11px; fill: #475569; }}
    .wave-title, .tick {{ font-family: Consolas, monospace; font-size: 11px; fill: #64748b; }}
  </style>
</head>
<body>
  <h1>DCache Simulation Report</h1>
  <p>Generated from <code>target/test-reports</code> and <code>test_run_dir</code>.</p>

  <div class="summary">
    <div class="card"><div class="metric">{total_tests}</div><div class="label">Tests</div></div>
    <div class="card"><div class="metric">{failures}</div><div class="label">Failures</div></div>
    <div class="card"><div class="metric">{errors}</div><div class="label">Errors</div></div>
    <div class="card"><div class="metric">{skipped}</div><div class="label">Skipped</div></div>
    <div class="card"><div class="metric">{total_time:.1f}s</div><div class="label">Reported Time</div></div>
    <div class="card"><div class="metric">{len(waveforms)}</div><div class="label">Waveforms</div></div>
  </div>

  <h2>Test Cases</h2>
  <table>
    <thead><tr><th>Status</th><th>Test</th><th>Suite</th><th>Time</th><th>Relative Time</th><th>Wave</th></tr></thead>
    <tbody>{''.join(rows)}</tbody>
  </table>

  <h2>Selected Waveforms</h2>
  <p class="muted">Each waveform shows only the signals most relevant to that test's CacheCore verification target.</p>
  {''.join(waveform_sections) if waveform_sections else '<p class="muted">No per-test VCD waveforms were found.</p>'}

  <h2>Waveform Files</h2>
  <table>
    <thead><tr><th>Path</th><th>Bytes</th></tr></thead>
    <tbody>{''.join(wave_rows)}</tbody>
  </table>

  <h2>Run Directories</h2>
  <table>
    <thead><tr><th>Directory</th><th>Artifacts</th><th>Files</th></tr></thead>
    <tbody>{''.join(run_rows)}</tbody>
  </table>
</body>
</html>
"""
    output.write_text(content, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".", help="Workspace root")
    parser.add_argument("--out", default="sim_report.html", help="Output HTML path")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    output = Path(args.out)
    if not output.is_absolute():
        output = root / output

    render_report(root, output)
    print(output)


if __name__ == "__main__":
    main()
