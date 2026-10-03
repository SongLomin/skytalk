# 기내톡 (SkyTalk) — Windows 호스트 서버
# SkyTalk-Windows.bat 이 관리자 권한으로 이 코드를 실행합니다. 설치할 것은 없습니다.
# 1) 노트북 Wi-Fi로 핫스팟(Wi-Fi Direct)을 만들고  2) 그 안에서 채팅 서버를 엽니다.  인터넷은 필요 없습니다.
param(
  [int]$Port = 8080,
  [string]$DataDir = '',
  [string]$Ssid = 'SkyTalk',
  [string]$Pass = 'skytalk1234',
  [switch]$NoHotspot,
  [switch]$NoFirewall,
  [switch]$NoBrowser
)

Set-StrictMode -Off
$ErrorActionPreference = 'Continue'
$Version = '1.0.0'
$HtmlB64 = ''

$OnlineMs = 45000
$PollWaitMs = 20000
$MaxText = 2000
$MaxUpload = 6MB
$RespCap = 300
$IdRe = '^[A-Za-z0-9_-]{4,40}$'
$ImgRe = '^[a-z0-9]{8,40}\.(jpg|png)$'
$Csp = "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"

try { $Host.UI.RawUI.WindowTitle = '기내톡 서버 (SkyTalk)' } catch { }
Add-Type -AssemblyName System.Web
$Utf8 = New-Object System.Text.UTF8Encoding($false)
$Clock = [Diagnostics.Stopwatch]::StartNew()

