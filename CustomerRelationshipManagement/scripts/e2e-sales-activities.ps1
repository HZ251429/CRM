# Sales as their own section; activities (meeting in person/online, call, other) about a customer or a task.
# Super admin login: set CRM_SUPERADMIN_USERNAME / CRM_SUPERADMIN_PASSWORD (defaults: superadmin / superadmin123).
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$SAU = if ($env:CRM_SUPERADMIN_USERNAME) { $env:CRM_SUPERADMIN_USERNAME } else { 'superadmin' }
$pw = 'secret123'
$c = ('S' + (Get-Random -Maximum 9999))
Call POST '/admin/tenants' $SAU $SA @{ name = "Sales $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw } | Out-Null
$a = "boss@$c"; $L = "lead@$c"; $R = "rep@$c"
$lead = Call POST '/users' $a $pw @{ username = 'lead'; password = $pw; fullName = 'Lata Lead' }
$rep = Call POST '/users' $a $pw @{ username = 'rep'; password = $pw; fullName = 'Ravi Rep'; managerId = $lead.id }
$other = Call POST '/users' $a $pw @{ username = 'other'; password = $pw; fullName = 'Other' }
$p = Call POST '/products' $a $pw @{ name = 'Purifier'; unit = 'pcs' }
Call POST '/stock' $a $pw @{ productId = $p.id; quantity = 500 } | Out-Null
$t = Call POST '/tasks' $a $pw @{ productId = $p.id; assigneeId = $lead.id; units = 100; title = 'Diwali push' }
$sub = Call POST '/tasks' $L $pw @{ parentId = $t.id; assigneeId = $rep.id; units = 40 }
$otherTask = Call POST '/tasks' $a $pw @{ productId = $p.id; assigneeId = $other.id; units = 10; title = 'Not yours' }
$cu = Call POST '/customers' $R $pw @{ firstName = 'Meera'; lastName = 'Iyer'; email = "meera$c@x.com" }
$now = (Get-Date).ToString('yyyy-MM-ddTHH:mm')
function Say($label, $out) { "{0,-52} {1}" -f $label, $out }
function Act($body) { $r = Call POST '/activities' $R $pw $body; if ($r -is [string]) { $r } else { "id=$($r.id) type=$($r.type) mode=$($r.meetingMode) customer=$($r.customerId) task=$($r.taskId) subject='$($r.subject)'" } }

"--- activities"
Say 'online meeting with a customer' (Act @{ type = 'MEETING'; meetingMode = 'ONLINE'; customerId = $cu.id; subject = 'Demo on Zoom'; activityTime = $now })
Say 'meeting without mode -> in person' (Act @{ type = 'MEETING'; customerId = $cu.id; subject = 'Site visit'; activityTime = $now })
Say 'call about a task (no customer)' (Act @{ type = 'CALL'; taskId = $sub.id; subject = 'Checked stock for the push'; activityTime = $now })
Say 'other, about a customer' (Act @{ type = 'OTHER'; customerId = $cu.id; subject = 'Sent brochure'; activityTime = $now })
Say 'neither customer nor task (400)' (Act @{ type = 'CALL'; subject = 'x'; activityTime = $now })
Say "someone else's task (400)" (Act @{ type = 'CALL'; taskId = $otherTask.id; subject = 'x'; activityTime = $now })
Say 'no title (400)' (Act @{ type = 'CALL'; customerId = $cu.id; activityTime = $now })
Say 'type SALE without a product (400)' (Act @{ type = 'SALE'; customerId = $cu.id; subject = 'x'; activityTime = $now })
"--- sales"
Say 'sale, no title, task given (task dropped)' (Act @{ customerId = $cu.id; taskId = $sub.id; productId = $p.id; unitsSold = 15; saleAmount = 15000; amountPaid = 10000; activityTime = $now })
Say 'sale without a customer (400)' (Act @{ productId = $p.id; unitsSold = 1; saleAmount = 100; activityTime = $now })
Say 'sale over the balance (400: 25 left)' (Act @{ customerId = $cu.id; productId = $p.id; unitsSold = 26; saleAmount = 100; activityTime = $now })
"--- lists"
$acts = Call GET '/activities/page?activitiesOnly=true' $R $pw
Say 'Activities section' ("$($acts.totalElements) entries, any sales: " + [bool]($acts.content | ForEach-Object { $_ } | Where-Object productId))
$sales = Call GET '/activities/page?salesOnly=true' $R $pw
Say 'Sales section' ("$($sales.totalElements) sale, total $($sales.saleTotal), credit $($sales.creditTotal)")
$onTask = Call GET "/activities/page?activitiesOnly=true&taskId=$($sub.id)" $R $pw
Say 'activities on the task' (($onTask.content | ForEach-Object { "$($_.subject) [task: $($_.taskTitle)]" }) -join '; ')
Say "rep's task progress (15 of 40)" ((Call GET '/tasks' $R $pw | ForEach-Object { $_ } | Where-Object id -eq $sub.id) | ForEach-Object { "$($_.soldUnits)/$($_.units), own balance $($_.ownBalance)" })
"--- what the lead is told"
(Call GET '/notifications' $L $pw).items | ForEach-Object { $_ } | Select-Object -First 5 | ForEach-Object { "  $($_.message)" }
"tenant code: $c"
