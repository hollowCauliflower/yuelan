# 月澜定制图标打包 - 方式一：收邮件
# 由“定制打包-收邮件.bat”双击调起；脚本自动连邮箱扫描未读邮件、下载图片、打包并回信。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Host.UI.RawUI.WindowTitle = "月澜定制图标打包 - 收邮件"
Set-Location -LiteralPath $PSScriptRoot
. (Join-Path $PSScriptRoot 'pack-common.ps1')

Write-Host "=========================================="
Write-Host "  月澜定制图标打包 - 方式一：收邮件"
Write-Host "=========================================="
Write-Host ""
Write-Host "运行前请确认 icon_packager.config.json 里已填好邮箱和授权码。"
Write-Host ""

$node = Get-Command node -ErrorAction SilentlyContinue
if ($null -eq $node) {
    Write-Host "没检测到 Node.js，打包需要它。" -ForegroundColor Red
    Write-Host "请到 https://nodejs.org 下载 LTS 版安装（一路下一步），装完重新双击本脚本。" -ForegroundColor Red
    Read-Host "按回车键关闭窗口"
    exit 1
}

# 打包前确认版本号，避免忘记修改 version.properties
$version = Confirm-Version

# 选择本次打包使用的签名：默认走 signing.properties 配置的 release key
$useRelease = Select-SigningKey

$packArgs = @("icon_packager.js", "--from-email")
if ($useRelease) {
    $packArgs += "--release"
}
& node @packArgs
$code = $LASTEXITCODE
Write-Host ""
if ($code -eq 0) {
    Write-Host "处理完成，APK 在 tools\dist\ 目录里。" -ForegroundColor Green
    Start-Process -FilePath "explorer.exe" -ArgumentList "`"$PSScriptRoot\dist`""
} else {
    Write-Host "处理失败（退出码 $code），请把上面的红色报错截图发给我。" -ForegroundColor Red
}
Read-Host "按回车键关闭窗口"
