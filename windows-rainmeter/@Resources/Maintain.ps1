param([datetime]$Now = (Get-Date))

$ErrorActionPreference = 'Stop'
$dataFile = Join-Path $PSScriptRoot 'events.json'
if (-not (Test-Path -LiteralPath $dataFile)) { exit 0 }
$loaded = [IO.File]::ReadAllText($dataFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
$entries = if ($null -eq $loaded) { @() } else { @($loaded) }
$kept = [Collections.Generic.List[object]]::new()
$moved = 0
$deleted = 0
$today = $Now.Date

foreach ($entry in $entries) {
    if ($null -eq $entry) { continue }
    try {
        $entryDate = [datetime]::ParseExact([string]$entry.date, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
    } catch {
        $kept.Add($entry)
        continue
    }

    if ($entry.type -eq '待办' -and $entryDate -lt $today -and -not $entry.done) {
        $entry | Add-Member -NotePropertyName date -NotePropertyValue $today.ToString('yyyy-MM-dd') -Force
        $moved++
    }

    if ($entry.type -eq '日程' -and $entryDate -lt $today) { $deleted++; continue }
    $kept.Add($entry)
}

if ($moved -gt 0 -or $deleted -gt 0) {
    $json = ConvertTo-Json -InputObject @($kept.ToArray()) -Depth 6
    $temp = "$dataFile.maintain-$PID.tmp"
    try {
        [IO.File]::WriteAllText($temp, $json, [Text.Encoding]::UTF8)
        Move-Item -LiteralPath $temp -Destination $dataFile -Force
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force }
    }
}

[pscustomobject]@{Moved=$moved;Deleted=$deleted;Total=$kept.Count}

