# ============================================================
#  IMVU-TRACK v1.0  -  bot de terminal estilo BotPower/Catvu
#  Engines:
#    A) catvu.live/api/private-room/find   -> sala privada do alvo
#    B) catvu.live/api/private-room/watch  -> roster AO VIVO (SSE)
#    C) api.imvu.com (presence + user)     -> online/offline direto
#  Uso:
#    .\IMVU-TRACK.bat                      -> menu interativo
#    .\IMVU-TRACK.bat -Nick <nick>         -> checagem unica
#    .\IMVU-TRACK.bat -Vigia <nick> [seg]  -> monitora entrada/saida
#    .\IMVU-TRACK.bat -Watch <roomId> [seg]-> assiste a sala ao vivo
#    .\IMVU-TRACK.bat -Imvu <nick> [seg]   -> so IMVU direto
#    .\IMVU-TRACK.bat -Cid <nick>          -> so resolver nick->cid
#    .\IMVU-TRACK.bat -Raw -Nick <nick>    -> JSON cru do find
# ============================================================

param(
  [string]$Nick = "",
  [string]$Segs = "",
  [string]$Vigia = "",
  [string]$Watch = "",
  [string]$Imvu = "",
  [string]$Caca = "",
  [string]$Cid = "",
  [switch]$Raw
)

$ErrorActionPreference = "SilentlyContinue"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try { $OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) IMVU-Track/1.0"
$BASE = "https://catvu.live"
$ImvuApi = "https://api.imvu.com"

function Banner {
  Write-Host ""
  Write-Host "  IMVU-TRACK  " -NoNewline -ForegroundColor DarkGray
  Write-Host "[ catvu endpoint bot ]" -ForegroundColor Cyan
  Write-Host "  find -> salas privadas | watch -> roster ao vivo | imvu -> presence" -ForegroundColor DarkGray
  Write-Host ""
}

function Beep-Alert {
  try { [console]::Beep(900,220); Start-Sleep -Milliseconds 60; [console]::Beep(1250,220) } catch { Write-Host "`a" }
}

function Invoke-CatvuFind([string]$nick, [switch]$asJson) {
  $url = "$BASE/api/private-room/find?username=" + [uri]::EscapeDataString($nick)
  for ($i = 0; $i -lt 5; $i++) {
    try {
      $r = Invoke-RestMethod -Uri $url -TimeoutSec 20 -Headers @{ "User-Agent" = $UA; "Accept" = "application/json" }
      if ($asJson) { return ($r | ConvertTo-Json -Depth 10) }
      return $r
    } catch {
      $resp = $_.Exception.Response
      $code = 0
      if ($resp) { $code = [int]$resp.StatusCode }
      if ($code -eq 429 -and $i -lt 4) {
        Write-Host "  [!] rate limit (429) - esperando 20s e tentando de novo ($($i+1)/5)" -ForegroundColor DarkYellow
        Start-Sleep -Seconds 20
      } elseif ($i -lt 4) {
        Start-Sleep -Seconds 2
      } else {
        Write-Host "  [!] falhou: $($_.Exception.Message)" -ForegroundColor Red
        return $null
      }
    }
  }
  return $null
}

function Get-ImvuNode([string]$path) {
  try {
    $r = Invoke-RestMethod -Uri "$ImvuApi/$path" -TimeoutSec 12 -Headers @{ "User-Agent" = $UA }
    foreach ($v in $r.denormalized.PSObject.Properties.Value) { return $v }
    return $null
  } catch { return $null }
}

function Get-ImvuOnline([string]$cid) {
  $n = Get-ImvuNode "presence/presence-$cid"
  if ($n) { return [bool]$n.data.online }
  return $null
}

function Get-ImvuUser([string]$cid) {
  $n = Get-ImvuNode "user/user-$cid"
  if ($n) { return $n.data }
  return $null
}

function Resolve-CidDirect([string]$nick) {
  # fallback sem catvu: redirect do web_av_pic
  try {
    $r = Invoke-WebRequest -Uri ("https://www.imvu.com/catalog/web_av_pic.php?av=" + [uri]::EscapeDataString($nick)) -MaximumRedirection 8 -UseBasicParsing -TimeoutSec 15 -Headers @{ "User-Agent" = $UA }
    $u = $r.BaseResponse.ResponseUri.AbsoluteUri
    if ($u -match "/avatars/(\d+)_") { return $matches[1] }
    return $null
  } catch { return $null }
}

