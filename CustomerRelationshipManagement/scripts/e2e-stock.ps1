# Stock: the admin's tasks cannot exceed stock left; leads hand out only their own unsold, unsplit balance.
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$pw = 'secret123'
$c = ('S' + (Get-Random -Maximum 9999))
Call POST '/admin/tenants' superadmin $SA @{ name = "Stock $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw } | Out-Null
$a = "boss@$c"
$lead = Call POST '/users' $a $pw @{ username = 'lead'; password = $pw; fullName = 'Lead' }
$m1 = Call POST '/users' "lead@$c" $pw @{ username = 'm1'; password = $pw; fullName = 'Member One' }
$p = Call POST '/products' $a $pw @{ name = 'Widget'; unit = 'pcs' }
$cust = Call POST '/customers' "lead@$c" $pw @{ firstName = 'A'; lastName = 'Cust'; email = 'a@x.com' }
function Stock { $r = (Call GET '/stock' $a $pw) | ForEach-Object { $_ } | Where-Object productId -eq $p.id; "stock total=$($r.total) assigned=$($r.assigned) left=$($r.left) sold=$($r.sold)" }

"--- no stock: admin task fails (400)"; Call POST '/tasks' $a $pw @{ productId = $p.id; assigneeId = $lead.id; units = 10; title = 'Push' }
"--- member cannot see stock (403)"; Call GET '/stock' "lead@$c" $pw
"--- add 100, remove 10"
$e1 = Call POST '/stock' $a $pw @{ productId = $p.id; quantity = 100; note = 'Invoice 1' }
Call POST '/stock' $a $pw @{ productId = $p.id; quantity = -10; note = 'Damaged' } | Out-Null; Stock
"--- admin task of 91 fails (400), 60 ok"
Call POST '/tasks' $a $pw @{ productId = $p.id; assigneeId = $lead.id; units = 91; title = 'Push' }
$t = Call POST '/tasks' $a $pw @{ productId = $p.id; assigneeId = $lead.id; units = 60; title = 'Push' }; Stock
"--- remove 31 fails (400); undo +100 entry fails (409)"
Call POST '/stock' $a $pw @{ productId = $p.id; quantity = -31 }
Call DELETE "/stock/entries/$($e1.id)" $a $pw
"--- admin raises task to 91 fails (400), to 80 ok"
Call PUT "/tasks/$($t.id)" $a $pw @{ units = 91; title = 'Push' }
(Call PUT "/tasks/$($t.id)" $a $pw @{ units = 80; title = 'Push' }).units; Stock
"--- lead sells 30 himself; balance now 50"
Call POST '/activities' "lead@$c" $pw @{ customerId = $cust.id; subject = 'Sale'; productId = $p.id; unitsSold = 30; saleAmount = 3000 } | Out-Null
"--- lead splits 51 fails (400), 40 ok"
Call POST '/tasks' "lead@$c" $pw @{ parentId = $t.id; assigneeId = $m1.id; units = 51 }
$sub = Call POST '/tasks' "lead@$c" $pw @{ parentId = $t.id; assigneeId = $m1.id; units = 40 }
"--- lead top-level task without source fails (400)"
Call POST '/tasks' "lead@$c" $pw @{ productId = $p.id; assigneeId = $m1.id; units = 1; title = 'X' }
"--- lead raises sub-task by 11 fails (400), by 10 ok (balance 10)"
Call PUT "/tasks/$($sub.id)" "lead@$c" $pw @{ units = 51 }
(Call PUT "/tasks/$($sub.id)" "lead@$c" $pw @{ units = 50 }).units
"--- lead's task ownBalance (expect 0)"; ((Call GET '/tasks' "lead@$c" $pw) | ForEach-Object { $_ } | Where-Object id -eq $t.id).ownBalance
"--- admin lowers task below 80 fails (400: all handed out or sold)"; Call PUT "/tasks/$($t.id)" $a $pw @{ units = 79; title = 'Push' }
Stock