function Say([string]$text, [string]$color = 'Gray') { Write-Host $text -ForegroundColor $color }
function NowMs { [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() }
function JStr([string]$s) { [System.Web.HttpUtility]::JavaScriptStringEncode($s, $true) }
function JBool([bool]$b) { if ($b) { 'true' } else { 'false' } }

function Clean-Name([string]$s) {
  if (-not $s) { return '' }
  $s = [regex]::Replace([regex]::Replace($s, '[\x00-\x1f\x7f]', ' '), '\s+', ' ').Trim()
  $si = New-Object Globalization.StringInfo $s
  if ($si.LengthInTextElements -gt 20) { $s = $si.SubstringByTextElements(0, 20) }
  return $s
}
function Clean-Seat([string]$s) {
  if (-not $s) { return '' }
  $s = [regex]::Replace($s.ToUpperInvariant(), '[^0-9A-Z]', '')
  if ($s.Length -gt 6) { $s = $s.Substring(0, 6) }
  return $s
}
function Clean-Text([string]$s) {
  if (-not $s) { return '' }
  $s = $s.Replace("`r`n", "`n").Replace("`r", "`n")
  $s = [regex]::Replace($s, '[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]', '')
  return $s.TrimEnd().TrimStart([char[]]"`n")
}
function To-Long([string]$s, [long]$default) {
  $v = 0L
  if ([long]::TryParse($s, [ref]$v)) { return $v }
  return $default
}

# ---------------- 저장소 ----------------
if (-not $DataDir) { $DataDir = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'SkyTalk-data' }
$ImgDir = Join-Path $DataDir 'img'
[void](New-Item -ItemType Directory -Force -Path $ImgDir)
$MsgFile = Join-Path $DataDir 'messages.jsonl'
$MembersFile = Join-Path $DataDir 'members.json'
$RoomFile = Join-Path $DataDir 'room.json'

$MsgIds = New-Object 'System.Collections.Generic.List[long]'
$MsgJson = New-Object 'System.Collections.Generic.List[string]'
$ByCid = New-Object 'System.Collections.Generic.Dictionary[string,long]'
$Members = New-Object 'System.Collections.Generic.Dictionary[string,object]'
$Waiters = New-Object 'System.Collections.Generic.List[object]'
$script:Rev = 0L
$script:DirtyAt = -1L
$script:MembersChanged = $false
$script:LastMemberSave = 0L
$script:Sid = ''

function Get-LastId { if ($MsgIds.Count -gt 0) { return $MsgIds[$MsgIds.Count - 1] }; return 0L }

function Load-Room {
  try { $script:Sid = [string](([IO.File]::ReadAllText($RoomFile, $Utf8)) | ConvertFrom-Json).sid } catch { $script:Sid = '' }
  if ($script:Sid -cnotmatch $IdRe) {
    $script:Sid = 'r' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
    [IO.File]::WriteAllText($RoomFile, '{"sid":' + (JStr $script:Sid) + ',"created":' + (NowMs) + '}', $Utf8)
  }
  if (Test-Path -LiteralPath $MsgFile) {
    foreach ($line in [IO.File]::ReadAllLines($MsgFile, $Utf8)) {
      $mId = [regex]::Match($line, '^\{\s*"id"\s*:\s*(\d+)')
      if (-not $mId.Success) { continue }
      $id = [long]$mId.Groups[1].Value
      if ($MsgIds.Count -gt 0 -and $id -le $MsgIds[$MsgIds.Count - 1]) { continue }
      $MsgIds.Add($id); $MsgJson.Add($line.Trim())
      $mCid = [regex]::Match($line, '"cid"\s*:\s*"([A-Za-z0-9_-]+)"')
      if ($mCid.Success) { $ByCid[$mCid.Groups[1].Value] = $id }
    }
  }
  if (Test-Path -LiteralPath $MembersFile) {
    try {
      $obj = [IO.File]::ReadAllText($MembersFile, $Utf8) | ConvertFrom-Json
      foreach ($p in $obj.PSObject.Properties) {
        $v = $p.Value
        $nm = Clean-Name ([string]$v.name)
        if ($p.Name -cmatch $IdRe -and $nm) {
          $Members[$p.Name] = [pscustomobject]@{ name = $nm; seat = (Clean-Seat ([string]$v.seat)); seen = (To-Long ([string]$v.seen) 0); read = (To-Long ([string]$v.read) 0) }
        }
      }
    } catch { }
  }
}

function Save-Members {
  $sb = New-Object System.Text.StringBuilder
  [void]$sb.Append('{')
  $first = $true
  foreach ($kv in $Members.GetEnumerator()) {
    if (-not $first) { [void]$sb.Append(',') }
    $first = $false
    $m = $kv.Value
    [void]$sb.Append((JStr $kv.Key) + ':{"name":' + (JStr $m.name) + ',"seat":' + (JStr $m.seat) + ',"seen":' + $m.seen + ',"read":' + $m.read + '}')
  }
  [void]$sb.Append('}')
  try {
    [IO.File]::WriteAllText($MembersFile + '.tmp', $sb.ToString(), $Utf8)
    Move-Item -LiteralPath ($MembersFile + '.tmp') -Destination $MembersFile -Force
  } catch { }
  $script:MembersChanged = $false
  $script:LastMemberSave = $Clock.ElapsedMilliseconds
}

function Add-Message([string]$kind, [string]$text, [string]$uid, [string]$name, [string]$seat, [string]$img, [string]$cid) {
  $id = (Get-LastId) + 1
  $ts = NowMs
  $json = '{"id":' + $id + ',"ts":' + $ts + ',"kind":' + (JStr $kind) + ',"uid":' + (JStr $uid) + ',"name":' + (JStr $name) + ',"seat":' + (JStr $seat) + ',"text":' + (JStr $text) + ',"img":' + (JStr $img) + ',"cid":' + (JStr $cid) + '}'
  $MsgIds.Add($id); $MsgJson.Add($json)
  if ($cid) { $ByCid[$cid] = $id }
  try { [IO.File]::AppendAllText($MsgFile, $json + "`n", $Utf8) } catch { Say ('! 대화를 파일에 저장하지 못했어요: ' + $_.Exception.Message) 'Yellow' }
  $script:Rev++
  $script:DirtyAt = -1L
  $stamp = (Get-Date).ToString('HH:mm')
  if ($kind -eq 'sys') { Say ("[{0}] · {1}" -f $stamp, $text) 'DarkCyan' }
  else {
    $body = $text.Replace("`n", ' ')
    if ($img) { $body = '[사진] ' + $body }
    if ($body.Length -gt 70) { $body = $body.Substring(0, 70) + '…' }
    $who = $name; if ($seat) { $who = "$name($seat)" }
    Say ("[{0}] {1}: {2}" -f $stamp, $who, $body) 'Gray'
  }
  return $id
}

function Set-Dirty { if ($script:DirtyAt -lt 0) { $script:DirtyAt = $Clock.ElapsedMilliseconds } }

function Update-Member([string]$uid, [string]$name, [string]$seat, [long]$read) {
  if (-not $uid -or -not $name) { return }
  $t = NowMs
  $last = Get-LastId
  if ($read -gt $last) { $read = $last }
  if ($read -lt 0) { $read = 0 }
  if (-not $Members.ContainsKey($uid)) {
    $Members[$uid] = [pscustomobject]@{ name = $name; seat = $seat; seen = $t; read = $read }
    $sfx = ''; if ($seat) { $sfx = " ($seat)" }
    [void](Add-Message 'sys' ('{0}님이 들어왔어요{1}' -f $name, $sfx) '' '' '' '' '')
    Save-Members
    return
  }
  $m = $Members[$uid]
  if ($m.name -cne $name) {
    $old = $m.name; $m.name = $name
    [void](Add-Message 'sys' ('이름 변경: {0} → {1}' -f $old, $name) '' '' '' '' '')
    Save-Members
  }
  if ($m.seat -cne $seat) { $m.seat = $seat; $script:MembersChanged = $true; Set-Dirty }
  if (($t - $m.seen) -gt $OnlineMs) { Set-Dirty }
  $m.seen = $t
  if ($read -gt $m.read) { $m.read = $read; $script:MembersChanged = $true; Set-Dirty }
}

function Get-SnapshotJson([long]$since) {
  $n = $MsgIds.Count
  $start = 0
  if ($n -gt 0) {
    $idx = $MsgIds.BinarySearch($since)
    if ($idx -ge 0) { $start = $idx + 1 } else { $start = -bnot $idx }
  }
  $gap = $false
  if (($n - $start) -gt $RespCap) { $start = $n - $RespCap; $gap = ($since -gt 0) }
  $sb = New-Object System.Text.StringBuilder
  [void]$sb.Append('{"sid":' + (JStr $script:Sid) + ',"rev":' + $script:Rev + ',"now":' + (NowMs) + ',"last":' + (Get-LastId) + ',"gap":' + (JBool $gap) + ',"msgs":[')
  for ($i = $start; $i -lt $n; $i++) {
    if ($i -gt $start) { [void]$sb.Append(',') }
    [void]$sb.Append($MsgJson[$i])
  }
  [void]$sb.Append('],"members":[')
  $first = $true
  foreach ($kv in $Members.GetEnumerator()) {
    if (-not $first) { [void]$sb.Append(',') }
    $first = $false
    $m = $kv.Value
    [void]$sb.Append('{"uid":' + (JStr $kv.Key) + ',"name":' + (JStr $m.name) + ',"seat":' + (JStr $m.seat) + ',"seen":' + $m.seen + ',"read":' + $m.read + '}')
  }
  [void]$sb.Append(']}')
  return $sb.ToString()
}

# ---------------- 네트워크 정보 ----------------
$script:IpCache = @()
$script:IpCacheAt = -100000L
function Get-LocalIPv4 {
  if (($Clock.ElapsedMilliseconds - $script:IpCacheAt) -lt 8000) { return $script:IpCache }
  $list = New-Object 'System.Collections.Generic.List[string]'
  try {
    foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
      if ($nic.OperationalStatus -ne 'Up') { continue }
      foreach ($ua in $nic.GetIPProperties().UnicastAddresses) {
        $a = $ua.Address
        if ($a.AddressFamily -ne 'InterNetwork') { continue }
        $s = $a.ToString()
        if ($s.StartsWith('127.') -or $s.StartsWith('169.254.')) { continue }
        if (-not $list.Contains($s)) { $list.Add($s) }
      }
    }
  } catch { }
  $script:IpCache = @($list | Sort-Object { Rank-Ip $_ })
  $script:IpCacheAt = $Clock.ElapsedMilliseconds
  return $script:IpCache
}
function Rank-Ip([string]$ip) { if ($script:HotspotIp -and $ip -eq $script:HotspotIp) { return 0 }; if ($ip -like '192.168.137.*') { return 1 }; return 5 }

