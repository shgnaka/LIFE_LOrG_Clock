#!/usr/bin/env python3
import argparse
import json
import os
import pathlib
import shlex
import subprocess
import sys
from datetime import datetime, timezone


ROOT_DIR = pathlib.Path(__file__).resolve().parents[1]


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_json(path: pathlib.Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path: pathlib.Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, indent=2, sort_keys=True)
        handle.write("\n")


def wsl_path(path: pathlib.Path) -> str:
    resolved = path.resolve()
    drive = resolved.drive.rstrip(":").lower()
    if not drive:
        return resolved.as_posix()
    rest = resolved.as_posix().split(":", 1)[1].lstrip("/")
    return f"/mnt/{drive}/{rest}"


def parse_args(argv):
    parser = argparse.ArgumentParser(description="Run a repository security loop module.")
    parser.add_argument("--module", required=True, help="Module under security-loop/modules.")
    parser.add_argument("--iterations", type=int, default=3, help="Max iterations.")
    parser.add_argument("--out", default="", help="Output directory.")
    parser.add_argument("--model", default=os.environ.get("CODEX_MODEL", ""), help="Model name override.")
    parser.add_argument("--fail-on-high", dest="fail_on_high", action="store_true", default=True)
    parser.add_argument("--no-fail-on-high", dest="fail_on_high", action="store_false")
    parser.add_argument("--skip-gates", action="store_true", help="Skip validation gates.")
    args = parser.parse_args(argv)
    if args.iterations < 1:
        parser.error("--iterations must be a positive integer")
    return args


def validate_manifest(manifest, manifest_path: pathlib.Path) -> None:
    def require(condition, message):
        if not condition:
            raise SystemExit(f"{message}: {manifest_path}")

    require(isinstance(manifest.get("module"), str) and manifest["module"], "manifest.module must be a non-empty string")
    require(isinstance(manifest.get("attacker"), dict), "manifest.attacker must be an object")
    require(isinstance(manifest["attacker"].get("entry"), str) and manifest["attacker"]["entry"], "manifest.attacker.entry must be a non-empty string")
    require(isinstance(manifest.get("defender"), dict), "manifest.defender must be an object")
    require(isinstance(manifest["defender"].get("entry"), str) and manifest["defender"]["entry"], "manifest.defender.entry must be a non-empty string")
    require(isinstance(manifest.get("targets"), list) and manifest["targets"], "manifest.targets must be a non-empty array")
    require(all(isinstance(item, str) and item for item in manifest["targets"]), "manifest.targets must contain non-empty strings")
    require(isinstance(manifest.get("gate_commands"), list) and manifest["gate_commands"], "manifest.gate_commands must be a non-empty array")
    require(all(isinstance(item, str) and item for item in manifest["gate_commands"]), "manifest.gate_commands must contain non-empty strings")


def validate_attacker_report(report, module):
    if report.get("module") != module:
        raise SystemExit(f"attacker report module mismatch: {report.get('module')} != {module}")
    if not report.get("finding_id") or not report.get("severity") or not isinstance(report.get("findings"), list):
        raise SystemExit("attacker report lacks required fields")


def validate_defender_report(report, attacker, module):
    if report.get("module") != module:
        raise SystemExit(f"defender report module mismatch: {report.get('module')} != {module}")
    if report.get("finding_id") != attacker.get("finding_id"):
        raise SystemExit("defender report finding_id does not match attacker report")
    if not report.get("fix_strategy") or not isinstance(report.get("patch_plan"), list):
        raise SystemExit("defender report lacks required fields")


def run_entry(entry_path: pathlib.Path, env):
    if os.name == "nt":
        assignments = []
        for key in ("MODULE", "CODEX_MODEL"):
            if key in env:
                assignments.append(f"{key}={shlex.quote(env[key])}")
        for key in ("MODULE_ROOT", "ROOT_DIR", "OUT_FILE", "IN_REPORT"):
            value = env.get(key)
            if value:
                assignments.append(f"{key}={shlex.quote(wsl_path(pathlib.Path(value)))}")
        command = (
            f"cd {shlex.quote(wsl_path(ROOT_DIR))} && "
            f"{' '.join(assignments)} bash {shlex.quote(wsl_path(entry_path))}"
        )
        result = subprocess.run(["bash", "-lc", command])
    else:
        result = subprocess.run(["bash", str(entry_path)], cwd=ROOT_DIR, env=env)
    if result.returncode != 0:
        raise SystemExit(result.returncode)


def run_gate(out_dir: pathlib.Path, name: str, command: str):
    log_file = out_dir / f"{name}.log"
    with log_file.open("w", encoding="utf-8", newline="\n") as log:
        if os.name == "nt":
            windows_command = command.replace("./gradlew", ".\\gradlew.bat", 1)
            result = subprocess.run(
                windows_command,
                cwd=ROOT_DIR,
                stdout=log,
                stderr=subprocess.STDOUT,
                shell=True,
            )
        else:
            result = subprocess.run(["bash", "-lc", command], cwd=ROOT_DIR, stdout=log, stderr=subprocess.STDOUT)
    return {
        "name": name,
        "command": command,
        "status": "passed" if result.returncode == 0 else "failed",
        "log": str(log_file),
    }


