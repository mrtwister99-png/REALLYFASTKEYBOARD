# Testovaci relace s vice efekty v jednom behu.
# Postup: pust skript, testuj na telefonu jeden efekt, az ho dotestujes, napis jeho nazev TADY do terminalu a Enter
# (skript ulozi statistiku snimku za ten usek a vynuluje ji pro dalsi efekt). Prazdny Enter = konec.
# Vysledky: testdata/runs/<nazev>.txt (cely vypis gfxinfo), testdata/vysledky.txt (tabulka), testdata/log.txt (pady a logy).
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$runs = Join-Path $dir "runs"
New-Item -ItemType Directory -Force $runs | Out-Null
$summary = Join-Path $dir "vysledky.txt"
$log = Join-Path $dir "log.txt"

function Get-Stat($text, $pattern) {
    if ($text -match $pattern) { return $Matches[1] }
    return "?"
}

adb logcat -b all -c
adb shell dumpsys gfxinfo cz.reallyfast reset | Out-Null
"efekt | sekund | snimku | janky % | p50 | p90 | p99 | gpu p50 | gpu p99 (ms)" | Out-File $summary -Encoding utf8
Write-Host "Bezi. Testuj prvni efekt na telefonu (alespon 60 s). Az skoncis, napis sem jeho nazev a Enter. Prazdny Enter = konec."
$start = Get-Date
while ($true) {
    $name = Read-Host "Nazev prave dotestovaneho efektu"
    if ([string]::IsNullOrWhiteSpace($name)) { break }
    $secs = [int]((Get-Date) - $start).TotalSeconds
    $text = (adb shell dumpsys gfxinfo cz.reallyfast) -join "`n"
    adb shell dumpsys gfxinfo cz.reallyfast reset | Out-Null
    $start = Get-Date
    $safe = ($name -replace '[^\w\-]', '_')
    $file = Join-Path $runs "$safe.txt"
    $n = 2
    while (Test-Path $file) { $file = Join-Path $runs "$safe-$n.txt"; $n++ }
    $text | Out-File $file -Encoding utf8
    $row = @(
        $name, $secs,
        (Get-Stat $text '(?m)^Total frames rendered: (\d+)'),
        (Get-Stat $text '(?m)^Janky frames: \d+ \(([\d.,]+)%\)'),
        (Get-Stat $text '(?m)^50th percentile: (\d+)ms'),
        (Get-Stat $text '(?m)^90th percentile: (\d+)ms'),
        (Get-Stat $text '(?m)^99th percentile: (\d+)ms'),
        (Get-Stat $text '(?m)^50th gpu percentile: (\d+)ms'),
        (Get-Stat $text '(?m)^99th gpu percentile: (\d+)ms')
    ) -join " | "
    $row | Add-Content $summary -Encoding utf8
    Write-Host $row
    # Log se uklada po kazdem useku, aby se neztratil ani pri Ctrl+C.
    adb logcat -d -b main,crash fAZT:V AndroidRuntime:E DEBUG:E "*:S" | Out-File -FilePath $log -Encoding utf8
    Write-Host "Ulozeno. Dalsi efekt, nebo prazdny Enter."
}

adb logcat -d -b main,crash fAZT:V AndroidRuntime:E DEBUG:E "*:S" | Out-File -FilePath $log -Encoding utf8
Write-Host ""
Write-Host "Hotovo. Tabulka: $summary, podrobnosti: $runs, log: $log"
