$ErrorActionPreference = 'Stop'
$Source = $PSScriptRoot
while (-not (Test-Path -LiteralPath (Join-Path $Source 'VERSION')) -and (Split-Path -Parent $Source) -ne $Source) {
    $Source = Split-Path -Parent $Source
}
$Version = (Get-Content -LiteralPath (Join-Path $Source 'VERSION') -Raw).Trim()
$Candidates = @(
    (Join-Path (Join-Path $env:ProgramData 'CPlus') $Version),
    (Join-Path (Join-Path $HOME '.bin') ('CPlus-' + $Version))
)
$Target = $Candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $Target) {
    Write-Host "C-plus $Version is not installed in the standard Windows locations."
    exit 0
}
if ($Target -notin $Candidates) { throw "Refusing to remove unexpected path: $Target" }

$Icon = Join-Path $Target 'icons\cplus.ico'
$BackupRoot = 'HKCU:\Software\CPlus\FileIconBackups'
foreach ($Extension in @('.cp', '.c+')) {
    $IconKey = "HKCU:\Software\Classes\SystemFileAssociations\$Extension\DefaultIcon"
    $BackupKey = Join-Path $BackupRoot $Extension
    if (-not (Test-Path -LiteralPath $IconKey)) { continue }
    $Current = (Get-Item -LiteralPath $IconKey).GetValue('')
    if ($Current -ne "`"$Icon`",0") { continue }
    $Backup = Get-Item -LiteralPath $BackupKey -ErrorAction SilentlyContinue
    if ($null -ne $Backup -and $Backup.GetValue('OriginalPresent', 0) -eq 1) {
        Set-Item -LiteralPath $IconKey -Value $Backup.GetValue('OriginalValue', '')
    } else {
        Remove-Item -LiteralPath $IconKey -Recurse -Force
    }
    Remove-Item -LiteralPath $BackupKey -Recurse -Force -ErrorAction SilentlyContinue
}
if ((Test-Path -LiteralPath $BackupRoot) -and -not (Get-ChildItem -LiteralPath $BackupRoot -Force | Select-Object -First 1)) {
    Remove-Item -LiteralPath $BackupRoot -Force
}

$UserPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$NewPath = @($UserPath -split ';' | Where-Object { $_ -and $_.TrimEnd('\') -ine $Target.TrimEnd('\') }) -join ';'
[Environment]::SetEnvironmentVariable('Path', $NewPath, 'User')

$Menu = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\C+'
$ShortcutPath = Join-Path $Menu 'C+ Developer Console.lnk'
if (Test-Path -LiteralPath $ShortcutPath) { Remove-Item -LiteralPath $ShortcutPath -Force }
$ConsolePath = Join-Path $Menu 'C+ Developer Console.cmd'
if (Test-Path -LiteralPath $ConsolePath) { Remove-Item -LiteralPath $ConsolePath -Force }
if ((Test-Path -LiteralPath $Menu) -and -not (Get-ChildItem -LiteralPath $Menu -Force | Select-Object -First 1)) {
    Remove-Item -LiteralPath $Menu -Force
}

$CmdRc = Join-Path $HOME '.cmdrc'
$Marker = "rem CPLUS $Version"
if (Test-Path -LiteralPath $CmdRc) {
    $Lines = Get-Content -LiteralPath $CmdRc
    $CreatedCmdRc = $Lines -contains 'rem CPLUS_CREATED_BY_CPLUS'
    $Filtered = @()
    $Skip = $false
    foreach ($Line in $Lines) {
        if ($Line -eq $Marker) { $Skip = $true; continue }
        if ($Skip -and $Line -match '^set "(CPLUS_HOME|PATH)=') { continue }
        $Skip = $false
        $Filtered += $Line
    }
    if ($CreatedCmdRc) { $Filtered = @($Filtered | Where-Object { $_ -ne 'rem CPLUS_CREATED_BY_CPLUS' -and $_ -ne '@echo off' }) }
    if ($Filtered.Count -eq 0 -or ($CreatedCmdRc -and -not ($Filtered | Where-Object { $_.Trim() }))) {
        Remove-Item -LiteralPath $CmdRc -Force
    } else {
        Set-Content -LiteralPath $CmdRc -Encoding ASCII -Value $Filtered
    }
}

$RegistryPath = 'Software\Microsoft\Command Processor'
$Key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($RegistryPath, $true)
$HasOtherCplusEntries = (Test-Path -LiteralPath $CmdRc) -and (Select-String -LiteralPath $CmdRc -Pattern '^rem CPLUS ' -Quiet)
if ($null -ne $Key -and -not $HasOtherCplusEntries) {
    $Existing = [string]$Key.GetValue('AutoRun', '')
    $CallCmdRc = "if exist `"$CmdRc`" call `"$CmdRc`""
    $Updated = $Existing.Replace(" & $CallCmdRc", '').Replace($CallCmdRc, '').Trim(' ', '&')
    if ($Updated -ne $Existing) {
        if ([string]::IsNullOrWhiteSpace($Updated)) {
            $Key.DeleteValue('AutoRun', $false)
        } else {
            $Key.SetValue('AutoRun', $Updated, [Microsoft.Win32.RegistryValueKind]::String)
        }
        Write-Host 'Removed C-plus AutoRun entry; unrelated AutoRun commands were preserved.'
    }
    $Key.Close()
} elseif ($null -ne $Key) {
    $Key.Close()
}

Remove-Item -LiteralPath $Target -Recurse -Force
Write-Host "Removed C-plus $Version from $Target and removed its user PATH entry and shortcut."
if (Test-Path -LiteralPath $CmdRc) { Write-Host "Preserved other .cmdrc content at $CmdRc." }
