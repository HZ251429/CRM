# Follow-ups: an activity with "follow-up needed", the follow-up list (mine / team), mark done, move, reopen,
# the reminder in the bell and on WhatsApp, and the WhatsApp "followups" command. Takes about 2.5 minutes
# (it waits for the reminder job, which runs every minute).
# Super admin login: set CRM_SUPERADMIN_USERNAME / CRM_SUPERADMIN_PASSWORD (defaults: superadmin / superadmin123).
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$SAU = if ($env:CRM_SUPERADMIN_USERNAME) { $env:CRM_SUPERADMIN_USERNAME } else { 'superadmin' }
$pw = 'secret123'
$c = ('F' + (Get-Random -Maximum 9999))
$phone = "95000$(Get-Random -Minimum 10000 -Maximum 99999)"
Call POST '/admin/tenants' $SAU $SA @{ name = "Follow-ups $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw } | Out-Null
$a = "boss@$c"; $L = "lead@$c"; $R = "rep@$c"
$lead = Call POST '/users' $a $pw @{ username = 'lead'; password = $pw; fullName = 'Lata Lead' }
$rep = Call POST '/users' $a $pw @{ username = 'rep'; password = $pw; fullName = 'Ravi Rep'; managerId = $lead.id }
$cust = Call POST '/customers' $R $pw @{ name = 'Meera Iyer'; phone = '98765 43210' }
function Say($label, $out) { "{0,-46} {1}" -f $label, $out }
function At($minutes) { (Get-Date).AddMinutes($minutes).ToString('yyyy-MM-ddTHH:mm:ss') }
function Log($body) { $r = Call POST '/activities' $R $pw $body; if ($r -is [string]) { $r } else { $script:last = $r; "#$($r.id) follow-up $($r.followUpAt) remind $($r.followUpRemindMinutes)" } }
function Fu($user, $who, $open) { @((Call GET "/followups?who=$who&open=$open" $user $pw) | ForEach-Object { $_ }) }

"--- logging activities with a follow-up"
Say 'call, follow up in 1 min, remind at the time' (Log @{ type = 'CALL'; customerId = $cust.id; subject = 'Price call'; followUpAt = (At 1); followUpNote = 'Send revised quote'; followUpRemindMinutes = 0 })
$soon = $last
Say 'meeting, follow up in 3 days, 1 day before' (Log @{ type = 'MEETING'; customerId = $cust.id; subject = 'Demo'; followUpAt = (At 4320); followUpRemindMinutes = 1440 })
$later = $last
Say 'follow-up in the past (400)' (Log @{ type = 'CALL'; customerId = $cust.id; subject = 'x'; followUpAt = (At -60) })
Say 'odd reminder minutes (400)' (Log @{ type = 'CALL'; customerId = $cust.id; subject = 'x'; followUpAt = (At 60); followUpRemindMinutes = 7 })
Say 'no follow-up' (Log @{ type = 'CALL'; customerId = $cust.id; subject = 'Plain call' })

"--- lists"
Say 'rep: my open follow-ups' ((Fu $R 'me' $true | ForEach-Object { "$($_.subject) @ $($_.followUpAt)" }) -join ' | ')
Say 'rep summary' ((Call GET '/followups/summary' $R $pw) | ForEach-Object { "overdue $($_.overdue), today $($_.today)" })
Say 'lead: mine / team' ("$(@(Fu $L 'me' $true).Count) / $(@(Fu $L 'team' $true).Count)")
Say 'lead cannot mark the rep''s done (404)' (Call PUT "/followups/$($later.id)" $L $pw @{ done = $true })

"--- move, done, reopen"
Say 'odd reminder on move (400)' (Call PUT "/followups/$($later.id)" $R $pw @{ remindMinutes = 7 })
Say 'rep moves it to 5 days' ((Call PUT "/followups/$($later.id)" $R $pw @{ followUpAt = (At 7200); remindMinutes = 60; note = 'Check budget' }) | ForEach-Object { "$($_.followUpAt) remind $($_.followUpRemindMinutes) '$($_.followUpNote)'" })
Say 'moving into the past (400)' (Call PUT "/followups/$($later.id)" $R $pw @{ followUpAt = (At -10) })
Say 'done' ((Call PUT "/followups/$($later.id)" $R $pw @{ done = $true }).followUpDoneAt -ne $null)
Say 'open / done counts' ("$(@(Fu $R 'me' $true).Count) / $(@(Fu $R 'me' $false).Count)")
Say 'reopen' ((Call PUT "/followups/$($later.id)" $R $pw @{ done = $false }).followUpDoneAt -eq $null)

"--- WhatsApp"
$code = (Call POST '/whatsapp/link-code' $R $pw).code
Call POST '/whatsapp/test-chat' $R $pw @{ phone = $phone; text = $code } | Out-Null
$wa = @(Call POST '/whatsapp/test-chat' $R $pw @{ phone = $phone; text = 'followups' })
Say 'followups command' (($wa[0].text -split "`n")[0..3] -join ' / ')
Say 'buttons' (($wa[0].buttons | ForEach-Object { "$($_.title)=$($_.id)" }) -join ', ')
Say 'menu has the row' (@((Call POST '/whatsapp/test-chat' $R $pw @{ phone = $phone; text = 'menu' })[0].list.rows | Where-Object id -eq 'm:followups').Count)

"--- reminder (waiting for the job)"
$deadline = (Get-Date).AddSeconds(170)
do {
  Start-Sleep -Seconds 15
  $n = @((Call GET '/notifications' $R $pw).items | ForEach-Object { $_ } | Where-Object type -eq 'FOLLOW_UP_DUE')
} while ($n.Count -eq 0 -and (Get-Date) -lt $deadline)
Say 'bell reminder' (($n | ForEach-Object message) -join ' | ')
Say 'lead is not told' (@((Call GET '/notifications' $L $pw).items | ForEach-Object { $_ } | Where-Object type -eq 'FOLLOW_UP_DUE').Count)
Say 'WhatsApp alert queued' (((Call GET '/whatsapp/test-chat/outbox?after=0' $R $pw) | ForEach-Object { $_ } | Where-Object text -like '*Follow-up*' | ForEach-Object text) -join ' | ')
Start-Sleep -Seconds 65
Say 'sent only once' (@((Call GET '/notifications' $R $pw).items | ForEach-Object { $_ } | Where-Object type -eq 'FOLLOW_UP_DUE').Count)
Say 'summary now' ((Call GET '/followups/summary' $R $pw) | ForEach-Object { "overdue $($_.overdue), today $($_.today)" })
"--- WhatsApp: mark no. 1 done"
$id = ($wa[0].buttons | Select-Object -First 1).id
$done = @(Call POST '/whatsapp/test-chat' $R $pw @{ phone = $phone; replyId = $id })
Say "tap $id" (($done | ForEach-Object { ($_.text -split "`n")[0] }) -join ' // ')
Say 'open now' ((Fu $R 'me' $true | ForEach-Object subject) -join ', ')
"tenant code: $c"
