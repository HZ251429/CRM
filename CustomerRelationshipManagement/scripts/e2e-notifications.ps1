# Leads (and the admin) are notified when someone below them adds a customer, a team member or an activity.
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$pw = 'secret123'
$c = ('N' + (Get-Random -Maximum 9999))
Call POST '/admin/tenants' superadmin $SA @{ name = "Notify $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw; adminFullName = 'Boss' } | Out-Null
$a = "boss@$c"
$lead = Call POST '/users' $a $pw @{ username = 'lead'; password = $pw; fullName = 'Lead' }
$rep = Call POST '/users' "lead@$c" $pw @{ username = 'rep'; password = $pw; fullName = 'Rep' }
$peer = Call POST '/users' $a $pw @{ username = 'peer'; password = $pw; fullName = 'Peer' }
$prod = Call POST '/products' $a $pw @{ name = 'Purifier'; unit = 'pcs' }
function Inbox($u) {
  $r = Call GET '/notifications' "$u@$c" $pw
  "{0,-5} unread={1}: {2}" -f $u, $r.unread, ((@($r.items) | ForEach-Object { "[$($_.type)] $($_.message)" }) -join ' | ')
}
"--- after: lead added Rep (boss told), admin added Peer (nobody above the admin)"
Inbox 'boss'; Inbox 'lead'
"--- rep adds a customer and a sale -> lead and boss are told; peer and rep are not"
$cu = Call POST '/customers' "rep@$c" $pw @{ firstName = 'Big'; lastName = 'Buyer'; email = 'b@x.com'; company = 'Acme' }
Call POST '/activities' "rep@$c" $pw @{ customerId = $cu.id; type = 'VISIT'; subject = 'First order'; productId = $prod.id; unitsSold = 10; saleAmount = 45000 } | Out-Null
Inbox 'lead'; Inbox 'boss'; Inbox 'peer'; Inbox 'rep'
"--- rep adds a member under self -> lead and boss told"
Call POST '/users' "rep@$c" $pw @{ username = 'jr'; password = $pw; fullName = 'Junior' } | Out-Null
Inbox 'lead'
"--- mark one read, then all read"
$first = @((Call GET '/notifications' "lead@$c" $pw).items)[0]
Call POST "/notifications/$($first.id)/read" "lead@$c" $pw | Out-Null
"lead unread after one read: " + (Call GET '/notifications' "lead@$c" $pw).unread
"boss cannot mark lead's notification (404): " + (Call POST "/notifications/$($first.id)/read" $a $pw)
Call POST '/notifications/read-all' "lead@$c" $pw | Out-Null
"lead unread after read-all: " + (Call GET '/notifications' "lead@$c" $pw).unread
"tenant code: $c"
