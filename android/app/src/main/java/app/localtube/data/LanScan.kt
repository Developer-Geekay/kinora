package app.localtube.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** Looks for computers on the local network that answer on the SMB port (445). Only touches this network's own addresses. */
object LanScan {
    /** This device's own address on the home network, e.g. "192.168.100.37", or null if it isn't on one. */
    fun myAddress(): Pair<Inet4Address, Int>? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { ni -> ni.interfaceAddresses.map { ni to it } }
            .firstOrNull { (_, a) -> (a.address as? Inet4Address)?.isSiteLocalAddress == true }
            ?.let { (_, a) -> (a.address as Inet4Address) to a.networkPrefixLength.toInt() }
    }.getOrNull()

    suspend fun findSmbHosts(): List<String> = withContext(Dispatchers.IO) {
        val (me, prefix) = myAddress() ?: return@withContext emptyList()
        val bytes = me.address
        // scan the device's /24 (a bigger network is scanned only for the /24 it sits in: fast, and what a home has)
        val base = "${bytes[0].toInt() and 255}.${bytes[1].toInt() and 255}.${bytes[2].toInt() and 255}."
        val own = bytes[3].toInt() and 255
        val gate = Semaphore(48)
        coroutineScope {
            (1..254).filter { it != own }.map { i ->
                async {
                    gate.withPermit {
                        val host = base + i
                        runCatching { Socket().use { it.connect(InetSocketAddress(host, 445), 700); host } }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull().also { if (prefix < 0) Unit }
        }.sortedBy { it.substringAfterLast('.').toInt() }
    }
}
