package br.com.imvuradar.app

object Config {
    const val API = "https://api.imvu.com"
    const val WS = "wss://wss-imq.imvu.com/streaming/imvu_pre"
    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    // repositorio de releases (publico) usado pelo auto-update
    const val UPDATE_REPO = "ronilsondesouza045-beep/imvu-radar"
    const val GITHUB_LATEST = "https://api.github.com/repos/$UPDATE_REPO/releases/latest"

    const val PREFS = "imvu_radar_prefs"
    const val KEY_USER = "enc_user"
    const val KEY_PASS = "enc_pass"
    const val KEY_UPDATE = "update_checked_at"
    const val notif_channelId = "radar_monitor"
    const val NOTIF_ID = 4711
}