function Show-Find($r) {
  if (-not $r) { return $false }
  $p = $r.profile
  $cid = $r.cid
  if ($p) {
    Write-Host "  ALVO : " -NoNewline; Write-Host "$($p.displayName)  " -NoNewline -ForegroundColor White
    Write-Host "[$($p.username)]  " -NoNewline -ForegroundColor DarkGray
    Write-Host "CID $cid" -ForegroundColor DarkGray
  } else {
    Write-Host "  ALVO : $($r.query)  CID $cid" -ForegroundColor White
  }
  if ($r.online -eq $true) { Write-Host "  ESTADO: ONLINE" -ForegroundColor Green }
  else { Write-Host "  ESTADO: offline" -ForegroundColor DarkGray }

  $rooms = @($r.rooms)
  if ($rooms.Count -gt 0) {
    if ($r.hasOccupiedPrivateRoom -eq $true) {
      Write-Host "  SALA PRIVADA: SIM  ($($r.privateRoomCount) ocupada(s))" -ForegroundColor Yellow
    } else {
      Write-Host "  SALA PRIVADA: sim, ocupando $($r.privateRoomCount)" -ForegroundColor DarkYellow
    }
    foreach ($rm in $rooms) {
      $tag = "publica"
      $col = "Gray"
      if ($rm.isPrivate -eq $true) { $tag = "PRIVADA"; $col = "Yellow" }
      Write-Host "    * " -NoNewline -ForegroundColor DarkGray
      Write-Host "$($rm.roomName)" -NoNewline -ForegroundColor White
      Write-Host "  [$($rm.roomId)]  " -NoNewline -ForegroundColor Cyan
      Write-Host "<$tag>" -ForegroundColor $col
    }
  } else {
    Write-Host "  SALA PRIVADA: nao agora" -ForegroundColor DarkGray
    if ($r.hint) { Write-Host "  ($($r.hint))" -ForegroundColor DarkGray }
  }
  return $true
}

function Watch-RoomStream([string]$roomId, [int]$seconds) {
  Write-Host "  assistindo $roomId por $seconds s (Ctrl+C p/ sair)" -ForegroundColor Cyan
  Write-Host ""
  $req = [System.Net.HttpWebRequest]::Create("$BASE/api/private-room/watch/$roomId")
  $req.UserAgent = $UA
  $req.Accept = "text/event-stream"
  $req.Timeout = 30000
  $req.ReadWriteTimeout = 30000
  $deadline = (Get-Date).AddSeconds($seconds)
  $evt = "message"; $buf = @()
  function Dispatch($ev, $lines) {
    if ($lines.Count -eq 0) { return }
    $json = ($lines -join "`n")
    try { $obj = $json | ConvertFrom-Json } catch { return }
    switch ($ev) {
      "status" {
        $m = $obj.data.message
        Write-Host "  [i] $m" -ForegroundColor DarkGray
      }
      "snapshot" {
        $parts = @($obj.data.participants)
        $mods = @($obj.data.moderators)
        Write-Host "  [=] ROSTER: $($parts.Count) dentro" -ForegroundColor Cyan
        foreach ($p in $parts) {
          $nm = $p.displayName; if (-not $nm) { $nm = $p.username }
          Write-Host "      - $nm  " -NoNewline -ForegroundColor White
          Write-Host "[$($p.username) / $($p.cid)]" -ForegroundColor DarkGray
        }
        foreach ($m in $mods) { Write-Host "      (mod) $($m.username)" -ForegroundColor DarkYellow }
      }
      "joined" {
        $d = $obj.data
        $p = $d.person; if (-not $p) { $p = $d }
        $nm = $p.displayName; if (-not $nm) { $nm = $p.username }
        Write-Host "  [+] ENTROU: $nm  " -NoNewline -ForegroundColor Green
        Write-Host "[$($p.username)]" -ForegroundColor DarkGray
      }
      "left" {
        $d = $obj.data
        $p = $d.person; if (-not $p) { $p = $d }
        $nm = $p.displayName; if (-not $nm) { $nm = $p.username }
        Write-Host "  [-] SAIU:   $nm  " -NoNewline -ForegroundColor Red
        Write-Host "[$($p.username)]" -ForegroundColor DarkGray
      }
      "changed" {
        $d = $obj.data
        Write-Host "  [~] mudanca: $($d | ConvertTo-Json -Compress)" -ForegroundColor DarkGray
      }
      "error" {
        Write-Host "  [x] erro: $($obj.data | ConvertTo-Json -Compress)" -ForegroundColor Red
      }
      default {
        $d = $obj.data
        if ($d) { Write-Host "  [.] $ev : $($d | ConvertTo-Json -Compress)" -ForegroundColor DarkGray }
      }
    }
  }
  try {
    $resp = $req.GetResponse()
    $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
    while ((Get-Date) -lt $deadline) {
      $line = $sr.ReadLine()
      if ($null -eq $line) { Start-Sleep -Milliseconds 300; continue }
      if ($line -eq "") { Dispatch $evt $buf; $buf = @(); $evt = "message"; continue }
      if ($line.StartsWith("event:")) { $evt = $line.Substring(6).Trim() }
      elseif ($line.StartsWith("data:")) { $buf += $line.Substring(5).TrimStart() }
    }
    $sr.Close(); $resp.Close()
    Write-Host "  [i] tempo esgotado" -ForegroundColor DarkGray
  } catch {
    Write-Host "  [x] stream caiu: $($_.Exception.Message)" -ForegroundColor Red
  }
}

