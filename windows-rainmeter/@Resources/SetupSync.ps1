$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'SupabaseApi.ps1')
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$form = New-Object System.Windows.Forms.Form
$form.Text = '连接手机与桌面日历'
$form.Size = New-Object System.Drawing.Size(560,360)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.MinimizeBox = $false
$form.Font = New-Object System.Drawing.Font('Microsoft YaHei',10)

function Add-Label([string]$text,[int]$y) {
    $label = New-Object System.Windows.Forms.Label
    $label.Text = $text
    $label.Location = New-Object System.Drawing.Point(20,$y)
    $label.Size = New-Object System.Drawing.Size(135,28)
    $form.Controls.Add($label)
}
function Add-Field([int]$y) {
    $field = New-Object System.Windows.Forms.TextBox
    $field.Location = New-Object System.Drawing.Point(160,$y)
    $field.Size = New-Object System.Drawing.Size(350,29)
    $form.Controls.Add($field)
    return $field
}

Add-Label '项目 URL' 30
$urlBox = Add-Field 26
Add-Label 'Publishable key' 75
$keyBox = Add-Field 71
Add-Label '日历账号邮箱' 120
$emailBox = Add-Field 116
Add-Label '日历账号密码' 165
$passwordBox = Add-Field 161
$passwordBox.UseSystemPasswordChar = $true

$hint = New-Object System.Windows.Forms.Label
$hint.Text = '使用手机页面注册的日历账号。密码仅用于这次登录，不会保存。'
$hint.Location = New-Object System.Drawing.Point(20,215)
$hint.Size = New-Object System.Drawing.Size(490,32)
$hint.ForeColor = [System.Drawing.Color]::Gray
$form.Controls.Add($hint)

$connect = New-Object System.Windows.Forms.Button
$connect.Text = '连接'
$connect.Location = New-Object System.Drawing.Point(421,260)
$connect.Size = New-Object System.Drawing.Size(89,33)
$connect.Add_Click({
    $url = $urlBox.Text.Trim().TrimEnd('/')
    $key = $keyBox.Text.Trim()
    if ($url -notmatch '^https://[^/]+\.supabase\.co$' -or $key -notmatch '^sb_publishable_') {
        [System.Windows.Forms.MessageBox]::Show('请填写 Supabase 项目 URL 和 sb_publishable_ 开头的密钥。') | Out-Null
        return
    }
    try {
        $json = ConvertTo-Json -InputObject @{email=$emailBox.Text.Trim();password=$passwordBox.Text} -Compress
        $session = Invoke-RestMethod -Uri "$url/auth/v1/token?grant_type=password" -Method Post `
            -Headers @{apikey=$key} -ContentType 'application/json' `
            -Body ([Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 20
        [IO.File]::WriteAllText($script:ConfigFile,
            (ConvertTo-Json -InputObject @{url=$url;key=$key} -Compress),[Text.Encoding]::UTF8)
        Save-SyncSession $session
        $passwordBox.Text = ''
        $form.DialogResult = [System.Windows.Forms.DialogResult]::OK
        $form.Close()
    } catch {
        [System.Windows.Forms.MessageBox]::Show('连接失败。请确认已验证邮箱、账号密码和项目连接信息。','连接失败') | Out-Null
    }
})
$form.Controls.Add($connect)
$form.AcceptButton = $connect

$cancel = New-Object System.Windows.Forms.Button
$cancel.Text = '取消'
$cancel.Location = New-Object System.Drawing.Point(322,260)
$cancel.Size = New-Object System.Drawing.Size(89,33)
$cancel.Add_Click({ $form.Close() })
$form.Controls.Add($cancel)
$form.CancelButton = $cancel

if (Test-Path -LiteralPath $script:ConfigFile) {
    try {
        $existing = [IO.File]::ReadAllText($script:ConfigFile,[Text.Encoding]::UTF8) | ConvertFrom-Json
        $urlBox.Text = [string]$existing.url
        $keyBox.Text = [string]$existing.key
    } catch { }
}
[void]$form.ShowDialog()
$form.Dispose()

