package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class LocalDnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var packetJob: Job? = null
    private var forwardJob: Job? = null
    private var cleanupJob: Job? = null

    private var forwardSocket: DatagramSocket? = null
    private val outputStreamLock = Any()
    private val pendingQueries = ConcurrentHashMap<Int, PendingQuery>()

    private data class PendingQuery(
        val clientIp: ByteArray,
        val clientPort: Int,
        val ipId: Int,
        val timestamp: Long
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnTunnel()
                return START_NOT_STICKY
            }
            else -> {
                startVpnTunnel()
                return START_STICKY
            }
        }
    }

    private fun startVpnTunnel() {
        if (vpnInterface != null) return
        try {
            createNotificationChannel()
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("PureGuard DNS 隧道运行中")
                    .setContentText("正在本地极速过滤广告请求")
                    .setSmallIcon(android.R.drawable.ic_secure)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("PureGuard DNS 隧道运行中")
                    .setContentText("正在本地极速过滤广告请求")
                    .setSmallIcon(android.R.drawable.ic_secure)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()
            }

            startForeground(NOTIFICATION_ID, notification)

            val builder = Builder()
                .setSession("PureGuard-LocalDNS")
                .setMtu(1500)
                .addAddress("10.111.222.1", 24)
                .addDnsServer("10.111.222.2")
                // 仅拦截虚拟 DNS 地址 (10.111.222.2:53)，短剧视频大流直接走物理网卡畅通不减速
                .addRoute("10.111.222.2", 32)

            vpnInterface = builder.establish()
            _isRunning.value = true

            // 初始化受保护的上游转发套接字（使用 protect 绕过 VPN 虚拟路由）
            try {
                val sock = DatagramSocket()
                protect(sock)
                forwardSocket = sock
            } catch (_: Exception) {
            }

            val descriptor = vpnInterface?.fileDescriptor
            if (descriptor != null) {
                val inputStream = FileInputStream(descriptor)
                val outputStream = FileOutputStream(descriptor)

                // 启动上游 DNS 响应监听协程
                forwardJob = serviceScope.launch {
                    runUpstreamReceiveLoop(outputStream)
                }

                // 启动定期清理过期查询协程
                cleanupJob = serviceScope.launch {
                    while (isActive && _isRunning.value) {
                        delay(4000L)
                        val now = System.currentTimeMillis()
                        pendingQueries.entries.removeIf { now - it.value.timestamp > 3500L }
                    }
                }

                // 启动 VPN 虚拟网卡读取主循环
                packetJob = serviceScope.launch {
                    runDnsInspectionLoop(inputStream, outputStream)
                }
            }
        } catch (e: Exception) {
            _isRunning.value = false
            stopSelf()
        }
    }

    private fun runDnsInspectionLoop(inputStream: FileInputStream, outputStream: FileOutputStream) {
        val buffer = ByteArray(4096)
        val upstreamAddr = try {
            InetAddress.getByName(UPSTREAM_PRIMARY_DNS)
        } catch (_: Exception) {
            null
        }

        try {
            while (serviceScope.isActive && _isRunning.value) {
                val length = inputStream.read(buffer)
                if (length <= 0) continue

                val ipHeaderLength = (buffer[0].toInt() and 0x0F) * 4
                if (length < ipHeaderLength + 8 + 12) continue

                // 校验 IPv4 (4) 与 UDP 协议号 (17)
                val version = (buffer[0].toInt() ushr 4) and 0x0F
                val protocol = buffer[9].toInt() and 0xFF
                if (version != 4 || protocol != 17) continue

                // 目的端口必须为 53 (DNS)
                val dstPort = ((buffer[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                    (buffer[ipHeaderLength + 3].toInt() and 0xFF)
                if (dstPort != 53) continue

                val domain = extractDnsQueryDomain(buffer, length)
                if (domain.isNullOrBlank()) continue

                val blocked = isDomainBlocked?.invoke(domain) ?: false
                onDomainInspected?.invoke(domain)

                if (blocked) {
                    // 广告域名：直接发送即时 NXDOMAIN 响应秒级阻断
                    sendBlockedDnsResponse(outputStream, buffer, length)
                } else {
                    // 正常短剧/业务域名：透明高速转发至公共 DNS 绝不丢包
                    forwardQueryToUpstream(buffer, length, ipHeaderLength, upstreamAddr)
                }
            }
        } catch (_: Exception) {
            // Stream closed on stop
        } finally {
            try {
                inputStream.close()
                outputStream.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun forwardQueryToUpstream(
        rawPacket: ByteArray,
        length: Int,
        ipHeaderLength: Int,
        upstreamAddr: InetAddress?
    ) {
        val sock = forwardSocket ?: return
        val targetAddr = upstreamAddr ?: return

        val dnsOffset = ipHeaderLength + 8
        val dnsLen = length - dnsOffset
        if (dnsLen < 12) return

        val txId = ((rawPacket[dnsOffset].toInt() and 0xFF) shl 8) or (rawPacket[dnsOffset + 1].toInt() and 0xFF)
        val clientIp = rawPacket.copyOfRange(12, 16)
        val clientPort = ((rawPacket[ipHeaderLength].toInt() and 0xFF) shl 8) or (rawPacket[ipHeaderLength + 1].toInt() and 0xFF)
        val ipId = ((rawPacket[4].toInt() and 0xFF) shl 8) or (rawPacket[5].toInt() and 0xFF)

        pendingQueries[txId] = PendingQuery(
            clientIp = clientIp,
            clientPort = clientPort,
            ipId = ipId,
            timestamp = System.currentTimeMillis()
        )

        try {
            val packet = DatagramPacket(rawPacket, dnsOffset, dnsLen, targetAddr, 53)
            sock.send(packet)
        } catch (_: Exception) {
            pendingQueries.remove(txId)
        }
    }

    private fun runUpstreamReceiveLoop(outputStream: FileOutputStream) {
        val sock = forwardSocket ?: return
        val receiveBuffer = ByteArray(4096)
        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)

        while (serviceScope.isActive && _isRunning.value) {
            try {
                sock.receive(packet)
                val responseLen = packet.length
                if (responseLen < 12) continue

                val txId = ((receiveBuffer[0].toInt() and 0xFF) shl 8) or (receiveBuffer[1].toInt() and 0xFF)
                val pending = pendingQueries.remove(txId) ?: continue

                // 组装完整 IPv4 + UDP 响应报文回写虚拟网卡
                val totalLen = 20 + 8 + responseLen
                val responsePacket = ByteArray(totalLen)

                // 1. IP Header
                responsePacket[0] = 0x45.toByte()
                responsePacket[1] = 0x00
                responsePacket[2] = ((totalLen ushr 8) and 0xFF).toByte()
                responsePacket[3] = (totalLen and 0xFF).toByte()
                responsePacket[4] = ((pending.ipId ushr 8) and 0xFF).toByte()
                responsePacket[5] = (pending.ipId and 0xFF).toByte()
                responsePacket[6] = 0x40.toByte() // Don't Fragment
                responsePacket[7] = 0x00
                responsePacket[8] = 64 // TTL
                responsePacket[9] = 17 // UDP
                responsePacket[10] = 0
                responsePacket[11] = 0
                // 源 IP：虚拟 DNS (10.111.222.2)
                responsePacket[12] = 10
                responsePacket[13] = 111
                responsePacket[14] = 222.toByte()
                responsePacket[15] = 2
                // 目的 IP：客户端 IP
                System.arraycopy(pending.clientIp, 0, responsePacket, 16, 4)

                val ipChecksum = computeIpChecksum(responsePacket, 20)
                responsePacket[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
                responsePacket[11] = (ipChecksum and 0xFF).toByte()

                // 2. UDP Header
                val udpOffset = 20
                // 源端口 53
                responsePacket[udpOffset] = 0
                responsePacket[udpOffset + 1] = 53
                // 目的端口：原客户端端口
                responsePacket[udpOffset + 2] = ((pending.clientPort ushr 8) and 0xFF).toByte()
                responsePacket[udpOffset + 3] = (pending.clientPort and 0xFF).toByte()
                val udpLen = 8 + responseLen
                responsePacket[udpOffset + 4] = ((udpLen ushr 8) and 0xFF).toByte()
                responsePacket[udpOffset + 5] = (udpLen and 0xFF).toByte()
                responsePacket[udpOffset + 6] = 0 // Checksum 0
                responsePacket[udpOffset + 7] = 0

                // 3. DNS Response Payload
                System.arraycopy(receiveBuffer, 0, responsePacket, 28, responseLen)

                synchronized(outputStreamLock) {
                    outputStream.write(responsePacket)
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 高性能解析 IPv4 UDP 53 端口的 DNS 查询报文，提取 QNAME 域名。
     * 使用 StringBuilder 原地解析与 US_ASCII 解码，杜绝频繁 GC 抖动。
     */
    fun extractDnsQueryDomain(rawPacket: ByteArray, length: Int): String? {
        if (length < 40) return null
        val version = (rawPacket[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        if ((rawPacket[9].toInt() and 0xFF) != 17) return null

        val ipHeaderLength = (rawPacket[0].toInt() and 0x0F) * 4
        if (length < ipHeaderLength + 8 + 12) return null

        val dstPort = ((rawPacket[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
            (rawPacket[ipHeaderLength + 3].toInt() and 0xFF)
        if (dstPort != 53) return null

        var offset = ipHeaderLength + 8 + 12
        val sb = StringBuilder(64)
        while (offset < length) {
            val labelLen = rawPacket[offset].toInt() and 0xFF
            if (labelLen == 0) break
            if (labelLen > 63) return null
            offset++
            if (offset + labelLen > length) return null

            if (sb.isNotEmpty()) {
                sb.append('.')
            }
            sb.append(String(rawPacket, offset, labelLen, java.nio.charset.StandardCharsets.US_ASCII))
            offset += labelLen
        }
        return if (sb.isNotEmpty()) sb.toString() else null
    }

    /**
     * 对被拦截的广告域名直接发送即时 NXDOMAIN DNS 响应，
     * 避免播放器/SDK 网络请求长时间挂起导致黑屏死等倒计时
     */
    private fun sendBlockedDnsResponse(output: FileOutputStream, rawPacket: ByteArray, length: Int) {
        try {
            val ipHeaderLength = (rawPacket[0].toInt() and 0x0F) * 4
            val questionEnd = findDnsQuestionEnd(rawPacket, ipHeaderLength + 8 + 12, length)
            if (questionEnd <= 0) return

            val dnsLength = questionEnd - (ipHeaderLength + 8)
            val totalLength = ipHeaderLength + 8 + dnsLength
            val response = ByteArray(totalLength)
            System.arraycopy(rawPacket, 0, response, 0, totalLength)

            // 1. Swap IP addresses
            for (i in 0 until 4) {
                val temp = response[12 + i]
                response[12 + i] = response[16 + i]
                response[16 + i] = temp
            }
            // Set IP Total Length
            response[2] = ((totalLength ushr 8) and 0xFF).toByte()
            response[3] = (totalLength and 0xFF).toByte()
            // Recalculate IP Checksum
            response[10] = 0
            response[11] = 0
            val ipChecksum = computeIpChecksum(response, ipHeaderLength)
            response[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
            response[11] = (ipChecksum and 0xFF).toByte()

            // 2. Swap UDP Ports
            val srcPort0 = response[ipHeaderLength]
            val srcPort1 = response[ipHeaderLength + 1]
            response[ipHeaderLength] = response[ipHeaderLength + 2]
            response[ipHeaderLength + 1] = response[ipHeaderLength + 3]
            response[ipHeaderLength + 2] = srcPort0
            response[ipHeaderLength + 3] = srcPort1

            // Set UDP Length & Zero Checksum
            val udpLength = 8 + dnsLength
            response[ipHeaderLength + 4] = ((udpLength ushr 8) and 0xFF).toByte()
            response[ipHeaderLength + 5] = (udpLength and 0xFF).toByte()
            response[ipHeaderLength + 6] = 0
            response[ipHeaderLength + 7] = 0

            // 3. DNS Header: Set Response Flag and NXDOMAIN (0x8183)
            val dnsOffset = ipHeaderLength + 8
            response[dnsOffset + 2] = 0x81.toByte()
            response[dnsOffset + 3] = 0x83.toByte()
            response[dnsOffset + 6] = 0
            response[dnsOffset + 7] = 0
            response[dnsOffset + 8] = 0
            response[dnsOffset + 9] = 0
            response[dnsOffset + 10] = 0
            response[dnsOffset + 11] = 0

            synchronized(outputStreamLock) {
                output.write(response)
            }
        } catch (_: Exception) {
        }
    }

    private fun findDnsQuestionEnd(raw: ByteArray, startOffset: Int, maxLen: Int): Int {
        var offset = startOffset
        while (offset < maxLen) {
            val len = raw[offset].toInt() and 0xFF
            if (len == 0) {
                return offset + 1 + 4
            }
            offset += 1 + len
        }
        return -1
    }

    private fun computeIpChecksum(header: ByteArray, length: Int): Int {
        var sum = 0
        var i = 0
        while (i < length) {
            val word = ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.inv()) and 0xFFFF
    }

    private fun stopVpnTunnel() {
        _isRunning.value = false
        packetJob?.cancel()
        forwardJob?.cancel()
        cleanupJob?.cancel()
        try {
            forwardSocket?.close()
        } catch (_: Exception) {
        }
        forwardSocket = null
        pendingQueries.clear()

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopVpnTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PureGuard 本地 DNS 过滤服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.example.vpn.START"
        const val ACTION_STOP = "com.example.vpn.STOP"
        private const val CHANNEL_ID = "pureguard_vpn_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UPSTREAM_PRIMARY_DNS = "223.5.5.5"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        var onDomainInspected: ((String) -> Unit)? = null
        var isDomainBlocked: ((String) -> Boolean)? = null
    }
}
