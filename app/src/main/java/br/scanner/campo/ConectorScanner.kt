package br.scanner.campo

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Busca e conexão com o Wi-Fi do scanner, como o FireEye faz:
 *  1. escaneia as redes e fica só com as que começam com "AGS" (o nome da rede do scanner, ex.: AGS326046265; a
 *     rede é aberta, sem senha);
 *  2. pede ao Android para conectar nela (WifiNetworkSpecifier, sem exigir internet) e recebe a REDE de volta;
 *  3. o socket do scanner é aberto por essa rede (Equipamento), enquanto o resto do app (RTK) segue pelos dados móveis.
 */
class ConectorScanner(private val act: Activity) {
    companion object {
        const val PREFIXO = "AGS"
        const val IP = "192.168.2.11"
        const val PORTA = 8080
        const val REQ_PERMISSOES = 4711

        /** Só as redes do scanner, sem repetição e sem aspas, as de sinal mais forte primeiro. */
        fun filtrarSsids(redes: List<Pair<String?, Int>>): List<String> =
            redes.mapNotNull { (ssid, nivel) -> ssid?.trim('"')?.takeIf { it.startsWith(PREFIXO, ignoreCase = false) }?.let { it to nivel } }
                .sortedByDescending { it.second }.map { it.first }.distinct()
    }

    private val ctx: Context = act.applicationContext
    private val ui = Handler(Looper.getMainLooper())
    private val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callbackRede: ConnectivityManager.NetworkCallback? = null
    private var receptorScan: BroadcastReceiver? = null
    private var tentativaLegado: Runnable? = null

    // ------------------------------------------------------------------ permissões e ajustes do celular
    private fun permissoesNecessarias(): Array<String> {
        val l = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        return l.filter { act.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    /** true se já pode escanear; senão pede a permissão (a resposta chega em Activity.onRequestPermissionsResult). */
    fun garantirPermissoes(): Boolean {
        val faltam = permissoesNecessarias()
        if (faltam.isEmpty()) return true
        act.requestPermissions(faltam, REQ_PERMISSOES)
        return false
    }

    fun permissoesConcedidas(resultados: IntArray) = resultados.isNotEmpty() && resultados.all { it == PackageManager.PERMISSION_GRANTED }

    fun wifiLigado(): Boolean = wm.isWifiEnabled

    @Suppress("DEPRECATION")
    fun ligarWifi() {
        if (Build.VERSION.SDK_INT >= 29) act.startActivity(Intent(Settings.Panel.ACTION_WIFI))      // o Android 10+ não deixa o app ligar o Wi-Fi
        else wm.isWifiEnabled = true
    }

    /** O Android só devolve a lista de redes ao app com a Localização do celular ligada (exigência dele). */
    fun localizacaoLigada(): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else true
    }

    fun abrirAjusteLocalizacao() = act.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    // ------------------------------------------------------------------ 0) o celular já está na rede do scanner?
    /** Procura, entre as redes Wi-Fi já conectadas, uma onde o scanner responde (conexão manual pelas configurações). */
    @Suppress("DEPRECATION")
    fun procurarNasRedesAtuais(): Network? {
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            try {
                n.socketFactory.createSocket().use { s -> s.connect(InetSocketAddress(IP, PORTA), 1500) }
                return n
            } catch (_: Exception) { }
        }
        return null
    }

    // ------------------------------------------------------------------ 1) escanear
    @Suppress("DEPRECATION")
    fun escanear(aoTerminar: (List<String>) -> Unit) {
        pararEscaneamento()
        var entregue = false
        fun entregar() {
            if (entregue) return
            entregue = true
            pararEscaneamento()
            val lista = try { wm.scanResults.map { it.SSID to it.level } } catch (_: SecurityException) { emptyList() }
            aoTerminar(filtrarSsids(lista))
        }
        receptorScan = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { entregar() }
        }
        ctx.registerReceiver(receptorScan, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        val iniciou = try { wm.startScan() } catch (_: SecurityException) { false }
        // o Android limita a frequência dos escaneamentos: se ele não começou, vale a última lista que ele tem
        ui.postDelayed({ entregar() }, if (iniciou) 12_000 else 600)
    }

    fun pararEscaneamento() {
        receptorScan?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) { } }
        receptorScan = null
    }

    // ------------------------------------------------------------------ 2) conectar na rede escolhida
    fun conectar(ssid: String, aoRede: (Network) -> Unit, aoFalha: (String) -> Unit, aoPerder: () -> Unit) {
        liberar()
        if (Build.VERSION.SDK_INT >= 29) conectarAndroid10(ssid, aoRede, aoFalha, aoPerder) else conectarLegado(ssid, aoRede, aoFalha)
    }

    private fun conectarAndroid10(ssid: String, aoRede: (Network) -> Unit, aoFalha: (String) -> Unit, aoPerder: () -> Unit) {
        val espec = WifiNetworkSpecifier.Builder().setSsid(ssid).build()               // rede aberta (a do scanner não tem senha)
        val pedido = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)              // o Wi-Fi do scanner não tem internet
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .setNetworkSpecifier(espec)
        if (Build.VERSION.SDK_INT >= 31) pedido.setIncludeOtherUidNetworks(false)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { ui.post { aoRede(network) } }
            override fun onUnavailable() { ui.post { aoFalha("o Android não conseguiu conectar em $ssid (confirme na janela do Android ou chegue mais perto do scanner)") } }
            override fun onLost(network: Network) { ui.post { aoPerder() } }
        }
        callbackRede = cb
        cm.requestNetwork(pedido.build(), cb, 45_000)                                   // 45 s: tempo de tocar em "Conectar" na janela do Android
    }

    @Suppress("DEPRECATION")
    private fun conectarLegado(ssid: String, aoRede: (Network) -> Unit, aoFalha: (String) -> Unit) {
        val cfg = WifiConfiguration().apply {
            SSID = "\"$ssid\""; allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        }
        val existente = wm.configuredNetworks?.firstOrNull { it.SSID == cfg.SSID }
        val id = existente?.networkId ?: wm.addNetwork(cfg)
        if (id < 0) { aoFalha("o Android recusou criar a rede $ssid"); return }
        wm.disconnect(); wm.enableNetwork(id, true); wm.reconnect()
        val fim = System.currentTimeMillis() + 25_000
        val r = object : Runnable {
            override fun run() {
                val n = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                when {
                    n != null -> aoRede(n)
                    System.currentTimeMillis() > fim -> aoFalha("não conectou em $ssid em 25 s")
                    else -> ui.postDelayed(this, 1000)
                }
            }
        }
        tentativaLegado = r; ui.postDelayed(r, 2000)
    }

    /** Solta a rede (o Android desconecta do Wi-Fi do scanner) e cancela qualquer busca em andamento. */
    fun liberar() {
        pararEscaneamento()
        tentativaLegado?.let { ui.removeCallbacks(it) }; tentativaLegado = null
        callbackRede?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) { } }
        callbackRede = null
    }
}