# ---------------- HTTP ----------------
function Send-Bytes($ctx, [int]$code, [string]$ctype, [byte[]]$bytes, [string]$cache) {
  $res = $ctx.Response
  try {
    $res.StatusCode = $code
    $res.ContentType = $ctype
    $res.AddHeader('Cache-Control', $cache)
    $res.AddHeader('X-Content-Type-Options', 'nosniff')
    $res.AddHeader('Referrer-Policy', 'no-referrer')
    if ($ctype.StartsWith('text/html')) { $res.AddHeader('Content-Security-Policy', $Csp) }
    $res.ContentLength64 = $bytes.Length
    if ($ctx.Request.HttpMethod -ne 'HEAD' -and $bytes.Length -gt 0) { $res.OutputStream.Write($bytes, 0, $bytes.Length) }
  } catch { } finally { try { $res.Close() } catch { } }
}
function Send-Json($ctx, [int]$code, [string]$json) { Send-Bytes $ctx $code 'application/json; charset=utf-8' ($Utf8.GetBytes($json)) 'no-store' }
function Send-Error($ctx, [int]$code, [string]$msg) { Send-Json $ctx $code ('{"ok":false,"error":' + (JStr $msg) + '}') }

function Read-Body($req, [long]$max) {
  if ($req.ContentLength64 -gt $max) { return $null }
  $ms = New-Object System.IO.MemoryStream
  $buf = New-Object byte[] 65536
  $total = 0L
  while ($true) {
    $n = $req.InputStream.Read($buf, 0, $buf.Length)
    if ($n -le 0) { break }
    $total += $n
    if ($total -gt $max) { return $null }
    $ms.Write($buf, 0, $n)
  }
  return , $ms.ToArray()
}

function Parse-Query([string]$raw) {
  $q = @{}
  foreach ($pair in $raw.Split('&')) {
    if (-not $pair) { continue }
    $i = $pair.IndexOf('=')
    if ($i -lt 0) { $k = $pair; $v = '' } else { $k = $pair.Substring(0, $i); $v = $pair.Substring($i + 1) }
    try { $q[[Uri]::UnescapeDataString($k.Replace('+', ' '))] = [Uri]::UnescapeDataString($v.Replace('+', ' ')) } catch { }
  }
  return $q
}

function Respond-Poll($ctx, [long]$since, [string]$uid) {
  if ($uid -and $Members.ContainsKey($uid)) { $Members[$uid].seen = NowMs }
  Send-Json $ctx 200 (Get-SnapshotJson $since)
}

function Get-InfoJson {
  $urls = @(Get-LocalIPv4 | ForEach-Object { JStr ("http://{0}:{1}" -f $_, $script:ActivePort) }) -join ','
  $wifi = 'null'
  if ($script:WifiSsid) { $wifi = '{"ssid":' + (JStr $script:WifiSsid) + ',"pass":' + (JStr $script:WifiPass) + '}' }
  return '{"ok":true,"app":"skytalk","ver":' + (JStr $Version) + ',"host":"windows","sid":' + (JStr $script:Sid) + ',"port":' + $script:ActivePort + ',"urls":[' + $urls + '],"wifi":' + $wifi + '}'
}

