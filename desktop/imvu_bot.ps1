# ============================================================
#  IMVU-BOT v2  -  engine propria estilo BotPower (gateway API)
#  Login no api.imvu.com com conta bot -> presence + sala atual
#  Amigo do bot = localizacao exata (sala, publica ou privada).
#  Uso:
#    .\IMVU-BOT.bat                      -> menu interativo
#    .\IMVU-BOT.bat -Login               -> testa login e mostra conta
#    .\IMVU-BOT.bat -Local <nick>        -> onde o nick esta agora
#    .\IMVU-BOT.bat -Add <nick>          -> pedido de amizade do bot
#    .\IMVU-BOT.bat -Amigos              -> dashboard: amigos e salas
#    .\IMVU-BOT.bat -Vigia2 <nick> [seg] -> monitora sala/presenca
#    .\IMVU-BOT.bat -Sala <roomId>       -> roster da sala (autenticado)
# ============================================================

param(
  [string]$Segs = "",
  [string]$Local = "",
  [string]$Add = "",
  [string]$Vigia = "",
  [string]$Sala = "",
  [switch]$Amigos,
  [switch]$Login,
  [switch]$Onde,
  [string]$Caca = "",
  [string]$Link = "",
  [string]$Alvo = "",
  [string]$Cid = ""
)

