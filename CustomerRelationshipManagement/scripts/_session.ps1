# Shared helper for the e2e scripts: signs in through /api/auth/login (session cookie + CSRF token)
# and keeps one web session per user.  Usage:  . "$PSScriptRoot\_session.ps1"
if (-not $base) { $base = 'http://localhost:8081/api' }
$SA = if ($env:CRM_SUPERADMIN_PASSWORD) { $env:CRM_SUPERADMIN_PASSWORD } else { 'superadmin123' }
$script:sessions = @{}

function Csrf($ws) {
  $c = $ws.Cookies.GetCookies([Uri]$base) | Where-Object Name -eq 'XSRF-TOKEN' | Select-Object -First 1
  if ($c) { [Uri]::UnescapeDataString($c.Value) } else { '' }
}

function Session($user, $pass) {
  $key = "$($user.ToLower())|$pass"
  if ($script:sessions.ContainsKey($key)) { return $script:sessions[$key] }
  $ws = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  Invoke-WebRequest -Uri "$base/auth/csrf" -WebSession $ws -UseBasicParsing | Out-Null
  $body = @{ username = $user; password = $pass } | ConvertTo-Json
  try {
    Invoke-WebRequest -Uri "$base/auth/login" -Method POST -Body $body -ContentType 'application/json' `
      -Headers @{ 'X-XSRF-TOKEN' = (Csrf $ws) } -WebSession $ws -UseBasicParsing | Out-Null
  } catch { return $null }
  $script:sessions[$key] = $ws
  return $ws
}

function Call($method, $path, $user, $pass, $body) {
  $ws = Session $user $pass
  if (-not $ws) { return 'HTTP 401' }
  $req = @{ Uri = "$base$path"; Method = $method; WebSession = $ws; ContentType = 'application/json'; UseBasicParsing = $true
            Headers = @{ 'X-XSRF-TOKEN' = (Csrf $ws) } }
  if ($body) { $req.Body = ($body | ConvertTo-Json) }
  try { $r = Invoke-WebRequest @req; if ($r.Content) { return $r.Content | ConvertFrom-Json } } catch { return "HTTP " + [int]$_.Exception.Response.StatusCode }
}
