$ErrorActionPreference = 'Stop'
$Source = $PSScriptRoot
while (-not (Test-Path -LiteralPath (Join-Path $Source 'VERSION')) -and (Split-Path -Parent $Source) -ne $Source) {
    $Source = Split-Path -Parent $Source
}
$Version = (Get-Content -LiteralPath (Join-Path $Source 'VERSION') -Raw).Trim()
if ($Version -notmatch '^[A-Za-z0-9._+-]+$') { throw 'Invalid bundle VERSION' }

$SystemRoot = Join-Path $env:ProgramData 'CPlus'
$UserRoot = Join-Path $HOME '.bin'
$Target = Join-Path $SystemRoot $Version
$InstallKind = 'system'
try {
    New-Item -ItemType Directory -Force -Path $SystemRoot | Out-Null
    $Probe = Join-Path $SystemRoot ('.cplus-write-test-' + [guid]::NewGuid())
    [IO.File]::WriteAllText($Probe, '')
    Remove-Item -LiteralPath $Probe -Force
} catch {
    $InstallKind = 'user'
    $Target = Join-Path $UserRoot ('CPlus-' + $Version)
    New-Item -ItemType Directory -Force -Path $Target | Out-Null
}

New-Item -ItemType Directory -Force -Path $Target | Out-Null
if ([IO.Path]::GetFullPath($Source).TrimEnd('\') -ine [IO.Path]::GetFullPath($Target).TrimEnd('\')) {
    Get-ChildItem -LiteralPath $Source -Force | Copy-Item -Destination $Target -Recurse -Force
}

$UserPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$PathEntries = @($UserPath -split ';' | Where-Object { $_ })
if (-not ($PathEntries | Where-Object { $_.TrimEnd('\') -ieq $Target.TrimEnd('\') })) {
    [Environment]::SetEnvironmentVariable('Path', (($PathEntries + $Target) -join ';'), 'User')
}

$CmdRc = Join-Path $HOME '.cmdrc'
$Marker = "rem CPLUS $Version"
if (-not (Test-Path -LiteralPath $CmdRc)) {
    Set-Content -LiteralPath $CmdRc -Encoding ASCII -Value "@echo off`r`nrem CPLUS_CREATED_BY_CPLUS`r`n"
    Write-Host "Created $CmdRc."
}
if (-not (Select-String -LiteralPath $CmdRc -SimpleMatch $Marker -Quiet)) {
    Add-Content -LiteralPath $CmdRc -Encoding ASCII -Value "`r`n$Marker`r`nset `"CPLUS_HOME=$Target`"`r`nset `"PATH=$Target;%PATH%`"`r`n"
}
$RegistryPath = 'Software\Microsoft\Command Processor'
$Key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey($RegistryPath)
$Existing = [string]$Key.GetValue('AutoRun', '')
$CallCmdRc = "if exist `"$CmdRc`" call `"$CmdRc`""
if (-not $Existing.Contains($CallCmdRc)) {
    $NewAutoRun = if ($Existing) { "$Existing & $CallCmdRc" } else { $CallCmdRc }
    $Key.SetValue('AutoRun', $NewAutoRun, [Microsoft.Win32.RegistryValueKind]::String)
    Write-Host "Registered $CmdRc in HKCU\Software\Microsoft\Command Processor\AutoRun."
}
$Key.Close()

$Menu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\C+'
New-Item -ItemType Directory -Force -Path $Menu | Out-Null
$ConsolePath = Join-Path $Menu 'C+ Developer Console.cmd'
Set-Content -LiteralPath $ConsolePath -Encoding ASCII -Value "@echo off`r`ntitle C+ Developer Console`r`nset `"CPLUS_HOME=$Target`"`r`nset `"PATH=$Target;%PATH%`"`r`n"
$ShortcutPath = Join-Path $Menu 'C+ Developer Console.lnk'
$Shell = New-Object -ComObject WScript.Shell
$Shortcut = $Shell.CreateShortcut($ShortcutPath)
$Shortcut.TargetPath = $env:ComSpec
$Shortcut.Arguments = "/K `"$ConsolePath`""
$Shortcut.WorkingDirectory = $Target
$Shortcut.Description = 'C-plus developer command prompt'
$Shortcut.Save()

Write-Host "C-plus $Version installed ($InstallKind) to $Target"
Write-Host "Added to the current user's PATH. Open a new terminal; .cmdrc is loaded through the Command Processor AutoRun registry entry."
Write-Host "Developer Console shortcut: $ShortcutPath"
