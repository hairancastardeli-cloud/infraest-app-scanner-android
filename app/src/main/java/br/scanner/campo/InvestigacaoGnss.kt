package br.scanner.campo

import javax.net.SocketFactory

/**
 * Investigação do GNSS do scanner pelo adb (Wi-Fi), SÓ LEITURA: nada é gravado, apagado ou configurado no scanner.
 * Objetivo: saber qual serial traz o GGA com o levantamento parado, quem segura cada serial e que ferramentas existem
 * no aparelho, para montar o auxiliar que manda o GNSS ao app sem iniciar o levantamento.
 * O último item lê 2 s de cada serial candidata (com o levantamento PARADO o "pos" não está rodando e ninguém lê o GNSS).
 */
object InvestigacaoGnss {
    val COMANDOS: List<Pair<String, String>> = listOf(
        "sistema" to "uname -a; id; head -2 /etc/os-release; uptime",
        "processos" to "ps -eo pid,etime,args | grep -v '\\[' | head -70",
        "seriais" to "ls -l /dev/ttyS* /dev/ttyUSB* /dev/ttyACM* 2>&1",
        "tráfego das seriais (agora e 3 s depois)" to "cat /proc/tty/driver/serial; sleep 3; echo '--- 3 s depois ---'; cat /proc/tty/driver/serial",
        "quem segura cada serial" to "for p in /proc/[0-9]*; do for f in \$p/fd/*; do l=\$(readlink \$f 2>/dev/null); " +
            "case \$l in /dev/tty*) echo \"\$(cat \$p/comm 2>/dev/null) pid=\${p#/proc/} \$l\";; esac; done; done | sort | uniq",
        "velocidade das seriais" to "for t in /dev/ttyS0 /dev/ttyS1 /dev/ttyS2 /dev/ttyS4 /dev/ttyS5; do echo \"== \$t\"; stty -F \$t -a 2>&1 | head -1; done",
        "ferramentas" to "for c in nc ncat netcat socat python3 perl busybox stty timeout; do printf '%s: ' \$c; command -v \$c || echo nao; done",
        "portas TCP abertas" to "(ss -tlnp || netstat -tlnp) 2>/dev/null",
        "configuração" to "cat /mnt/cfg/DataManager.cfg; echo; cat /mnt/cfg/version.txt 2>/dev/null; echo; df -h /mnt/sd /mnt/cfg 2>&1",
        "amostra de 2 s de cada serial (NMEA)" to "for t in /dev/ttyS1 /dev/ttyS2 /dev/ttyS4 /dev/ttyS5; do echo \"== \$t\"; " +
            "timeout 2 cat \$t 2>&1 | tr -cd '\\11\\12\\15\\40-\\176' | grep -a -o '\\\$G[A-Z]*,[^*]*' | head -4; done",
    )

    /**
     * DURANTE o levantamento (o "pos" rodando), também só leitura e SEM ler as seriais (roubaria os dados do pos): que
     * serial o pos abre, a velocidade e o tráfego de cada uma (a do receptor recebe o GGA; a da correção transmite com o
     * RTK ligado), como o GGA chega ao DataManager e se o disco é sobreposto em memória (o que se grava some ao reiniciar).
     */
    val COMANDOS_LEVANTANDO: List<Pair<String, String>> = listOf(
        "programas do levantamento" to "ps -eo pid,etime,args | grep -E 'pos|Ag310|camera|DataManager' | grep -v grep",
        "o que o pos e o DataManager abrem" to "for n in pos DataManager; do for p in \$(pidof \$n); do echo \"== \$n pid=\$p\"; " +
            "ls -l /proc/\$p/fd 2>/dev/null | grep -E 'tty|socket|pipe' | sed 's/.*[0-9] //' | sort | uniq -c; done; done",
        "tráfego das seriais (agora e 3 s depois)" to "cat /proc/tty/driver/serial; sleep 3; echo '--- 3 s depois ---'; cat /proc/tty/driver/serial",
        "velocidade das seriais" to "for t in /dev/ttyS0 /dev/ttyS1 /dev/ttyS4 /dev/ttyS5 /dev/ttyS6; do echo \"== \$t\"; stty -F \$t -a 2>&1 | head -1; done",
        "conexões locais (GGA do pos ao DataManager)" to "(ss -tnp; ss -xp | grep -E 'pos|DataManager') 2>/dev/null | head -30",
        "disco: sobreposto em memória?" to "cat /etc/overlayroot.conf 2>/dev/null | grep -v '^#' | grep .; mount | grep -E ' / |/mnt|overlay|media'; " +
            "ls /mnt/sd 2>&1 | head",
        "ponto de montagem e log do pos" to "ls -la /mnt/cfg/*.log* /mnt/sd/log 2>&1 | tail -15",
    )

    /** Roda [comandos] e devolve o relatório (cada comando com título). [progresso] recebe o título do comando em andamento. */
    fun rodar(fabrica: SocketFactory, ip: String, porta: Int = 5555, comandos: List<Pair<String, String>> = COMANDOS,
              progresso: (String) -> Unit = {}): String {
        val sb = StringBuilder()
        Adb(fabrica, ip, porta).use { adb ->
            adb.conectar()
            sb.append("adb: ${adb.banner}\n")
            for ((titulo, cmd) in comandos) {
                progresso(titulo)
                sb.append("\n### $titulo\n")
                sb.append(try { adb.shell(cmd).trimEnd() } catch (e: Exception) { "[falhou: ${e.message}]" }).append('\n')
            }
        }
        return sb.toString()
    }
}
