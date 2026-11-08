# Products, sales tasks (assign + split down the hierarchy) and units sold from activities.
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$pw = 'secret123'
$c = ('P' + (Get-Random -Maximum 9999))
Call POST '/admin/tenants' superadmin $SA @{ name = "Tasks $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw } | Out-Null
$a = "boss@$c"
$asst  = Call POST '/users' $a $pw @{ username = 'asst';  password = $pw; fullName = 'Assistant' }
$rep1  = Call POST '/users' $a $pw @{ username = 'rep1';  password = $pw; fullName = 'Rep One'; managerId = $asst.id }
$rep2  = Call POST '/users' $a $pw @{ username = 'rep2';  password = $pw; fullName = 'Rep Two'; managerId = $asst.id }
$other = Call POST '/users' $a $pw @{ username = 'other'; password = $pw; fullName = 'Other' }

"--- products: member cannot add (403), admin adds, duplicate (409)"
Call POST '/products' "rep1@$c" $pw @{ name = 'X'; unit = 'pcs' }
$prod = Call POST '/products' $a $pw @{ name = 'Purifier'; unit = 'pcs' }
$prod2 = Call POST '/products' $a $pw @{ name = 'Filter'; unit = 'boxes' }
Call POST '/products' $a $pw @{ name = 'purifier'; unit = 'pcs' }
"products visible to rep1: " + (((Call GET '/products' "rep1@$c" $pw) | ForEach-Object { $_ } | ForEach-Object name) -join ', ')
# the admin can only assign units that are in stock
Call POST '/stock' $a $pw @{ productId = $prod.id; quantity = 1000; note = 'test stock' } | Out-Null
Call POST '/stock' $a $pw @{ productId = $prod2.id; quantity = 100; note = 'test stock' } | Out-Null

"--- task without a title is rejected (400)"
Call POST '/tasks' $a $pw @{ productId = $prod.id; assigneeId = $asst.id; units = 5 }
"--- admin assigns 100 to assistant; assigning to self fails (400)"
$t = Call POST '/tasks' $a $pw @{ productId = $prod.id; assigneeId = $asst.id; units = 100; title = 'November' }
"task $($t.id): $($t.units) $($t.unit) of $($t.productName) to $($t.assignedToName), status $($t.status)"
Call POST '/tasks' $a $pw @{ productId = $prod.id; assigneeId = (Call GET '/me' $a $pw).id; units = 5; title = 'x' }

"--- assistant splits 60 + 30; another 20 fails (only 10 left -> 400)"
$s1 = Call POST '/tasks' "asst@$c" $pw @{ parentId = $t.id; assigneeId = $rep1.id; units = 60 }
$s2 = Call POST '/tasks' "asst@$c" $pw @{ parentId = $t.id; assigneeId = $rep2.id; units = 30 }
"split: rep1=$($s1.units) rep2=$($s2.units) title inherited='$($s1.title)'"
Call POST '/tasks' "asst@$c" $pw @{ parentId = $t.id; assigneeId = $rep2.id; units = 20 }
"--- rep1 cannot split to a peer (400) or edit its task (403); other cannot split asst task (403)"
Call POST '/tasks' "rep1@$c" $pw @{ parentId = $s1.id; assigneeId = $rep2.id; units = 5 }
Call PUT "/tasks/$($s1.id)" "rep1@$c" $pw @{ units = 1 }
Call POST '/tasks' "other@$c" $pw @{ parentId = $t.id; assigneeId = $rep1.id; units = 1 }
"--- a member cannot create a task from scratch (400: units must come from their own task); the admin gives rep2 20 boxes of Filter"
Call POST '/tasks' "asst@$c" $pw @{ productId = $prod2.id; assigneeId = $rep2.id; units = 20; title = 'Filter boxes' }
$own = Call POST '/tasks' $a $pw @{ productId = $prod2.id; assigneeId = $rep2.id; units = 20; title = 'Filter boxes' }
"admin task: $($own.units) $($own.unit) $($own.productName)"

"--- reps log sales in activities"
$cu1 = Call POST '/customers' "rep1@$c" $pw @{ firstName = 'A'; lastName = 'One'; email = 'a1@x.com' }
$cu2 = Call POST '/customers' "rep2@$c" $pw @{ firstName = 'B'; lastName = 'Two'; email = 'b2@x.com' }
Call POST '/activities' "rep1@$c" $pw @{ customerId = $cu1.id; type = 'VISIT'; subject = 'Sold some'; productId = $prod.id; unitsSold = 25; saleAmount = 25000 } | Out-Null
Call POST '/activities' "rep2@$c" $pw @{ customerId = $cu2.id; type = 'MEETING'; subject = 'Big order'; productId = $prod.id; unitsSold = 30; saleAmount = 30000; amountPaid = 10000 } | Out-Null
Call POST '/activities' "rep2@$c" $pw @{ customerId = $cu2.id; type = 'CALL'; subject = 'Filters'; productId = $prod2.id; unitsSold = 5; saleAmount = 2500 } | Out-Null
"product without units (400): " + (Call POST '/activities' "rep1@$c" $pw @{ customerId = $cu1.id; subject = 'x'; productId = $prod.id })

"--- progress (expect rep1 25/60 IN_PROGRESS, rep2 30/30 COMPLETED, asst 55/100 IN_PROGRESS, filter 5/20)"
(Call GET '/tasks' $a $pw) | ForEach-Object { $_ } | ForEach-Object { "  $($_.assignedToName): $($_.productName) $($_.soldUnits)/$($_.units) $($_.status) split=$($_.splitUnits)" }
"--- visibility (expect admin 4, asst 4, rep1 1, rep2 2, other 0)"
foreach ($u in 'boss','asst','rep1','rep2','other') { "  $u sees " + @((Call GET '/tasks' "$u@$c" $pw) | ForEach-Object { $_ }).Count }

"--- edits: asst lowers rep1 to 50 (ok); admin lowers parent to 79 (< 80 split, 400) then 80 (ok)"
(Call PUT "/tasks/$($s1.id)" "asst@$c" $pw @{ units = 50 }).units
Call PUT "/tasks/$($t.id)" $a $pw @{ units = 79; title = 'November' }
(Call PUT "/tasks/$($t.id)" $a $pw @{ units = 80; title = 'November' }).units
"--- sold units cannot be taken back: asst lowers rep1 (25 sold) to 20 (400)"
Call PUT "/tasks/$($s1.id)" "asst@$c" $pw @{ units = 20 }
"--- oldest first: admin raises the parent to 90, asst gives rep1 a second task of 10. rep1 sells 30 -> expect 50/50 COMPLETED and 5/10"
(Call PUT "/tasks/$($t.id)" $a $pw @{ units = 90; title = 'November' }).units
$s4 = Call POST '/tasks' "asst@$c" $pw @{ parentId = $t.id; assigneeId = $rep1.id; units = 10 }
Call POST '/activities' "rep1@$c" $pw @{ customerId = $cu1.id; type = 'VISIT'; subject = 'More'; productId = $prod.id; unitsSold = 30; saleAmount = 30000 } | Out-Null
function Show($id) { $x = (Call GET '/tasks' $a $pw) | ForEach-Object { $_ } | Where-Object id -eq $id; "  task $id ($($x.assignedToName)): $($x.soldUnits)/$($x.units) $($x.status)" }
Show $s1.id; Show $s4.id
"--- rep1 has 5 left: selling 10 is refused (400), selling 5 -> second 10/10 COMPLETED; parent 90/90 COMPLETED"
Call POST '/activities' "rep1@$c" $pw @{ customerId = $cu1.id; type = 'VISIT'; subject = 'More'; productId = $prod.id; unitsSold = 10; saleAmount = 10000 }
Call POST '/activities' "rep1@$c" $pw @{ customerId = $cu1.id; type = 'VISIT'; subject = 'More'; productId = $prod.id; unitsSold = 5; saleAmount = 5000 } | Out-Null
Show $s1.id; Show $s4.id; Show $t.id
"--- delete parent with sub-tasks (409); deactivated product cannot be sold (400)"
Call DELETE "/tasks/$($t.id)" $a $pw
Call PUT "/products/$($prod2.id)" $a $pw @{ name = 'Filter'; unit = 'boxes'; active = $false } | Out-Null
Call POST '/activities' "rep2@$c" $pw @{ customerId = $cu2.id; subject = 'x'; productId = $prod2.id; unitsSold = 1; saleAmount = 500 }
"tenant code: $c"
