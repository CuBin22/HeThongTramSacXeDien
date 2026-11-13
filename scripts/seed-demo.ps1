param(
  [string]$BaseUrl = 'http://localhost:8080',
  [string]$AdminEmail = $(if ($env:DEMO_ADMIN_EMAIL) { $env:DEMO_ADMIN_EMAIL } else { 'admin@campus.local' }),
  [string]$AdminPassword = $(if ($env:DEMO_ADMIN_PASSWORD) { $env:DEMO_ADMIN_PASSWORD } else { 'DemoAdmin123!' })
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSVersion.Major -lt 6) {
  [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
}
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Invoke-DemoSql([string]$Sql) {
  $result = docker compose exec -T postgres psql -qAt -v ON_ERROR_STOP=1 -U campus_charge -d campus_charge -c $Sql
  if ($LASTEXITCODE -ne 0) { throw 'Could not prepare demo accounts in the Compose PostgreSQL service.' }
  return $result
}

$escapedAdminEmail = $AdminEmail.Replace("'", "''").Trim().ToLowerInvariant()
$stationName = 'B' + [char]0x00e3 + 'i xe B' + [char]0x1ec7 + 'nh vi' + [char]0x1ec7 + 'n'
$csrfResponse = Invoke-RestMethod -SessionVariable session -Uri "$BaseUrl/api/auth/csrf"
$csrf = $csrfResponse.token

$users = @(
  @{ email = $AdminEmail; password = $AdminPassword; role = 'ADMIN' },
  @{ email = 'student@campus.local'; password = 'DemoStudent123!'; role = 'USER' }
)
$existingRows = Invoke-DemoSql "SELECT email FROM users WHERE email IN ('$escapedAdminEmail','operator@campus.local','student@campus.local');"
$existingEmails = @($existingRows | ForEach-Object { "$($_)".Trim().ToLowerInvariant() } | Where-Object { $_ })
foreach ($account in $users) {
  if ($existingEmails -contains $account.email.ToLowerInvariant()) { continue }
  try {
    Invoke-RestMethod -WebSession $session -Method Post -Uri "$BaseUrl/api/auth/register" -ContentType 'application/json; charset=utf-8' -Headers @{ 'X-CSRF-TOKEN' = $csrf } -Body (@{ email = $account.email; password = $account.password } | ConvertTo-Json) | Out-Null
  } catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 409) { throw }
  }
}

$null = Invoke-DemoSql "UPDATE users SET role = CASE email WHEN '$escapedAdminEmail' THEN 'ADMIN' ELSE 'USER' END, enabled = true WHERE email IN ('$escapedAdminEmail','student@campus.local');"
$null = Invoke-DemoSql "UPDATE users SET role='USER', enabled=false WHERE email='operator@campus.local';"

Remove-Variable session -ErrorAction SilentlyContinue
$csrfResponse = Invoke-RestMethod -SessionVariable session -Uri "$BaseUrl/api/auth/csrf"
$csrf = $csrfResponse.token
$loggedIn = Invoke-RestMethod -WebSession $session -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType 'application/x-www-form-urlencoded' -Headers @{ 'X-CSRF-TOKEN' = $csrf } -Body @{ email = $AdminEmail; password = $AdminPassword }
if ($loggedIn.role -ne 'ADMIN') { throw "Login succeeded for $AdminEmail, but the account is not ADMIN. Check the demo seed settings." }
$csrfResponse = Invoke-RestMethod -WebSession $session -Uri "$BaseUrl/api/auth/csrf"
$csrf = $csrfResponse.token

$accounts = Invoke-RestMethod -WebSession $session -Uri "$BaseUrl/api/admin/users"
$accounts = @($accounts)
$createdAdmin = $accounts | Where-Object email -eq $AdminEmail | Select-Object -First 1
if (-not $createdAdmin) { throw "Admin account $AdminEmail was not created." }
if ($createdAdmin.role -ne 'ADMIN' -or -not $createdAdmin.enabled) {
  Invoke-RestMethod -WebSession $session -Method Put -Uri "$BaseUrl/api/admin/users/$($createdAdmin.userId)" -ContentType 'application/json; charset=utf-8' -Headers @{ 'X-CSRF-TOKEN' = $csrf } -Body (@{ role = 'ADMIN'; enabled = $true } | ConvertTo-Json) | Out-Null
}
foreach ($account in $users) {
  $existing = $accounts | Where-Object email -eq $account.email | Select-Object -First 1
  if ($existing -and ($existing.role -ne $account.role -or -not $existing.enabled)) {
    Invoke-RestMethod -WebSession $session -Method Put -Uri "$BaseUrl/api/admin/users/$($existing.userId)" -ContentType 'application/json; charset=utf-8' -Headers @{ 'X-CSRF-TOKEN' = $csrf } -Body (@{ role = $account.role; enabled = $true } | ConvertTo-Json) | Out-Null
  }
  if (-not $existing) {
    throw "Demo account registration did not create $($account.email). Use a fresh database or reconcile the existing account manually."
  }
}