$ErrorActionPreference = "SilentlyContinue"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try { $OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$Api = "https://api.imvu.com"
$UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
$CfgPath = Join-Path $PSScriptRoot "bot.cfg"

$script:Sess = $null
$script:Sauce = ""
$script:MyCid = ""
$script:MyName = ""

function Beep-Alert {
  try { [console]::Beep(900,220); Start-Sleep -Milliseconds 60; [console]::Beep(1250,220) } catch { Write-Host "`a" }
}

function Banner {
  Write-Host ""
  Write-Host "  IMVU-BOT  " -NoNewline -ForegroundColor DarkGray
  Write-Host "[ gateway autenticado ]" -ForegroundColor Cyan
  Write-Host "  login proprio no api.imvu.com -> sala de amigos, privada ou nao" -ForegroundColor DarkGray
  Write-Host ""
}

function Node($resp, $url) {
  if (-not $resp) { return $null }
  foreach ($p in $resp.denormalized.PSObject.Properties) {
    if ($p.Name -eq $url) { return $p.Value }
  }
  return $null
}

function Login-Imvu {
  if (-not (Test-Path $CfgPath)) {
    Write-Host "  [x] bot.cfg nao encontrado - crie com username/password da conta bot" -ForegroundColor Red
    return $false
  }
  $cfg = Get-Content $CfgPath -Raw | ConvertFrom-Json
  if (-not $cfg.username -or -not $cfg.password) {
    Write-Host "  [x] bot.cfg invalido" -ForegroundColor Red
    return $false
  }
  $sess = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  $body = @{ gdpr_cookie_acceptance = $true; username = $cfg.username; password = $cfg.password; remember_device = $true } | ConvertTo-Json -Compress
  try {
    $lg = Invoke-WebRequest -Uri "$Api/login" -Method Post -ContentType "application/json" -Body $body -WebSession $sess -UseBasicParsing -TimeoutSec 30 -Headers @{ "User-Agent" = $UA; "Accept" = "application/json"; "Origin" = "https://www.imvu.com"; "Referer" = "https://www.imvu.com/" }
  } catch {
    $msg = $_.Exception.Message
    $resp = $_.Exception.Response
    $detail = ""
    if ($resp) { try { $sr = New-Object System.IO.StreamReader($resp.GetResponseStream()); $detail = $sr.ReadToEnd() } catch { } }
    if ($detail -and $detail.Length -gt 300) { $detail = $detail.Substring(0,300) }
    Write-Host "  [x] login falhou: $msg $detail" -ForegroundColor Red
    return $false
  }
  $env = $lg.Content | ConvertFrom-Json
  if ($env.status -ne "success") {
    Write-Host "  [x] login negado: $($lg.Content)" -ForegroundColor Red
    return $false
  }
  $data = ($env.denormalized.PSObject.Properties | Select-Object -First 1).Value.data
  $script:Sess = $sess
  $script:Sauce = $data.sauce
  $script:MyCid = ($data.user.id -split "/")[-1]
  $me = Api-Get "/login/me"
  if ($me) {
    $mnode = ($me.denormalized.PSObject.Properties | Select-Object -First 1).Value
    $script:MyCid = $mnode.relations.quick_chat_profile
    if ($mnode.relations.user) {
      $unode = Api-Get ($mnode.relations.user -replace [regex]::Escape($Api), "")
      if ($unode) { $script:MyName = (($unode.denormalized.PSObject.Properties | Select-Object -First 1).Value.data).display_name }
    }
  }
  return $true
}

function Api-Get([string]$path) {
  try {
    $r = Invoke-WebRequest -Uri "$Api$path" -WebSession $script:Sess -UseBasicParsing -TimeoutSec 20 -Headers @{ "User-Agent" = $UA }
    return ($r.Content | ConvertFrom-Json)
  } catch { return $null }
}

function Resolve-Nick([string]$nick) {
  $r = Api-Get "/user?username=$([uri]::EscapeDataString($nick))"
  if (-not $r) { return $null }
  foreach ($p in $r.denormalized.PSObject.Properties) {
    if ($p.Name -match "/user/user-(\d+)$") {
      return @{ cid = $matches[1]; url = $p.Name; node = $p.Value }
    }
  }
  return $null
}

function Get-RoomInfo([string]$roomUrl) {
  if (-not $roomUrl) { return $null }
  $r = Api-Get ($roomUrl -replace [regex]::Escape($Api), "")
  if (-not $r) { return $null }
  $node = $null
  foreach ($p in $r.denormalized.PSObject.Properties) {
    if ($p.Value.data -and $p.Value.data.name) { $node = $p.Value; break }
  }
  if (-not $node) { $node = ($r.denormalized.PSObject.Properties | Select-Object -First 1).Value }
  if (-not $node) { return $null }
  $d = $node.data
  $info = @{
    name = $d.name; privacy = $d.privacy; occupancy = $d.occupancy; capacity = $d.capacity
    roomId = ($roomUrl -split "-")[-2] + "-" + ($roomUrl -split "-")[-1]
    owner = ""
  }
  if ($node.relations.owner) {
    $ow = Api-Get ($node.relations.owner -replace [regex]::Escape($Api), "")
    if ($ow) { $info.owner = ((($ow.denormalized.PSObject.Properties | Select-Object -First 1).Value.data)).username }
  }
  return $info
}

function Show-Local([string]$nick) {
  $res = Resolve-Nick $nick
  if (-not $res) {
    Write-Host "  [x] '$nick' nao resolveu" -ForegroundColor Red
    return
  }
  $d = $res.node.data
  Write-Host "  ALVO : " -NoNewline; Write-Host "$($d.display_name)  " -NoNewline -ForegroundColor White
  Write-Host "[$($d.username)]  CID $($res.cid)" -ForegroundColor DarkGray
  $on = $d.online
  if ($on) { Write-Host "  ESTADO: ONLINE" -ForegroundColor Green } else { Write-Host "  ESTADO: offline" -ForegroundColor DarkGray }
  Write-Host "  procurando sala nas listas oficiais do IMVU..." -ForegroundColor DarkGray
  $map = Get-RoomMap @("friends", "explore", "feature", "recent")
  if ($map.ContainsKey($res.cid)) {
    $r = $map[$res.cid]
    $col = "Gray"; $tag = "publica"
    if ($r.privacy -eq "private") { $tag = "PRIVADA"; $col = "Yellow" }
    Write-Host "  SALA  : " -NoNewline; Write-Host "$($r.roomName)" -NoNewline -ForegroundColor White
    Write-Host "  [$($r.roomId)]  <$tag>  $($r.occupancy)/$($r.capacity)" -ForegroundColor $col
    if ($r.owner) { Write-Host "          dono: $($r.owner)  |  gente na sala: $($r.people)" -ForegroundColor DarkGray }
    return
  }
  Write-Host "  SALA  : nao achada nas listas do IMVU" -ForegroundColor DarkYellow
  Write-Host "          (sala privada de terceiro so aparece pra quem esta dentro -> -Link ou convida o bot)" -ForegroundColor DarkGray
  $enum = Enum-RoomsAuth $res.cid 120
  if ($enum.Count -gt 0) {
    Write-Host "          varredas $($enum.Count) sala(s) propria(s) dele..." -ForegroundColor DarkGray
    foreach ($rid in $enum.Keys) {
      $rm = $enum[$rid]
      $roster = Get-ChatRoster $rid
      if ($roster) {
        foreach ($p in $roster) {
          if ("$($p.cid)" -eq "$($res.cid)") {
            Write-Host "          >>> achado na propria sala dele: $($rm.roomName) [$rid] <$($rm.privacy)>" -ForegroundColor Green
          }
        }
      }
    }
  }
}

function Add-Friend([string]$nick) {
  $res = Resolve-Nick $nick
  if (-not $res) { Write-Host "  [x] '$nick' nao resolveu" -ForegroundColor Red; return }
  $tcid = $res.cid
  # ja e amigo?
  $fr = Api-Get "/user/user-$($script:MyCid)/friends?limit=200"
  if ($fr) {
    foreach ($p in $fr.denormalized.PSObject.Properties) {
      if ($p.Name -match "/user/user-(\d+)$" -and $p.Value.relations.viewer_friend) {
        if ("$($matches[1])" -eq "$tcid") {
          Write-Host "  [i] $($res.node.data.display_name) ja e amigo do bot" -ForegroundColor Yellow
          Write-Host "      agora roda -Alvo $($res.node.data.username) pra ver a sala dele" -ForegroundColor DarkGray
          return
        }
      }
    }
  }
  $body = '{"id":"https://api.imvu.com/user/user-' + $tcid + '"}'
  try {
    $r = Invoke-WebRequest -Uri "$Api/user/user-$($script:MyCid)/outbound_friend_requests" -Method Post -ContentType "application/json" -Body $body -WebSession $script:Sess -UseBasicParsing -TimeoutSec 20 -Headers @{ "User-Agent" = $UA; "x-imvu-sauce" = $script:Sauce; "x-imvu-application" = "next_desktop/1"; "Origin" = "https://www.imvu.com" }
    Write-Host "  [+] pedido de amizade enviado pra $($res.node.data.display_name) [$($res.cid)]  (status $($r.StatusCode))" -ForegroundColor Green
    Write-Host "      depois que aceitar: -Alvo <nick> mostra a sala dele na hora" -ForegroundColor DarkGray
  } catch {
    $resp = $_.Exception.Response
    $detail = ""
    if ($resp) { try { $sr = New-Object System.IO.StreamReader($resp.GetResponseStream()); $detail = $sr.ReadToEnd() } catch { }; if ($detail.Length -gt 300) { $detail = $detail.Substring(0,300) } }
    Write-Host "  [x] pedido recusado ($([int]$resp.StatusCode))" -ForegroundColor Red
    Write-Host "      IMVU devolveu corpo vazio nesse erro: normalmente ja sao amigos ou pedido duplicado." -ForegroundColor DarkGray
    if ($detail) { Write-Host "      detalhe: $detail" -ForegroundColor DarkGray }
  }
}

function Show-Friends {
  $fr = Api-Get "/user/user-$($script:MyCid)/friends?limit=100"
  if (-not $fr) { Write-Host "  [x] nao consegui ler amigos" -ForegroundColor Red; return }
  $col = $null
  foreach ($p in $fr.denormalized.PSObject.Properties) {
    if ($p.Name -match "/friends(\?|$)") { $col = $p.Value; break }
  }
  if (-not $col) { $col = ($fr.denormalized.PSObject.Properties | Select-Object -First 1).Value }
  $items = @()
  if ($col -and $col.data) { $items = @($col.data.items) }
  Write-Host "  AMIGOS: $($col.data.total_count) total" -ForegroundColor Cyan
  Write-Host ""
  if ($items.Count -eq 0) {
    Write-Host "  o bot nao tem amigos ainda. use -Add <nick> pra comecar." -ForegroundColor DarkGray
    return
  }
  Write-Host "  lendo salas oficiais (Join Your Friends)..." -ForegroundColor DarkGray
  $map = Get-RoomMap @("friends", "explore", "feature", "recent")
  foreach ($it in $items) {
    $node = Node $fr $it
    if (-not $node) { continue }
    $u = $node.relations.ref
    $unode = Node $fr $u
    if (-not $unode) { continue }
    $d = $unode.data
    $cid = ($u -split "-")[-1]
    Write-Host "  - $($d.display_name) [$($d.username)] cid=$cid" -NoNewline -ForegroundColor White
    Write-Host "  [$($(if ($d.online) { "ONLINE" } else { "offline" }))]" -NoNewline -ForegroundColor $(if ($d.online) { "Green" } else { "DarkGray" })
    if ($map.ContainsKey($cid)) {
      $r = $map[$cid]
      $col2 = "Gray"; $tag = "publica"
      if ($r.privacy -eq "private") { $tag = "PRIVADA"; $col2 = "Yellow" }
      Write-Host "  -> $($r.roomName) [$($r.roomId)] <$tag> $($r.occupancy)/$($r.capacity)" -ForegroundColor $col2
    } else {
      Write-Host "  -> sem leitura de sala (fora das listas do IMVU)" -ForegroundColor DarkGray
    }
  }
}

function Roster-Room([string]$roomId) {
  $r = Api-Get "/chat/chat-$roomId"
  if (-not $r) { Write-Host "  [x] nao consegui ler a sala (privada de terceiro? o bot precisa estar dentro/amigo do dono)" -ForegroundColor Red; return }
  Write-Host "  ROSTER [$roomId]:" -ForegroundColor Cyan
  foreach ($p in $r.denormalized.PSObject.Properties) {
    if ($p.Name -match "/participants$") {
      foreach ($it in @($p.Value.data.items)) {
        $pnode = Node $r $it
        if (-not $pnode) { continue }
        $u = $pnode.relations.ref
        $unode = Node $r $u
        if (-not $unode) { continue }
        $d = $unode.data
        Write-Host "    - $($d.display_name)  " -NoNewline -ForegroundColor White
        Write-Host "[$($d.username) / $((($u -split '-')[-1]))]" -ForegroundColor DarkGray
      }
    }
  }
}

function Start-Vigia2([string]$nick, [int]$interval) {
  Write-Host "  vigiando '$nick' a cada $interval s (Ctrl+C p/ sair)" -ForegroundColor Cyan
  Write-Host ""
  $last = ""
  while ($true) {
    $res = Resolve-Nick $nick
    if ($res) {
      $d = $res.node.data
      $ri = $null
      $map = Get-RoomMap @("friends", "explore", "recent")
      if ($map.ContainsKey($res.cid)) { $ri = $map[$res.cid] }
      $roomKey = ""
      if ($ri) { $roomKey = "$($ri.roomName)|$($ri.privacy)|$($ri.roomId)" }
      $key = "$($d.online)|$roomKey"
      if ($key -ne $last) {
        Write-Host "  ---- " -NoNewline -ForegroundColor DarkGray; Write-Host "$(Get-Date -Format 'HH:mm:ss')" -NoNewline -ForegroundColor DarkGray; Write-Host " ----" -ForegroundColor DarkGray
        if ($d.online) { Write-Host "  ONLINE" -ForegroundColor Green } else { Write-Host "  offline" -ForegroundColor Red }
        if ($ri) {
          $col2 = "Gray"; $tag = "publica"
          if ($ri.privacy -eq "private") { $tag = "PRIVADA"; $col2 = "Yellow" }
          Write-Host "  SALA : " -NoNewline; Write-Host "$($ri.roomName)  " -NoNewline -ForegroundColor White
          Write-Host "[$($ri.roomId)] <$tag> $($ri.occupancy)/$($ri.capacity)  dono: $($ri.owner)" -ForegroundColor $col2
          Beep-Alert
        } else {
          Write-Host "  sem leitura de sala agora (offline, ou sala fora das listas do IMVU)" -ForegroundColor DarkGray
        }
        Write-Host ""
        $last = $key
      }
    }
    Start-Sleep -Seconds $interval
  }
}

function Get-ChatRoster([string]$roomId) {
  $r = Api-Get "/chat/chat-$roomId"
  if (-not $r) { return $null }
  $items = @()
  foreach ($p in $r.denormalized.PSObject.Properties) {
    if ($p.Name -match "/participants$") { $items = @($p.Value.data.items) }
  }
  $out = @()
  foreach ($it in $items) {
    $pnode = Node $r $it
    if (-not $pnode) { continue }
    $u = $pnode.relations.ref
    $unode = Node $r $u
    if (-not $unode) { continue }
    $d = $unode.data
    $out += [pscustomobject]@{ cid = ($u -split "-")[-1]; username = $d.username; display_name = $d.display_name }
  }
  return $out
}

function Enum-RoomsAuth([string]$cid, [int]$maxN) {
  $out = @{}
  for ($n = 1; $n -le $maxN; $n++) {
    $rid = "$cid-$n"
    try {
      $r = Invoke-RestMethod -Uri "$Api/room/room-$rid" -WebSession $script:Sess -UseBasicParsing -TimeoutSec 8 -Headers @{ "User-Agent" = $UA }
      foreach ($p in $r.denormalized.PSObject.Properties) {
        $d = $p.Value.data
        if ($d -and $d.name) {
          $out[$rid] = [pscustomobject]@{ roomId = $rid; roomName = $d.name; privacy = $d.privacy; occupancy = $d.occupancy; capacity = $d.capacity }
        }
      }
    } catch {
      $code = 0
      if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
      if ($code -eq 429) { Start-Sleep -Seconds 15; $n-- }
    }
    if ($n % 20 -eq 0) { Start-Sleep -Milliseconds 400 }
  }
  return $out
}

function Start-CacaBot([string]$alvo, [string]$extraOwners, [int]$maxN) {
  $res = Resolve-Nick $alvo
  if (-not $res) { Write-Host "  [x] alvo '$alvo' nao resolveu" -ForegroundColor Red; return }
  $tcid = $res.cid
  Write-Host "  ALVO: $alvo  [CID $tcid]" -ForegroundColor Cyan
  $owners = @($alvo)
  if ($extraOwners) { $owners += @($extraOwners -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }) }
  $owners = $owners | Select-Object -Unique
  $cand = @{}
  foreach ($o in $owners) {
    $ocid = $null
    if ($o -ieq $alvo) { $ocid = $tcid } else { $r2 = Resolve-Nick $o; if ($r2) { $ocid = $r2.cid } }
    if (-not $ocid) { Write-Host "  [i] nao resolvei '$o'" -ForegroundColor DarkGray; continue }
    Write-Host "  [enum] salas do cid $ocid ($o)..." -ForegroundColor DarkGray
    $enum = Enum-RoomsAuth $ocid $maxN
    foreach ($rid in $enum.Keys) { if (-not $cand.ContainsKey($rid)) { $cand[$rid] = $enum[$rid] } }
    Write-Host "  [enum] $($enum.Count) sala(s) de $o" -ForegroundColor DarkGray
  }
  if ($cand.Count -eq 0) { Write-Host "  [x] nenhuma sala nos nicks informados" -ForegroundColor Yellow; return }
  Write-Host "  checando $($cand.Count) sala(s) via roster autenticado..." -ForegroundColor Cyan
  Write-Host ""
  $n = 0
  foreach ($rid in $cand.Keys) {
    $n++
    $rm = $cand[$rid]
    $roster = Get-ChatRoster $rid
    $hit = $false
    if ($roster) { foreach ($p in $roster) { if ("$($p.cid)" -eq "$tcid") { $hit = $true } } }
    $tag = "publica"; $col = "Gray"
    if ($rm.privacy -eq "private") { $tag = "PRIVADA"; $col = "Yellow" }
    if ($hit) {
      Write-Host ""
      Write-Host "  [!!!] ACHADO: " -NoNewline -ForegroundColor Green
      Write-Host "$($rm.roomName)  [$rid]  <$tag>" -ForegroundColor White
      Write-Host "  dentro ($($roster.Count)):" -ForegroundColor Cyan
      foreach ($p in $roster) {
        Write-Host "      - $($p.display_name)  " -NoNewline -ForegroundColor White
        Write-Host "[$($p.username) / $($p.cid)]" -ForegroundColor DarkGray
      }
      Beep-Alert; Beep-Alert
      return
    }
    $cnt = 0; if ($roster) { $cnt = $roster.Count }
    Write-Host "  [$n/$($cand.Count)] $($rm.roomName) [$rid] <$tag> - nao ($cnt visivel(is))" -ForegroundColor DarkGray
    if ($rm.privacy -eq "private" -and $cnt -le 1) { Write-Host "        (privada: roster so aparece se o bot estiver DENTRO dela)" -ForegroundColor DarkGray }
  }
  Write-Host ""
  Write-Host "  alvo nao achado. se a sala e privada, o bot precisa ser convidado pra dentro (modo ONDE)." -ForegroundColor Yellow
}

