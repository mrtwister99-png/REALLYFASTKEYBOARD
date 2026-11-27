# Testovaci relace: vynuluje log a statistiku, sbira log klavesnice a po Ctrl+C ulozi oba vysledky.
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $dir "log.txt"
$fps = Join-Path $dir "fps.txt"

adb logcat -c
adb shell dumpsys gfxinfo cz.reallyfast reset | Out-Null
Write-Host "Bezi. Testuj na telefonu. Az skoncis, stiskni Ctrl+C."

try {
    adb logcat -s fAZT | Tee-Object -FilePath $log
}
finally {
    adb shell dumpsys gfxinfo cz.reallyfast | Out-File -FilePath $fps -Encoding utf8
    Write-Host ""
    Write-Host "Hotovo. Ulozeno: $log a $fps"
}
