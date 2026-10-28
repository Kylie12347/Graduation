# 在PowerShell中执行此脚本；也可以直接在IDEA运行CabinetApplication。
$ErrorActionPreference = 'Stop'
$backendPath = Join-Path $PSScriptRoot '..\backend'
Push-Location $backendPath
try {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw '请先安装JDK 17并配置JAVA_HOME/PATH' }
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw '请先安装Maven并配置PATH，或使用IDEA内置Maven' }
    & mvn spring-boot:run
} finally { Pop-Location }
