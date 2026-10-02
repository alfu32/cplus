$ErrorActionPreference = 'Stop'

$project = 'C:\Users\alfu64\Development\cplus'
Set-Location $project

Write-Output "host=$(hostname)"
Write-Output "location=$(Get-Location)"
Write-Output "timestamp=$(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK')"
Write-Output '--- git status ---'
git status --short
Write-Output '--- git head ---'
git log -1 --oneline
Write-Output '--- toolchain ---'
java -version
clang --version | Select-Object -First 3
Write-Output '--- MinGW UCRT64 ---'
where.exe gcc
gcc --version | Select-Object -First 3
