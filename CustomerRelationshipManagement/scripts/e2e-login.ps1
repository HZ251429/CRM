# Sign in with username, email or mobile number; uniqueness; lockout counts per account.
# Super admin login: set CRM_SUPERADMIN_USERNAME / CRM_SUPERADMIN_PASSWORD (defaults: superadmin / superadmin123).
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$SAU = if ($env:CRM_SUPERADMIN_USERNAME) { $env:CRM_SUPERADMIN_USERNAME } else { 'superadmin' }
$pw = 'secret123'
$c = ('L' + (Get-Random -Maximum 9999))
$n = Get-Random -Minimum 10000 -Maximum 99999
$bossEmail = "boss$n@example.com"; $bossMobile = "98765$n"
function Login($id, $pass = $pw) {
  $ws = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  Invoke-WebRequest -Uri "$base/auth/csrf" -WebSession $ws -UseBasicParsing | Out-Null
  $body = @{ username = $id; password = $pass } | ConvertTo-Json
  try {
    $r = Invoke-WebRequest -Uri "$base/auth/login" -Method POST -Body $body -ContentType 'application/json' `
      -Headers @{ 'X-XSRF-TOKEN' = (Csrf $ws) } -WebSession $ws -UseBasicParsing
    return 'OK as ' + ($r.Content | ConvertFrom-Json).username
  } catch {
    $code = [int]$_.Exception.Response.StatusCode
    $msg = ''; try { $msg = (New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch {}
    return "HTTP $code $(($msg | ConvertFrom-Json -ErrorAction SilentlyContinue).message)"
  }
}

"--- super admin creates a company whose admin has an email and a mobile (typed in mixed formats)"
$t = Call POST '/admin/tenants' $SAU $SA @{ name = "Login $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw; adminEmail = "  BOSS$n@Example.COM "; adminMobile = "+91 98765-$n" }
"tenant $($t.code) created"
"--- the admin signs in five ways (all expect OK as boss@$c)"
foreach ($id in "boss@$c", "BOSS@$($c.ToLower())", $bossEmail, $bossMobile, "098765 $n", "+91 98765 $n", "0091$bossMobile") { "{0,-26} {1}" -f $id, (Login $id) }
"--- wrong password / unknown account (expect 401 with a neutral message)"
Login $bossEmail 'wrong-pass'
Login 'nobody@nowhere.com'

$a = "boss@$c"
"--- admin adds a member with email + mobile"
$m = Call POST '/users' $a $pw @{ username = 'rita'; password = $pw; fullName = 'Rita'; email = "rita$n@example.com"; mobile = "91234$n" }
"member: $($m.username)  email=$($m.email)  mobile=$($m.mobile)"
"--- duplicates are refused across companies and formats (expect 409, 409)"
Call POST '/users' $a $pw @{ username = 'dup1'; password = $pw; fullName = 'Dup'; email = "RITA$n@EXAMPLE.com" }
Call POST '/users' $a $pw @{ username = 'dup2'; password = $pw; fullName = 'Dup'; mobile = "+91-91234-$n" }
"--- bad values are refused (expect 400, 400)"
Call POST '/users' $a $pw @{ username = 'bad1'; password = $pw; fullName = 'Bad'; email = 'not-an-email' }
Call POST '/users' $a $pw @{ username = 'bad2'; password = $pw; fullName = 'Bad'; mobile = '12' }
"--- removing the email stops email sign-in (expect OK, then 401, then OK by mobile)"
Login "rita$n@example.com"
Call PUT "/users/$($m.id)" $a $pw @{ fullName = 'Rita'; managerId = $null; teamRoleId = $null; active = $true; email = '' } | Out-Null
Login "rita$n@example.com"
Login "91234$n"
"--- an update without email/mobile keeps them (expect mobile still set)"
(Call PUT "/users/$($m.id)" $a $pw @{ fullName = 'Rita R'; managerId = $null; teamRoleId = $null; active = $true }).mobile
"--- lockout counts per account: 5 wrong passwords via username, mobile and email mixed, then the right password (expect 429)"
foreach ($id in "rita@$c", "91234$n", "rita@$c", "91234$n", "rita@$c") { Login $id 'wrong-pass' | Out-Null }
Login "rita@$c"
"tenant code: $c"
