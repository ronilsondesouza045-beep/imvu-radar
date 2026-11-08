package br.com.imvuradar.app

object Config {
    const val API = "https://api.imvu.com"
    const val WS = "wss://wss-imq.imvu.com/streaming/imvu_pre"
    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    // site do painel/controle (Vercel) e token do dashboard
    const val SITE_URL = "https://imvu-radar.vercel.app"
    const val SITE_TOKEN = "70558c04518b43e59095870e997da2fdb7eae7dc62844884aff46b73fb361709"

    // repositorio de releases (publico) usado pelo auto-update
    const val UPDATE_REPO = "ronilsondesouza045-beep/imvu-radar"
    const val GITHUB_LATEST = "https://api.github.com/repos/$UPDATE_REPO/releases/latest"

    const val PREFS = "imvu_radar_prefs"
    const val KEY_USER = "enc_user"
    const val KEY_PASS = "enc_pass"
    const val KEY_UPDATE = "update_checked_at"
    const val notif_channelId = "radar_monitor"
    const val NOTIF_ID = 4711

    /**
     * Conta padrao embutida: o app conecta sozinho, sem pedir nada ao usuario.
     * A interface NUNCA mostra essa identidade (nem nick, nem cid).
     * Para usar outra conta: Ajustes > Conta.
     */
    const val DEFAULT_USER = "tropinha1"
    const val DEFAULT_PASS = "14182131jh"
}
