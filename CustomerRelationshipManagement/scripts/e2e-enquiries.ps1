# Enquiries: take an enquiry for a new customer, hand it to someone below; the customer joins their list,
# and activities / sales against the customer move the enquiry to Contacted / Won.
# Super admin login: set CRM_SUPERADMIN_USERNAME / CRM_SUPERADMIN_PASSWORD (defaults: superadmin / superadmin123).
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$SAU = if ($env:CRM_SUPERADMIN_USERNAME) { $env:CRM_SUPERADMIN_USERNAME } else { 'superadmin' }
$pw = 'secret123'
$c = ('E' + (Get-Random -Maximum 9999))
Call POST '/admin/tenants' $SAU $SA @{ name = "Enquiries $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw } | Out-Null
$a = "boss@$c"; $L = "lead@$c"; $R = "rep@$c"; $O = "other@$c"
$lead = Call POST '/users' $a $pw @{ username = 'lead'; password = $pw; fullName = 'Lata Lead' }
$rep = Call POST '/users' $a $pw @{ username = 'rep'; password = $pw; fullName = 'Ravi Rep'; managerId = $lead.id }
$other = Call POST '/users' $a $pw @{ username = 'other'; password = $pw; fullName = 'Other' }
$p = Call POST '/products' $a $pw @{ name = 'Purifier'; unit = 'pcs' }
Call POST '/stock' $a $pw @{ productId = $p.id; quantity = 100 } | Out-Null
Call POST '/tasks' $a $pw @{ productId = $p.id; assigneeId = $rep.id; units = 20; title = 'Diwali push' } | Out-Null
function Say($label, $out) { "{0,-48} {1}" -f $label, $out }
function Enq($user, $body) { $r = Call POST '/enquiries' $user $pw $body; if ($r -is [string]) { $r } else { $script:last = $r; "#$($r.id) $($r.customerName) -> $($r.assignedToName), status $($r.status)" } }
function StatusOf($user, $id) { ((Call GET '/enquiries' $user $pw) | ForEach-Object { $_ } | Where-Object id -eq $id) | ForEach-Object { "$($_.status) (activities $($_.activities), sales $($_.sales), value $($_.salesAmount))" } }

"--- lead takes an enquiry and gives it to the rep"
Say 'new enquiry for the rep' (Enq $L @{ customer = @{ name = 'Meera Iyer'; phone = '98765 43210' }; requirement = 'Wants 2 purifiers for the office'; productId = $p.id; assigneeId = $rep.id })
$enq = $last
Say "customer is in the rep's list" (((Call GET '/customers?q=meera&page=0&size=5' $R $pw).content | ForEach-Object { "$($_.name), owner is rep: $($_.ownerId -eq $rep.id)" }) -join '; ')
Say 'the rep is told' (((Call GET '/notifications' $R $pw).items | ForEach-Object { $_ } | Where-Object type -eq 'ENQUIRY_ASSIGNED' | ForEach-Object message) -join ' | ')
Say 'to someone outside the team (400)' (Enq $L @{ customer = @{ name = 'X'; phone = '98765 00001' }; assigneeId = $other.id })
Say 'name only (400)' (Enq $L @{ customer = @{ name = 'Nobody' }; assigneeId = $rep.id })
"--- who sees it"
Say 'rep / lead / admin / other' ((($R, $L, $a, $O) | ForEach-Object { @((Call GET '/enquiries' $_ $pw) | ForEach-Object { $_ } | Where-Object id -eq $enq.id).Count }) -join ' / ')
"--- follow-up moves the status"
Say 'at first' (StatusOf $R $enq.id)
Call POST '/activities' $R $pw @{ type = 'CALL'; customerId = $enq.customerId; subject = 'Called to fix a demo' } | Out-Null
Say 'after the rep logs a call' (StatusOf $R $enq.id)
Call POST '/activities' $R $pw @{ customerId = $enq.customerId; productId = $p.id; unitsSold = 2; saleAmount = 24000 } | Out-Null
Say 'after the rep records a sale' (StatusOf $L $enq.id)
"--- closing as lost, and reopening"
Enq $L @{ customer = @{ name = 'Arjun Menon'; email = 'arjun@x.com' }; assigneeId = $lead.id } | Out-Null
$second = $last
Say 'lead closes their own enquiry as lost' ((Call PUT "/enquiries/$($second.id)/status" $L $pw @{ status = 'LOST' }).status)
Say 'reopened' ((Call PUT "/enquiries/$($second.id)/status" $L $pw @{ status = 'OPEN' }).status)
Say 'other cannot change it (404)' (Call PUT "/enquiries/$($second.id)/status" $O $pw @{ status = 'LOST' })
"tenant code: $c"
