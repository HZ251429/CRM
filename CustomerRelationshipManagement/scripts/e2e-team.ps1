# Members see only their own team and can add/edit members under themselves.
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$pw = 'secret123'
$c = ('T' + (Get-Random -Maximum 9999))
Call POST '/admin/tenants' superadmin $SA @{ name = "Team $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw } | Out-Null
$a = "boss@$c"
$lead = Call POST '/users' $a $pw @{ username = 'lead'; password = $pw; fullName = 'Lead' }
$other = Call POST '/users' $a $pw @{ username = 'other'; password = $pw; fullName = 'Other Lead' }
$role = Call POST '/roles' $a $pw @{ name = 'Executive' }
function Names($u) { ((Call GET '/users' "$u@$c" $pw) | ForEach-Object { $_ } | ForEach-Object fullName) -join ', ' }

"--- lead adds a member (no manager given -> reports to lead), with a role"
$m1 = Call POST '/users' "lead@$c" $pw @{ username = 'm1'; password = $pw; fullName = 'Member One'; teamRoleId = $role.id }
"m1 reports to $($m1.managerId) (lead=$($lead.id)), role=$($m1.teamRoleName)"
"--- lead adds m2 under m1"
$m2 = Call POST '/users' "lead@$c" $pw @{ username = 'm2'; password = $pw; fullName = 'Member Two'; managerId = $m1.id }
"m2 reports to $($m2.managerId) (m1=$($m1.id))"
"--- lead cannot add under someone outside the team (403)"
Call POST '/users' "lead@$c" $pw @{ username = 'x1'; password = $pw; fullName = 'X'; managerId = $other.id }
"--- who sees whom (expect lead: Lead, Member One, Member Two; m1: Member One, Member Two; other: Other Lead)"
"lead: " + (Names 'lead'); "m1: " + (Names 'm1'); "other: " + (Names 'other'); "admin: " + (Names 'boss')
"--- m1 (now a lead too) adds m3 under self"
$m3 = Call POST '/users' "m1@$c" $pw @{ username = 'm3'; password = $pw; fullName = 'Member Three' }
"m3 reports to $($m3.managerId) (m1=$($m1.id))"
"--- edits: lead renames m2 (ok); lead edits self (403); lead edits other (404/403); m1 edits lead (403)"
(Call PUT "/users/$($m2.id)" "lead@$c" $pw @{ fullName = 'Member 2'; managerId = $m1.id; active = $true }).fullName
Call PUT "/users/$($lead.id)" "lead@$c" $pw @{ fullName = 'Me'; managerId = $null; active = $true }
Call PUT "/users/$($other.id)" "lead@$c" $pw @{ fullName = 'Hack'; active = $true }
Call PUT "/users/$($lead.id)" "m1@$c" $pw @{ fullName = 'Hack'; active = $true }
"--- lead cannot move m2 to the admin (null manager -> stays in lead's team) or reset a password (403)"
(Call PUT "/users/$($m2.id)" "lead@$c" $pw @{ fullName = 'Member 2'; managerId = $null; active = $true }).managerId
Call PUT "/users/$($m2.id)" "lead@$c" $pw @{ fullName = 'Member 2'; managerId = $m1.id; active = $true; password = 'newpass123' }
"--- lead deactivates m3 (ok), members can read roles, cannot create roles (403)"
(Call PUT "/users/$($m3.id)" "lead@$c" $pw @{ fullName = 'Member Three'; managerId = $m1.id; active = $false }).active
((Call GET '/roles' "m2@$c" $pw) | ForEach-Object { $_ } | ForEach-Object name) -join ', '
Call POST '/roles' "lead@$c" $pw @{ name = 'Boss' }
"--- team overview for lead only lists their team"
((Call GET '/dashboard/team' "lead@$c" $pw) | ForEach-Object { $_ } | ForEach-Object fullName) -join ', '
"tenant code: $c"