function Get-RoomMap([string[]]$lists) {
  # varre as salas das listas oficiais e devolve mapa: CID -> sala onde a pessoa esta
  $rooms = Get-RoomLists $lists
  $map = @{}
  foreach ($rid in $rooms.Keys) {
    $roster = Get-ChatRoster $rid
    if (-not $roster -or $roster.Count -eq 0) { continue }
    $meta = Get-RoomInfo "$Api/room/room-$rid"
    foreach ($p in $roster) {
      if (-not $map.ContainsKey($p.cid)) {
        $map[$p.cid] = [pscustomobject]@{
          roomId = $rid; roomName = $meta.name; privacy = $meta.privacy
          occupancy = $meta.occupancy; capacity = $meta.capacity; owner = $meta.owner
          people = $roster.Count; source = $rooms[$rid]
        }
      }
    }
  }
  return $map
}

function Get-RoomLists([string[]]$lists) {
  # listas oficiais do IMVU (friends = "Join Your Friends": salas onde meus amigos estao)
  $out = @{}
  foreach ($lid in $lists) {
    $rc = Api-Get "/room_list/room_list-$($script:MyCid)-$lid/rooms"
    if (-not $rc) { continue }
    foreach ($p in $rc.denormalized.PSObject.Properties) {
      if ($p.Name -match "/rooms$") {
        foreach ($it in @($p.Value.data.items)) {
          $rid = ($it -split "/")[-1] -replace "^room-", ""
          if ($rid -and -not $out.ContainsKey($rid)) { $out[$rid] = $lid }
        }
      }
    }
  }
  return $out
}