function Invoke-Request($ctx) {
  $req = $ctx.Request
  $raw = $req.RawUrl
  $qi = $raw.IndexOf('?')
  if ($qi -ge 0) { $path = $raw.Substring(0, $qi); $query = $raw.Substring($qi + 1) } else { $path = $raw; $query = '' }
  $method = $req.HttpMethod

  if ($method -eq 'GET' -or $method -eq 'HEAD') {
    if ($path -eq '/' -or $path -eq '/index.html') { Send-Bytes $ctx 200 'text/html; charset=utf-8' $HtmlBytes 'no-cache'; return }
    if ($path -eq '/api/poll') {
      $q = Parse-Query $query
      $uid = [string]$q['uid']; if ($uid -cnotmatch $IdRe) { $uid = '' }
      $since = To-Long ([string]$q['since']) 0; if ($since -lt 0) { $since = 0 }
      $rev = To-Long ([string]$q['rev']) -1
      Update-Member $uid (Clean-Name ([string]$q['name'])) (Clean-Seat ([string]$q['seat'])) (To-Long ([string]$q['read']) 0)
      if ([string]$q['wait'] -ne '0' -and $rev -eq $script:Rev) {
        $Waiters.Add([pscustomobject]@{ Ctx = $ctx; Since = $since; Rev = $rev; Uid = $uid; Start = $Clock.ElapsedMilliseconds })
        return
      }
      Respond-Poll $ctx $since $uid
      return
    }
    if ($path -eq '/api/info') { Send-Json $ctx 200 (Get-InfoJson); return }
    if ($path.StartsWith('/img/')) {
      $name = $path.Substring(5)
      $fp = Join-Path $ImgDir $name
      if ($name -cmatch $ImgRe -and (Test-Path -LiteralPath $fp)) {
        $ct = 'image/png'; if ($name.EndsWith('.jpg')) { $ct = 'image/jpeg' }
        Send-Bytes $ctx 200 $ct ([IO.File]::ReadAllBytes($fp)) 'public, max-age=31536000, immutable'
        return
      }
      Send-Error $ctx 404 '사진을 찾을 수 없어요'; return
    }
    if ($path -eq '/favicon.ico') { Send-Bytes $ctx 204 'image/x-icon' (New-Object byte[] 0) 'max-age=86400'; return }
    Send-Error $ctx 404 'not found'; return
  }

  if ($method -eq 'POST') {
    if ($path -eq '/api/send') {
      $body = Read-Body $req 65536
      if ($null -eq $body) { Send-Error $ctx 413 '메시지가 너무 커요'; return }
      try { $d = $Utf8.GetString($body) | ConvertFrom-Json } catch { $d = $null }
      if ($null -eq $d) { Send-Error $ctx 400 '잘못된 요청이에요'; return }
      $uid = [string]$d.uid; $cid = [string]$d.cid
      $name = Clean-Name ([string]$d.name); $seat = Clean-Seat ([string]$d.seat)
      $text = Clean-Text ([string]$d.text); $img = [string]$d.img
      if ($uid -cnotmatch $IdRe -or $cid -cnotmatch $IdRe) { Send-Error $ctx 400 '잘못된 요청이에요'; return }
      if (-not $name) { Send-Error $ctx 400 '이름을 먼저 정해 주세요'; return }
      if ($text.Length -gt $MaxText) { Send-Error $ctx 400 '메시지가 너무 길어요 (최대 2000자)'; return }
      if ($img -and ($img -cnotmatch $ImgRe -or -not (Test-Path -LiteralPath (Join-Path $ImgDir $img)))) { Send-Error $ctx 400 '사진을 찾을 수 없어요. 다시 보내 주세요'; return }
      if (-not $text -and -not $img) { Send-Error $ctx 400 '빈 메시지예요'; return }
      if ($ByCid.ContainsKey($cid)) { Send-Json $ctx 200 ('{"ok":true,"id":' + $ByCid[$cid] + '}'); return }
      Update-Member $uid $name $seat 0
      $id = Add-Message 'msg' $text $uid $name $seat $img $cid
      Send-Json $ctx 200 ('{"ok":true,"id":' + $id + '}')
      return
    }
    if ($path -eq '/api/upload') {
      $body = Read-Body $req $MaxUpload
      if ($null -eq $body) { Send-Error $ctx 413 '사진이 너무 커요'; return }
      $ext = ''
      if ($body.Length -ge 3 -and $body[0] -eq 0xFF -and $body[1] -eq 0xD8 -and $body[2] -eq 0xFF) { $ext = 'jpg' }
      elseif ($body.Length -ge 8 -and $body[0] -eq 0x89 -and $body[1] -eq 0x50 -and $body[2] -eq 0x4E -and $body[3] -eq 0x47) { $ext = 'png' }
      if (-not $ext) { Send-Error $ctx 400 'JPEG·PNG 사진만 보낼 수 있어요'; return }
      $fname = [Guid]::NewGuid().ToString('N').Substring(0, 20) + '.' + $ext
      try { [IO.File]::WriteAllBytes((Join-Path $ImgDir $fname), $body) } catch { Send-Error $ctx 500 '사진을 저장하지 못했어요'; return }
      Send-Json $ctx 200 ('{"ok":true,"id":' + (JStr $fname) + '}')
      return
    }
    Send-Error $ctx 404 'not found'; return
  }
  Send-Error $ctx 405 'method not allowed'
}