function Watch-RoomScan([string]$roomId, [string]$tcid, [int]$seconds) {
  $req = [System.Net.HttpWebRequest]::Create("$BASE/api/private-room/watch/$roomId")
  $req.UserAgent = $UA
  $req.Accept = "text/event-stream"
  $deadline = (Get-Date).AddSeconds($seconds)
  $evt = "message"; $buf = @()
  $match = $false
  try {
    $resp = $req.GetResponse()
    $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
    while ((Get-Date) -lt $deadline) {
      $line = $sr.ReadLine()
      if ($null -eq $line) { Start-Sleep -Milliseconds 200; continue }
      if ($line -eq "") {
        if ($buf.Count -gt 0) {
          try { $obj = ($buf -join "`n") | ConvertFrom-Json } catch { $obj = $null }
          if ($obj -and ($evt -eq "snapshot" -or $evt -eq "joined")) {
            $parts = @($obj.data.participants)
            if ($parts.Count -eq 0 -and $obj.data.person) { $parts = @($obj.data.person) }
            foreach ($p in $parts) { if ("$($p.cid)" -eq "$tcid") { $match = $true; $script:ScanRoster = $parts; break } }
          }
        }
        $buf = @(); $evt = "message"
        if ($match) { break }
        continue
      }
      if ($line.StartsWith("event:")) { $evt = $line.Substring(6).Trim() }
      elseif ($line.StartsWith("data:")) { $buf += $line.Substring(5).TrimStart() }
    }
    $sr.Close(); $resp.Close()
  } catch { Write-Host "  [!] $roomId : $($_.Exception.Message)" -ForegroundColor DarkGray }
  return $match
}

function Enum-Rooms([string]$cid, [int]$maxN) {
  $out = @{}
  for ($n = 1; $n -le $maxN; $n++) {
    $rid = "$cid-$n"
    try {
      $r = Invoke-RestMethod -Uri "https://api.imvu.com/room/room-$rid" -TimeoutSec 8 -Headers @{ "User-Agent" = $UA }
      foreach ($p in $r.denormalized.PSObject.Properties) {
        $d = $p.Value.data
        if ($d -and $d.name) {
          $out[$rid] = [pscustomobject]@{ roomId = $rid; roomName = $d.name; privacy = $d.privacy; occupancy = $d.occupancy; capacity = $d.capacity }
        }
      }
    } catch {
      $code = 0
      if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
      if ($code -eq 429) { Start-Sleep -Seconds 20; $n-- }
    }
    if ($n % 15 -eq 0) { Start-Sleep -Milliseconds 600 }
  }
  return $out
}