function Find-TargetRoom([string]$nick) {
  $res = Resolve-Nick $nick
  if (-not $res) { Write-Host "  [x] '$nick' nao resolveu" -ForegroundColor Red; return }
  $tcid = $res.cid
  Write-Host "  ALVO: $nick  [CID $tcid]" -ForegroundColor Cyan
  Write-Host "  consultando listas oficiais do IMVU (friends / explore / feature / recent)..." -ForegroundColor DarkGray
  $cand = Get-RoomLists @("friends", "explore", "feature", "recent", "favorite")
  Write-Host "  $($cand.Count) sala(s) candidata(s) nas listas" -ForegroundColor DarkGray
  Write-Host ""
  $n = 0
  foreach ($rid in $cand.Keys) {
    $n++
    $roster = Get-ChatRoster $rid
    if (-not $roster) { continue }
    $hit = $false
    foreach ($p in $roster) { if ("$($p.cid)" -eq "$tcid") { $hit = $true } }
    if ($hit) {
      $meta = Get-RoomInfo "$Api/room/room-$rid"
      $tag = "publica"; $col = "Gray"
      if ($meta.privacy -eq "private") { $tag = "PRIVADA"; $col = "Yellow" }
      Write-Host "  [!!!] $nick ESTA EM: " -NoNewline -ForegroundColor Green
      Write-Host "$($meta.name)  [$rid]" -NoNewline -ForegroundColor White
      Write-Host "  <$tag>  $($meta.occupancy)/$($meta.capacity)" -ForegroundColor $col
      if ($meta.owner) { Write-Host "  dono: $($meta.owner)" -ForegroundColor DarkGray }
      Write-Host "  dentro ($($roster.Count)):" -ForegroundColor Cyan
      foreach ($p in $roster) {
        Write-Host "      - $($p.display_name)  " -NoNewline -ForegroundColor White
        Write-Host "[$($p.username) / $($p.cid)]" -ForegroundColor DarkGray
      }
      Beep-Alert; Beep-Alert
      return
    }
  }
  Write-Host "  $nick nao aparece em nenhuma sala das listas do IMVU (agora)." -ForegroundColor Yellow
  Write-Host "  ele ta em sala de terceiro que o IMVU so revela pra quem esta dentro -> usa -Link ou convida o bot pra dentro." -ForegroundColor DarkGray
}