function Invoke-Tick {
  $t = $Clock.ElapsedMilliseconds
  if ($script:DirtyAt -ge 0 -and ($t - $script:DirtyAt) -ge 1000) { $script:DirtyAt = -1L; $script:Rev++ }
  for ($i = $Waiters.Count - 1; $i -ge 0; $i--) {
    $w = $Waiters[$i]
    if ($w.Rev -ne $script:Rev -or ($t - $w.Start) -ge $PollWaitMs) {
      $Waiters.RemoveAt($i)
      Respond-Poll $w.Ctx $w.Since $w.Uid
    }
  }
  if ($script:MembersChanged -and ($t - $script:LastMemberSave) -gt 20000) { Save-Members }
}

# ---------------- Windows 기능: 절전 방지, 빠른 편집 끄기 ----------------
try {
  Add-Type -Namespace SkyTalk -Name Native -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);
[DllImport("kernel32.dll")] public static extern IntPtr GetStdHandle(int nStdHandle);
[DllImport("kernel32.dll")] public static extern bool GetConsoleMode(IntPtr hConsoleHandle, out uint lpMode);
[DllImport("kernel32.dll")] public static extern bool SetConsoleMode(IntPtr hConsoleHandle, uint dwMode);
'@ -ErrorAction Stop
  $script:NativeOk = $true
} catch { $script:NativeOk = $false }
function Set-KeepAwake([bool]$on) {
  if (-not $script:NativeOk) { return }
  if ($on) { [void][SkyTalk.Native]::SetThreadExecutionState([uint32]2147483649) }  # ES_CONTINUOUS | ES_SYSTEM_REQUIRED
  else { [void][SkyTalk.Native]::SetThreadExecutionState([uint32]2147483648) }       # ES_CONTINUOUS
}
function Disable-QuickEdit {
  if (-not $script:NativeOk) { return }
  try {
    $h = [SkyTalk.Native]::GetStdHandle(-10)
    $mode = [uint32]0
    if ([SkyTalk.Native]::GetConsoleMode($h, [ref]$mode)) { [void][SkyTalk.Native]::SetConsoleMode($h, [uint32](($mode -band 0xFFFFFFBF) -bor 0x80)) }
  } catch { }
}

# ---------------- 핫스팟 (인터넷 없이 노트북이 Wi-Fi를 만듦) ----------------
$script:Publisher = $null
$script:Tethering = $null
$script:WifiSsid = ''
$script:WifiPass = ''
$script:HotspotIp = ''
$script:HotspotKind = ''

function Wait-Ms([int]$ms) { [void](New-Object System.Threading.ManualResetEvent($false)).WaitOne($ms) }

function Get-IPv4Set {
  $set = @{}
  foreach ($nic in [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
    if ($nic.OperationalStatus -ne 'Up') { continue }
    foreach ($ua in $nic.GetIPProperties().UnicastAddresses) {
      if ($ua.Address.AddressFamily -eq 'InterNetwork') { $set[$ua.Address.ToString()] = $nic.Description }
    }
  }
  return $set
}

function Start-WifiDirectAp([string]$ssid, [string]$pass) {
  try {
    $null = [Windows.Devices.WiFiDirect.WiFiDirectAdvertisementPublisher, Windows.Devices.WiFiDirect, ContentType = WindowsRuntime]
    $null = [Windows.Security.Credentials.PasswordCredential, Windows.Security.Credentials, ContentType = WindowsRuntime]
    $before = Get-IPv4Set
    $pub = New-Object Windows.Devices.WiFiDirect.WiFiDirectAdvertisementPublisher
    $adv = $pub.Advertisement
    $adv.IsAutonomousGroupOwnerEnabled = $true
    $adv.LegacySettings.IsEnabled = $true
    $adv.LegacySettings.Ssid = $ssid
    $cred = New-Object Windows.Security.Credentials.PasswordCredential
    $cred.Password = $pass
    $adv.LegacySettings.Passphrase = $cred
    $pub.Start()
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ([string]$pub.Status -eq 'Created' -and $sw.ElapsedMilliseconds -lt 10000) { Wait-Ms 200 }
    if ([string]$pub.Status -ne 'Started') {
      $st = [string]$pub.Status
      try { $pub.Stop() } catch { }
      return @{ ok = $false; error = "Wi-Fi Direct 상태: $st" }
    }
    $script:Publisher = $pub
    # 새로 생긴 Wi-Fi Direct 어댑터의 주소(보통 192.168.137.1)를 찾습니다.
    $ip = ''
    $sw.Restart()
    while (-not $ip -and $sw.ElapsedMilliseconds -lt 15000) {
      Wait-Ms 500
      $now = Get-IPv4Set
      foreach ($k in $now.Keys) {
        if (-not $before.ContainsKey($k) -and -not $k.StartsWith('169.254.')) { $ip = $k; break }
      }
      if (-not $ip -and $now.ContainsKey('192.168.137.1')) { $ip = '192.168.137.1' }
    }
    return @{ ok = $true; ip = $ip }
  } catch {
    return @{ ok = $false; error = $_.Exception.Message }
  }
}

function Get-WinRtAwaiters {
  if ($script:AsTaskOp) { return }
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  $methods = [System.WindowsRuntimeSystemExtensions].GetMethods()
  $script:AsTaskOp = $methods | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
  $script:AsTaskAction = $methods | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' } | Select-Object -First 1
}
function Await-Op($op, [Type]$type) {
  $task = $script:AsTaskOp.MakeGenericMethod($type).Invoke($null, @($op))
  [void]$task.Wait(30000)
  return $task.Result
}
function Await-Action($action) {
  $task = $script:AsTaskAction.Invoke($null, @($action))
  [void]$task.Wait(30000)
}

function Get-TetheringManager {
  $null = [Windows.Networking.Connectivity.NetworkInformation, Windows.Networking.Connectivity, ContentType = WindowsRuntime]
  $null = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager, Windows.Networking.NetworkOperators, ContentType = WindowsRuntime]
  $profiles = @()
  $p = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
  if ($p) { $profiles += $p }
  foreach ($cp in [Windows.Networking.Connectivity.NetworkInformation]::GetConnectionProfiles()) {
    try { if ([string]$cp.GetNetworkConnectivityLevel() -ne 'None') { $profiles += $cp } } catch { }
  }
  foreach ($cp in $profiles) {
    try { return [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($cp) } catch { }
  }
  return $null
}

function Start-MobileHotspot([string]$ssid, [string]$pass) {
  try {
    Get-WinRtAwaiters
    $tm = Get-TetheringManager
    if (-not $tm) { return @{ ok = $false; error = '나눠 쓸 네트워크 연결이 없어요' } }
    $before = Get-IPv4Set
    if ([string]$tm.TetheringOperationalState -ne 'On') {
      $cfg = $tm.GetCurrentAccessPointConfiguration()
      if ($cfg.Ssid -cne $ssid -or $cfg.Passphrase -cne $pass) {
        # 끝날 때 원래 모바일 핫스팟 이름·비밀번호로 되돌리기 위해 기억해 둡니다.
        $script:OldApSsid = $cfg.Ssid; $script:OldApPass = $cfg.Passphrase
        $cfg.Ssid = $ssid; $cfg.Passphrase = $pass
        Await-Action ($tm.ConfigureAccessPointAsync($cfg))
      }
      $r = Await-Op ($tm.StartTetheringAsync()) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])
      if ([string]$r.Status -ne 'Success') { return @{ ok = $false; error = ('모바일 핫스팟: ' + [string]$r.Status + ' ' + [string]$r.AdditionalErrorMessage) } }
      $script:Tethering = $tm
    }
    $cfg = $tm.GetCurrentAccessPointConfiguration()
    $ip = ''
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while (-not $ip -and $sw.ElapsedMilliseconds -lt 10000) {
      $now = Get-IPv4Set
      if ($now.ContainsKey('192.168.137.1')) { $ip = '192.168.137.1'; break }
      foreach ($k in $now.Keys) { if (-not $before.ContainsKey($k) -and -not $k.StartsWith('169.254.')) { $ip = $k; break } }
      if (-not $ip) { Wait-Ms 500 }
    }
    return @{ ok = $true; ip = $ip; ssid = $cfg.Ssid; pass = $cfg.Passphrase }
  } catch {
    return @{ ok = $false; error = $_.Exception.Message }
  }
}

