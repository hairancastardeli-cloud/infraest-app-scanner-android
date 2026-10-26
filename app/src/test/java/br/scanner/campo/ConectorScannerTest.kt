package br.scanner.campo

import org.junit.Assert.assertEquals
import org.junit.Test

class ConectorScannerTest {
    @Test fun filtraSoAsRedesDoScanner() {
        val achadas = listOf<Pair<String?, Int>>(
            "CASA_5G" to -40, "AGS326046265" to -70, "\"AGS999\"" to -55, "" to -30, null to -20,
            "AGS326046265" to -65, "ags_minusculo" to -50, "Visitantes" to -80,
        )
        // mais forte primeiro, sem repetir e sem aspas; "ags" minúsculo não é rede do scanner
        assertEquals(listOf("AGS999", "AGS326046265"), ConectorScanner.filtrarSsids(achadas))
        assertEquals(emptyList<String>(), ConectorScanner.filtrarSsids(listOf("CASA" to -30)))
    }
}