function Show-OndeEstou {
  $me = Api-Get "/user/user-$($script:MyCid)"
  if (-not $me) { Write-Host "  [x] nao li o no do bot" -ForegroundColor Red; return }
  $node = ($me.denormalized.PSObject.Properties | Select-Object -First 1).Value
  $rel = $node.relations
  $roomUrl = $null
  foreach ($k in $rel.PSObject.Properties.Name) {
    if ($k -match "current_room|active_room|in_room") { $roomUrl = $rel.PSObject.Properties[$k].Value }
  }
  if ($roomUrl) {
    $ri = Get-RoomInfo $roomUrl
    if ($ri) {
      $col2 = "Gray"; $tag = "publica"
      if ($ri.privacy -eq "private") { $tag = "PRIVADA"; $col2 = "Yellow" }
      Write-Host "  BOT ESTA EM: " -NoNewline; Write-Host "$($ri.name)" -NoNewline -ForegroundColor White
      Write-Host "  [$($ri.roomId)]  <$tag>  $($ri.occupancy)/$($ri.capacity)" -ForegroundColor $col2
      Roster-Room $ri.roomId
      return
    }
  }
  # fallback: salas recentes do bot
  Write-Host "  sem relacao de sala ativa no no do bot - olhando recent_rooms..." -ForegroundColor DarkGray
  $rc = Api-Get "/user/user-$($script:MyCid)/recent_rooms?limit=3"
  if ($rc) {
    foreach ($p in $rc.denormalized.PSObject.Properties) {
      if ($p.Name -match "/room/room-(\d+-\d+)$") {
        $rid = $matches[1]
        $d = $p.Value.data
        $col2 = "Gray"; $tag = "publica"
        if ($d.privacy -eq "private") { $tag = "PRIVADA"; $col2 = "Yellow" }
        Write-Host "  [recente] $($d.name)  [$rid]  <$tag>  occ=$($d.occupancy)/$($d.capacity)" -ForegroundColor $col2
      }
    }
  } else {
    Write-Host "  [x] sem dados. o bot precisa estar dentro de uma sala (loga a conta no IMVU e entra na sala)" -ForegroundColor Yellow
  }
}

