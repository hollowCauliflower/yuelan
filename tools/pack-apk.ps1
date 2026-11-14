# 月澜普通 APK 打包：版本号确认 + 签名选择 + clean 构建，双击“打包普通APK.bat”即可运行。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Host.UI.RawUI.WindowTitle = "月澜普通 APK 打包"
Set-Location -LiteralPath $PSScriptRoot
. (Join-Path $PSScriptRoot 'pack-common.ps1')

Write-Host "=========================================="
Write-Host "  月澜普通 APK 打包"
Write-Host "=========================================="
Write-Host ""

$version = Confirm-Version
$useRelease = Select-SigningKey

Write-Host ""
Write-Host "准备打包：VERSION_CODE=$($version.Code)，VERSION_NAME=$($version.Name)，$(if ($useRelease) { 'release' } else { 'debug' })" -ForegroundColor Cyan

$ok = Invoke-GradleBuild -UseRelease $useRelease
Write-Host ""
if (-not $ok) {
    Write-Host "打包失败，请把上面的报错截图发给我。" -ForegroundColor Red
    Read-Host "按回车键关闭窗口"
    exit 1
}

$apk = Get-LatestApk -UseRelease $useRelease
if ([string]::IsNullOrWhiteSpace($apk)) {
    Write-Host "打包失败：没有找到生成的 APK。" -ForegroundColor Red
    Read-Host "按回车键关闭窗口"
    exit 1
}

if ($useRelease -and $apk -match 'unsigned') {
    Write-Host "打包完成，但 release APK 未签名，无法直接安装。请检查 signing.properties。" -ForegroundColor Red
    Write-Host "APK：$apk"
    Read-Host "按回车键关闭窗口"
    exit 1
}

$sizeMb = [math]::Round((Get-Item -LiteralPath $apk).Length / 1MB, 2)
Write-Host "打包成功：$apk" -ForegroundColor Green
Write-Host "APK 大小：$sizeMb MB" -ForegroundColor Green
Start-Process -FilePath "explorer.exe" -ArgumentList "/select,`"$apk`""
Read-Host "按回车键关闭窗口"
