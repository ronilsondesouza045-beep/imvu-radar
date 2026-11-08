package br.com.imvuradar.app.core

/**
 * Estado local do app (nao vai pra rede).
 * RoomWebActivity grava aqui a sala que o usuario abriu no cliente IMVU
 * dentro do app; o Tracker usa isso como pista imediata quando o alvo
 * e a propria conta (nao precisa esperar WebSocket).
 */
object LocalState {
    @Volatile var lastRoomId: String = ""
}
