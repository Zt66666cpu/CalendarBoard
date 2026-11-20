param(
    [Parameter(Mandatory=$true)][string]$Id,
    [switch]$Apply,
    [string]$NewDate = '',
    [string]$NewTitle = '',
    [string]$NewStart = '',
    [string]$NewEnd = '',
    [string]$NewType = '',
    [string]$NewPlace = ''
)

$ErrorActionPreference = 'Stop'
if ($Id -notmatch '^[0-9a-fA-F]{32}$') { exit 0 }
$dataFile = Join-Path $PSScriptRoot 'events.json'
if (-not (Test-Path -LiteralPath $dataFile)) { exit 0 }

function Assert-Fields([string]$date,[string]$title,[string]$start,[string]$end,[string]$type) {
    if ($title.Trim().Length -eq 0) { throw '内容不能为空。' }
    try { [void][datetime]::ParseExact($date, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture) }
    catch { throw '日期格式应为 2026-11-10。' }
    if ($type -notin @('课程','待办','日程')) { throw '请选择类别。' }
    if (($start -and $start -notmatch '^([01]\d|2[0-3]):[0-5]\d$') -or
        ($end -and $end -notmatch '^([01]\d|2[0-3]):[0-5]\d$')) { throw '时间格式应为 08:30。' }
    if ($end -and -not $start) { throw '填写结束时间前，请先填写开始时间。' }
    if ($end -and [timespan]::Parse($end) -le [timespan]::Parse($start)) {
        throw '结束时间要晚于开始时间。'
    }
}

