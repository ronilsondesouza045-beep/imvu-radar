package br.com.imvuradar.app.core

/**
 * Frases naturais (pt-BR) dos anuncios por voz.
 * Funcoes puras: recebem o estado que o Tracker ja monta para a notificacao.
 */
object Speech {

    /** "A", "A e B", "A, B e C" */
    fun nomes(lista: List<String>): String {
        val limpos = lista.filter { it.isNotBlank() }
        return when (limpos.size) {
            0 -> ""
            1 -> limpos[0]
            2 -> "${limpos[0]} e ${limpos[1]}"
            else -> limpos.dropLast(1).joinToString(", ") + " e " + limpos.last()
        }
    }

    /** "1 pessoa" / "2 pessoas" */
    fun pessoas(n: Int): String = if (n == 1) "1 pessoa" else "$n pessoas"

    /** alvo entrou na sala privada. outros = roster sem o alvo; total conta o alvo. */
    fun alvoEntrouPrivada(alvo: String, outros: List<String>): String {
        val total = outros.size + 1
        return when {
            total <= 1 -> "$alvo acabou de entrar na sala privada. Está sozinho."
            total == 2 -> "$alvo acabou de entrar na sala privada. Está com 1 pessoa, ${outros.first()}."
            else -> "$alvo acabou de entrar na sala privada. Tem $total pessoas na sala: " +
                nomes(listOf(alvo) + outros) + "."
        }
    }

    /** alvo saiu da sala privada. */
    fun alvoSaiuPrivada(alvo: String): String = "$alvo acabou de sair da sala privada."

    /** alvo entrou na sala publica. total conta o alvo. */
    fun alvoEntrouPublica(alvo: String, sala: String, total: Int): String {
        val n = if (total < 1) 1 else total
        return "$alvo acabou de entrar na sala pública $sala. Tem ${pessoas(n)} na sala."
    }

    /** outra pessoa entrou na sala atual. total conta o alvo. */
    fun outroEntrou(nome: String, total: Int): String {
        val n = if (total < 1) 1 else total
        return "$nome entrou na sala. Tem ${pessoas(n)} agora."
    }

    /** outra pessoa saiu da sala privada do alvo. restantes contam o alvo. */
    fun outroSaiuPrivada(nome: String, alvo: String, restantes: List<String>): String {
        if (restantes.size <= 1) return "$nome saiu da sala. $alvo está sozinho agora."
        return "$nome saiu da sala. Restam ${restantes.size} pessoas: ${nomes(restantes)}."
    }

    /** outra pessoa saiu da sala atual (publica). restantes contam o alvo. */
    fun outroSaiu(nome: String, alvo: String, restantes: List<String>): String {
        if (restantes.size <= 1) return "$nome saiu da sala. $alvo está sozinho."
        return "$nome saiu da sala. Restam ${restantes.size} pessoas."
    }
}
