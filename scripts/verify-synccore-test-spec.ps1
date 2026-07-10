param(
    [string]$RequirementsPath = "docs/synccore-integration/in-repository-requirements.md",
    [string]$TestSpecPath = "docs/synccore-integration/in-repository-test-spec.md"
)

$ErrorActionPreference = "Stop"

function Read-RequiredFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required file not found: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw
}

function Get-TestRows([string]$Text) {
    $testIdPattern = '^(ARC|ENG|ING|SEC|DB|IDN|INT|QUA|INS)-\d+$'
    $rows = @()
    foreach ($line in ($Text -split "`r?`n")) {
        if (-not $line.StartsWith("|")) {
            continue
        }
        $columns = @($line.Trim().Trim("|").Split("|") | ForEach-Object { $_.Trim() })
        if ($columns.Count -lt 4 -or $columns[0] -notmatch $testIdPattern) {
            continue
        }
        $rows += [pscustomobject]@{
            Id = $columns[0]
            Requirement = $columns[1]
            Operation = $columns[2]
            Oracle = $columns[3]
        }
    }
    return $rows
}

function Assert-NoDuplicates([object[]]$Rows, [string]$Label) {
    $duplicates = @($Rows | Group-Object Id | Where-Object Count -ne 1)
    if ($duplicates.Count -gt 0) {
        $details = $duplicates | ForEach-Object { "$($_.Name) x$($_.Count)" }
        throw "$Label contains duplicate test IDs: $($details -join ', ')"
    }
}

$requirementsText = Read-RequiredFile $RequirementsPath
$testSpecText = Read-RequiredFile $TestSpecPath

$requirementsRows = @(Get-TestRows $requirementsText)
$testSpecRows = @(Get-TestRows $testSpecText)

Assert-NoDuplicates $requirementsRows "Requirements catalog"
Assert-NoDuplicates $testSpecRows "Test specification"

$requirementsIds = @($requirementsRows.Id | Sort-Object -Unique)
$testSpecIds = @($testSpecRows.Id | Sort-Object -Unique)

$missingFromSpec = @($requirementsIds | Where-Object { $_ -notin $testSpecIds })
$orphanedInSpec = @($testSpecIds | Where-Object { $_ -notin $requirementsIds })

if ($missingFromSpec.Count -gt 0) {
    throw "Acceptance IDs missing from test specification: $($missingFromSpec -join ', ')"
}
if ($orphanedInSpec.Count -gt 0) {
    throw "Test specification IDs not declared by requirements: $($orphanedInSpec -join ', ')"
}

$blankDefinitions = @(
    $testSpecRows | Where-Object {
        [string]::IsNullOrWhiteSpace($_.Requirement) -or
        [string]::IsNullOrWhiteSpace($_.Operation) -or
        [string]::IsNullOrWhiteSpace($_.Oracle)
    }
)
if ($blankDefinitions.Count -gt 0) {
    throw "Test specification contains blank owner/operation/oracle fields: $($blankDefinitions.Id -join ', ')"
}

$requirementDefinitions = @(
    [regex]::Matches(
        $requirementsText,
        '(?m)^### ((?:AR|FR|SR|DR|OR|CR|QR)-\d+)'
    ) | ForEach-Object { $_.Groups[1].Value }
)
$mappedRequirements = @(
    $requirementsRows.Requirement |
        ForEach-Object {
            [regex]::Matches($_, '(?:AR|FR|SR|DR|OR|CR|QR)-\d+') |
                ForEach-Object { $_.Value }
        } |
        Sort-Object -Unique
)
$unmappedRequirements = @(
    $requirementDefinitions |
        Where-Object { $_ -notin $mappedRequirements }
)
if ($unmappedRequirements.Count -gt 0) {
    throw "Numbered requirements without an acceptance test: $($unmappedRequirements -join ', ')"
}

Write-Output (
    "sync-core test specification verified: " +
    "$($requirementDefinitions.Count) requirements, " +
    "$($requirementsIds.Count) acceptance tests"
)
