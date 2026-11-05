$base = 'http://localhost:8081/api'
function AuthH($u, $p) { @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${u}:${p}")) } }
function Call($method, $path, $user, $pass, $body) {
  $req = @{ Uri = "$base$path"; Method = $method; Headers = (AuthH $user $pass); ContentType = 'application/json'; UseBasicParsing = $true }
  if ($body) { $req.Body = ($body | ConvertTo-Json) }
  try { $r = Invoke-WebRequest @req; if ($r.Content) { return $r.Content | ConvertFrom-Json } } catch { return "HTTP " + [int]$_.Exception.Response.StatusCode }
}
$c = ('R' + (Get-Random -Maximum 9999))
$t = Call POST '/admin/tenants' superadmin superadmin123 @{ name = "Roles $c"; code = $c; adminUsername = 'boss'; adminPassword = 'secret123' }
$a = "boss@$c"
"--- roles: create, duplicate (409), list"
$r1 = Call POST '/roles' $a secret123 @{ name = 'Sales Manager' }
$r2 = Call POST '/roles' $a secret123 @{ name = 'Executive' }
Call POST '/roles' $a secret123 @{ name = 'sales manager' }
(Call GET '/roles' $a secret123 | ForEach-Object { $_.name }) -join ', '
"--- members with roles and hierarchy"
$m1 = Call POST '/users' $a secret123 @{ username = 'lead'; password = 'secret123'; fullName = 'Lead'; teamRoleId = $r1.id }
$m2 = Call POST '/users' $a secret123 @{ username = 'exec'; password = 'secret123'; fullName = 'Exec'; managerId = $m1.id; teamRoleId = $r2.id }
"m1 role=$($m1.teamRoleName) mgr=$($m1.managerId); m2 role=$($m2.teamRoleName) mgr=$($m2.managerId) (lead id $($m1.id))"
"--- unknown role (expect 400)"
Call POST '/users' $a secret123 @{ username = 'bad'; password = 'secret123'; fullName = 'Bad'; teamRoleId = 999999 }
"--- delete role in use (expect 409), delete after reassign"
Call DELETE "/roles/$($r1.id)" $a secret123
"--- member cannot manage roles (expect 403)"
Call GET '/roles' "lead@$c" secret123
"--- super admin detail shows role"
$d = Call GET "/admin/tenants/$($t.id)/details" superadmin superadmin123
($d.users | ForEach-Object { "$($_.username):$($_.teamRoleName)" }) -join ', '