function Show-Account {
  Write-Host "  CONTA BOT:" -ForegroundColor Cyan
  Write-Host "    cid  : $($script:MyCid)" -ForegroundColor White
  Write-Host "    nome : $($script:MyName)" -ForegroundColor White
  Write-Host "    sauce: $($script:Sauce.Substring(0,12))..." -ForegroundColor DarkGray
  $fr = Api-Get "/user/user-$($script:MyCid)/friends?limit=0"
  if ($fr) { $c = ($fr.denormalized.PSObject.Properties | Select-Object -First 1).Value; Write-Host "    amigos: $($c.data.total_count)" -ForegroundColor White }
  Write-Host ""
  Write-Host "  logado e pronto. comandos: -Local, -Add, -Amigos, -Vigia2, -Sala" -ForegroundColor DarkGray
}

# ---------------- CLI ----------------
if (-not (Login-Imvu)) { exit }

if ($Login) { Banner; Show-Account; exit }
if ($Amigos) { Banner; Show-Friends; exit }
if ($Onde) { Banner; Show-OndeEstou; exit }
if ($Cid) {
  Banner
  $r = Resolve-Nick $Cid
  if ($r) { Write-Host "  $Cid  ->  CID $($r.cid)   [$($r.node.data.username)]" -ForegroundColor Cyan }
  else { Write-Host "  [x] nao resolveu '$Cid'" -ForegroundColor Red }
  exit
}
if ($Alvo) { Banner; Find-TargetRoom $Alvo; exit }
if ($Link) {
  Banner
  $rid = ""
  if ($Link -match "room-(\d+-\d+)") { $rid = $matches[1] }
  elseif ($Link -match "(\d+-\d+)") { $rid = $matches[1] }
  if (-not $rid) { Write-Host "  [x] nao achei roomId no link: $Link" -ForegroundColor Red; exit }
  Write-Host "  roomId: $rid" -ForegroundColor Cyan
  $ri = Get-RoomInfo "$Api/room/room-$rid"
  if ($ri) {
    $col2 = "Gray"; $tag = "publica"
    if ($ri.privacy -eq "private") { $tag = "PRIVADA"; $col2 = "Yellow" }
    Write-Host "  SALA: $($ri.name)  [$($ri.roomId)]  <$tag>  $($ri.occupancy)/$($ri.capacity)" -ForegroundColor $col2
    if ($ri.owner) { Write-Host "  dono: $($ri.owner)" -ForegroundColor DarkGray }
  }
  Roster-Room $rid
  exit
}
if ($Caca) {
  Banner
  $extras = ""
  $maxN = 250
  if ($Segs -match "^\d+$") { $maxN = [int]$Segs } elseif ($Segs) { $extras = $Segs }
  if ($Local -and -not ($Local -match "^\d+$")) { if ($extras) { $extras = "$extras,$Local" } else { $extras = $Local } }
  Start-CacaBot $Caca $extras $maxN
  exit
}
if ($Local) { Banner; Show-Local $Local; exit }
if ($Add) { Banner; Add-Friend $Add; exit }
if ($Sala) { Banner; Roster-Room $Sala; exit }
if ($Vigia) {
  Banner
  $int = 15
  if ($Segs -match "^\d+$") { $int = [int]$Segs }
  Start-Vigia2 $Vigia $int
  exit
}

