# Windows PowerShell 5.1 or PowerShell 7. No credentials as arguments.
[CmdletBinding()]
param([switch]$NoBrowser, [ValidateRange(1, 1800)][int]$TimeoutSeconds = 180)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\WindowsLauncher.ps1"
exit (Start-QLCStream -Root (Split-Path $PSScriptRoot -Parent) -NoBrowser:$NoBrowser -TimeoutSeconds $TimeoutSeconds)
