# CI pro Windows: testy Rust jadra. Pouziti: powershell -File scripts\ci.ps1
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..\crates\keyboard-core")
cargo test --all-targets
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "OK"