def severity_is_high_or_critical(finding):
    return finding.get("severity") in {"high", "critical"}


def main(argv) -> int:
    args = parse_args(argv)
    started_at = utc_now()
    module_dir = ROOT_DIR / "security-loop" / "modules" / args.module
    if not module_dir.is_dir():
        raise SystemExit(f"module not found: {args.module}")
    manifest_path = module_dir / "manifest.json"
    if not manifest_path.is_file():
        raise SystemExit(f"manifest not found: {manifest_path}")
    manifest = load_json(manifest_path)
    validate_manifest(manifest, manifest_path)

    attacker_entry = manifest["attacker"]["entry"]
    defender_entry = manifest["defender"]["entry"]
    attacker_path = module_dir / attacker_entry
    defender_path = module_dir / defender_entry
    if not attacker_path.is_file():
        raise SystemExit(f"attacker entry not found: {attacker_path}")
    if not defender_path.is_file():
        raise SystemExit(f"defender entry not found: {defender_path}")

    if args.out:
        out_dir = pathlib.Path(args.out)
        if not out_dir.is_absolute():
            out_dir = ROOT_DIR / out_dir
    else:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        out_dir = ROOT_DIR / "security-loop" / "out" / f"{stamp}-{args.module}"
    out_dir.mkdir(parents=True, exist_ok=True)

    iterations = []
    attack_reports = []
    defense_reports = []
    remaining_findings = []

    for iteration in range(1, args.iterations + 1):
        attacker_out = out_dir / f"iteration-{iteration}-attacker.json"
        defender_out = out_dir / f"iteration-{iteration}-defender.json"
        env = os.environ.copy()
        env.update(
            {
                "MODULE": args.module,
                "MODULE_ROOT": str(module_dir),
                "ROOT_DIR": str(ROOT_DIR),
                "OUT_FILE": str(attacker_out),
                "CODEX_MODEL": args.model,
            }
        )
        run_entry(attacker_path, env)
        attacker = load_json(attacker_out)
        validate_attacker_report(attacker, args.module)
        attack_reports.append(str(attacker_out))

        high_findings = [item for item in attacker.get("findings", []) if severity_is_high_or_critical(item)]
        defender_status = "skipped"
        if high_findings:
            env = os.environ.copy()
            env.update(
                {
                    "MODULE": args.module,
                    "MODULE_ROOT": str(module_dir),
                    "ROOT_DIR": str(ROOT_DIR),
                    "IN_REPORT": str(attacker_out),
                    "OUT_FILE": str(defender_out),
                    "CODEX_MODEL": args.model,
                }
            )
            run_entry(defender_path, env)
            defender = load_json(defender_out)
            validate_defender_report(defender, attacker, args.module)
            defense_reports.append(str(defender_out))
            defender_status = "generated"

        gates = []
        if not args.skip_gates:
            for index, command in enumerate(manifest["gate_commands"], start=1):
                gates.append(run_gate(out_dir, f"iter-{iteration}-gate-{index}", command))

        iterations.append(
            {
                "iteration": iteration,
                "attacker_report": str(attacker_out),
                "defender_report": str(defender_out),
                "defender_status": defender_status,
                "high_or_critical_count": len(high_findings),
                "gates": gates,
            }
        )
        remaining_findings = high_findings
        if not high_findings:
            break

    all_gates_ok = args.skip_gates or all(
        all(gate["status"] == "passed" for gate in iteration["gates"])
        for iteration in iterations
    )
    if not all_gates_ok:
        final_status = "failed_gates"
    elif remaining_findings:
        final_status = "needs_manual_intervention"
    else:
        final_status = "passed"

    summary = {
        "run_id": "security-loop-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"),
        "module": args.module,
        "started_at": started_at,
        "model": args.model,
        "execution": {
            "attacker_entry": attacker_entry,
            "attacker_path": str(attacker_path),
            "defender_entry": defender_entry,
            "defender_path": str(defender_path),
            "gate_commands": manifest["gate_commands"],
        },
        "configured_iterations": args.iterations,
        "iterations": iterations,
        "gates": {
            "skipped": args.skip_gates,
            "required": manifest["gate_commands"],
        },
        "schemas": {
            "attacker": str(ROOT_DIR / "security-loop" / "schemas" / "attacker-report.schema.json"),
            "defender": str(ROOT_DIR / "security-loop" / "schemas" / "defender-report.schema.json"),
        },
        "artifacts": {
            "attacker_reports": attack_reports,
            "defender_reports": defense_reports,
        },
        "remaining_findings": remaining_findings,
        "final_status": final_status,
    }
    summary_file = out_dir / "summary.json"
    write_json(summary_file, summary)
    print(json.dumps(summary, indent=2, sort_keys=True))

    if args.fail_on_high and remaining_findings:
        return 1
    if final_status == "failed_gates":
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
