$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080/api'
function AuthH($u, $p) { @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${u}:${p}")) } }
function Call($method, $path, $user, $pass, $body) {
  $req = @{ Uri = "$base$path"; Method = $method; Headers = (AuthH $user $pass); ContentType = 'application/json'; UseBasicParsing = $true }
  if ($body) { $req.Body = ($body | ConvertTo-Json) }
  try { $r = Invoke-WebRequest @req; if ($r.Content) { return $r.Content | ConvertFrom-Json } } catch { return "HTTP " + [int]$_.Exception.Response.StatusCode }
}
$sfx = Get-Random -Maximum 9999

"--- super admin creates two tenants"
$t1 = Call POST '/admin/tenants' superadmin superadmin123 @{ name = "Acme$sfx"; adminUsername = "acme$sfx"; adminPassword = 'secret123'; adminFullName = 'Acme Admin' }
$t2 = Call POST '/admin/tenants' superadmin superadmin123 @{ name = "Globex$sfx"; adminUsername = "globex$sfx"; adminPassword = 'secret123' }
"tenants: $($t1.name), $($t2.name)"

"--- tenant admin builds hierarchy: lead -> member"
$a = "acme$sfx"
$lead = Call POST '/users' $a secret123 @{ username = "lead$sfx"; password = 'secret123'; fullName = 'Lead Lisa' }
$mem  = Call POST '/users' $a secret123 @{ username = "mem$sfx"; password = 'secret123'; fullName = 'Member Mike'; managerId = $lead.id }
"lead id $($lead.id), member id $($mem.id) reports to $($mem.managerId)"

"--- member and lead create customers + deals"
$c1 = Call POST '/customers' "mem$sfx" secret123 @{ firstName = 'M'; lastName = 'Cust'; email = 'm@x.com' }
$c2 = Call POST '/customers' "lead$sfx" secret123 @{ firstName = 'L'; lastName = 'Cust'; email = 'l@x.com' }
Call POST '/deals' "mem$sfx" secret123 @{ customerId = $c1.id; title = 'Big deal'; amount = 1000; stage = 'WON' } | Out-Null
Call POST '/deals' "lead$sfx" secret123 @{ customerId = $c2.id; title = 'Lead deal'; amount = 500; stage = 'NEW' } | Out-Null

"--- lead dashboard (rolled up): expect 2 customers, 2 deals"
$d = Call GET '/dashboard' "lead$sfx" secret123
"lead sees customers=$($d.totalCustomers) deals=$($d.totalDeals) won=$($d.wonValue) teamSize=$($d.teamSize)"
"--- lead views member dashboard: expect 1 customer"
$d = Call GET "/dashboard?userId=$($mem.id)" "lead$sfx" secret123
"member dash customers=$($d.totalCustomers) won=$($d.wonValue)"
"--- member views own dashboard: expect 1 customer"
$d = Call GET '/dashboard' "mem$sfx" secret123
"member self customers=$($d.totalCustomers)"
"--- member tries lead dashboard: expect HTTP 403"
Call GET "/dashboard?userId=$($lead.id)" "mem$sfx" secret123
"--- member lists customers: expect only own (1)"
(Call GET '/customers/all' "mem$sfx" secret123).Count
"--- admin dashboard: expect 2 customers"
(Call GET '/dashboard' $a secret123).totalCustomers

"--- other tenant isolation: globex sees 0 customers, cannot read acme customer"
(Call GET '/customers/all' "globex$sfx" secret123).Count
Call GET "/customers/$($c1.id)" "globex$sfx" secret123
"--- member cannot access user mgmt / tenants: expect 403 403"
Call GET '/users' "mem$sfx" secret123
Call GET '/admin/tenants' $a secret123
"--- super admin has no CRM data: expect 403"
Call GET '/customers/all' superadmin superadmin123
"--- disable acme tenant then login: expect 401"
Call PUT "/admin/tenants/$($t1.id)/active" superadmin superadmin123 @{ active = $false } | Out-Null
Call GET '/me' "lead$sfx" secret123


