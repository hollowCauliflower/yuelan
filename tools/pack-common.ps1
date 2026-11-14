# 月澜打包脚本共用逻辑：版本号读写、签名选择、Gradle 构建。
# 由 pack-apk.ps1 / pack-wechat.ps1 / pack-mail.ps1 通过点源（. 空格 路径）引入。

$script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$script:VersionFile = Join-Path $script:ProjectRoot 'version.properties'
$script:Gradlew = Join-Path $script:ProjectRoot 'gradlew.bat'

# 读取 version.properties；文件缺失时回退到 1 / 1.0
function Get-VersionInfo {
    $code = 1
    $name = '1.0'
    if (Test-Path -LiteralPath $script:VersionFile) {
        foreach ($line in Get-Content -LiteralPath $script:VersionFile) {
            if ($line -match '^\s*VERSION_CODE\s*=\s*(\d+)') {
                $code = [int]$matches[1]
            } elseif ($line -match '^\s*VERSION_NAME\s*=\s*(.+)$') {
                $name = $matches[1].Trim()
            }
        }
    }
    return [PSCustomObject]@{ Code = $code; Name = $name }
}

# 写回 version.properties（保留文件头注释，不写 BOM，避免 Gradle 读取异常）
function Save-VersionInfo {
    param([int]$Code, [string]$Name)
    $content = @(
        '# 应用版本号唯一真源：改这里即可，build.gradle 会自动读取。',
        '# VERSION_CODE 每次发版必须 +1（应用更新检测用它判断新旧）。',
        "VERSION_CODE=$Code",
        "VERSION_NAME=$Name"
    ) -join "`r`n"
    [System.IO.File]::WriteAllText($script:VersionFile, $content + "`r`n",
        (New-Object System.Text.UTF8Encoding($false)))
}

# 展示当前版本并询问是否修改；返回修改后的版本对象
function Confirm-Version {
    $version = Get-VersionInfo
    Write-Host ""
    Write-Host "当前版本号：VERSION_CODE=$($version.Code)，VERSION_NAME=$($version.Name)" -ForegroundColor Cyan
    Write-Host "提示：如果是正式发布，请把 VERSION_CODE +1，否则应用内更新检测不会提示新版本。"
    $choice = (Read-Host "本次是否修改版本号？(y/N)").Trim().ToLower()
    if ($choice -eq 'y') {
        $defaultCode = $version.Code + 1
        $codeInput = (Read-Host "请输入新的 VERSION_CODE（直接回车默认 $defaultCode）").Trim()
        $newCode = $defaultCode
        if ($codeInput -match '^\d+$') {
            $newCode = [int]$codeInput
        } elseif (-not [string]::IsNullOrWhiteSpace($codeInput)) {
            Write-Host "VERSION_CODE 必须是数字，已使用默认值 $defaultCode" -ForegroundColor Yellow
        }

        $nameInput = (Read-Host "请输入新的 VERSION_NAME（直接回车保持 $($version.Name)）").Trim()
        $newName = if ([string]::IsNullOrWhiteSpace($nameInput)) { $version.Name } else { $nameInput }

        Save-VersionInfo -Code $newCode -Name $newName
        Write-Host "版本号已更新：VERSION_CODE=$newCode，VERSION_NAME=$newName" -ForegroundColor Green
        return (Get-VersionInfo)
    }

    Write-Host "保持当前版本号打包。" -ForegroundColor Yellow
    return $version
}

# 询问使用 release 还是 debug 签名；返回 $true 表示 release
function Select-SigningKey {
    Write-Host ""
    Write-Host "请选择本次打包使用的签名："
    Write-Host "  1. release key（推荐，使用 signing.properties 配置的密钥）"
    Write-Host "  2. debug key（Android Studio 默认调试签名，不能覆盖升级正式包）"
    $keyChoice = (Read-Host "请输入 1 或 2（直接回车默认 1）").Trim()
    $useRelease = $keyChoice -ne "2"

    if ($useRelease) {
        $signingFile = Join-Path $script:ProjectRoot "signing.properties"
        if (-not (Test-Path -LiteralPath $signingFile)) {
            Write-Host "未找到 signing.properties，无法使用 release key。" -ForegroundColor Red
            Write-Host "请先按 signing.properties.example 配置签名，或重新运行并选择 2 使用 debug key。" -ForegroundColor Yellow
            Read-Host "按回车键关闭窗口"
            exit 1
        }
    }
    return $useRelease
}

# 先 clean 再执行 assemble；返回是否构建成功
function Invoke-GradleBuild {
    param([bool]$UseRelease)
    $task = if ($UseRelease) { 'assembleRelease' } else { 'assembleDebug' }

    # gradlew 必须在项目根目录执行，脚本当前位于 tools，需要临时切换。
    # 把 stderr 合并后逐行转成字符串，避免 PowerShell 把 Gradle 的编译提示
    # 当成 NativeCommandError 整段标红。
    Push-Location -LiteralPath $script:ProjectRoot
    try {
        Write-Host ""
        Write-Host "清理上次构建产物（clean）…"
        & $script:Gradlew clean --offline -q 2>&1 | ForEach-Object { "$_" } | Out-Host
        $cleanExit = $LASTEXITCODE
        if ($cleanExit -ne 0) {
            Write-Host "clean 失败，继续尝试直接打包…" -ForegroundColor Yellow
        }

        Write-Host "开始打包（$task）…"
        & $script:Gradlew $task --offline -q 2>&1 | ForEach-Object { "$_" } | Out-Host
        $buildExit = $LASTEXITCODE
        return ($buildExit -eq 0)
    } finally {
        Pop-Location
    }
}

# 取对应变体目录下最新的 APK 完整路径
function Get-LatestApk {
    param([bool]$UseRelease)
    $dir = if ($UseRelease) {
        Join-Path $script:ProjectRoot 'app\build\outputs\apk\release'
    } else {
        Join-Path $script:ProjectRoot 'app\build\outputs\apk\debug'
    }
    if (-not (Test-Path -LiteralPath $dir)) {
        return $null
    }
    $apk = Get-ChildItem -LiteralPath $dir -Filter *.apk -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $apk) {
        return $null
    }
    return $apk.FullName
}
