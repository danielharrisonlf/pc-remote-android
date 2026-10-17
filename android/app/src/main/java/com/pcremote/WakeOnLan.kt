package com.pcremote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN (`wol.v1`) Magic Packet builder and UDP broadcaster.
 *
 * A Magic Packet consists of 6 bytes of `0xFF` followed by 16 repetitions of the
 * 6-byte target MAC address (total 102 bytes), sent over UDP ports 9 and 7 to
 * the local broadcast (`255.255.255.255`), the `/24` directed broadcast derived
 * from the saved PC IPv4 host, and the saved host itself.
 */
internal object WakeOnLan {
  private val PORTS = intArrayOf(9, 7)

  fun parseMac(mac: String): ByteArray? {
    val clean = mac.trim().lowercase().replace('-', ':')
    if (!Protocol.macPattern.matches(clean)) return null
    val parts = clean.split(':')
    if (parts.size != 6) return null
    val bytes = ByteArray(6)
    for (i in 0 until 6) {
      val v = parts[i].toIntOrNull(16) ?: return null
      bytes[i] = v.toByte()
    }
    if (bytes.all { it == 0.toByte() } || (bytes[0].toInt() and 0x01) != 0) return null
    return bytes
  }

  fun buildMagicPacket(mac: String): ByteArray? {
    val macBytes = parseMac(mac) ?: return null
    val packet = ByteArray(6 + 16 * 6)
    for (i in 0 until 6) {
      packet[i] = 0xFF.toByte()
    }
    for (rep in 0 until 16) {
      System.arraycopy(macBytes, 0, packet, 6 + rep * 6, 6)
    }
    return packet
  }

  fun broadcastTargets(host: String): List<String> {
    val targets = LinkedHashSet<String>()
    targets.add("255.255.255.255")
    val trimmed = host.trim()
    val ipv4Parts = trimmed.split('.')
    if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() in 0..255 }) {
      targets.add("${ipv4Parts[0]}.${ipv4Parts[1]}.${ipv4Parts[2]}.255")
      targets.add(trimmed)
    } else if (trimmed.isNotEmpty()) {
      targets.add(trimmed)
    }
    return targets.toList()
  }

  /** Blocking UDP transmission; call from an IO/background thread. Returns null on success or an error string. */
  fun send(macs: List<String>, host: String): String? {
    val packets = macs.mapNotNull(::buildMagicPacket)
    if (packets.isEmpty()) return "Endereço MAC do PC ainda não sincronizado. Conecte o PC ligado uma vez."
    val targets = broadcastTargets(host)
    var sentAny = false
    return try {
      DatagramSocket().use { socket ->
        socket.broadcast = true
        socket.soTimeout = 1500
        for (target in targets) {
          val address = try {
            InetAddress.getByName(target)
          } catch (_: Exception) {
            continue
          }
          for (packetBytes in packets) {
            for (port in PORTS) {
              try {
                socket.send(DatagramPacket(packetBytes, packetBytes.size, address, port))
                sentAny = true
              } catch (_: Exception) {
              }
            }
          }
        }
      }
      if (sentAny) null else "Não foi possível enviar o pacote Wake-on-LAN na rede local."
    } catch (_: Exception) {
      if (sentAny) null else "Não foi possível enviar o pacote Wake-on-LAN na rede local."
    }
  }
}
