-- Render static calendar text when the system date changes or the skin starts with yesterday's data.
function Check()
    if os.date('%Y-%m-%d') ~= SKIN:GetVariable('TodayISO') then
        SKIN:Bang('!CommandMeasure', 'MeasureRender', 'Run')
    end
end

function Update()
    Check()
    return 0
end

