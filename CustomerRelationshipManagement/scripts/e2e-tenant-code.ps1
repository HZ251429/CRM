$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$c = ('T' + (Get-Random -Maximum 9999)).Substring(0,5)
"--- create tenant code $c"
$t = Call POST '/admin/tenants' superadmin $SA @{ name = "Co $c"; code = $c.ToLower(); adminUsername = 'boss'; adminPassword = 'secret123' }
"created: $($t.name) code=$($t.code)"
"--- duplicate code (expect 409)"
Call POST '/admin/tenants' superadmin $SA @{ name = "Other $c"; code = $c; adminUsername = 'x'; adminPassword = 'secret123' }
"--- code too long (expect 400)"
Call POST '/admin/tenants' superadmin $SA @{ name = "Long $c"; code = 'ABCDEF'; adminUsername = 'x'; adminPassword = 'secret123' }
"--- login without code fails (expect 401), with code works"
Call GET '/me' 'boss' secret123
$me = Call GET '/me' "boss@$c" secret123
"admin login: $($me.username) role=$($me.role) code=$($me.tenantCode)"
"--- admin builds hierarchy: lead -> sub -> subsub"
$lead = Call POST '/users' "boss@$c" secret123 @{ username = 'Lead'; password = 'secret123'; fullName = 'Lead' }
$sub = Call POST '/users' "boss@$c" secret123 @{ username = 'sub'; password = 'secret123'; fullName = 'Sub'; managerId = $lead.id }
$ss = Call POST '/users' "boss@$c" secret123 @{ username = 'subsub'; password = 'secret123'; fullName = 'SubSub'; managerId = $sub.id }
"usernames: $($lead.username), $($sub.username), $($ss.username); subsub reports to $($ss.managerId) (sub=$($sub.id))"
"--- username with @ rejected (expect 400); duplicate (expect 409)"
Call POST '/users' "boss@$c" secret123 @{ username = 'a@b'; password = 'secret123'; fullName = 'X' }
Call POST '/users' "boss@$c" secret123 @{ username = 'lead'; password = 'secret123'; fullName = 'X' }
"--- case-insensitive login"
(Call GET '/me' "LEAD@$($c.ToLower())" secret123).username
