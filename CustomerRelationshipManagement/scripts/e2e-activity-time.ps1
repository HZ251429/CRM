$base = 'http://localhost:8081/api'
function AuthH($u, $p) { @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${u}:${p}")) } }
function Call($method, $path, $user, $pass, $body) {
  $req = @{ Uri = "$base$path"; Method = $method; Headers = (AuthH $user $pass); ContentType = 'application/json'; UseBasicParsing = $true }
  if ($body) { $req.Body = ($body | ConvertTo-Json) }
  try { $r = Invoke-WebRequest @req; if ($r.Content) { return $r.Content | ConvertFrom-Json } } catch { return "HTTP " + [int]$_.Exception.Response.StatusCode }
}
$c = ('M' + (Get-Random -Maximum 9999))
Call POST '/admin/tenants' superadmin superadmin123 @{ name = "Time $c"; code = $c; adminUsername = 'boss'; adminPassword = 'secret123' } | Out-Null
$a = "boss@$c"
$cust = Call POST '/customers' $a secret123 @{ firstName = 'T'; lastName = 'Cust'; email = 't@x.com' }
$act = Call POST '/activities' $a secret123 @{ customerId = $cust.id; type = 'CALL'; subject = 'Call back'; notes = 'x'; activityTime = '14:35' }
"created time=$($act.activityTime)"
$act2 = Call POST '/activities' $a secret123 @{ customerId = $cust.id; type = 'TASK'; subject = 'No time' }
"no-time activity time=[$($act2.activityTime)] (empty is fine)"
$upd = Call PUT "/activities/$($act.id)" $a secret123 @{ customerId = $cust.id; type = 'CALL'; subject = 'Call back'; activityTime = '09:05' }
"updated time=$($upd.activityTime)"
