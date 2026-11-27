# Testovaci relace: vynuluje log a statistiku, pak ceka na Ctrl+C a ulozi log klavesnice i statistiku snimku.
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$log = Join-Path $dir "log.txt"
$fps = Join-Path $dir "fps.txt"

adb logcat -b all -c
adb shell dumpsys gfxinfo cz.reallyfast reset | Out-Null
Write-Host "Bezi. Testuj na telefonu. Az skoncis, stiskni Ctrl+C."

try {
    while ($true) { Start-Sleep -Seconds 1 }
}
finally {
    adb logcat -d -b main,crash fAZT:V AndroidRuntime:E DEBUG:E "*:S" | Out-File -FilePath $log -Encoding utf8
    adb shell dumpsys gfxinfo cz.reallyfast | Out-File -FilePath $fps -Encoding utf8
    Write-Host ""
    Write-Host "Hotovo. Ulozeno: $log a $fps"
}