function Stop-Hotspots {
  if ($script:Publisher) { try { $script:Publisher.Stop() } catch { }; $script:Publisher = $null }
  if ($script:Tethering) {
    try { Get-WinRtAwaiters; [void](Await-Op ($script:Tethering.StopTetheringAsync()) ([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult])) } catch { }
    if ($script:OldApSsid) {
      try {
        $cfg = $script:Tethering.GetCurrentAccessPointConfiguration()
        $cfg.Ssid = $script:OldApSsid; $cfg.Passphrase = $script:OldApPass
        Await-Action ($script:Tethering.ConfigureAccessPointAsync($cfg))
      } catch { }
    }
    $script:Tethering = $null
  }
}

# 지금 연결돼 있는 Wi-Fi 이름 (없으면 빈 문자열)
function Get-WlanProfileName {
  try {
    $null = [Windows.Networking.Connectivity.NetworkInformation, Windows.Networking.Connectivity, ContentType = WindowsRuntime]
    foreach ($cp in [Windows.Networking.Connectivity.NetworkInformation]::GetConnectionProfiles()) {
      if ($cp.IsWlanConnectionProfile -and [string]$cp.GetNetworkConnectivityLevel() -ne 'None') { return [string]$cp.ProfileName }
    }
  } catch { }
  return ''
}

# 예/아니요 묻기. 정해진 시간 안에 답이 없으면 기본값으로 진행합니다.
function Ask-YesNo([string]$question, [int]$seconds, [bool]$default) {
  $hint = '아니요'; if ($default) { $hint = '예' }
  Write-Host ("{0} [Y/N] ({1}초 뒤 자동으로 {2}) " -f $question, $seconds, $hint) -ForegroundColor Yellow -NoNewline
  $answer = $default
  try {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $seconds) {
      if ([Console]::KeyAvailable) {
        $k = [Console]::ReadKey($true)
        if ($k.Key -eq 'Y') { $answer = $true; break }
        if ($k.Key -eq 'N') { $answer = $false; break }
        if ($k.Key -eq 'Enter') { break }
      }
      Start-Sleep -Milliseconds 100
    }
  } catch { }
  if ($answer) { Write-Host '예' } else { Write-Host '아니요' }
  return $answer
}

