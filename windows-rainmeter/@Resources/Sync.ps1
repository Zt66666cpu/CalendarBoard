param([switch]$DryRun)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'SupabaseApi.ps1')
$dataFile = Join-Path $PSScriptRoot 'events.json'
$baseFile = Join-Path $PSScriptRoot 'sync-base.json'
if (-not (Test-Path -LiteralPath $dataFile)) { throw '找不到本地 events.json。' }

function Read-Items([string]$path) {
    $loaded = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json
    if ($null -eq $loaded) { return ,@() }
    return ,@($loaded)
}
function Item-Hash($item) {
    $ordered = [ordered]@{}
    foreach ($property in @($item.PSObject.Properties | Sort-Object Name)) {
        $ordered[$property.Name] = $property.Value
    }
    $json = ConvertTo-Json -InputObject $ordered -Depth 10 -Compress
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($json)))).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
function Item-Map([object[]]$items) {
    $map = @{}
    foreach ($item in $items) {
        if ($null -eq $item -or [string]$item.id -notmatch '^[0-9a-fA-F]{32}$') { throw '本地或云端有无效的事项 ID。' }
        if ($map.ContainsKey([string]$item.id)) { throw '发现重复的事项 ID，同步已停止。' }
        $map[[string]$item.id] = $item
    }
    return $map
}
function Read-Base {
    $base = @{}
    if (Test-Path -LiteralPath $baseFile) {
        $loaded = [IO.File]::ReadAllText($baseFile,[Text.Encoding]::UTF8) | ConvertFrom-Json
        if ($null -ne $loaded) {
            foreach ($property in $loaded.PSObject.Properties) { $base[$property.Name] = [string]$property.Value }
        }
    }
    return $base
}
function Save-Json([string]$path,[object]$value) {
    $temp = "$path.sync-$PID.tmp"
    try {
        [IO.File]::WriteAllText($temp,(ConvertTo-Json -InputObject $value -Depth 10),[Text.Encoding]::UTF8)
        Move-Item -LiteralPath $temp -Destination $path -Force
    } finally { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force } }
}
function Fetch-Remote {
    $all = [Collections.Generic.List[object]]::new()
    $offset = 0
    do {
        $response = Invoke-CalendarApi 'GET' "/rest/v1/calendar_items?select=id,payload&order=id.asc&limit=1000&offset=$offset"
        $rows = if ($null -eq $response) { @() } else { @($response) }
        foreach ($row in $rows) {
            $item = $row.payload
            if ($null -eq $item) { throw '云端出现空事项。' }
            $item | Add-Member -NotePropertyName id -NotePropertyValue ([string]$row.id) -Force
            $all.Add($item)
        }
        $offset += $rows.Count
    } while ($rows.Count -eq 1000)
    return ,$all.ToArray()
}

$local = Read-Items $dataFile
$localMap = Item-Map $local
$base = Read-Base
$remote = Fetch-Remote
$remoteMap = Item-Map $remote
$upserts = [Collections.Generic.List[object]]::new()
$deletions = [Collections.Generic.List[string]]::new()
$conflicts = [Collections.Generic.List[string]]::new()

foreach ($id in $localMap.Keys) {
    $localHash = Item-Hash $localMap[$id]
    if ($base.ContainsKey($id) -and $base[$id] -eq $localHash) { continue }
    if ($remoteMap.ContainsKey($id)) {
        $remoteHash = Item-Hash $remoteMap[$id]
        if ($base.ContainsKey($id)) {
            if ($remoteHash -ne $base[$id] -and $remoteHash -ne $localHash) { $conflicts.Add($id); continue }
        } elseif ($remoteHash -ne $localHash) { $conflicts.Add($id); continue }
    } elseif ($base.ContainsKey($id)) { $conflicts.Add($id); continue }
    $upserts.Add($localMap[$id])
}
foreach ($id in $base.Keys) {
    if ($localMap.ContainsKey($id) -or -not $remoteMap.ContainsKey($id)) { continue }
    if ((Item-Hash $remoteMap[$id]) -ne $base[$id]) { $conflicts.Add($id); continue }
    $deletions.Add($id)
}
if ($conflicts.Count -gt 0) { throw "电脑和手机同时修改了 $($conflicts.Count) 条相同事项；已停止同步以保护两边数据。" }
if ($DryRun) {
    [pscustomobject]@{Upload=$upserts.Count;Delete=$deletions.Count;Remote=$remote.Count;Local=$local.Count;DryRun=$true}
    exit 0
}

$newRows = [Collections.Generic.List[object]]::new()
foreach ($item in $upserts) {
    if ($remoteMap.ContainsKey([string]$item.id)) {
        $id = [uri]::EscapeDataString([string]$item.id)
        $null = Invoke-CalendarApi 'PATCH' "/rest/v1/calendar_items?id=eq.$id" @{payload=$item}
    } else {
        $newRows.Add(@{id=[string]$item.id;payload=$item})
    }
}
for ($i=0; $i -lt $newRows.Count; $i+=100) {
    $end = [math]::Min($i+99,$newRows.Count-1)
    $batch = @($newRows.GetRange($i,$end-$i+1).ToArray())
    $null = Invoke-CalendarApi 'POST' '/rest/v1/calendar_items' $batch
}
foreach ($id in $deletions) {
    $encoded = [uri]::EscapeDataString($id)
    $null = Invoke-CalendarApi 'DELETE' "/rest/v1/calendar_items?id=eq.$encoded"
}

$remote = Fetch-Remote
$remoteMap = Item-Map $remote
$newBase = [ordered]@{}
foreach ($id in @($remoteMap.Keys | Sort-Object)) { $newBase[$id] = Item-Hash $remoteMap[$id] }
$sameLocal = $localMap.Count -eq $remoteMap.Count
if ($sameLocal) {
    foreach ($id in $localMap.Keys) {
        if (-not $remoteMap.ContainsKey($id) -or (Item-Hash $localMap[$id]) -ne (Item-Hash $remoteMap[$id])) {
            $sameLocal = $false; break
        }
    }
}
if (-not $sameLocal) { Save-Json $dataFile @($remote) }
Save-Json $baseFile $newBase
[pscustomobject]@{Upload=$upserts.Count;Delete=$deletions.Count;Remote=$remote.Count;Local=$local.Count;DryRun=$false}

