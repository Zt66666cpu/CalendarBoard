param([Parameter(Mandatory=$true)][string]$Id)

$ErrorActionPreference = 'Stop'
if ($Id -notmatch '^[0-9a-fA-F]{32}$') { exit 0 }
$dataFile = Join-Path $PSScriptRoot 'events.json'
if (-not (Test-Path -LiteralPath $dataFile)) { exit 0 }

$loaded = [IO.File]::ReadAllText($dataFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
$entries = if ($null -eq $loaded) { @() } else { @($loaded) }
$remaining = @($entries | Where-Object { [string]$_.id -ne $Id })
if ($remaining.Count -eq $entries.Count) { exit 0 }

$json = ConvertTo-Json -InputObject @($remaining) -Depth 5
$temp = "$dataFile.tmp"
[IO.File]::WriteAllText($temp, $json, [Text.Encoding]::UTF8)
Move-Item -LiteralPath $temp -Destination $dataFile -Force

