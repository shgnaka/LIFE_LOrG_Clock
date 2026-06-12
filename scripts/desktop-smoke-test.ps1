[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$RuntimeOnly,
    [switch]$InstallerOnly,
    [switch]$KeepInstalled,
    [string]$SmokePackageVersion,
    [string]$ArtifactsDirectory = "artifacts/desktop-smoke",
    [int]$ProcessTimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$artifacts = Join-Path $repoRoot $ArtifactsDirectory
$smokeRoot = Join-Path ([System.IO.Path]::GetTempPath()) "org-clock-desktop-smoke\org-root"
$report = Join-Path $artifacts "smoke-report.json"
$msiLog = Join-Path $artifacts "msi-install.log"

New-Item -ItemType Directory -Force $artifacts, $smokeRoot | Out-Null

if ($RuntimeOnly -and $InstallerOnly) {
    throw "-RuntimeOnly and -InstallerOnly cannot be used together"
}

function Get-DesktopPackageName {
    param([string]$ArtifactName)

    if ($ArtifactName -like "org-clock-desktop-smoke*") {
        return "org-clock-desktop-smoke"
    }
    return "org-clock-desktop"
}

function Start-ProcessWithTimeout {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$Description
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in $ArgumentList) {
        $startInfo.ArgumentList.Add($argument)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw "$Description failed to start"
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($ProcessTimeoutSeconds * 1000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        throw "$Description timed out after $ProcessTimeoutSeconds seconds"
    }
    $process.WaitForExit()
    return [PSCustomObject]@{
        ExitCode = $process.ExitCode
        StandardOutput = $stdoutTask.GetAwaiter().GetResult()
        StandardError = $stderrTask.GetAwaiter().GetResult()
    }
}

function Write-SmokeDiagnostics {
    param(
        [string]$Executable,
        [int]$ExitCode,
        [string]$StandardOutput,
        [string]$StandardError
    )

    Write-Host "Smoke executable: $Executable"
    Write-Host "Smoke root: $smokeRoot"
    Write-Host "Smoke report: $report"
    Write-Host "Smoke exit code: $ExitCode"
    if (-not [string]::IsNullOrWhiteSpace($StandardOutput)) {
        Write-Host "Smoke stdout:"
        Write-Host $StandardOutput
    }
    if (-not [string]::IsNullOrWhiteSpace($StandardError)) {
        Write-Host "Smoke stderr:"
        Write-Host $StandardError
    }
    if (Test-Path $report) {
        Write-Host "Smoke report contents:"
        Get-Content $report -Raw -Encoding UTF8 | Write-Host
    } else {
        Write-Warning "Smoke report was not generated."
    }
}

if (-not $SkipBuild) {
    $packageTask = if ($RuntimeOnly) { ":desktopApp:createDistributable" } else { ":desktopApp:packageMsi" }
    if (-not $SmokePackageVersion) {
        $buildNumber = [int]((Get-Date).ToUniversalTime().Ticks % 60000) + 1
        $SmokePackageVersion = "0.0.$buildNumber"
    }
    & (Join-Path $repoRoot "gradlew.bat") $packageTask "-Pdesktop.version=$SmokePackageVersion" "-Pdesktop.smoke=true"
    if ($LASTEXITCODE -ne 0) { throw "Desktop package build failed with exit code $LASTEXITCODE" }
}

$runtimePackageName = if ($SkipBuild) { "org-clock-desktop" } else { "org-clock-desktop-smoke" }
$runtimeExeName = "$runtimePackageName.exe"
$runtimeExe = Get-ChildItem (Join-Path $repoRoot "desktopApp/build/compose/binaries/main/app") -Recurse -Filter $runtimeExeName -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty FullName -First 1
if ($RuntimeOnly) {
    if (-not $runtimeExe) { throw "Distributable executable was not found. Run without -SkipBuild first." }
    Remove-Item $report -Force -ErrorAction SilentlyContinue
    $runtimeSmoke = Start-ProcessWithTimeout $runtimeExe @(
        "--smoke-test", "--root", $smokeRoot, "--report", $report
    ) "Distributable executable smoke test"
    if ($runtimeSmoke.ExitCode -ne 0) {
        Write-SmokeDiagnostics $runtimeExe $runtimeSmoke.ExitCode $runtimeSmoke.StandardOutput $runtimeSmoke.StandardError
        throw "Distributable executable smoke test failed with exit code $($runtimeSmoke.ExitCode)"
    }
    $result = Get-Content $report -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $result.passed) { throw "Smoke report contains failed checks: $report" }
    Write-Host "Desktop distributable smoke test passed."
    Write-Host "Executable: $runtimeExe"
    Write-Host "Report: $report"
    exit 0
}

