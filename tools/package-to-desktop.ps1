$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$DesktopDir = Join-Path $env:USERPROFILE "Desktop\MyLunarCore"
$JarName = "MyLunarCore.jar"

Write-Host ">>> 编译打包..."
Push-Location $ProjectRoot
try {
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 打包失败，exit code=$LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$JarSource = Join-Path $ProjectRoot "target\$JarName"
if (-not (Test-Path $JarSource)) {
    throw "未找到 JAR: $JarSource"
}

Write-Host ">>> 复制到桌面 $DesktopDir ..."
New-Item -ItemType Directory -Force -Path $DesktopDir | Out-Null
Copy-Item $JarSource (Join-Path $DesktopDir $JarName) -Force

# 可选：复制外部 data 目录，便于桌面环境热更 Banners.json
$DataSource = Join-Path $ProjectRoot "data"
if (Test-Path $DataSource) {
    Copy-Item $DataSource (Join-Path $DesktopDir "data") -Recurse -Force
}

@'
@echo off
chcp 65001 >nul
echo === MyLunarCore 启动完整服务器 ===
echo 需要本地 MySQL (lunarcore 库)，按 Ctrl+C 停止
java -jar "%~dp0MyLunarCore.jar"
pause
'@ | Set-Content -Path (Join-Path $DesktopDir "run-server.bat") -Encoding UTF8

@'
MyLunarCore 桌面运行包
======================

启动完整游戏服务器（需 MySQL）
   双击 run-server.bat
   或: java -jar MyLunarCore.jar
'@ | Set-Content -Path (Join-Path $DesktopDir "使用说明.txt") -Encoding UTF8

Write-Host ">>> 完成！文件已输出到: $DesktopDir"
Get-ChildItem $DesktopDir | Format-Table Name, Length, LastWriteTime