# ---------------- 시작 ----------------
Disable-QuickEdit
$IsAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if ($HtmlB64) { $HtmlBytes = [Convert]::FromBase64String($HtmlB64) }
else {
  $cand = @()
  if ($PSScriptRoot) { $cand += (Join-Path $PSScriptRoot 'client.html') }
  $found = $cand | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
  if ($found) { $HtmlBytes = [IO.File]::ReadAllBytes($found) } else { $HtmlBytes = $Utf8.GetBytes('<h1>client.html not found</h1>') }
}

Say ''
Say '  >> 기내톡(SkyTalk) 서버를 준비하고 있어요…' 'Cyan'
Load-Room

# 1) 핫스팟
if (-not $NoHotspot) {
  if ($Pass.Length -lt 8) { Say '  ! Wi-Fi 비밀번호는 8자 이상이어야 해요. 기본값(skytalk1234)으로 바꿉니다.' 'Yellow'; $Pass = 'skytalk1234' }
  $wlan = Get-WlanProfileName
  $done = $false
  if ($wlan) {
    # 다른 Wi-Fi(호텔·기내 Wi-Fi 등)에 연결된 채로 Wi-Fi Direct 핫스팟을 켜면, 노트북에 따라 팀원 폰이
    # 접속하지 못하는 문제가 있어요. 이때는 그 연결을 나눠 쓰는 Windows 모바일 핫스팟이 안정적입니다.
    Say ("  - 이 노트북은 지금 Wi-Fi '{0}'에 연결돼 있어요 → 모바일 핫스팟으로 켜는 중…" -f $wlan) 'Gray'
    $hs = Start-MobileHotspot $Ssid $Pass
    if ($hs.ok) {
      $script:HotspotKind = '모바일 핫스팟'; $script:WifiSsid = $hs.ssid; $script:WifiPass = $hs.pass; $script:HotspotIp = $hs.ip; $done = $true
    } else {
      Say ('    (모바일 핫스팟 실패: ' + $hs.error + ')') 'DarkYellow'
      Say ("  ! Wi-Fi '{0}'에 연결된 채로는 팀원 폰이 접속하지 못할 수 있어요." -f $wlan) 'Yellow'
      if (Ask-YesNo ("    Wi-Fi '{0}' 연결을 끊고 계속할까요?" -f $wlan) 12 $true) {
        & netsh wlan disconnect 2>$null | Out-Null
        Wait-Ms 2500
      }
    }
  }
  if (-not $done) {
    Say '  - 노트북 Wi-Fi로 핫스팟을 켜는 중… (최대 20초)' 'Gray'
    $hs = Start-WifiDirectAp $Ssid $Pass
    if ($hs.ok) {
      $script:HotspotKind = 'Wi-Fi Direct'; $script:WifiSsid = $Ssid; $script:WifiPass = $Pass; $script:HotspotIp = $hs.ip
    } elseif (-not $wlan) {
      Say ('    (Wi-Fi Direct 방식 실패: ' + $hs.error + ') → Windows 모바일 핫스팟으로 다시 시도') 'DarkYellow'
      $hs2 = Start-MobileHotspot $Ssid $Pass
      if ($hs2.ok) {
        $script:HotspotKind = '모바일 핫스팟'; $script:WifiSsid = $hs2.ssid; $script:WifiPass = $hs2.pass; $script:HotspotIp = $hs2.ip
      } else {
        Say ('    (모바일 핫스팟도 실패: ' + $hs2.error + ')') 'DarkYellow'
      }
    } else {
      Say ('    (Wi-Fi Direct 방식 실패: ' + $hs.error + ')') 'DarkYellow'
    }
  }
}

# 2) 방화벽
$script:FirewallAdded = $false
if (-not $NoFirewall -and $IsAdmin) {
  & netsh advfirewall firewall delete rule name="SkyTalk" 2>$null | Out-Null
  & netsh advfirewall firewall add rule name="SkyTalk" dir=in action=allow protocol=TCP localport="$Port-$($Port + 9)" profile=any 2>$null | Out-Null
  $script:FirewallAdded = ($LASTEXITCODE -eq 0)
}

# 3) 서버
$listener = $null
$script:ActivePort = 0
$script:LocalOnly = $false
for ($p = $Port; $p -lt $Port + 10 -and -not $script:ActivePort; $p++) {
  foreach ($prefix in @("http://+:$p/", "http://localhost:$p/")) {
    $l = New-Object System.Net.HttpListener
    $l.IgnoreWriteExceptions = $true
    $l.Prefixes.Add($prefix)
    try {
      $l.Start()
      $listener = $l; $script:ActivePort = $p; $script:LocalOnly = $prefix.Contains('localhost')
      break
    } catch {
      try { $l.Close() } catch { }
      $code = 0; if ($_.Exception.InnerException) { $code = $_.Exception.InnerException.ErrorCode } elseif ($_.Exception.ErrorCode) { $code = $_.Exception.ErrorCode }
      if ($code -ne 5) { break }   # 5 = 권한 없음 → localhost로 다시 시도, 그 밖의 오류 → 다음 포트
    }
  }
}
if (-not $listener) {
  Say "  ! $Port~$($Port + 9) 포트를 열 수 없어요. 다른 프로그램을 닫고 다시 실행해 주세요." 'Red'
  Stop-Hotspots
  exit 1
}

