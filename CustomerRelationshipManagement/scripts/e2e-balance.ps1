# A lead's own units balance goes down when they split units to their team.
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$pw = 'secret123'
$c = ('B' + (Get-Random -Maximum 9999))
Call POST '/admin/tenants' superadmin $SA @{ name = "Balance $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw } | Out-Null
$a = "boss@$c"
$lead = Call POST '/users' $a $pw @{ username = 'lead'; password = $pw; fullName = 'Lead' }
$ra = Call POST '/users' $a $pw @{ username = 'ra'; password = $pw; fullName = 'Rep A'; managerId = $lead.id }
$rb = Call POST '/users' $a $pw @{ username = 'rb'; password = $pw; fullName = 'Rep B'; managerId = $lead.id }
$prod = Call POST '/products' $a $pw @{ name = 'Widget'; unit = 'pcs' }
$t = Call POST '/tasks' $a $pw @{ productId = $prod.id; assigneeId = $lead.id; units = 100; title = 'Widget drive' }
$cuA = Call POST '/customers' "ra@$c" $pw @{ firstName = 'A'; lastName = 'Cust'; email = 'a@x.com' }
$cuL = Call POST '/customers' "lead@$c" $pw @{ firstName = 'L'; lastName = 'Cust'; email = 'l@x.com' }
function Bal($label) {
  $d = Call GET '/dashboard' "lead@$c" $pw
  $tasks = (Call GET '/tasks' "lead@$c" $pw) | ForEach-Object { $_ }
  $own = ($tasks | ForEach-Object { "$($_.assignedToName)=$($_.ownBalance)/team $($_.teamBalance)" }) -join '; '
  "{0,-48} lead balance={1}  split={2}  | {3}" -f $label, $d.unitsBalance, $d.unitsSplit, $own
}
Bal 'lead has 100, nothing split (expect 100)'
Call POST '/tasks' "lead@$c" $pw @{ parentId = $t.id; assigneeId = $ra.id; units = 30 } | Out-Null
Call POST '/tasks' "lead@$c" $pw @{ parentId = $t.id; assigneeId = $rb.id; units = 40 } | Out-Null
Bal 'split 30 to A, 40 to B (expect 30)'
Call POST '/activities' "ra@$c" $pw @{ customerId = $cuA.id; subject = 's1'; productId = $prod.id; unitsSold = 10; saleAmount = 1000 } | Out-Null
Bal 'A sells 10 (expect lead 30, A 20)'
"A tries to sell 25 with only 20 left (expect HTTP 400): " + (Call POST '/activities' "ra@$c" $pw @{ customerId = $cuA.id; subject = 's2'; productId = $prod.id; unitsSold = 25; saleAmount = 2500 })
Call POST '/activities' "ra@$c" $pw @{ customerId = $cuA.id; subject = 's2'; productId = $prod.id; unitsSold = 20; saleAmount = 2000 } | Out-Null
Bal 'A sells the remaining 20 (expect lead 30, A 0)'
Call POST '/activities' "lead@$c" $pw @{ customerId = $cuL.id; subject = 's3'; productId = $prod.id; unitsSold = 5; saleAmount = 500 } | Out-Null
Bal 'lead sells 5 (expect lead 25)'
"lead tries 26 with 25 left (expect HTTP 400): " + (Call POST '/activities' "lead@$c" $pw @{ customerId = $cuL.id; subject = 's4'; productId = $prod.id; unitsSold = 26; saleAmount = 500 })
"Rep B sells a product with no task (expect HTTP 400): " + (Call POST '/activities' "rb@$c" $pw @{ customerId = $cuA.id; subject = 'x'; productId = (Call POST '/products' $a $pw @{ name = 'Other'; unit = 'pcs' }).id; unitsSold = 1; saleAmount = 10 })
$team = (Call GET '/dashboard/team' $a $pw) | ForEach-Object { $_ } | ForEach-Object { "$($_.fullName)=$($_.unitsBalance)" }
"team overview balances: " + ($team -join ', ') + "  (expect Lead=25, Rep A=0, Rep B=40)"
