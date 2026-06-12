[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$RuntimeOnly,
    [switch]$KeepInstalled,
    [string]$SmokePackageVersion,
    [string]$ArtifactsDirectory = "artifacts/desktop-smoke"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$artifacts = Join-Path $repoRoot $ArtifactsDirectory
$smokeRoot = Join-Path $artifacts "org-root-日本語"
$report = Join-Path $artifacts "smoke-report.json"
$msiLog = Join-Path $artifacts "msi-install.log"

New-Item -ItemType Directory -Force $artifacts, $smokeRoot | Out-Null

if (-not $SkipBuild) {
    $packageTask = if ($RuntimeOnly) { ":desktopApp:createDistributable" } else { ":desktopApp:packageMsi" }
    if (-not $SmokePackageVersion) {
        $buildNumber = [int]((Get-Date).ToUniversalTime().Ticks % 60000) + 1
        $SmokePackageVersion = "0.0.$buildNumber"
    }
    & (Join-Path $repoRoot "gradlew.bat") $packageTask "-Pdesktop.version=$SmokePackageVersion" "-Pdesktop.smoke=true"
    if ($LASTEXITCODE -ne 0) { throw "Desktop package build failed with exit code $LASTEXITCODE" }
}

$runtimeExe = Get-ChildItem (Join-Path $repoRoot "desktopApp/build/compose/binaries/main/app") -Recurse -Filter "org-clock-desktop-smoke.exe" -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty FullName -First 1
if ($RuntimeOnly) {
    if (-not $runtimeExe) { throw "Distributable executable was not found. Run without -SkipBuild first." }
    $runtimeSmoke = Start-Process $runtimeExe -ArgumentList @(
        "--smoke-test", "--root", "`"$smokeRoot`"", "--report", "`"$report`""
    ) -Wait -PassThru
    if ($runtimeSmoke.ExitCode -ne 0) {
        throw "Distributable executable smoke test failed with exit code $($runtimeSmoke.ExitCode)"
    }
    $result = Get-Content $report -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $result.passed) { throw "Smoke report contains failed checks: $report" }
    Write-Host "Desktop distributable smoke test passed."
    Write-Host "Executable: $runtimeExe"
    Write-Host "Report: $report"
    exit 0
}

$msi = Get-ChildItem (Join-Path $repoRoot "desktopApp/build/compose/binaries/main") -Recurse -Filter *.msi |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if (-not $msi) { throw "No MSI was found. Run without -SkipBuild first." }

$installedBySmoke = $false
try {
    $install = Start-Process msiexec.exe -ArgumentList @(
        "/i", "`"$($msi.FullName)`"", "/qn", "/norestart", "/l*v", "`"$msiLog`""
    ) -Wait -PassThru
    if ($install.ExitCode -ne 0) {
        if ($install.ExitCode -eq 1625) {
            throw "MSI installation was blocked by Windows policy or an existing managed installation. Use -RuntimeOnly for the packaged-runtime checks. See $msiLog"
        }
        throw "MSI installation failed with exit code $($install.ExitCode). See $msiLog"
    }
    $installedBySmoke = $true

    $exeCandidates = @(
        (Join-Path $env:LOCALAPPDATA "Programs\org-clock-desktop-smoke\org-clock-desktop-smoke.exe"),
        (Join-Path $env:LOCALAPPDATA "org-clock-desktop-smoke\org-clock-desktop-smoke.exe"),
        (Join-Path $env:ProgramFiles "org-clock-desktop-smoke\org-clock-desktop-smoke.exe")
    )
    if (${env:ProgramFiles(x86)}) {
        $exeCandidates += Join-Path ${env:ProgramFiles(x86)} "org-clock-desktop-smoke\org-clock-desktop-smoke.exe"
    }
    $exe = $exeCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $exe) {
        $exe = Get-ChildItem @($env:LOCALAPPDATA, $env:ProgramFiles) -Recurse -Filter "org-clock-desktop-smoke.exe" -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName -First 1
    }
    if (-not $exe) { throw "Installed org-clock-desktop-smoke.exe was not found" }

    $startMenuShortcut = Get-ChildItem @(
        (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"),
        (Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs")
    ) -Recurse -Filter "*org*clock*.lnk" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $startMenuShortcut) { throw "Start menu shortcut was not created" }

    $desktopShortcut = Get-ChildItem @(
        [Environment]::GetFolderPath("Desktop"),
        [Environment]::GetFolderPath("CommonDesktopDirectory")
    ) -Filter "*org*clock*.lnk" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $desktopShortcut) { throw "Desktop shortcut was not created" }

    $installedSmoke = Start-Process $exe -ArgumentList @(
        "--smoke-test", "--root", "`"$smokeRoot`"", "--report", "`"$report`""
    ) -Wait -PassThru
    if ($installedSmoke.ExitCode -ne 0) {
        throw "Packaged executable smoke test failed with exit code $($installedSmoke.ExitCode)"
    }
    $result = Get-Content $report -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $result.passed) { throw "Smoke report contains failed checks: $report" }

    Write-Host "Desktop MSI smoke test passed."
    Write-Host "MSI: $($msi.FullName)"
    Write-Host "Report: $report"
}
finally {
    if (-not $KeepInstalled -and $installedBySmoke) {
        $uninstall = Start-Process msiexec.exe -ArgumentList @(
            "/x", "`"$($msi.FullName)`"", "/qn", "/norestart"
        ) -Wait -PassThru
        if ($uninstall.ExitCode -ne 0) {
            Write-Warning "MSI uninstall failed with exit code $($uninstall.ExitCode)"
        }
    }
}
