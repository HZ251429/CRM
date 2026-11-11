# WhatsApp phase 2: alerts (task assigned, team updates), per-person settings, daily summary.
# Super admin login: set CRM_SUPERADMIN_USERNAME / CRM_SUPERADMIN_PASSWORD (defaults: superadmin / superadmin123).
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$SAU = if ($env:CRM_SUPERADMIN_USERNAME) { $env:CRM_SUPERADMIN_USERNAME } else { 'superadmin' }
$pw = 'secret123'
$c = ('A' + (Get-Random -Maximum 9999))
$n = Get-Random -Minimum 10000 -Maximum 99999
$leadPhone = "95000$n"; $repPhone = "94000$n"

Call POST '/admin/tenants' $SAU $SA @{ name = "Alerts $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw } | Out-Null
$a = "boss@$c"; $L = "lead@$c"; $R = "rep@$c"
$lead = Call POST '/users' $a $pw @{ username = 'lead'; password = $pw; fullName = 'Lata Lead' }
$rep = Call POST '/users' $a $pw @{ username = 'rep'; password = $pw; fullName = 'Ravi Rep'; managerId = $lead.id }
$p = Call POST '/products' $a $pw @{ name = 'Purifier'; unit = 'pcs' }
Call POST '/stock' $a $pw @{ productId = $p.id; quantity = 500 } | Out-Null

function Chat($user, $phone, $text, $reply) { Call POST '/whatsapp/test-chat' $user $pw @{ phone = $phone; text = $text; replyId = $reply } }
function Link($user, $phone) { $code = (Call POST '/whatsapp/link-code' $user $pw).code; Chat $user $phone $code | Out-Null }
$seen = @{}
function NewAlerts($user) {
  if (-not $seen.ContainsKey($user)) { $seen[$user] = (Call GET '/whatsapp/test-chat/outbox/latest' $user $pw).id }
  $m = @((Call GET "/whatsapp/test-chat/outbox?after=$($seen[$user])" $user $pw) | ForEach-Object { $_ })
  if ($m.Count -and $m[-1].id) { $seen[$user] = $m[-1].id }
  if (-not $m.Count -or -not $m[0].id) { return '(none)' }
  ($m | ForEach-Object { "[$($_.kind)] " + (($_.text -split "`n")[0]) }) -join '  //  '
}
function Say($label, $out) { "{0,-46} {1}" -f $label, $out }

Link $L $leadPhone; Link $R $repPhone
NewAlerts $L | Out-Null; NewAlerts $R | Out-Null
"--- task alerts"
$t = Call POST '/tasks' $a $pw @{ productId = $p.id; assigneeId = $lead.id; units = 100; title = 'Diwali push' }
Say 'admin assigns 100 to lead -> lead gets' (NewAlerts $L)
Call POST '/tasks' $L $pw @{ parentId = $t.id; assigneeId = $rep.id; units = 40 } | Out-Null
Say 'lead hands 40 to rep -> rep gets' (NewAlerts $R)
Say '...and the lead gets nothing for it' (NewAlerts $L)
Say 'the bell also has it (rep)' (((Call GET '/notifications' $R $pw).items | Where-Object type -eq 'TASK_ASSIGNED' | ForEach-Object message) -join ' | ')
"--- team alerts reach the lead"
$cust = Call POST '/customers' $R $pw @{ firstName = 'Meera'; lastName = 'Iyer'; email = "meera$n@x.com" }
Say 'rep adds a customer on the website' (NewAlerts $L)
Chat $R $repPhone 'sale' | Out-Null; Chat $R $repPhone 'iyer' | Out-Null; Chat $R $repPhone $null "c:$($cust.id)" | Out-Null
Chat $R $repPhone $null "p:$($p.id)" | Out-Null; Chat $R $repPhone '15' | Out-Null
Chat $R $repPhone '15000' | Out-Null; Chat $R $repPhone $null 'paid:full' | Out-Null; Chat $R $repPhone $null 'save:yes' | Out-Null
Say 'rep logs a sale on WhatsApp' (NewAlerts $L)
"--- settings: lead turns team alerts off"
$s = Call PUT '/whatsapp/settings' $L $pw @{ alertsTeam = $false; alertsTasks = $true; summaryEnabled = $true; summaryHour = 19 }
Say 'saved settings' "team=$($s.alertsTeam) tasks=$($s.alertsTasks) summary=$($s.summaryEnabled)@$($s.summaryHour)"
Call POST '/customers' $R $pw @{ firstName = 'Arjun'; lastName = 'Menon'; email = "arjun$n@x.com" } | Out-Null
Say 'rep adds another customer (expect none)' (NewAlerts $L)
Say 'bell still shows it for the lead' (@((Call GET '/notifications' $L $pw).items | ForEach-Object { $_ } | Where-Object { $_.message -match 'Arjun' }).Count)
"--- summaries"
$sum = Chat $R $repPhone 'summary'
Say 'rep types "summary"' ((($sum[0].text -split "`n") | Where-Object { $_ -match 'Sales|sell|Purifier|Open' }) -join ' | ')
Call POST '/whatsapp/summary-now' $L $pw | Out-Null
$ls = (Call GET "/whatsapp/test-chat/outbox?after=$($seen[$L])" $L $pw) | ForEach-Object { $_ } | Where-Object kind -eq 'SUMMARY' | Select-Object -Last 1
Say 'lead "send summary now"' ((($ls.text -split "`n") | Where-Object { $_ -match 'team|Sales|Activities' }) -join ' | ')
"--- safety: each person only sees their own alerts"
Say 'rep sees team alerts meant for the lead (expect 0)' ((Call GET '/whatsapp/test-chat/outbox?after=0' $R $pw) | ForEach-Object { $_ } | Where-Object { $_.text -match 'added a new customer|logged a' } | Measure-Object).Count
"tenant code: $c"