$stationId = (Invoke-DemoSql "SELECT id FROM stations WHERE name = U&'B\00e3i xe B\1ec7nh vi\1ec7n' ORDER BY id LIMIT 1;").Trim()
if ($stationId) {
  $station = [pscustomobject]@{ stationId = $stationId; name = $stationName }
} else {
  $createdStation = Invoke-RestMethod -WebSession $session -Method Post -Uri "$BaseUrl/api/admin/stations" -ContentType 'application/json; charset=utf-8' -Headers @{ 'X-CSRF-TOKEN' = $csrf } -Body '{"name":"B\u00e3i xe B\u1ec7nh vi\u1ec7n","connectorType":"STANDARD"}'
  $station = [pscustomobject]@{ stationId = $createdStation.stationId; name = $stationName }
}

$portsResponse = Invoke-RestMethod -WebSession $session -Uri "$BaseUrl/api/stations/$($station.stationId)/ports"
$ports = if ($null -eq $portsResponse) { @() } elseif ($portsResponse -is [array]) { $portsResponse } else { @($portsResponse) }
if (-not $ports -or $ports.Count -lt 2) {
  $missing = [Math]::Max(2 - @($ports).Count, 0)
  for ($index = 0; $index -lt $missing; $index++) {
    Invoke-RestMethod -WebSession $session -Method Post -Uri "$BaseUrl/api/admin/stations/$($station.stationId)/ports" -Headers @{ 'X-CSRF-TOKEN' = $csrf } | Out-Null
  }
}
$portsResponse = Invoke-RestMethod -WebSession $session -Uri "$BaseUrl/api/stations/$($station.stationId)/ports"
$ports = if ($null -eq $portsResponse) { @() } elseif ($portsResponse -is [array]) { $portsResponse } else { @($portsResponse) }
$activatedPorts = 0
foreach ($port in $ports) {
  if ($port.status -eq 'OFFLINE' -and $port.lastSeenAt) {
    $recoveryUri = "$BaseUrl/api/admin/ports/$($port.portId)/recover"
    $recovery = Invoke-RestMethod -WebSession $session -Method Post -Uri $recoveryUri -Headers @{ 'X-CSRF-TOKEN' = $csrf }
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(15)
    while ($recovery.status -ne 'ACKNOWLEDGED' -and [DateTimeOffset]::UtcNow -lt $deadline) {
      Start-Sleep -Seconds 1
      $recovery = Invoke-RestMethod -WebSession $session -Method Post -Uri $recoveryUri -Headers @{ 'X-CSRF-TOKEN' = $csrf }
    }
    if ($recovery.status -eq 'ACKNOWLEDGED') {
      Invoke-RestMethod -WebSession $session -Method Put -Uri "$BaseUrl/api/admin/ports/$($port.portId)" -ContentType 'application/json; charset=utf-8' -Headers @{ 'X-CSRF-TOKEN' = $csrf } -Body '{"enabled":true}' | Out-Null
      $activatedPorts++
    } else {
      Write-Host "Port $($port.portId) has not confirmed recovery yet; it remains OFFLINE."
    }
  }
}
$portsResponse = Invoke-RestMethod -WebSession $session -Uri "$BaseUrl/api/stations/$($station.stationId)/ports"
$ports = if ($null -eq $portsResponse) { @() } elseif ($portsResponse -is [array]) { $portsResponse } else { @($portsResponse) }
$simulatorPorts = @($ports | ForEach-Object { "$($station.stationId):$($_.portId)" }) -join ','
Set-Content -LiteralPath '.simulator-ports' -Value $simulatorPorts -NoNewline

$tariffs = @(Invoke-RestMethod -WebSession $session -Uri "$BaseUrl/api/admin/tariffs")
if (-not $tariffs -or -not ($tariffs | Where-Object { -not $_.stationId -and $_.active -and ([DateTimeOffset]$_.effectiveAt -le [DateTimeOffset]::UtcNow) })) {
  $null = Invoke-RestMethod -WebSession $session -Method Post -Uri "$BaseUrl/api/admin/tariffs" -ContentType 'application/json; charset=utf-8' -Headers @{ 'X-CSRF-TOKEN' = $csrf } -Body (@{ stationId = $null; pricePerKwh = 3000; effectiveAt = [DateTimeOffset]::UtcNow.ToString('o') } | ConvertTo-Json)
}

Write-Host "Demo station ready: $($station.name) ($($station.stationId))"
Write-Host 'Demo account passwords are demo-only; see README.md.'
Write-Host 'Saved the simulator station/port mapping to .simulator-ports (ignored local file).'
if ($activatedPorts -gt 0) { Write-Host "Activated $activatedPorts port(s) with a fresh simulator heartbeat." }
elseif ($ports | Where-Object { $_.status -eq 'OFFLINE' }) { Write-Host 'Ports are waiting for simulator heartbeats. New demo ports are discovered and activated automatically on their first healthy heartbeat.' }
