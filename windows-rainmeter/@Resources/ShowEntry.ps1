param(
    [Parameter(Mandatory=$true)][string]$Id,
    [switch]$TextOnly
)

$ErrorActionPreference = 'Stop'
if ($Id -notmatch '^[0-9a-fA-F]{32}$') { exit 0 }
$dataFile = Join-Path $PSScriptRoot 'events.json'
if (-not (Test-Path -LiteralPath $dataFile)) { exit 0 }

$loaded = [IO.File]::ReadAllText($dataFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
$entry = @($loaded | Where-Object { [string]$_.id -eq $Id } | Select-Object -First 1)
if ($entry.Count -eq 0) { exit 0 }
$entry = $entry[0]

$heading = @([string]$entry.date, [string]$entry.type, [string]$entry.time) |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$details = [string]$entry.title
$place = if ($entry.place) { [string]$entry.place } else { [string]$entry.room }
if ($place) { $details += "`r`n`r`n场地：$place" }
$headingText = $heading -join '  ·  '
if ($TextOnly) {
    Write-Output $headingText
    Write-Output $details
    exit 0
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$form = New-Object System.Windows.Forms.Form
$form.Text = '查看完整内容'
$form.Size = New-Object System.Drawing.Size(560,260)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.MinimizeBox = $false
$form.TopMost = $true
$form.BackColor = [System.Drawing.Color]::FromArgb(39,44,52)
$form.ForeColor = [System.Drawing.Color]::White
$form.Font = New-Object System.Drawing.Font('Microsoft YaHei',10)

$header = New-Object System.Windows.Forms.Label
$header.Text = $headingText
$header.Location = New-Object System.Drawing.Point(22,20)
$header.Size = New-Object System.Drawing.Size(500,30)
$header.ForeColor = [System.Drawing.Color]::FromArgb(220,226,232)
$form.Controls.Add($header)

$body = New-Object System.Windows.Forms.TextBox
$body.Text = $details
$body.Location = New-Object System.Drawing.Point(22,55)
$body.Size = New-Object System.Drawing.Size(500,125)
$body.Multiline = $true
$body.ReadOnly = $true
$body.ScrollBars = 'Vertical'
$body.BackColor = [System.Drawing.Color]::FromArgb(55,61,70)
$body.ForeColor = [System.Drawing.Color]::White
$body.BorderStyle = 'None'
$body.Font = New-Object System.Drawing.Font('Microsoft YaHei',12)
$form.Controls.Add($body)

$hint = New-Object System.Windows.Forms.Label
$hint.Text = '20 秒后自动关闭'
$hint.Location = New-Object System.Drawing.Point(22,191)
$hint.Size = New-Object System.Drawing.Size(180,28)
$hint.ForeColor = [System.Drawing.Color]::FromArgb(175,184,193)
$form.Controls.Add($hint)

$close = New-Object System.Windows.Forms.Button
$close.Text = '关闭'
$close.Location = New-Object System.Drawing.Point(438,187)
$close.Size = New-Object System.Drawing.Size(84,30)
$close.Add_Click({ $form.Close() })
$form.Controls.Add($close)
$form.CancelButton = $close

$edit = New-Object System.Windows.Forms.Button
$edit.Text = '编辑'
$edit.Location = New-Object System.Drawing.Point(344,187)
$edit.Size = New-Object System.Drawing.Size(84,30)
$edit.Add_Click({ $form.DialogResult = [System.Windows.Forms.DialogResult]::OK; $form.Close() })
$form.Controls.Add($edit)

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 20000
$timer.Add_Tick({ $timer.Stop(); $form.Close() })
$form.Add_Shown({ $body.SelectionStart = 0; $body.SelectionLength = 0; $timer.Start() })
try { $choice = $form.ShowDialog() } finally { $timer.Dispose(); $form.Dispose() }
if ($choice -eq [System.Windows.Forms.DialogResult]::OK) {
    & (Join-Path $PSScriptRoot 'EditEntry.ps1') -Id $Id
}

