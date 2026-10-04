$ErrorActionPreference = 'Stop'
Set-Location 'C:\Users\alfu64\Development\cplus'
Write-Output 'validation checkout: cplus'
git status --short
git log -1 --oneline
Get-CimInstance Win32_Process | Where-Object { $_.Name -in @('cmake.exe','clang.exe') -and $_.CommandLine -like '*cplus*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
foreach ($path in @('parser-tree-sitter/build/native-ktreesitter-cmake', 'parser-tree-sitter/build/native-parser-cmake')) {
    if (Test-Path $path) { Remove-Item -Recurse -Force $path }
}
Write-Output ('cmake=' + ((Get-Command cmake).Source))
Write-Output ('clang=' + ((Get-Command clang).Source))
Write-Output ('ninja=' + ((Get-Command ninja -ErrorAction SilentlyContinue).Source))
$env:CPATH = 'C:\msys64\ucrt64\include'
$env:LIBRARY_PATH = 'C:\msys64\ucrt64\lib'
$env:PATH = 'C:\msys64\ucrt64\bin;' + $env:PATH
$env:CPLUS_TEST_COMPILER = 'gcc'
Write-Output ('CPATH=' + $env:CPATH)
Write-Output ('PATH=' + $env:PATH)
& .\gradlew.bat --offline --no-daemon :parser-tree-sitter:jvmTest :parser-tree-sitter:testTreeSitterGrammar --max-workers=1 --console=plain
$result = $LASTEXITCODE
Write-Output ('parser test exit=' + $result)
exit $result
