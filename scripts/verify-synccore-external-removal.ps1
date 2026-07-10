param(
    [string]$Root = "."
)

$ErrorActionPreference = "Stop"

$scanTargets = @(
    "settings.gradle.kts",
    "build.gradle.kts",
    "app/build.gradle.kts",
    "app/src/main",
    "desktopApp/src/main",
    "sync-core/src",
    "shared/src",
    "security-loop/modules/sync-core-transport-lan",
    "docs/project-overview.md",
    "docs/synccore-integration/overview.md",
    "docs/synccore-integration/contract.md",
    "docs/synccore-integration/execution-plan-m1.md"
)

$forbiddenPatterns = @(
    "SYNC_CORE_DIR",
    "synccore\.dir",
    "app/src/synccore",
    "lanonly-p2p-cmdsync-core",
    "io\.github\.shgnaka\.synccore",
    "sync-core-api",
    "sync-core-engine",
    "sync-core-android",
    "SynccoreEngineClientFactory"
)

$violations = @()
foreach ($relativeTarget in $scanTargets) {
    $target = Join-Path $Root $relativeTarget
    if (-not (Test-Path -LiteralPath $target)) {
        continue
    }
    $files = if (Test-Path -LiteralPath $target -PathType Container) {
        Get-ChildItem -LiteralPath $target -Recurse -File
    } else {
        Get-Item -LiteralPath $target
    }
    foreach ($file in $files) {
        $text = Get-Content -LiteralPath $file.FullName -Raw
        foreach ($pattern in $forbiddenPatterns) {
            if ($text -match $pattern) {
                $relativePath = [System.IO.Path]::GetRelativePath((Resolve-Path -LiteralPath $Root), $file.FullName)
                $violations += "$relativePath contains forbidden external sync-core marker: $pattern"
            }
        }
    }
}

if ($violations.Count -gt 0) {
    throw ($violations -join [Environment]::NewLine)
}

Write-Output "sync-core external dependency removal verified"
