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

    /** Roda tudo e devolve o relatório (cada comando com título). [progresso] recebe o título do comando em andamento. */
    fun rodar(fabrica: SocketFactory, ip: String, porta: Int = 5555, progresso: (String) -> Unit = {}): String {
        val sb = StringBuilder()
        Adb(fabrica, ip, porta).use { adb ->
            adb.conectar()
            sb.append("adb: ${adb.banner}\n")
            for ((titulo, cmd) in COMANDOS) {
                progresso(titulo)
                sb.append("\n### $titulo\n")
                sb.append(try { adb.shell(cmd).trimEnd() } catch (e: Exception) { "[falhou: ${e.message}]" }).append('\n')
            }
        }
        return sb.toString()
    }
}
