# Customers: a name and at least one of mobile, email or address (website/API and WhatsApp).
# Super admin login: set CRM_SUPERADMIN_USERNAME / CRM_SUPERADMIN_PASSWORD (defaults: superadmin / superadmin123).
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$SAU = if ($env:CRM_SUPERADMIN_USERNAME) { $env:CRM_SUPERADMIN_USERNAME } else { 'superadmin' }
$pw = 'secret123'
$c = ('C' + (Get-Random -Maximum 9999))
Call POST '/admin/tenants' $SAU $SA @{ name = "Customers $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw } | Out-Null
$R = "rep@$c"
Call POST '/users' "boss@$c" $pw @{ username = 'rep'; password = $pw; fullName = 'Ravi Rep' } | Out-Null
function Add($body) { $r = Call POST '/customers' $R $pw $body; if ($r -is [string]) { $r } else { "name='$($r.name)' first='$($r.firstName)' last='$($r.lastName)' mobile=$($r.phone) email=$($r.email) address=$($r.address)" } }
function Say($label, $out) { "{0,-40} {1}" -f $label, $out }

Say 'name + mobile' (Add @{ name = 'Meera Iyer'; phone = '98765 43210' })
Say 'one-word name + address' (Add @{ name = 'Ganesh'; address = '12 MG Road, Kochi' })
Say 'name + email' (Add @{ name = '  Arjun   K  Menon '; email = 'arjun@x.com' })
Say 'name only (400)' (Add @{ name = 'Nobody' })
Say 'no name (400)' (Add @{ phone = '98765 00000' })
Say 'bad email (400)' (Add @{ name = 'Bad'; email = 'not-an-email' })
Say 'bad mobile (400)' (Add @{ name = 'Bad'; phone = '12' })
$meera = (Call GET '/customers?q=meera&page=0&size=5' $R $pw).content[0]
Say 'edit: add email, keep mobile' ((Call PUT "/customers/$($meera.id)" $R $pw @{ name = 'Meera Iyer'; phone = $meera.phone; email = 'meera@x.com' }).email)
Say 'edit: remove every contact (400)' (Call PUT "/customers/$($meera.id)" $R $pw @{ name = 'Meera Iyer'; phone = ''; email = ''; address = '' })
Say 'search by mobile digits "43210"' (((Call GET '/customers?q=43210&page=0&size=5' $R $pw).content | ForEach-Object name) -join ', ')
Say 'search by address "kochi"' (((Call GET '/customers?q=kochi&page=0&size=5' $R $pw).content | ForEach-Object name) -join ', ')

"--- WhatsApp: name, then at least one of mobile / email / address"
$ph = "+91 97300 1$((Get-Random -Minimum 1000 -Maximum 9999))"
function Chat($text, $reply) { $r = Call POST '/whatsapp/test-chat' $R $pw @{ phone = $ph; text = $text; replyId = $reply }; if ($r -is [string]) { $r } else { ((@($r | ForEach-Object { $_ }) | ForEach-Object { ($_.text -split "`n")[0] }) -join ' // ') } }
Chat (Call POST '/whatsapp/link-code' $R $pw).code | Out-Null
Say 'add customer' (Chat $null 'm:customer')
Say 'name' (Chat 'Priya Nair')
Say 'skip mobile' (Chat $null 'skip')
Say 'skip email' (Chat $null 'skip')
Say 'skip address -> asks again' (Chat $null 'skip')
Say 'bad mobile' (Chat '12')
Say 'mobile' (Chat '91234 56789')
Say 'skip email' (Chat $null 'skip')
Say 'skip address' (Chat $null 'skip')
Say 'save' (Chat $null 'save:yes')
Say 'on the website' (((Call GET '/customers?q=priya&page=0&size=5' $R $pw).content | ForEach-Object { "$($_.name), mobile $($_.phone)" }) -join '; ')
Call DELETE '/whatsapp/link' $R $pw | Out-Null
"tenant code: $c"
