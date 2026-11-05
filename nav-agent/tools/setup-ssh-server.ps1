# setup-ssh-server.ps1
#
# Purpose: finish the Windows OpenSSH Server setup on this machine.
# The server binaries are already installed (C:\Windows\System32\OpenSSH\sshd.exe),
# but C:\ProgramData\ssh is empty, so sshd has no config and refuses to start.
#
# MUST run in an elevated PowerShell (Run as Administrator):
#   powershell -ExecutionPolicy Bypass -File "D:\workbuddy\2026-10-31-21-31-07\nav-agent\tools\setup-ssh-server.ps1"
#
# It only does local, reversible things:
#   1. copies sshd_config_default -> C:\ProgramData\ssh\sshd_config
#   2. makes sure PasswordAuthentication is on (LAN use; switch to keys later)
#   3. starts sshd and sets it to start automatically
#   4. prints the listening address so you can verify from the phone

$ErrorActionPreference = 'Stop'

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "ERROR: not elevated. Right-click PowerShell -> Run as Administrator." -ForegroundColor Red
    exit 1
}

$src = "$env:WINDIR\System32\OpenSSH\sshd_config_default"
$dir = "$env:ProgramData\ssh"
$dst = "$dir\sshd_config"

if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

if (-not (Test-Path $dst)) {
    Copy-Item $src $dst
    Write-Host "created $dst"
} else {
    Write-Host "config already exists, leaving it alone: $dst"
}

# Password auth on. The default template already says "yes", but it is often
# commented out; make it explicit so the phone can log in with the Windows password.
$lines = Get-Content $dst
$hasPassword = $lines | Where-Object { $_ -match '^\s*PasswordAuthentication\s+yes' }
if (-not $hasPassword) {
    $lines = $lines -replace '^\s*#?\s*PasswordAuthentication.*', 'PasswordAuthentication yes'
    Set-Content -Path $dst -Value $lines -Encoding ASCII
    Write-Host "enabled PasswordAuthentication"
}

# sshd: start now + auto start on boot
Start-Service sshd
Set-Service -Name sshd -StartupType Automatic

# ssh-agent is optional but handy once you switch to key auth
Set-Service -Name ssh-agent -StartupType Automatic -ErrorAction SilentlyContinue
Start-Service ssh-agent -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "---- sshd ----"
Get-Service sshd | Format-Table Name, Status, StartType -AutoSize
Write-Host "---- listening ----"
Get-NetTCPConnection -LocalPort 22 -State Listen -ErrorAction SilentlyContinue |
    Select-Object LocalAddress, LocalPort | Format-Table -AutoSize
Write-Host "---- addresses ----"
Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -like '192.168.*' -or $_.IPAddress -like '10.*' } |
    Select-Object IPAddress | Format-Table -AutoSize
Write-Host ""
Write-Host "From the phone:  ssh $env:USERNAME@<address-above>"
Write-Host "Firewall inbound rule 'OpenSSH SSH Server (sshd)' should already be enabled."
