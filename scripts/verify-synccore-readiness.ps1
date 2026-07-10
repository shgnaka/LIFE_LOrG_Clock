param(
    [string]$Root = "."
)

$ErrorActionPreference = "Stop"

$requiredFiles = [ordered]@{
    "docs/synccore-integration/in-repository-requirements.md" = "Status: Requirements Baseline v1"
    "docs/synccore-integration/in-repository-api-contract.md" = "Status: API Baseline v1"
    "docs/synccore-integration/in-repository-test-spec.md" = "Status: Test Specification v1"
    "docs/synccore-integration/in-repository-storage-migration.md" = "Status: Storage Baseline v1"
    "docs/synccore-integration/in-repository-threat-model.md" = "Status: Threat Model v1"
    "docs/synccore-integration/in-repository-identity-migration.md" = "Status: Identity Baseline v1"
    "docs/synccore-integration/in-repository-implementation-plan.md" = "Status: Implemented through PR-7 / Gate 5"
    "docs/synccore-integration/in-repository-readiness.md" = "IMPLEMENTED / GATE 5 COMPLETE"
    "scripts/verify-synccore-test-spec.ps1" = "sync-core test specification verified"
}

foreach ($entry in $requiredFiles.GetEnumerator()) {
    $path = Join-Path $Root $entry.Key
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing readiness artifact: $($entry.Key)"
    }
    $text = Get-Content -LiteralPath $path -Raw
    if (-not $text.Contains($entry.Value)) {
        throw "Readiness artifact lacks required marker '$($entry.Value)': $($entry.Key)"
    }
}

$requirementsPath = Join-Path $Root "docs/synccore-integration/in-repository-requirements.md"
$requirements = Get-Content -LiteralPath $requirementsPath -Raw
$gate1Match = [regex]::Match(
    $requirements,
    '(?s)### Gate 1: Requirements reviewed(?<body>.*?)### Gate 2:'
)
if (-not $gate1Match.Success) {
    throw "Could not locate Gate 1 readiness checklist."
}
if ($gate1Match.Groups["body"].Value -match '(?m)^- \[ \]') {
    throw "Gate 1 contains unchecked preparation items."
}

$reviewDocs = @(
    "docs/synccore-integration/in-repository-api-contract.md",
    "docs/synccore-integration/in-repository-storage-migration.md",
    "docs/synccore-integration/in-repository-threat-model.md",
    "docs/synccore-integration/in-repository-identity-migration.md",
    "docs/synccore-integration/in-repository-implementation-plan.md"
)
foreach ($relativePath in $reviewDocs) {
    $text = Get-Content -LiteralPath (Join-Path $Root $relativePath) -Raw
    if ($text -match '(?m)^- \[ \]') {
        throw "Preparation checklist contains unchecked item: $relativePath"
    }
}

$fixtureRoot = Join-Path $Root "app/src/test/resources/synccore/legacy-v1"
$requiredFixtures = @(
    "clock-command-payload.json",
    "clock-result-payload.json",
    "command-envelope.json",
    "command-envelope.canonical.txt",
    "schema-v1.sql",
    "migration-1-2.sql",
    "schema-v2.sql",
    "README.md"
)
foreach ($fixture in $requiredFixtures) {
    if (-not (Test-Path -LiteralPath (Join-Path $fixtureRoot $fixture) -PathType Leaf)) {
        throw "Missing compatibility fixture: $fixture"
    }
}

$traceabilityScript = Join-Path $Root "scripts/verify-synccore-test-spec.ps1"
$traceabilityOutput = & $traceabilityScript

Write-Output $traceabilityOutput
Write-Output (
    "sync-core implementation readiness verified: " +
    "$($requiredFiles.Count) artifacts, $($requiredFixtures.Count) fixtures, Gate 5 complete"
)
