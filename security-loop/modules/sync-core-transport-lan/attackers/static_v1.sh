#!/usr/bin/env bash
set -euo pipefail

: "${MODULE:?MODULE is required}"
: "${OUT_FILE:?OUT_FILE is required}"

MODULE_ROOT="${MODULE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
ROOT_DIR="${ROOT_DIR:-$(cd "$MODULE_ROOT/../../.." && pwd)}"

python3 - "$MODULE" "$MODULE_ROOT" "$ROOT_DIR" "$OUT_FILE" <<'PY'
import json
import pathlib
import re
import sys
from datetime import datetime, timezone

module, module_root_raw, root_raw, out_file_raw = sys.argv[1:5]
module_root = pathlib.Path(module_root_raw)
root = pathlib.Path(root_raw)
out_file = pathlib.Path(out_file_raw)
manifest = json.loads((module_root / "manifest.json").read_text(encoding="utf-8"))


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def target_files():
    for relative in manifest.get("targets", []):
        path = root / relative
        if path.is_file():
            yield path
        elif path.is_dir():
            yield from path.rglob("*")


def text_files():
    for path in target_files():
        if path.is_file() and path.suffix in {".kt", ".java", ".kts", ".md", ".json"}:
            try:
                yield path, path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue


files = list(text_files())
combined = "\n".join(text for _, text in files)


def rel(path: pathlib.Path) -> str:
    return path.relative_to(root).as_posix()


def grep(pattern: str, max_hits: int = 5):
    regex = re.compile(pattern)
    hits = []
    for path, text in files:
        for line_number, line in enumerate(text.splitlines(), start=1):
            if regex.search(line):
                hits.append(f"{rel(path)}:{line_number}:{line.strip()}")
                if len(hits) >= max_hits:
                    return hits
    return hits


def has(pattern: str) -> bool:
    return re.search(pattern, combined, flags=re.MULTILINE) is not None


def add_finding(findings, finding_id, severity, title, category, evidence, repro_steps, affected_files, confidence, recommended_fix):
    findings.append(
        {
            "finding_id": finding_id,
            "module": module,
            "title": title,
            "severity": severity,
            "category": category,
            "evidence": evidence,
            "repro_steps": repro_steps,
            "affected_files": affected_files,
            "confidence": confidence,
            "recommended_fix": recommended_fix,
        }
    )


findings = []

legacy_external_path = "app/src/" + "synccore"
legacy_external_factory = "Synccore" + "EngineClientFactory"
legacy_pattern = (
    r"HttpIncomingCommandSource|"
    + re.escape(legacy_external_path)
    + r"|"
    + re.escape(legacy_external_factory)
    + r"|verificationMethod\s*=\s*\"schema\+sender\+timestamp\+replay\""
)
legacy_markers = grep(legacy_pattern)
if legacy_markers:
    add_finding(
        findings,
        "SYNC-LEGACY-001",
        "high",
        "Security-loop target still references legacy external ingress path",
        "architecture",
        "; ".join(legacy_markers),
        "Run the security-loop against the configured targets; stale external adapter references can mask the active internal ingress path.",
        sorted({item.split(":", 1)[0] for item in legacy_markers}),
        "high",
        "Remove legacy external adapter references and run the loop against :sync-core plus current host adapters.",
    )

if not has(r"class\s+SyncRawIngressReceiver") or not has(r"decodeAndVerifyCurrent\("):
    add_finding(
        findings,
        "SYNC-INGRESS-001",
        "high",
        "Raw ingress path lacks a public verified-envelope facade",
        "authenticity",
        "Could not find SyncRawIngressReceiver delegating to decodeAndVerifyCurrent in configured targets.",
        "Submit raw JSON to /v1/messages; without a verified-envelope facade the host may bypass signature/trust checks.",
        ["sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore"],
        "medium",
        "Expose only an api-package raw ingress facade that verifies signatures and trust before receiveVerified.",
    )

