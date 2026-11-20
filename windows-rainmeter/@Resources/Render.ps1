param(
    [ValidateSet('Render','Prev','Next','Today','Select')][string]$Action = 'Render',
    [string]$TargetDate = ''
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$stateFile = Join-Path $root 'view.txt'
$selectedFile = Join-Path $root 'selected.txt'
$dataFile = Join-Path $root 'events.json'
$includeFile = Join-Path $root 'CalendarData.inc'
$today = (Get-Date).Date
$null = & (Join-Path $root 'Maintain.ps1')
$previousToday = $null
if (Test-Path -LiteralPath $includeFile) {
    $dateLine = [IO.File]::ReadAllLines($includeFile) | Where-Object { $_ -match '^TodayISO=\d{4}-\d{2}-\d{2}$' } | Select-Object -First 1
    if ($dateLine) {
        try { $previousToday = [datetime]::ParseExact($dateLine.Substring(9), 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture) } catch { }
    }
}
$view = [datetime]::new($today.Year, $today.Month, 1)
$selected = $today
if (Test-Path -LiteralPath $stateFile) {
    try { $view = [datetime]::ParseExact(([IO.File]::ReadAllText($stateFile)).Trim(), 'yyyy-MM', [Globalization.CultureInfo]::InvariantCulture) } catch { }
}
if (Test-Path -LiteralPath $selectedFile) {
    try { $selected = [datetime]::ParseExact(([IO.File]::ReadAllText($selectedFile)).Trim(), 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture) } catch { }
}
if ($previousToday -and $previousToday -lt $today -and $selected -eq $previousToday) {
    $selected = $today
    if ($view.Year -eq $previousToday.Year -and $view.Month -eq $previousToday.Month) {
        $view = [datetime]::new($today.Year, $today.Month, 1)
    }
}
switch ($Action) {
    'Prev'  { $view = $view.AddMonths(-1); $selected = $view }
    'Next'  { $view = $view.AddMonths(1); $selected = $view }
    'Today' { $view = [datetime]::new($today.Year, $today.Month, 1); $selected = $today }
    'Select' {
        try { $selected = [datetime]::ParseExact($TargetDate, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture) } catch { }
    }
}
[IO.File]::WriteAllText($stateFile, $view.ToString('yyyy-MM'), [Text.Encoding]::UTF8)
[IO.File]::WriteAllText($selectedFile, $selected.ToString('yyyy-MM-dd'), [Text.Encoding]::UTF8)

$entries = @()
if (Test-Path -LiteralPath $dataFile) {
    try {
        $loaded = [IO.File]::ReadAllText($dataFile, [Text.Encoding]::UTF8) | ConvertFrom-Json
        if ($null -ne $loaded) { $entries = @($loaded) }
    } catch { $entries = @() }
}

function Clean([string]$value, [int]$limit = 28) {
    $value = ($value -replace '[\r\n\t]', ' ' -replace '[#\[\]|]', ' ').Trim()
    if ($value.Length -gt $limit) { return $value.Substring(0, $limit - 1) + '…' }
    return $value
}
function AddVar([Collections.Generic.List[string]]$lines, [string]$name, [string]$value) {
    $lines.Add("$name=$value")
}
function FormatEntry($entry, [int]$limit = 20, [bool]$includePlace = $true) {
    $time = if ($entry.time) { [string]$entry.time + ' ' } else { '' }
    $name = if ($entry.type -eq '课程' -and $entry.display) { [string]$entry.display } else { [string]$entry.title }
    $place = if ($entry.place) { [string]$entry.place } else { [string]$entry.room }
    if ($entry.type -eq '课程' -and $entry.source -eq 'school-timetable-2026-fall') {
        $name = switch ([string]$entry.title) {
            '通用英语（基础）' { '通用英语' }
            '线性代数与空间解析几何Ⅰ' { '线代' }
            '人类文明经典赏析' { '经典赏析' }
            '心理健康与创新能力' { '心理健康' }
            '数据结构与算法Ⅰ' { '数据结构' }
            default { $name }
        }
        if ($place) {
            $place = $place -replace '^品学楼', '' -replace '^主楼', ''
        }
    }
    $placeText = if ($includePlace -and $place) { ' · ' + $place } else { '' }
    return Clean "$time$name$placeText" $limit
}
function FormatMonthEntry($entry) {
    return Clean ([string]$entry.title) 8
}

$lunarCalendar = [Globalization.ChineseLunisolarCalendar]::new()
$lunarDays = @('', '初一','初二','初三','初四','初五','初六','初七','初八','初九','初十',
    '十一','十二','十三','十四','十五','十六','十七','十八','十九','二十',
    '廿一','廿二','廿三','廿四','廿五','廿六','廿七','廿八','廿九','三十')
function GetCalendarNote([datetime]$date) {
    $solar = switch ($date.ToString('MM-dd')) {
        '01-01' { '元旦' }
        '05-01' { '劳动节' }
        '06-01' { '儿童节' }
        '09-10' { '教师节' }
        '10-01' { '国庆节' }
        '12-25' { '圣诞节' }
        default { '' }
    }
    if ($solar) { return @{Label=$solar; Holiday=$true} }
    try {
        $lunarYear = $lunarCalendar.GetYear($date)
        $monthIndex = $lunarCalendar.GetMonth($date)
        $lunarDay = $lunarCalendar.GetDayOfMonth($date)
        $leapIndex = $lunarCalendar.GetLeapMonth($lunarYear)
        $isLeapMonth = ($leapIndex -gt 0 -and $monthIndex -eq $leapIndex)
        $lunarMonth = $monthIndex
        if ($leapIndex -gt 0 -and $monthIndex -ge $leapIndex) { $lunarMonth-- }
        if (-not $isLeapMonth) {
            $festival = switch ("$lunarMonth-$lunarDay") {
                '1-1' { '春节' }
                '1-15' { '元宵节' }
                '5-5' { '端午节' }
                '8-15' { '中秋节' }
                default { '' }
            }
            if ($festival) { return @{Label=$festival; Holiday=$true} }
            if ($lunarMonth -eq 12 -and $lunarDay -eq $lunarCalendar.GetDaysInMonth($lunarYear,$monthIndex)) {
                return @{Label='除夕'; Holiday=$true}
            }
        }
        return @{Label=$lunarDays[$lunarDay]; Holiday=$false}
    } catch { return @{Label=''; Holiday=$false} }
}

$lines = [Collections.Generic.List[string]]::new()
$lines.Add('[Variables]')
AddVar $lines 'MonthTitle' ($view.ToString('yyyy年M月'))
$weekNames = @('星期日','星期一','星期二','星期三','星期四','星期五','星期六')
AddVar $lines 'TodayTitle' ($today.ToString('M月d日') + '  ' + $weekNames[[int]$today.DayOfWeek])
AddVar $lines 'TodayISO' ($today.ToString('yyyy-MM-dd'))
AddVar $lines 'SelectedISO' ($selected.ToString('yyyy-MM-dd'))
AddVar $lines 'SelectedTitle' ($(if ($selected -eq $today) { '今天 · ' } else { '' }) + $selected.ToString('M月d日') + '  ' + $weekNames[[int]$selected.DayOfWeek])
$weekNumber = [math]::Floor(($selected - [datetime]::new(2026,8,31)).TotalDays / 7) + 1
AddVar $lines 'WeekInfo' $(if ($weekNumber -ge 1 -and $weekNumber -le 18) { "第${weekNumber}周" } else { '学期外' })
$offset = (([int]$view.DayOfWeek + 6) % 7)
$start = $view.AddDays(-$offset)
for ($i = 1; $i -le 42; $i++) {
    $date = $start.AddDays($i - 1)
    $key = '{0:D2}' -f $i
    $dateString = $date.ToString('yyyy-MM-dd')
    $dayEntries = @($entries | Where-Object { $_.date -eq $dateString -and $_.type -eq '日程' } | Sort-Object time, title)
    AddVar $lines "Date$key" $dateString
    AddVar $lines "Day$key" ([string]$date.Day)
    $note = GetCalendarNote $date
    AddVar $lines "Note$key" $note.Label
    AddVar $lines "NoteColor$key" $(if ($note.Holiday) { '255,226,177,255' } else { '205,211,218,235' })
    AddVar $lines "EntryA$key" $(if ($dayEntries.Count -ge 1) { FormatMonthEntry $dayEntries[0] } else { '' })
    AddVar $lines "EntryB$key" $(if ($dayEntries.Count -ge 2) { FormatMonthEntry $dayEntries[1] } else { '' })
    AddVar $lines "EntryC$key" $(if ($dayEntries.Count -ge 3) { FormatMonthEntry $dayEntries[2] } else { '' })
    AddVar $lines "CellBg$key" $(if ($date -eq $selected) { '255,255,255,78' } elseif ($date -eq $today) { '255,255,255,35' } elseif ($date.Month -eq $view.Month) { '0,0,0,30' } else { '0,0,0,12' })
    AddVar $lines "DayColor$key" $(if ($date -eq $selected) { '255,255,255,255' } elseif ($date.Month -eq $view.Month) { '245,247,249,255' } else { '190,196,202,255' })
}
$selectedEntries = @($entries | Where-Object { $_.date -eq $selected.ToString('yyyy-MM-dd') } | Sort-Object time, title)
$groups = @(
    @{Prefix='Course'; Type='课程'; Max=4; Empty='这天还没有课程'},
    @{Prefix='Todo'; Type='待办'; Max=5; Empty='这天还没有待办'},
    @{Prefix='Schedule'; Type='日程'; Max=4; Empty='这天还没有日程'}
)
foreach ($group in $groups) {
    $items = @($selectedEntries | Where-Object { $_.type -eq $group.Type })
    for ($j = 1; $j -le $group.Max; $j++) {
        $hasItem = $items.Count -ge $j
        $value = if ($hasItem) { FormatEntry $items[$j - 1] $(if ($group.Prefix -eq 'Course') { 38 } else { 25 }) ($group.Prefix -ne 'Schedule') } elseif ($j -eq 1 -and $items.Count -eq 0) { $group.Empty } else { '' }
        AddVar $lines ($group.Prefix + $j) $value
        if ($group.Prefix -eq 'Schedule') {
            $place = if ($hasItem -and $items[$j - 1].place) { [string]$items[$j - 1].place } elseif ($hasItem -and $items[$j - 1].room) { [string]$items[$j - 1].room } else { '' }
            AddVar $lines ('SchedulePlace' + $j) $(if ($place) { Clean ('场地：' + $place) 26 } else { '' })
        }
        AddVar $lines ($group.Prefix + 'Id' + $j) $(if ($hasItem) { [string]$items[$j - 1].id } else { '' })
        AddVar $lines ($group.Prefix + 'Delete' + $j) $(if ($hasItem) { '○' } else { '' })
    }
}
$text = ($lines -join "`r`n") + "`r`n"
$temp = "$includeFile.tmp"
[IO.File]::WriteAllText($temp, $text, [Text.Encoding]::Unicode)
Move-Item -LiteralPath $temp -Destination $includeFile -Force