function Save-Entry([string]$date,[string]$title,[string]$start,[string]$end,[string]$type,[string]$place) {
    $date=$date.Trim(); $title=$title.Trim(); $start=$start.Trim(); $end=$end.Trim(); $type=$type.Trim(); $place=$place.Trim()
    Assert-Fields $date $title $start $end $type
    $loaded = [IO.File]::ReadAllText($dataFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $entries = if ($null -eq $loaded) { @() } else { @($loaded) }
    $target = @($entries | Where-Object { [string]$_.id -eq $Id } | Select-Object -First 1)
    if ($target.Count -eq 0) { throw '这条事项已不存在，请刷新日历。' }
    $target=$target[0]
    $time = if ($end) { "$start-$end" } else { $start }
    foreach ($field in @{
        date=$date; title=$title; startTime=$start; endTime=$end;
        time=$time; type=$type; place=$place
    }.GetEnumerator()) {
        $target | Add-Member -NotePropertyName $field.Key -NotePropertyValue $field.Value -Force
    }
    if ($target.PSObject.Properties.Name -contains 'display') {
        $target | Add-Member -NotePropertyName display -NotePropertyValue $title -Force
    }
    if ($target.PSObject.Properties.Name -contains 'room') {
        $target | Add-Member -NotePropertyName room -NotePropertyValue $place -Force
    }
    $json = ConvertTo-Json -InputObject @($entries) -Depth 6
    $temp = "$dataFile.tmp"
    [IO.File]::WriteAllText($temp, $json, [Text.Encoding]::UTF8)
    Move-Item -LiteralPath $temp -Destination $dataFile -Force
}

if ($Apply) {
    Save-Entry $NewDate $NewTitle $NewStart $NewEnd $NewType $NewPlace
    exit 0
}

$loaded = [IO.File]::ReadAllText($dataFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
$matchingEntries = @($loaded | Where-Object { [string]$_.id -eq $Id } | Select-Object -First 1)
if ($matchingEntries.Count -eq 0) { exit 0 }
$entry = $matchingEntries[0]

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$form = New-Object System.Windows.Forms.Form
$form.Text = '编辑安排'
$form.Size = New-Object System.Drawing.Size(530,402)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.MinimizeBox = $false
$form.TopMost = $true
$form.Font = New-Object System.Drawing.Font('Microsoft YaHei',10)

function Add-Label([string]$text,[int]$y) {
    $label = New-Object System.Windows.Forms.Label
    $label.Text = $text
    $label.Location = New-Object System.Drawing.Point(22,$y)
    $label.Size = New-Object System.Drawing.Size(110,27)
    $form.Controls.Add($label)
}
function Add-TextBox([int]$y,[string]$value) {
    $box = New-Object System.Windows.Forms.TextBox
    $box.Location = New-Object System.Drawing.Point(145,$y)
    $box.Size = New-Object System.Drawing.Size(338,29)
    $box.Text = $value
    $form.Controls.Add($box)
    return $box
}

Add-Label '内容' 22
$titleBox = Add-TextBox 18 ([string]$entry.title)
$titleBox.Size = New-Object System.Drawing.Size(338,55)
$titleBox.Multiline = $true
$titleBox.ScrollBars = 'Vertical'

Add-Label '日期' 82
$datePicker = New-Object System.Windows.Forms.DateTimePicker
$datePicker.Location = New-Object System.Drawing.Point(145,78)
$datePicker.Size = New-Object System.Drawing.Size(338,29)
$datePicker.Format = 'Custom'
$datePicker.CustomFormat = 'yyyy-MM-dd'
try { $datePicker.Value = [datetime]::ParseExact([string]$entry.date,'yyyy-MM-dd',[Globalization.CultureInfo]::InvariantCulture) }
catch { $datePicker.Value = Get-Date }
$form.Controls.Add($datePicker)

$oldStart=[string]$entry.startTime; $oldEnd=[string]$entry.endTime
if (-not $oldStart -and [string]$entry.time -match '^(\d\d:\d\d)(?:-(\d\d:\d\d))?$') {
    $oldStart=$matches[1]; $oldEnd=$matches[2]
}
Add-Label '开始时间' 120
$startBox = Add-TextBox 116 $oldStart
Add-Label '结束时间' 158
$endBox = Add-TextBox 154 $oldEnd
Add-Label '类别' 196
$typeBox = New-Object System.Windows.Forms.ComboBox
$typeBox.Location = New-Object System.Drawing.Point(145,192)
$typeBox.Size = New-Object System.Drawing.Size(338,29)
$typeBox.DropDownStyle = 'DropDownList'
[void]$typeBox.Items.AddRange(@('课程','待办','日程'))
$typeBox.SelectedItem = [string]$entry.type
$form.Controls.Add($typeBox)

Add-Label '场地' 234
$oldPlace = if ($entry.place) { [string]$entry.place } else { [string]$entry.room }
$placeBox = Add-TextBox 230 $oldPlace

$hint = New-Object System.Windows.Forms.Label
$hint.Text = '时间可留空；修改只影响这一条事项。'
$hint.Location = New-Object System.Drawing.Point(145,274)
$hint.Size = New-Object System.Drawing.Size(338,25)
$hint.ForeColor = [System.Drawing.Color]::Gray
$form.Controls.Add($hint)

$save = New-Object System.Windows.Forms.Button
$save.Text = '保存'
$save.Location = New-Object System.Drawing.Point(399,311)
$save.Size = New-Object System.Drawing.Size(84,32)
$save.Add_Click({
    try {
        Assert-Fields ($datePicker.Value.ToString('yyyy-MM-dd')) $titleBox.Text $startBox.Text.Trim() $endBox.Text.Trim() ([string]$typeBox.SelectedItem)
    } catch {
        [System.Windows.Forms.MessageBox]::Show($_.Exception.Message,'请检查输入') | Out-Null
        return
    }
    $form.DialogResult = [System.Windows.Forms.DialogResult]::OK
    $form.Close()
})
$form.Controls.Add($save)
$form.AcceptButton = $save

$cancel = New-Object System.Windows.Forms.Button
$cancel.Text = '取消'
$cancel.Location = New-Object System.Drawing.Point(304,311)
$cancel.Size = New-Object System.Drawing.Size(84,32)
$cancel.Add_Click({ $form.DialogResult = [System.Windows.Forms.DialogResult]::Cancel; $form.Close() })
$form.Controls.Add($cancel)
$form.CancelButton = $cancel

$result = $form.ShowDialog()
if ($result -ne [System.Windows.Forms.DialogResult]::OK) { exit 0 }
Save-Entry ($datePicker.Value.ToString('yyyy-MM-dd')) $titleBox.Text $startBox.Text $endBox.Text ([string]$typeBox.SelectedItem) $placeBox.Text
$form.Dispose()

