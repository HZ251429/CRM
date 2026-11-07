# Any user can introduce roles; roles are visible to the creator, their line above and their team below.
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$pw = 'secret123'
$c = ('R' + (Get-Random -Maximum 9999))
Call POST '/admin/tenants' superadmin $SA @{ name = "Roles $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw } | Out-Null
$a = "boss@$c"
$lead = Call POST '/users' $a $pw @{ username = 'lead'; password = $pw; fullName = 'Lead' }
$other = Call POST '/users' $a $pw @{ username = 'other'; password = $pw; fullName = 'Other Lead' }
$m1 = Call POST '/users' "lead@$c" $pw @{ username = 'm1'; password = $pw; fullName = 'Member One' }
function Roles($u) { ((Call GET '/roles' $u $pw) | ForEach-Object { $_ } | ForEach-Object { "$($_.name)[$($_.createdByName),edit=$($_.canEdit)]" }) -join ', ' }

$exec = Call POST '/roles' $a $pw @{ name = 'Executive' }
$tl = Call POST '/roles' "lead@$c" $pw @{ name = 'Team Lead' }
$jr = Call POST '/roles' "m1@$c" $pw @{ name = 'Junior' }
$ol = Call POST '/roles' "other@$c" $pw @{ name = 'Other Role' }
"--- visibility"
"admin: " + (Roles $a)
"lead (expect Executive, Junior, Team Lead): " + (Roles "lead@$c")
"m1 (expect Executive, Junior, Team Lead): " + (Roles "m1@$c")
"other (expect Executive, Other Role): " + (Roles "other@$c")
"--- duplicate name (409)"; Call POST '/roles' "m1@$c" $pw @{ name = 'executive' }
"--- lead renames own role (ok)"; (Call PUT "/roles/$($tl.id)" "lead@$c" $pw @{ name = 'Team Leader' }).name
"--- lead renames admin role (403)"; Call PUT "/roles/$($exec.id)" "lead@$c" $pw @{ name = 'X' }
"--- lead renames other's role (404)"; Call PUT "/roles/$($ol.id)" "lead@$c" $pw @{ name = 'X' }
"--- lead assigns Junior (from m1) to new member (ok)"
(Call POST '/users' "lead@$c" $pw @{ username = 'm2'; password = $pw; fullName = 'Member Two'; teamRoleId = $jr.id }).teamRoleName
"--- lead assigns other's role (400)"
Call POST '/users' "lead@$c" $pw @{ username = 'm3'; password = $pw; fullName = 'Member Three'; teamRoleId = $ol.id }
"--- m1 deletes Junior while assigned (409)"; Call DELETE "/roles/$($jr.id)" "m1@$c" $pw
"--- admin renames lead's role (ok)"; (Call PUT "/roles/$($tl.id)" $a $pw @{ name = 'Squad Lead' }).name
"--- other deletes own role (ok)"; Call DELETE "/roles/$($ol.id)" "other@$c" $pw; "other: " + (Roles "other@$c")