function Start-Caca([string]$alvo, [string]$extras, [int]$perRoom) {
  $f = Invoke-CatvuFind $alvo
  $tcid = $null
  if ($f) { $tcid = $f.cid }
  if (-not $tcid) { $tcid = Resolve-CidDirect $alvo }
  if (-not $tcid) { Write-Host "  [x] alvo '$alvo' nao resolveu" -ForegroundColor Red; return }
  Write-Host "  ALVO: $alvo  [CID $tcid]" -ForegroundColor Cyan
  $cand = @{}
  function Add-Rooms($rooms) { foreach ($rm in @($rooms)) { if ($rm.roomId) { $cand[$rm.roomId] = $rm } } }
  if ($f) { Add-Rooms $f.rooms }
  try { $s = Invoke-RestMethod -Uri "$BASE/api/search?q=$([uri]::EscapeDataString($alvo))" -TimeoutSec 20 -Headers @{ "User-Agent" = $UA }; Add-Rooms $s.rooms } catch { }
  $owners = @($alvo)
  if ($extras) { $owners += @($extras -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
  $owners = $owners | Select-Object -Unique
  foreach ($o in $owners) {
    $ef = Invoke-CatvuFind $o
    if ($ef) { Add-Rooms $ef.rooms }
    try { $es = Invoke-RestMethod -Uri "$BASE/api/search?q=$([uri]::EscapeDataString($o))" -TimeoutSec 20 -Headers @{ "User-Agent" = $UA }; Add-Rooms $es.rooms } catch { }
    Start-Sleep -Seconds 2
  }
  # ---- enumeracao direta: varre <cid>-1..<cid>-250 no IMVU (acha salas privadas do dono) ----
  foreach ($o in $owners) {
    $ocid = $null
    if ($o -ieq $alvo) { $ocid = $tcid }
    else {
      $of = Invoke-CatvuFind $o
      if ($of) { $ocid = $of.cid }
      if (-not $ocid) { $ocid = Resolve-CidDirect $o }
    }
    if ($ocid) {
      Write-Host "  [enum] varrendo salas do dono $o (cid $ocid)..." -ForegroundColor DarkGray
      $enum = Enum-Rooms $ocid 250
      foreach ($rid in $enum.Keys) {
        $rm = $enum[$rid]
        if (-not $cand.ContainsKey($rid)) {
          if ($rm.privacy -eq "private" -or $rm.occupancy -gt 0) { $cand[$rid] = $rm }
        }
      }
      Write-Host "  [enum] $($enum.Count) sala(s) achadas do dono $o" -ForegroundColor DarkGray
    }
  }
  if ($cand.Count -eq 0) {
    Write-Host "  [x] nenhuma sala conhecida pra esses nicks. passa o roomId pro modo WATCH, ou o nick do dono da sala." -ForegroundColor Yellow
    return
  }
  Write-Host "  varrendo $($cand.Count) sala(s) candidata(s)... ($perRoom s cada)" -ForegroundColor Cyan
  Write-Host ""
  foreach ($rid in $cand.Keys) {
    $rm = $cand[$rid]
    $nm = $rm.roomName; if (-not $nm) { $nm = $rm.name }; if (-not $nm) { $nm = $rid }
    Write-Host "  [scan] $nm  [$rid]" -NoNewline -ForegroundColor DarkGray
    $script:ScanRoster = @()
    $found = Watch-RoomScan $rid $tcid $perRoom
    if ($found) {
      Write-Host ""
      Write-Host "  [!!!] ALVO AQUI: " -NoNewline -ForegroundColor Green; Write-Host "$nm  [$rid]" -ForegroundColor White
      if ($script:ScanRoster.Count -gt 0) {
        Write-Host "  dentro ($($script:ScanRoster.Count)):" -ForegroundColor Cyan
        foreach ($p in $script:ScanRoster) {
          $pn = $p.displayName; if (-not $pn) { $pn = $p.username }
          Write-Host "      - $pn  " -NoNewline -ForegroundColor White
          Write-Host "[$($p.username) / $($p.cid)]" -ForegroundColor DarkGray
        }
      }
      Beep-Alert; Beep-Alert
      return
    }
    Write-Host "  - nao" -ForegroundColor DarkGray
    Start-Sleep -Seconds 2
  }
  Write-Host "  alvo nao achado nas salas conhecidas (sala do dono fora do range 1..250, ou dono diferente)" -ForegroundColor Yellow
}

function Start-Vigia([string]$nick, [int]$interval) {
  Write-Host "  vigiando '$nick' a cada $interval s  (Ctrl+C p/ sair)" -ForegroundColor Cyan
  Write-Host ""
  $prevKey = $null
  while ($true) {
    $r = Invoke-CatvuFind $nick
    if ($r) {
      $key = "$($r.online)|$($r.hasOccupiedPrivateRoom)|$(@($r.rooms).Count)"
      if ($key -ne $prevKey) {
        if ($null -ne $prevKey) {
          if ($prevKey -match "^True\|" -and $key -notmatch "^True\|") { Write-Host "  [!] FICOU OFFLINE" -ForegroundColor Red; Beep-Alert }
          elseif ($prevKey -notmatch "^True\|" -and $key -match "^True\|") { Write-Host "  [!] FICOU ONLINE" -ForegroundColor Green; Beep-Alert }
          if ($prevKey -notmatch "\|True\|" -and $key -match "\|True\|") {
            Write-Host "  [!!!] ENTROU EM SALA PRIVADA" -ForegroundColor Yellow; Beep-Alert; Beep-Alert
          } elseif ($prevKey -match "\|True\|" -and $key -notmatch "\|True\|") {
            Write-Host "  [.] saiu da sala privada" -ForegroundColor DarkYellow
          }
        }
        Write-Host "  ---- " -NoNewline -ForegroundColor DarkGray; Write-Host "$(Get-Date -Format 'HH:mm:ss')" -NoNewline -ForegroundColor DarkGray; Write-Host " ----" -ForegroundColor DarkGray
        Show-Find $r | Out-Null
        # cross-check direto no IMVU
        $on = Get-ImvuOnline $r.cid
        if ($null -ne $on) { Write-Host "  [imvu] presence: " -NoNewline -ForegroundColor DarkGray; if ($on) { Write-Host "online" -ForegroundColor Green } else { Write-Host "offline" -ForegroundColor DarkGray } }
        Write-Host ""
        $prevKey = $key
      }
    }
    Start-Sleep -Seconds $interval
  }
}

function Start-ImvuDirect([string]$nick, [int]$seconds) {
  $r = Invoke-CatvuFind $nick
  $cid = $null
  if ($r) { $cid = $r.cid }
  if (-not $cid) { $cid = Resolve-CidDirect $nick }
  if (-not $cid) { Write-Host "  [x] nao achei cid pra '$nick'" -ForegroundColor Red; return }
  Write-Host "  alvo '$nick' -> CID $cid | IMVU direto por $seconds s" -ForegroundColor Cyan
  Write-Host ""
  $deadline = (Get-Date).AddSeconds($seconds)
  $last = $null
  while ((Get-Date) -lt $deadline) {
    $on = Get-ImvuOnline $cid
    $u = Get-ImvuUser $cid
    $st = "offline"; if ($on) { $st = "online" }
    if ($st -ne $last) {
      Write-Host "  $(Get-Date -Format 'HH:mm:ss')  " -NoNewline -ForegroundColor DarkGray
      if ($on) { Write-Host "ONLINE " -NoNewline -ForegroundColor Green } else { Write-Host "offline " -NoNewline -ForegroundColor Red }
      if ($u) { Write-Host "$($u.display_name)  " -NoNewline -ForegroundColor White; Write-Host "(availability: '$($u.availability)')" -ForegroundColor DarkGray }
      if ($on) { Beep-Alert }
      $last = $st
    }
    Start-Sleep -Seconds 5
  }
}

function Resolve-Only([string]$nick) {
  $r = Invoke-CatvuFind $nick
  if ($r -and $r.cid) { Write-Host "  $nick  ->  CID $($r.cid)   [via catvu]" -ForegroundColor Cyan; return }
  $cid = Resolve-CidDirect $nick
  if ($cid) { Write-Host "  $nick  ->  CID $cid   [via imvu redirect]" -ForegroundColor Cyan; return }
  Write-Host "  [x] nao resolveu '$nick'" -ForegroundColor Red
}

# ---------------- modo CLI ----------------
if ($Cid) { Banner; Resolve-Only $Cid; exit }
if ($Caca) {
  Banner
  $extras = ""
  $perRoom = 6
  if ($Segs -match "^\d+$") { $perRoom = [int]$Segs } elseif ($Segs) { $extras = $Segs }
  if ($Nick -match "^\d+$") { $perRoom = [int]$Nick } elseif ($Nick) { if ($extras) { $extras = "$extras,$Nick" } else { $extras = $Nick } }
  Start-Caca $Caca $extras $perRoom
  exit
}
if ($Vigia) {
  Banner
  $int = 15
  if ($Segs -match "^\d+$") { $int = [int]$Segs } elseif ($Nick -match "^\d+$") { $int = [int]$Nick }
  Start-Vigia $Vigia $int
  exit
}
if ($Watch) {
  Banner
  $sec = 120
  if ($Segs -match "^\d+$") { $sec = [int]$Segs } elseif ($Nick -match "^\d+$") { $sec = [int]$Nick }
  Watch-RoomStream $Watch $sec
  exit
}
if ($Imvu) {
  Banner
  $sec = 40
  if ($Segs -match "^\d+$") { $sec = [int]$Segs } elseif ($Nick -match "^\d+$") { $sec = [int]$Nick }
  Start-ImvuDirect $Imvu $sec
  exit
}
if ($Nick) {
  Banner
  $r = Invoke-CatvuFind $Nick -asJson:$Raw
  if ($Raw) { $r; exit }
  $null = Show-Find $r
  exit
}

# ---------------- menu interativo ----------------
while ($true) {
  Banner
  Write-Host "  1) TRACK  - checar nick (sala privada agora?)"
  Write-Host "  2) VIGIA  - monitorar entrada/saida de sala privada"
  Write-Host "  3) WATCH  - assistir sala ao vivo (roster, join/leave)"
  Write-Host "  4) IMVU   - presence direto no api.imvu.com"
  Write-Host "  5) CID    - resolver nick -> cid"
  Write-Host "  6) CACA   - alvo + dono(s) de sala -> achar em qual sala ele esta"
  Write-Host "  0) sair"
  Write-Host ""
  $opt = Read-Host "  opcao"
  switch ($opt) {
    "1" {
      Banner
      $n = Read-Host "  nick"
      if ($n) { $r = Invoke-CatvuFind $n; $null = Show-Find $r; Write-Host "" }
    }
    "2" {
      Banner
      $n = Read-Host "  nick"
      if ($n) { $iv = Read-Host "  intervalo em seg (enter = 15)"; if ($iv -notmatch "^\d+$") { $iv = 15 }; Start-Vigia $n ([int]$iv) }
    }
    "3" {
      Banner
      $rid = Read-Host "  roomId (ex: 226939065-160)"
      if ($rid) { $sv = Read-Host "  segundos (enter = 120)"; if ($sv -notmatch "^\d+$") { $sv = 120 }; Watch-RoomStream $rid ([int]$sv) }
    }
    "4" {
      Banner
      $n = Read-Host "  nick"
      if ($n) { $sv = Read-Host "  segundos (enter = 40)"; if ($sv -notmatch "^\d+$") { $sv = 40 }; Start-ImvuDirect $n ([int]$sv) }
    }
    "5" {
      Banner
      $n = Read-Host "  nick"
      if ($n) { Resolve-Only $n }
    }
    "6" {
      Banner
      $alv = Read-Host "  nick do alvo"
      if ($alv) {
        $don = Read-Host "  nick do dono da sala (ou lista, virgula; enter = so o alvo)"
        $pv = Read-Host "  segundos por sala (enter = 6)"
        if ($pv -notmatch "^\d+$") { $pv = 6 }
        Start-Caca $alv $don ([int]$pv)
      }
    }
    "0" { exit }
    default { Write-Host "  [x] opcao invalida" -ForegroundColor Red }
  }
}