Set-KeepAwake $true
$script:IpCacheAt = -100000L
$ips = @(Get-LocalIPv4)
$mainIp = ''
if ($script:HotspotIp) { $mainIp = $script:HotspotIp } elseif ($ips.Count -gt 0) { $mainIp = $ips[0] }
$line = '=' * 60
Say ''
Say $line 'DarkGray'
Say "  >> 기내톡 서버가 켜졌어요   v$Version" 'Green'
Say $line 'DarkGray'
if ($NoHotspot) {
  Say '  핫스팟 없이 시작했어요. 노트북과 휴대폰을 같은 Wi-Fi에 연결한 뒤 아래 주소를 여세요:' 'Cyan'
  foreach ($ip in $ips) { Say ("     http://{0}:{1}" -f $ip, $script:ActivePort) 'White' }
} elseif ($script:WifiSsid) {
  Say '  팀원에게 이렇게 알려 주세요' 'Cyan'
  Say ('   ① Wi-Fi 이름 : ' + $script:WifiSsid) 'White'
  Say ('      비밀번호  : ' + $script:WifiPass) 'White'
  if ($mainIp) { Say ("   ② 주소창 입력: http://{0}:{1}" -f $mainIp, $script:ActivePort) 'White' }
  else { Say ("   ② 주소창 입력: http://(Wi-Fi 설정의 '라우터' 주소):{0}" -f $script:ActivePort) 'White' }
  Say ("     (핫스팟 방식: {0})" -f $script:HotspotKind) 'DarkGray'
} else {
  Say '  ! 핫스팟을 켜지 못했어요. 아래 중 하나로 같은 Wi-Fi를 만들어 주세요:' 'Yellow'
  Say '     · 기내 Wi-Fi에 노트북을 연결(결제 불필요)한 뒤 이 창을 닫고 다시 실행' 'Yellow'
  Say '     · 설정 > 네트워크 및 인터넷 > 모바일 핫스팟 켜기' 'Yellow'
  Say '     · 갤럭시 등 휴대폰 핫스팟에 노트북과 팀원이 모두 연결' 'Yellow'
  if ($ips.Count -gt 0) { Say '  지금 이 노트북의 주소:' 'Cyan'; foreach ($ip in $ips) { Say ("     http://{0}:{1}" -f $ip, $script:ActivePort) 'White' } }
}
if ($script:LocalOnly) { Say '  ! 관리자 권한이 없어 이 노트북에서만 접속돼요. SkyTalk-Windows.bat으로 실행해 주세요.' 'Yellow' }
elseif (-not $script:FirewallAdded -and -not $NoFirewall) { Say '  ! 방화벽 규칙을 추가하지 못했어요. 휴대폰에서 안 열리면 방화벽 알림에서 [허용]을 눌러 주세요.' 'Yellow' }
Say ("  이 노트북에서 보기: http://localhost:{0}" -f $script:ActivePort) 'Gray'
Say ('  대화 저장 위치: ' + $DataDir) 'DarkGray'
Say '  ※ 노트북 덮개를 닫으면 절전으로 꺼질 수 있어요. 화면만 어둡게 하고 열어 두세요.' 'DarkGray'
Say '  ※ 끄려면 이 창에서 Ctrl+C (또는 창 닫기)' 'DarkGray'
Say $line 'DarkGray'
Say ''

if (-not $NoBrowser) { try { Start-Process -FilePath 'explorer.exe' -ArgumentList ("http://localhost:{0}/" -f $script:ActivePort) } catch { } }

# 핫스팟 주소가 늦게 잡히거나 Wi-Fi가 새로 연결되면 새 주소를 알려 줍니다.
$script:KnownIps = @{}
foreach ($ip in $ips) { $script:KnownIps[$ip] = $true }
$script:NextIpCheck = $Clock.ElapsedMilliseconds + 5000
function Watch-NewIps {
  if ($Clock.ElapsedMilliseconds -lt $script:NextIpCheck) { return }
  $script:NextIpCheck = $Clock.ElapsedMilliseconds + 10000
  $script:IpCacheAt = -100000L
  foreach ($ip in @(Get-LocalIPv4)) {
    if (-not $script:KnownIps.ContainsKey($ip)) {
      $script:KnownIps[$ip] = $true
      Say ("  + 새 접속 주소가 생겼어요: http://{0}:{1}" -f $ip, $script:ActivePort) 'Green'
    }
  }
}

try {
  $async = $listener.BeginGetContext($null, $null)
  while ($listener.IsListening) {
    if ($async.AsyncWaitHandle.WaitOne(100)) {
      $ctx = $null
      try { $ctx = $listener.EndGetContext($async) } catch { }
      if ($listener.IsListening) { $async = $listener.BeginGetContext($null, $null) }
      if ($ctx) {
        try { Invoke-Request $ctx }
        catch {
          Say ('  ! 요청 처리 오류: ' + $_.Exception.Message) 'DarkYellow'
          try { Send-Error $ctx 500 'server error' } catch { }
        }
      }
    }
    Invoke-Tick
    Watch-NewIps
  }
} finally {
  foreach ($w in $Waiters) { try { $w.Ctx.Response.Abort() } catch { } }
  try { $listener.Stop(); $listener.Close() } catch { }
  Stop-Hotspots
  if ($script:FirewallAdded) { & netsh advfirewall firewall delete rule name="SkyTalk" 2>$null | Out-Null }
  Set-KeepAwake $false
  Save-Members
  Say '기내톡 서버를 껐어요. 대화는 저장돼 있어요.' 'Cyan'
}
