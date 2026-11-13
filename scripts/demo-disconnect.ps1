param(
  [Parameter(Mandatory = $true)][string]$PortName,
  [switch]$Restore,
  [ValidateRange(20, 300)][int]$TimeoutSeconds = 60
)
$ErrorActionPreference = 'Stop'
$name = $PortName.Trim().ToUpperInvariant()
if ($name -notmatch '^(AC|DC)-\d{2,}-[A-Z0-9-]+$') {
  throw 'PortName must look like AC-03-TYPE2 or DC-01-CCS2.'
}
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
  $rows = @(docker compose exec -T postgres psql -qAt -v ON_ERROR_STOP=1 -U campus_charge -d campus_charge -c "SELECT station_id::text || ':' || id::text || ':' || display_name FROM ports WHERE upper(display_name)='$name';" | Where-Object { "$_".Trim() })
  if ($LASTEXITCODE -ne 0) { throw 'Cannot read ports. Check Docker Compose.' }
  if ($rows.Count -ne 1) { throw "Port '$PortName' was not found or is ambiguous. Check the name shown in the station list." }
  $parts = "$($rows[0])".Trim().Split(':')
  $station, $port = $parts[0], $parts[1]
  $displayName = $parts[2].ToUpperInvariant()
  $action = if ($Restore) { 'RECONNECT_PORT' } else { 'DISCONNECT_PORT' }
  $sentAt = [DateTimeOffset]::UtcNow.ToString('o')
  $body = @{ eventId=[guid]::NewGuid().ToString(); stationId=$station; portId=$port; action=$action; timestamp=$sentAt; schemaVersion=1 } | ConvertTo-Json -Compress
  $previousScenario = $env:SCENARIO
  $previousTopic = $env:TOPIC
  try {
    # Environment transport preserves JSON quotes in Windows PowerShell 5.1.
    $env:SCENARIO = $body
    $env:TOPIC = "campus/$station/$port/scenarios"
    docker compose --profile tools run --rm -e SCENARIO -e TOPIC mqtt-tools
    if ($LASTEXITCODE -ne 0) { throw 'Cannot publish scenario.' }
  } finally {
    $env:SCENARIO = $previousScenario
    $env:TOPIC = $previousTopic
  }
  Write-Host "Waiting for backend confirmation: $action on $displayName"
  $condition = if ($Restore) { "p.last_seen_at > '$sentAt'::timestamptz" } else { "p.status='OFFLINE' AND EXISTS (SELECT 1 FROM incidents i WHERE i.port_id=p.id AND i.status<>'RESOLVED' AND i.description='HEARTBEAT_TIMEOUT')" }
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    $result = docker compose exec -T postgres psql -qAt -v ON_ERROR_STOP=1 -U campus_charge -d campus_charge -c "SELECT CASE WHEN $condition THEN 'CONFIRMED' ELSE p.status END FROM ports p WHERE p.id='$port';"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify device state.' }
    if ("$result".Trim() -eq 'CONFIRMED') {
      if ($Restore) { Write-Host 'Connection restored. Pending STOP can now be acknowledged. Staff must resolve the incident before reopening the port.' }
      else { Write-Host "OFFLINE confirmed for $displayName; incident recorded. Admin: receive and assign the incident, record the repair report, check the device, then confirm reopening the port." }
      return
    }
    Start-Sleep -Seconds 1
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "No confirmation within $TimeoutSeconds seconds (state: $result). Check simulator logs and ensure the updated simulator image is running."
} finally {
  Pop-Location
}