# ---------------- menu ----------------
while ($true) {
  Banner
  Write-Host "  bot: $($script:MyName)  [cid $($script:MyCid)]" -ForegroundColor Cyan
  Write-Host ""
  Write-Host "  1) LOCAL   - onde o nick esta agora (sala, privada ou nao)"
  Write-Host "  2) ADD     - pedido de amizade (libera a localizacao)"
  Write-Host "  3) AMIGOS  - dashboard: cada amigo e sua sala"
  Write-Host "  4) VIGIA   - monitorar nick (sala/presenca, com bip)"
  Write-Host "  5) SALA    - roster autenticado de um roomId"
  Write-Host "  6) ONDE    - sala onde o BOT esta + roster (bota o bot na sala!)"
  Write-Host "  7) CACA    - achar o alvo: varre salas do dono e le roster"
  Write-Host "  8) ALVO    - ONDE o nick esta agora (lista oficial Join Your Friends)"
  Write-Host "  0) sair"
  Write-Host ""
  $opt = Read-Host "  opcao"
  if ($null -eq $opt) { exit }
  if ($opt -eq "" -and [Console]::IsInputRedirected) { exit }
  switch ($opt) {
    "1" { Banner; $n = Read-Host "  nick"; if ($n) { Show-Local $n } }
    "2" { Banner; $n = Read-Host "  nick"; if ($n) { Add-Friend $n } }
    "3" { Banner; Show-Friends }
    "4" { Banner; $n = Read-Host "  nick"; if ($n) { $iv = Read-Host "  intervalo (enter = 15)"; if ($iv -notmatch "^\d+$") { $iv = 15 }; Start-Vigia2 $n ([int]$iv) } }
    "5" { Banner; $rid = Read-Host "  roomId"; if ($rid) { Roster-Room $rid } }
    "6" { Banner; Show-OndeEstou }
    "7" {
      Banner
      $alv = Read-Host "  nick do alvo"
      if ($alv) {
        $don = Read-Host "  nick do dono da sala (ou lista; enter = so o alvo)"
        $mx = Read-Host "  ate que numero de sala (enter = 250)"
        if ($mx -notmatch "^\d+$") { $mx = 250 }
        Start-CacaBot $alv $don ([int]$mx)
      }
    }
    "8" {
      Banner
      $alv = Read-Host "  nick do alvo"
      if ($alv) { Find-TargetRoom $alv }
    }
    "0" { exit }
    default { Write-Host "  [x] opcao invalida" -ForegroundColor Red }
  }
}
