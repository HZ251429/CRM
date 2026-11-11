# WhatsApp Cloud API connector against a fake Meta (scripts/fake-meta.ps1 style server writing a .jsonl log).
# Start the CRM with WHATSAPP_PHONE_NUMBER_ID=123456789 WHATSAPP_ACCESS_TOKEN=test-token
# WHATSAPP_APP_SECRET=test-app-secret WHATSAPP_VERIFY_TOKEN=test-verify WHATSAPP_GRAPH_URL=http://localhost:8099
param([string]$base = 'http://localhost:8082/api', [string]$metaLog = "$env:TEMP\fake-meta.jsonl",
  [string]$secret = 'test-app-secret')
. "$PSScriptRoot\_session.ps1"
$SAU = if ($env:CRM_SUPERADMIN_USERNAME) { $env:CRM_SUPERADMIN_USERNAME } else { 'superadmin' }
$pw = 'secret123'
$c = ('M' + (Get-Random -Maximum 9999))
$n = Get-Random -Minimum 10000 -Maximum 99999
$repWa = "9193001$n"; $leadWa = "9192001$n"   # as Meta sends them: country code + number
$hook = "$base/whatsapp/webhook"

function Sign([byte[]]$bytes, $key) {
  $h = New-Object System.Security.Cryptography.HMACSHA256 (,[Text.Encoding]::UTF8.GetBytes($key))
  'sha256=' + (($h.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
}
# a webhook call shaped like Meta's; $msg is the "messages" item
function Hook($msg, $key = $secret, [switch]$noSig) {
  $payload = @{ object = 'whatsapp_business_account'; entry = @(@{ id = 'WABA'; changes = @(@{ field = 'messages'
          value = @{ messaging_product = 'whatsapp'; metadata = @{ phone_number_id = '123456789' }; messages = @($msg) } }) }) }
  $bytes = [Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Depth 10 -Compress))
  $headers = @{}; if (-not $noSig) { $headers['X-Hub-Signature-256'] = Sign $bytes $key }
  try { (Invoke-WebRequest -Uri $hook -Method POST -Body $bytes -ContentType 'application/json' -Headers $headers -UseBasicParsing).StatusCode }
  catch { [int]$_.Exception.Response.StatusCode }
}
function Text($from, $text) { Hook @{ from = $from; id = "wamid.in$(Get-Random)"; timestamp = '0'; type = 'text'; text = @{ body = $text } } }
function Tap($from, $id, $kind = 'button_reply') {
  Hook @{ from = $from; id = "wamid.in$(Get-Random)"; timestamp = '0'; type = 'interactive'; interactive = @{ type = $kind; $kind = @{ id = $id; title = $id } } }
}
$sent = 0
function Sent($wait = 6) {
  Start-Sleep -Seconds $wait
  $all = @(Get-Content $metaLog -Encoding utf8 -ErrorAction SilentlyContinue | ForEach-Object { $_ | ConvertFrom-Json })
  $new = $all | Select-Object -Skip $script:sent; $script:sent = $all.Count
  if (-not $new) { return '(nothing)' }
  ($new | ForEach-Object {
    $b = $_.body | ConvertFrom-Json
    $what = switch ($b.type) {
      'text' { 'text: ' + (($b.text.body -split "`n")[0]) }
      'interactive' { "$($b.interactive.type): " + (($b.interactive.body.text -split "`n")[0]) + $(if ($b.interactive.type -eq 'list') { ' {' + (($b.interactive.action.sections[0].rows | ForEach-Object title) -join '|') + '}' } else { ' [' + (($b.interactive.action.buttons | ForEach-Object { $_.reply.title }) -join '|') + ']' }) }
      'template' { "template $($b.template.name): " + (($b.template.components | ForEach-Object { ($_.parameters | ForEach-Object { if ($_.text) { $_.text } else { 'payload=' + $_.payload } }) -join ', ' }) -join ' ; ') }
    }
    "to $($b.to) -> $what" }) -join "`n" + (' ' * 48)
}
function Say($label, $out) { "{0,-46} {1}" -f $label, $out }

"--- Meta's address check"
Say 'right verify token' ((Invoke-WebRequest "$hook`?hub.mode=subscribe&hub.verify_token=test-verify&hub.challenge=12345" -UseBasicParsing).Content)
$code403 = try { (Invoke-WebRequest "$hook`?hub.mode=subscribe&hub.verify_token=nope&hub.challenge=1" -UseBasicParsing).StatusCode } catch { [int]$_.Exception.Response.StatusCode }
Say 'wrong verify token (403)' $code403
"--- signatures"
Say 'no signature (401)' (Hook @{ from = $repWa; id = 'x1'; type = 'text'; text = @{ body = 'hi' } } -noSig)
Say 'wrong secret (401)' (Hook @{ from = $repWa; id = 'x2'; type = 'text'; text = @{ body = 'hi' } } -key 'not-the-secret')
Say '...and nothing was sent' (Sent 2)

Call POST '/admin/tenants' $SAU $SA @{ name = "Meta $c"; code = $c; adminUsername = 'boss'; adminPassword = $pw } | Out-Null
$a = "boss@$c"
$lead = Call POST '/users' $a $pw @{ username = 'lead'; password = $pw; fullName = 'Lata Lead' }
$rep = Call POST '/users' $a $pw @{ username = 'rep'; password = $pw; fullName = 'Ravi Rep'; managerId = $lead.id }
$fail = Call POST '/users' $a $pw @{ username = 'fail'; password = $pw; fullName = 'Unreachable' }
$p = Call POST '/products' $a $pw @{ name = 'Purifier'; unit = 'pcs' }
Call POST '/stock' $a $pw @{ productId = $p.id; quantity = 500 } | Out-Null

"--- a real conversation through the webhook"
Say 'unknown number says hi' (Text $repWa 'hi'); Say '  CRM replies via Meta' (Sent)
$code = (Call POST '/whatsapp/link-code' "rep@$c" $pw).code
Say "rep sends link code" (Text $repWa $code); Say '  CRM replies via Meta' (Sent)
Say 'rep taps My balance in the list' (Tap $repWa 'm:balance' 'list_reply'); Say '  CRM replies via Meta' (Sent)
$dup = @{ from = $repWa; id = "wamid.same-$(Get-Random)"; timestamp = '0'; type = 'text'; text = @{ body = 'tasks' } }
Hook $dup | Out-Null; Hook $dup | Out-Null
Say 'same message delivered twice -> one reply' (Sent)
Say 'a photo (not supported)' (Hook @{ from = $repWa; id = "wamid.in$(Get-Random)"; type = 'image'; image = @{ id = 'm1' } }); Say '  CRM replies via Meta' (Sent)

"--- alerts: rep wrote just now (normal message); lead never wrote (template)"
Call POST '/whatsapp/test-chat' "lead@$c" $pw @{ phone = "+$leadWa"; text = (Call POST '/whatsapp/link-code' "lead@$c" $pw).code } | Out-Null
$t = Call POST '/tasks' $a $pw @{ productId = $p.id; assigneeId = $lead.id; units = 100; title = 'Diwali push' }
Call POST '/tasks' "lead@$c" $pw @{ parentId = $t.id; assigneeId = $rep.id; units = 40 } | Out-Null
Say 'admin -> lead, lead -> rep' (Sent 22)
"--- summary for the lead (outside the window): template with a Show summary button"
Call POST '/whatsapp/summary-now' "lead@$c" $pw | Out-Null
Say 'send summary now' (Sent 22)
Say 'lead taps Show summary (template button)' (Hook @{ from = $leadWa; id = "wamid.in$(Get-Random)"; type = 'button'; button = @{ payload = 'm:summary'; text = 'Show summary' } })
Say '  CRM replies via Meta' (Sent)
"--- Meta refuses a message: it is retried and the reason kept"
Call POST '/whatsapp/test-chat' "fail@$c" $pw @{ phone = '+910000000000'; text = (Call POST '/whatsapp/link-code' "fail@$c" $pw).code } | Out-Null
Call POST '/tasks' $a $pw @{ productId = $p.id; assigneeId = $fail.id; units = 5; title = 'Will fail' } | Out-Null
Start-Sleep -Seconds 35
"tenant code: $c"
