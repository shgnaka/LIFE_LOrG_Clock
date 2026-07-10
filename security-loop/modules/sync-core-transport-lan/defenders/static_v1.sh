#!/usr/bin/env bash
set -euo pipefail

: "${MODULE:?MODULE is required}"
: "${IN_REPORT:?IN_REPORT is required}"
: "${OUT_FILE:?OUT_FILE is required}"

python3 - "$MODULE" "$IN_REPORT" "$OUT_FILE" <<'PY'
import json
import pathlib
import sys
from datetime import datetime, timezone

module, in_report_raw, out_file_raw = sys.argv[1:4]
in_report = pathlib.Path(in_report_raw)
out_file = pathlib.Path(out_file_raw)
attacker = json.loads(in_report.read_text(encoding="utf-8"))


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def selected_finding(report):
    finding_id = report.get("finding_id")
    findings = report.get("findings") or [report]
    for finding in findings:
        if finding.get("finding_id") == finding_id:
            return finding
    for finding in findings:
        if finding.get("severity") in {"critical", "high"}:
            return finding
    return findings[0]


finding = selected_finding(attacker)
finding_id = finding.get("finding_id", "UNKNOWN")
severity = finding.get("severity", "low")
category = finding.get("category", "unknown")

plans = {
    "SYNC-LEGACY-001": (
        "Remove stale external sync-core references from the security-loop scope.",
        [
            "Update manifest targets to :sync-core and active host adapters.",
            "Delete stale external adapter references from loop context and findings.",
            "Run the loop and no-external verifier after the update.",
        ],
        ["verify-synccore-external-removal", "security-loop sync-core-transport-lan"],
    ),
    "SYNC-INGRESS-001": (
        "Route raw ingress through the public verified-envelope facade.",
        [
            "Keep SyncRawIngressReceiver in the api package.",
            "Delegate raw JSON decoding to decodeAndVerifyCurrent before receiveVerified.",
            "Add or update tests for unsigned, tampered, unknown, and revoked peer messages.",
        ],
        ["SyncRawIngressReceiverPublicApiTest", "EnvelopeCodecTest", "RawIngressReceiverTest"],
    ),
    "SYNC-INGRESS-002": (
        "Preserve signature, timestamp, and payload hash checks in the core decoder.",
        [
            "Reject payload hash mismatches before persistence.",
            "Reject timestamp skew outside the configured window.",
            "Reject invalid signatures and unknown/revoked peers before persistence.",
        ],
        ["EnvelopeCodecTest", "RawIngressReceiverTest"],
    ),
    "SYNC-EGRESS-001": (
        "Enforce HTTPS-only egress before network I/O.",
        [
            "Reject non-https endpoints in AndroidSyncCoreTransport.",
            "Reject non-https endpoints in DesktopSyncCoreTransport.",
            "Assert poster/exchange is not invoked for cleartext endpoints.",
        ],
        ["AndroidSyncCoreTransportTest", "DesktopSyncCoreTransportTest", "TransportSecurityTest"],
    ),
    "SYNC-EGRESS-002": (
        "Verify paired TLS certificate pins on every sync-core dispatch.",
        [
            "Build SSL contexts with the paired certificate SHA-256.",
            "Fail mismatches as CertificatePinMismatch terminal rejections.",
            "Cover matching and mismatching certificate paths in host tests.",
        ],
        ["AndroidSyncCoreTransportTest", "DesktopSyncCoreTransportTest", "TransportSecurityTest"],
    ),
    "SYNC-ID-001": (
        "Keep Android signing material in Android Keystore.",
        [
            "Generate P-256 ES256 signing keys with AndroidKeyStore.",
            "Expose only the public key for pairing/trust records.",
            "Add instrumented inspection for non-exportable private material.",
        ],
        ["AndroidSyncCoreIdentityAdapterTest", "connectedDebugAndroidTest"],
    ),
}

fix_strategy, patch_plan, tests = plans.get(
    finding_id,
    (
        "Apply targeted defensive changes with regression tests.",
        ["Harden the identified code path.", "Add a regression test for the attacker repro."],
        ["Regression test for finding reproduction"],
    ),
)

report = {
    "generated_at": utc_now(),
    "module": module,
    "finding_id": finding_id,
    "severity": severity,
    "category": category,
    "fix_strategy": fix_strategy,
    "recommended_fix_from_attacker": finding.get("recommended_fix", ""),
    "patch_plan": patch_plan,
    "tests_to_add_or_update": tests,
    "expected_risk_reduction": "high" if severity in {"critical", "high"} else "medium",
    "residual_risk": "Requires runtime validation and peer interoperability tests before default-on rollout.",
    "source_finding": finding,
}
out_file.parent.mkdir(parents=True, exist_ok=True)
out_file.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
