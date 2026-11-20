$script:SyncRoot = $PSScriptRoot
$script:ConfigFile = Join-Path $script:SyncRoot 'sync-config.json'
$script:SessionFile = Join-Path $script:SyncRoot 'sync-session.dat'

function Read-SyncConfig {
    if (-not (Test-Path -LiteralPath $script:ConfigFile)) { throw '尚未连接 Supabase。请先运行 SetupSync.ps1。' }
    $config = [IO.File]::ReadAllText($script:ConfigFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    if ([string]$config.url -notmatch '^https://[^/]+\.supabase\.co$' -or
        [string]$config.key -notmatch '^sb_publishable_') {
        throw '同步配置中的 URL 或 publishable key 无效。'
    }
    return $config
}

function Save-SyncSession($session) {
    Add-Type -AssemblyName System.Security
    $expires = if ($session.expires_at) { [long]$session.expires_at } else {
        [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + [long]$session.expires_in
    }
    $content = @{access_token=[string]$session.access_token;refresh_token=[string]$session.refresh_token;expires_at=$expires}
    if (-not $content.access_token -or -not $content.refresh_token) { throw '登录信息不完整。' }
    $bytes = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $content -Compress))
    $protected = [Security.Cryptography.ProtectedData]::Protect(
        $bytes, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    [IO.File]::WriteAllText($script:SessionFile,[Convert]::ToBase64String($protected),[Text.Encoding]::ASCII)
}

function Read-SyncSession {
    if (-not (Test-Path -LiteralPath $script:SessionFile)) { throw '尚未登录。请运行 SetupSync.ps1。' }
    Add-Type -AssemblyName System.Security
    $protected = [Convert]::FromBase64String(([IO.File]::ReadAllText($script:SessionFile)).Trim())
    $bytes = [Security.Cryptography.ProtectedData]::Unprotect(
        $protected, $null, [Security.Cryptography.DataProtectionScope]::CurrentUser)
    return ([Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json)
}

function Invoke-SyncAuth([string]$grant,[object]$body) {
    $config = Read-SyncConfig
    $json = ConvertTo-Json -InputObject $body -Depth 5 -Compress
    return Invoke-RestMethod -Uri "$($config.url)/auth/v1/token?grant_type=$grant" -Method Post `
        -Headers @{apikey=[string]$config.key} -ContentType 'application/json' `
        -Body ([Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 20
}

function Get-SyncAccessToken {
    $session = Read-SyncSession
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    if ([long]$session.expires_at -le $now + 60) {
        $session = Invoke-SyncAuth 'refresh_token' @{refresh_token=[string]$session.refresh_token}
        Save-SyncSession $session
    }
    return [string]$session.access_token
}

function Invoke-CalendarApi([string]$method,[string]$path,[object]$body=$null) {
    $config = Read-SyncConfig
    $token = Get-SyncAccessToken
    $args = @{
        Uri="$($config.url)$path"; Method=$method; TimeoutSec=25;
        Headers=@{apikey=[string]$config.key;Authorization="Bearer $token";Prefer='return=minimal'}
    }
    if ($null -ne $body) {
        $args.ContentType = 'application/json'
        $args.Body = [Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $body -Depth 10 -Compress))
    }
    return Invoke-RestMethod @args
}

