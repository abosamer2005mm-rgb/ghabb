package com.ghadd.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * VPN محلي خفيف: يوجّه عنوان DNS وهمي فقط إلى النفق، ثم يمرّر الاستعلامات
 * إلى DNS عائلي (Cloudflare Family). باقي حركة الإنترنت لا تمر عبر النفق إطلاقاً.
 */
class GhaddVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.ghadd.app.STOP"
        const val FAKE_DNS = "10.111.0.2"
        val UPSTREAMS = listOf("1.1.1.3", "1.0.0.3")
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        val pfd = Builder()
            .setSession("Ghadd")
            .addAddress("10.111.0.1", 32)
            .addDnsServer(FAKE_DNS)
            .addRoute(FAKE_DNS, 32)
            .setMtu(1500)
            .establish() ?: return
        tun = pfd
        running = true
        Prefs.setVpnOn(this, true)
        worker = Thread { loop(pfd) }.also { it.start() }
    }

    private fun stopVpn() {
        running = false
        Prefs.setVpnOn(this, false)
        worker?.interrupt()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        stopSelf()
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { running = false; pool.shutdownNow(); super.onDestroy() }

    private fun loop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n > 0) {
                    val pkt = buf.copyOf(n)
                    pool.execute { handle(pkt, output) }
                }
            }
        } catch (_: Exception) { }
    }

    private fun handle(pkt: ByteArray, out: FileOutputStream) {
        try {
            if (pkt.size < 28 || (pkt[0].toInt() shr 4) != 4) return
            val ihl = (pkt[0].toInt() and 0x0F) * 4
            if (pkt[9].toInt() != 17 || pkt.size < ihl + 8) return       // UDP فقط
            val srcPort = u16(pkt, ihl)
            if (u16(pkt, ihl + 2) != 53) return
            val udpLen = u16(pkt, ihl + 4)
            val end = minOf(pkt.size, ihl + udpLen)
            val query = pkt.copyOfRange(ihl + 8, end)

            val name = DnsParser.qname(query)
            val resp: ByteArray = if (name != null && Blocklist.isBlockedHost(name)) {
                Prefs.countBlock(this)
                DnsParser.nxdomain(query)
            } else {
                forward(query)
            } ?: return

            val packet = buildUdp(
                srcIp = pkt.copyOfRange(16, 20), dstIp = pkt.copyOfRange(12, 16),
                srcPort = 53, dstPort = srcPort, payload = resp
            )
            synchronized(out) { out.write(packet) }
        } catch (_: Exception) { }
    }

    private fun forward(q: ByteArray): ByteArray? {
        for (up in UPSTREAMS) {
            try {
                DatagramSocket().use { s ->
                    protect(s)                       // تجاوز النفق لتفادي الحلقة
                    s.soTimeout = 3000
                    s.send(DatagramPacket(q, q.size, InetAddress.getByName(up), 53))
                    val buf = ByteArray(4096)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val r = buf.copyOf(p.length)
                    if (DnsParser.looksBlocked(r)) Prefs.countBlock(this)
                    return r
                }
            } catch (_: Exception) { }
        }
        return null
    }

    private fun buildUdp(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val b = ByteBuffer.allocate(total)
        b.put(0x45.toByte()); b.put(0.toByte())
        b.putShort(total.toShort()); b.putShort(0); b.putShort(0x4000.toShort())
        b.put(64.toByte()); b.put(17.toByte()); b.putShort(0)
        b.put(srcIp); b.put(dstIp)
        val arr = b.array()
        val cs = checksum(arr, 0, 20)
        arr[10] = (cs shr 8).toByte(); arr[11] = cs.toByte()
        b.putShort(srcPort.toShort()); b.putShort(dstPort.toShort())
        b.putShort((8 + payload.size).toShort()); b.putShort(0)
        b.put(payload)
        return arr
    }

    private fun checksum(d: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len) { sum += ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF); i += 2 }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}

fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

object DnsParser {
    fun qname(q: ByteArray): String? {
        if (q.size < 13) return null
        var pos = 12
        val parts = ArrayList<String>()
        while (pos < q.size) {
            val len = q[pos].toInt() and 0xFF
            if (len == 0) break
            if (len >= 0xC0 || pos + 1 + len > q.size) return null
            parts.add(String(q, pos + 1, len, Charsets.US_ASCII))
            pos += len + 1
        }
        return parts.joinToString(".")
    }

    private fun questionEnd(q: ByteArray): Int {
        var pos = 12
        while (pos < q.size && q[pos].toInt() != 0) pos += (q[pos].toInt() and 0xFF) + 1
        return pos + 5
    }

    fun nxdomain(q: ByteArray): ByteArray? {
        val end = questionEnd(q)
        if (end > q.size) return null
        val r = q.copyOf(end)
        r[2] = 0x81.toByte(); r[3] = 0x83.toByte()          // QR+RD ، RA+NXDOMAIN
        for (i in 6..11) r[i] = 0
        return r
    }

    /** الـ DNS العائلي يردّ 0.0.0.0 للمواقع المحجوبة */
    fun looksBlocked(r: ByteArray): Boolean {
        if (r.size < 20 || u16(r, 6) < 1 || (r[3].toInt() and 0x0F) != 0) return false
        return (r.size - 4 until r.size).all { r[it].toInt() == 0 }
    }
}
