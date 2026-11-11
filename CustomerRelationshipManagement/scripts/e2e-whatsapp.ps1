# WhatsApp assistant through the test chat: linking, menu, balance, tasks, logging a sale, adding a customer, safety.
# Super admin login: set CRM_SUPERADMIN_USERNAME / CRM_SUPERADMIN_PASSWORD (defaults: superadmin / superadmin123).
$base = 'http://localhost:8081/api'
. "$PSScriptRoot\_session.ps1"
$SAU = if ($env:CRM_SUPERADMIN_USERNAME) { $env:CRM_SUPERADMIN_USERNAME } else { 'superadmin' }
$pw = 'secret123'
$c = ('W' + (Get-Random -Maximum 9999))
$n = Get-Random -Minimum 10000 -Maximum 99999
$repPhone = "97000$n"; $otherPhone = "96000$n"

Call POST '/admin/tenants' $SAU $SA @{ name = "WhatsApp $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw } | Out-Null
$a = "boss@$c"; $r = "rep@$c"
$rep = Call POST '/users' $a $pw @{ username = 'rep'; password = $pw; fullName = 'Ravi Rep' }
Call POST '/users' $a $pw @{ username = 'other'; password = $pw; fullName = 'Other' } | Out-Null
$p = Call POST '/products' $a $pw @{ name = 'Purifier'; unit = 'pcs' }
Call POST '/stock' $a $pw @{ productId = $p.id; quantity = 500 } | Out-Null
Call POST '/tasks' $a $pw @{ productId = $p.id; assigneeId = $rep.id; units = 50; title = 'November push' } | Out-Null
Call POST '/customers' $r $pw @{ firstName = 'Meera'; lastName = 'Iyer'; email = 'meera@x.com'; company = 'Iyer Traders' } | Out-Null

function Chat($user, $phone, $text, $reply) {
  $res = Call POST '/whatsapp/test-chat' $user $pw @{ phone = $phone; text = $text; replyId = $reply }
  if ($res -is [string]) { return $res }
  $script:last = @($res)
  ($script:last | ForEach-Object {
    $t = ($_.text -split "`n")[0]
    if ($_.buttons.Count) { $t += '  [' + (($_.buttons | ForEach-Object title) -join ' | ') + ']' }
    if ($_.list) { $t += '  {' + (($_.list.rows | ForEach-Object title) -join ' | ') + '}' }
    $t }) -join '  //  '
}
function Say($label, $out) { "{0,-34} {1}" -f $label, $out }

"--- an unknown number gets instructions"
Say 'hi (not linked)' (Chat $r $repPhone 'hi')
"--- linking"
Say 'wrong code' (Chat $r $repPhone 'ABCD-EFGH')
$code = (Call POST '/whatsapp/link-code' $r $pw).code
Say "send code $code" (Chat $r $repPhone "link $($code.ToLower())")
Say 'same code again (used up)' (Chat $r $repPhone $code)
Say 'status' ((Call GET '/whatsapp/status' $r $pw) | ForEach-Object { "linked=$($_.linked) phone=$($_.phone)" })
"--- safety: another user cannot write as the rep's number (expect 403)"
Say 'other uses rep phone' (Chat "other@$c" $repPhone 'hi')
"--- menu, balance, tasks"
Say 'hi' (Chat $r $repPhone 'hi')
Say 'tap My balance' (Chat $r $repPhone $null 'm:balance')
Say 'type tasks' (Chat $r $repPhone 'tasks')
"--- log a sale: Meera, visit, 60 refused (only 50), 20 for Rs 20,000, Rs 5,000 paid"
Say 'tap Log activity' (Chat $r $repPhone $null 'm:log')
Say 'search "iyer"' (Chat $r $repPhone 'iyer')
$cust = ($last[0].list.rows | Select-Object -First 1).id
Say "pick $cust" (Chat $r $repPhone $null $cust)
Say 'pick Visit' (Chat $r $repPhone $null 't:VISIT')
Say 'title' (Chat $r $repPhone 'Demo of the new purifier')
Say 'skip notes' (Chat $r $repPhone $null 'skip')
Say 'yes, a sale' (Chat $r $repPhone $null 'sale:yes')
Say "pick product p:$($p.id)" (Chat $r $repPhone $null "p:$($p.id)")
Say '60 units' (Chat $r $repPhone '60')
Say '20 units' (Chat $r $repPhone '20')
Say 'amount 20,000' (Chat $r $repPhone '20,000')
Say 'part on credit' (Chat $r $repPhone $null 'paid:part')
Say 'paid 5000' (Chat $r $repPhone '5000')
Say 'save' (Chat $r $repPhone $null 'save:yes')
$pg = Call GET '/activities/page?salesOnly=true' $r $pw
Say 'saved on the website' ("$($pg.totalElements) sale, units=$($pg.content[0].unitsSold) amount=$($pg.content[0].saleAmount) credit=$($pg.content[0].creditAmount) customer=$($pg.content[0].customerName)")
Chat $r $repPhone 'balance' | Out-Null; Say 'balance now (expect 30)' (($last[0].text -split "`n" | Where-Object { $_ -match 'sell' }) -join ' ')
"--- an 8-letter answer that looks like a code is taken as the answer (title 'Payments')"
Chat $r $repPhone 'log' | Out-Null
Chat $r $repPhone 'iyer' | Out-Null
Chat $r $repPhone $null $last[0].list.rows[0].id | Out-Null
Chat $r $repPhone $null 't:CALL' | Out-Null
Say 'title Payments' (Chat $r $repPhone 'Payments')
Chat $r $repPhone 'cancel' | Out-Null
"--- cancel in the middle"
Say 'log' (Chat $r $repPhone 'log')
Say 'cancel' (Chat $r $repPhone 'cancel')
"--- add a customer, then log for them"
Say 'tap Add customer' (Chat $r $repPhone $null 'm:customer')
Say 'first' (Chat $r $repPhone 'Arjun')
Say 'last' (Chat $r $repPhone 'Menon')
Say 'bad email' (Chat $r $repPhone 'arjun-at-x')
Say 'good email' (Chat $r $repPhone 'arjun@x.com')
Say 'skip phone' (Chat $r $repPhone $null 'skip')
Say 'company' (Chat $r $repPhone 'Menon Stores')
Say 'save' (Chat $r $repPhone $null 'save:yes')
Say 'on the website' (((Call GET '/customers?q=menon&page=0&size=5' $r $pw).content | ForEach-Object { "$($_.firstName) $($_.lastName), $($_.company), owner=$($_.ownerId -eq $rep.id)" }) -join '; ')
"--- wrong codes are limited: 5 wrong from one number, then even a right code is refused for 15 minutes"
1..5 | ForEach-Object { Chat "other@$c" $otherPhone 'ZZZZ-ZZZZ' | Out-Null }
$code2 = (Call POST '/whatsapp/link-code' "other@$c" $pw).code
Say 'right code after 5 wrong' (Chat "other@$c" $otherPhone $code2)
"--- unlink"
Call DELETE '/whatsapp/link' $r $pw | Out-Null
Say 'hi after unlink' (Chat $r $repPhone 'hi')
"tenant code: $c"