$msiCandidates = Get-ChildItem (Join-Path $repoRoot "desktopApp/build/compose/binaries/main") -Recurse -Filter *.msi
$msi = $msiCandidates |
    Where-Object {
        if ($SkipBuild) {
            $_.BaseName -notlike "org-clock-desktop-smoke*"
        } else {
            $_.BaseName -like "org-clock-desktop-smoke*"
        }
    } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if (-not $msi) { throw "No MSI was found. Run without -SkipBuild first." }

$packageName = Get-DesktopPackageName $msi.BaseName
$exeName = "$packageName.exe"

$installedBySmoke = $false
try {
    $install = Start-ProcessWithTimeout "msiexec.exe" @(
        "/i", $msi.FullName, "/qn", "/norestart", "/l*v", $msiLog
    ) "MSI installation"
    if ($install.ExitCode -ne 0) {
        if ($install.ExitCode -eq 1625) {
            throw "MSI installation was blocked by Windows policy or an existing managed installation. Use -RuntimeOnly for the packaged-runtime checks. See $msiLog"
        }
        throw "MSI installation failed with exit code $($install.ExitCode). See $msiLog"
    }
    $installedBySmoke = $true

    $exeCandidates = @(
        (Join-Path $env:LOCALAPPDATA "Programs\$packageName\$exeName"),
        (Join-Path $env:LOCALAPPDATA "$packageName\$exeName"),
        (Join-Path $env:ProgramFiles "$packageName\$exeName")
    )
    if (${env:ProgramFiles(x86)}) {
        $exeCandidates += Join-Path ${env:ProgramFiles(x86)} "$packageName\$exeName"
    }
    $exe = $exeCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $exe) {
        $exe = Get-ChildItem @($env:LOCALAPPDATA, $env:ProgramFiles) -Recurse -Filter $exeName -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName -First 1
    }
    if (-not $exe) { throw "Installed $exeName was not found for package $($msi.Name)" }

    $startMenuShortcut = Get-ChildItem @(
        (Join-Path $env:APPDATA "Microsoft\Windows\Start Menu\Programs"),
        (Join-Path $env:ProgramData "Microsoft\Windows\Start Menu\Programs")
    ) -Recurse -Filter "$packageName.lnk" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $startMenuShortcut) { throw "Start menu shortcut was not created" }

    $desktopShortcut = Get-ChildItem @(
        [Environment]::GetFolderPath("Desktop"),
        [Environment]::GetFolderPath("CommonDesktopDirectory")
    ) -Filter "$packageName.lnk" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $desktopShortcut) { throw "Desktop shortcut was not created" }

    if ($InstallerOnly) {
        Write-Host "Desktop MSI installation smoke test passed."
        Write-Host "Executable: $exe"
        Write-Host "Start menu shortcut: $($startMenuShortcut.FullName)"
        Write-Host "Desktop shortcut: $($desktopShortcut.FullName)"
    } else {
        Remove-Item $report -Force -ErrorAction SilentlyContinue
        $installedSmoke = Start-ProcessWithTimeout $exe @(
            "--smoke-test", "--root", $smokeRoot, "--report", $report
        ) "Packaged executable smoke test"
        if ($installedSmoke.ExitCode -ne 0) {
            Write-SmokeDiagnostics $exe $installedSmoke.ExitCode $installedSmoke.StandardOutput $installedSmoke.StandardError
            throw "Packaged executable smoke test failed with exit code $($installedSmoke.ExitCode)"
        }
        $result = Get-Content $report -Raw -Encoding UTF8 | ConvertFrom-Json
        if (-not $result.passed) { throw "Smoke report contains failed checks: $report" }
        Write-Host "Desktop MSI runtime smoke test passed."
        Write-Host "Report: $report"
    }
    Write-Host "MSI: $($msi.FullName)"
}
finally {
    if (-not $KeepInstalled -and $installedBySmoke) {
        $uninstall = Start-ProcessWithTimeout "msiexec.exe" @(
            "/x", $msi.FullName, "/qn", "/norestart"
        ) "MSI uninstall"
        if ($uninstall.ExitCode -ne 0) {
            Write-Warning "MSI uninstall failed with exit code $($uninstall.ExitCode)"
        }
    }
}
