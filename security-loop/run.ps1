$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$runner = Join-Path $root "security-loop/run.py"
python $runner @args
exit $LASTEXITCODE
