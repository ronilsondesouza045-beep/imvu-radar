# IMVU Radar

Tracker de presença e salas do IMVU. Duas fronts:

- **APK Android** (`app/`) — UI mobile com render 3D real (avatar, look, cena da sala), busca por nick,
  monitor de movimento em tempo real, força entrada em sala e auto-update.
- **Site desktop** (`desktop/`) — dashboard local em FastAPI + SSE (`site.py`), mesma engine.

## Engine (Kotlin no APK / Python no desktop)

- Login na API do IMVU (`api.imvu.com/login`) → `sauce` + `cid` + `session_id`
- Listas oficiais do IMVU (`room_list/room_list-<cid>-friends|explore|recent|feature`)
- Roster autenticado (`/chat/chat-<roomId>/participants`)
- Enumeração de salas por dono (`<cid>-1..<cid>-N`)
- Canal IMQ (websocket) para movimento em tempo real, com reconexão e watchdog
- Força entrada: `POST /chat/chat-<roomId>/participants`

## Build

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # assinado (keystore/radar.jks)
```

## Auto-update

O app consulta `https://api.github.com/repos/<owner>/imvu-radar/releases/latest`,
compara a versão do asset (`IMVURadar-<versionCode>.apk`) e baixa/instala.
**Só instala se a assinatura do APK for a mesma chave** (SHA-256 conferido no código).

## Privacidade

- Credenciais ficam cifradas no aparelho (AES-GCM + AndroidKeyStore)
- A interface nunca mostra identidade da conta conectada
- O app pede bateria otimizada pra manter o canal vivo em background
