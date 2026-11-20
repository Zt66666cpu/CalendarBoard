param(
    [Parameter(Mandatory=$true)][string]$Date,
    [ValidateSet('日程','课程','待办')][string]$DefaultType = '日程'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$form = New-Object System.Windows.Forms.Form
$form.Text = "添加安排 · $Date"
$form.Size = New-Object System.Drawing.Size(420,362)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.MinimizeBox = $false
$form.TopMost = $true
$form.Font = New-Object System.Drawing.Font('Microsoft YaHei',10)

function Label($text,$x,$y) {
    $c = New-Object System.Windows.Forms.Label
    $c.Text=$text; $c.Location=New-Object System.Drawing.Point($x,$y)
    $c.Size=New-Object System.Drawing.Size(80,26)
    $form.Controls.Add($c)
}
Label '内容' 22 28
$title = New-Object System.Windows.Forms.TextBox
$title.Location=New-Object System.Drawing.Point(105,25)
$title.Size=New-Object System.Drawing.Size(245,29)
$form.Controls.Add($title)
Label '开始时间' 22 76
$startTime = New-Object System.Windows.Forms.TextBox
$startTime.Location=New-Object System.Drawing.Point(105,73)
$startTime.Size=New-Object System.Drawing.Size(245,29)
$form.Controls.Add($startTime)
Label '结束时间' 22 119
$endTime = New-Object System.Windows.Forms.TextBox
$endTime.Location=New-Object System.Drawing.Point(105,116)
$endTime.Size=New-Object System.Drawing.Size(245,29)
$form.Controls.Add($endTime)
Label '类别' 22 162
$type = New-Object System.Windows.Forms.ComboBox
$type.DropDownStyle='DropDownList'
$type.Location=New-Object System.Drawing.Point(105,159)
$type.Size=New-Object System.Drawing.Size(245,29)
[void]$type.Items.AddRange(@('日程','课程','待办'))
$type.SelectedItem=$DefaultType
$form.Controls.Add($type)
Label '场地' 22 205
$place = New-Object System.Windows.Forms.TextBox
$place.Location=New-Object System.Drawing.Point(105,202)
$place.Size=New-Object System.Drawing.Size(245,29)
$form.Controls.Add($place)
$hint = New-Object System.Windows.Forms.Label
$hint.Text='时间和场地可留空。'
$hint.Location=New-Object System.Drawing.Point(105,243)
$hint.Size=New-Object System.Drawing.Size(275,28)
$hint.ForeColor=[System.Drawing.Color]::Gray
$form.Controls.Add($hint)
$save = New-Object System.Windows.Forms.Button
$save.Text='保存'
$save.Location=New-Object System.Drawing.Point(264,282)
$save.Size=New-Object System.Drawing.Size(86,32)
$save.Add_Click({
    if ([string]::IsNullOrWhiteSpace($title.Text)) {
        [System.Windows.Forms.MessageBox]::Show('请先填写内容。') | Out-Null
        return
    }
    $startValue = $startTime.Text.Trim()
    $endValue = $endTime.Text.Trim()
    if (($startValue -and $startValue -notmatch '^([01]\d|2[0-3]):[0-5]\d$') -or
        ($endValue -and $endValue -notmatch '^([01]\d|2[0-3]):[0-5]\d$')) {
        [System.Windows.Forms.MessageBox]::Show('时间请写成 08:30 这样的格式。') | Out-Null
        return
    }
    if ($endValue -and -not $startValue) {
        [System.Windows.Forms.MessageBox]::Show('填写结束时间前，请先填写开始时间。') | Out-Null
        return
    }
    if ($endValue -and [timespan]::Parse($endValue) -le [timespan]::Parse($startValue)) {
        [System.Windows.Forms.MessageBox]::Show('结束时间要晚于开始时间。') | Out-Null
        return
    }
    $form.DialogResult='OK'
    $form.Close()
})
$form.Controls.Add($save)
$form.AcceptButton=$save
$cancel = New-Object System.Windows.Forms.Button
$cancel.Text='取消'
$cancel.Location=New-Object System.Drawing.Point(170,282)
$cancel.Size=New-Object System.Drawing.Size(86,32)
$cancel.Add_Click({$form.DialogResult='Cancel';$form.Close()})
$form.Controls.Add($cancel)
if ($form.ShowDialog() -ne 'OK') { exit 0 }

$dataFile = Join-Path $PSScriptRoot 'events.json'
$entries = @()
if (Test-Path -LiteralPath $dataFile) {
    $loaded = [IO.File]::ReadAllText($dataFile,[Text.Encoding]::UTF8) | ConvertFrom-Json
    if ($null -ne $loaded) { $entries = @($loaded) }
}
$startValue = $startTime.Text.Trim()
$endValue = $endTime.Text.Trim()
$timeValue = if ($endValue) { "$startValue-$endValue" } else { $startValue }
$entries += [pscustomobject]@{
    id = [guid]::NewGuid().ToString('N')
    date = $Date
    time = $timeValue
    startTime = $startValue
    endTime = $endValue
    type = [string]$type.SelectedItem
    title = $title.Text.Trim()
    place = $place.Text.Trim()
    done = $false
}
$json = ConvertTo-Json -InputObject @($entries) -Depth 5
$temp = "$dataFile.tmp"
[IO.File]::WriteAllText($temp, $json, [Text.Encoding]::UTF8)
Move-Item -LiteralPath $temp -Destination $dataFile -Force