if not has(r"SignatureInvalid") or not has(r"TimestampOutOfRange") or not has(r"payload hash mismatch"):
    add_finding(
        findings,
        "SYNC-INGRESS-002",
        "high",
        "Envelope verification does not cover signature, timestamp, and payload hash rejection",
        "authenticity",
        "Required rejection markers SignatureInvalid, TimestampOutOfRange, and payload hash mismatch were not all found.",
        "Tamper signed fields, replay stale timestamps, or alter payloadJson after signing; missing checks may admit forged messages.",
        ["sync-core/src/commonMain/kotlin/io/github/shgnaka/orgclock/synccore/security/EnvelopeCodec.kt"],
        "medium",
        "Keep signature, timestamp skew, and payload hash verification in the core ingress decoder with regression tests.",
    )

android_https = grep(r"cleartext endpoint is not allowed", max_hits=10)
desktop_https = [hit for hit in android_https if hit.startswith("desktopApp/")]
android_https_hits = [hit for hit in android_https if hit.startswith("app/")]
if not android_https_hits or not desktop_https:
    add_finding(
        findings,
        "SYNC-EGRESS-001",
        "high",
        "Sync-core egress may allow non-HTTPS endpoints",
        "transport-security",
        "Missing cleartext rejection marker in Android or Desktop sync-core transport.",
        "Configure a peer endpoint as http://host:port and dispatch a command; egress must reject before network I/O.",
        ["app/src/main/java/com/example/orgclock/sync/AndroidSyncCoreTransport.kt", "desktopApp/src/main/kotlin/com/example/orgclock/desktop/DesktopSyncCoreTransport.kt"],
        "medium",
        "Reject endpoints that do not start with https:// before constructing network connections.",
    )

pin_hits = grep(r"CertificatePinMismatch|PinnedSslContext|pinnedSslContext|SecureFingerprintEquals|secureFingerprintEquals", max_hits=50)
if not any(hit.startswith("app/") for hit in pin_hits) or not any(hit.startswith("desktopApp/") for hit in pin_hits):
    add_finding(
        findings,
        "SYNC-EGRESS-002",
        "high",
        "Sync-core egress lacks certificate pin enforcement on one host",
        "transport-security",
        "Missing certificate pinning markers in Android or Desktop transport.",
        "Present a TLS server with a valid but unpaired certificate; dispatch must fail with CertificatePinMismatch.",
        ["app/src/main/java/com/example/orgclock/sync/AndroidSyncCoreTransport.kt", "desktopApp/src/main/kotlin/com/example/orgclock/desktop/DesktopSyncCoreTransport.kt"],
        "medium",
        "Verify peer certificate SHA-256 against the paired transport credential on every HTTPS dispatch.",
    )

if not has(r"AndroidKeyStore") or not (has(r"ES256") and (has(r"secp256r1") or has(r"KEY_ALGORITHM_EC"))):
    add_finding(
        findings,
        "SYNC-ID-001",
        "medium",
        "Android signing identity may not be backed by Keystore ES256/P-256",
        "key-management",
        "Could not find AndroidKeyStore and ES256/P-256 markers in configured targets.",
        "Inspect generated signing keys and ensure private material is non-exportable.",
        ["app/src/main/java/com/example/orgclock/sync/AndroidSyncCoreTransport.kt"],
        "medium",
        "Use Android Keystore for the sync-core ES256/P-256 signing key and keep private keys out of Room/files.",
    )

if not findings:
    add_finding(
        findings,
        "SYNC-000",
        "low",
        "No high-confidence finding from static sync-core transport heuristics",
        "analysis",
        "Current targets expose verified raw ingress, HTTPS egress rejection, TLS pinning markers, and ES256/P-256 signing markers.",
        "N/A",
        [],
        "low",
        "Continue dynamic and instrumented validation before default-on rollout.",
    )

severity_order = {"critical": 4, "high": 3, "medium": 2, "low": 1}
highest = max(findings, key=lambda item: severity_order.get(item["severity"], 0))
report = {
    "generated_at": utc_now(),
    "scanner": "attacker_static_v1_python",
    "module": module,
    "finding_id": highest["finding_id"],
    "severity": highest["severity"],
    "title": highest["title"],
    "category": highest["category"],
    "evidence": highest["evidence"],
    "repro_steps": highest["repro_steps"],
    "affected_files": highest["affected_files"],
    "confidence": highest["confidence"],
    "recommended_fix": highest["recommended_fix"],
    "findings": findings,
}
out_file.parent.mkdir(parents=True, exist_ok=True)
out_file.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
