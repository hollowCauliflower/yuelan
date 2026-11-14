# 月澜定制图标打包 - 方式二：微信图片
# 由“定制打包-微信图片.bat”双击调起；脚本自动定位到 tools 目录，输入文件夹名即可打包。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Host.UI.RawUI.WindowTitle = "月澜定制图标打包 - 微信图片"
Set-Location -LiteralPath $PSScriptRoot
. (Join-Path $PSScriptRoot 'pack-common.ps1')

Write-Host "=========================================="
Write-Host "  月澜定制图标打包 - 方式二：微信图片"
Write-Host "=========================================="
Write-Host ""
Write-Host "使用步骤："
Write-Host "  1. 在 tools\inbox\ 下新建文件夹，用对方昵称命名（如：小红）"
Write-Host "  2. 把微信里收到的图片（电脑微信右键图片 -> 另存为）放进该文件夹"
Write-Host "  3. 回到本窗口，输入该文件夹名并回车"
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

$name = (Read-Host "请输入文件夹名（tools\inbox\ 下的）").Trim()
if ([string]::IsNullOrWhiteSpace($name)) {
    Write-Host "未输入文件夹名，已取消" -ForegroundColor Yellow
    Read-Host "按回车键关闭窗口"
    exit 1
}

$dir = Join-Path $PSScriptRoot "inbox\$name"
if (-not (Test-Path -LiteralPath $dir)) {
    Write-Host "找不到文件夹 tools\inbox\$name，请先创建并把图片放进去" -ForegroundColor Yellow
    Read-Host "按回车键关闭窗口"
    exit 1
}

$images = Get-ChildItem -LiteralPath $dir -File |
    Where-Object { $_.Extension -match '^(\.jpg|\.jpeg|\.png|\.webp|\.bmp|\.gif)$' }
if ($images.Count -eq 0) {
    Write-Host "文件夹里没有可用的图片（支持 jpg / png / webp / bmp / gif）" -ForegroundColor Yellow
    Read-Host "按回车键关闭窗口"
    exit 1
}
Write-Host "发现 $($images.Count) 张图片：$($images.Name -join '、')"
Write-Host ""

$reply = (Read-Host "如需同时给对方发邮件，输入对方邮箱（直接回车跳过）").Trim()

# 选择本次打包使用的签名：默认走 signing.properties 配置的 release key
$useRelease = Select-SigningKey

$packArgs = @("icon_packager.js", "--from-dir", "inbox\$name", "--name", $name)
if ($useRelease) {
    $packArgs += "--release"
}
if (-not [string]::IsNullOrWhiteSpace($reply)) {
    $packArgs += @("--reply-to", $reply)
}
& node @packArgs
$code = $LASTEXITCODE
Write-Host ""
if ($code -eq 0) {
    Write-Host "打包成功，APK 在 tools\dist\ 目录里，微信发给对方即可。" -ForegroundColor Green
    Start-Process -FilePath "explorer.exe" -ArgumentList "`"$PSScriptRoot\dist`""
} else {
    Write-Host "打包失败（退出码 $code），请把上面的红色报错截图发给我。" -ForegroundColor Red
}
Read-Host "按回车键关闭窗口"
