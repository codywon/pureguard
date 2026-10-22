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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

class LocalDnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var packetJob: Job? = null

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
                    .setContentText("正在本地过滤域名请求")
                    .setSmallIcon(android.R.drawable.ic_secure)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("PureGuard DNS 隧道运行中")
                    .setContentText("正在本地过滤域名请求")
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
                // 仅路由虚拟 DNS 地址，保证正常短剧视频 CDN 流量直连不减速
                .addRoute("10.111.222.2", 32)

            vpnInterface = builder.establish()
            _isRunning.value = true

            packetJob = serviceScope.launch {
                runDnsInspectionLoop()
            }
        } catch (e: Exception) {
            _isRunning.value = false
            stopSelf()
        }
    }

    private fun runDnsInspectionLoop() {
        val descriptor = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(descriptor)
        val outputStream = FileOutputStream(descriptor)
        // 标准 DNS UDP 报文 MTU 通常不超过 1500，分配 4096 字节足够容纳全部 DNS 数据并减少内存占用
        val buffer = ByteArray(4096)

        try {
            while (serviceScope.isActive && _isRunning.value) {
                val length = inputStream.read(buffer)
                if (length > 0) {
                    val domain = extractDnsQueryDomain(buffer, length)
                    if (!domain.isNullOrBlank()) {
                        val blocked = isDomainBlocked?.invoke(domain) ?: false
                        onDomainInspected?.invoke(domain)
                        if (blocked) {
                            sendBlockedDnsResponse(outputStream, buffer, length)
                        }
                    }
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

    /**
     * 高性能解析 IPv4 UDP 53 端口的 DNS 查询报文，提取 QNAME 域名。
     * 使用 StringBuilder 原地解析与 US_ASCII 解码，杜绝频繁 GC 抖动。
     */
    fun extractDnsQueryDomain(rawPacket: ByteArray, length: Int): String? {
        // Fast-fail 极速前置检查
        if (length < 40) return null
        // 校验 IPv4
        val version = (rawPacket[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        // 校验 UDP 协议号 (17)
        if ((rawPacket[9].toInt() and 0xFF) != 17) return null

        val ipHeaderLength = (rawPacket[0].toInt() and 0x0F) * 4
        if (length < ipHeaderLength + 8 + 12) return null

        // 目的端口必须为 53 (DNS)
        val dstPort = ((rawPacket[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
            (rawPacket[ipHeaderLength + 3].toInt() and 0xFF)
        if (dstPort != 53) return null

        var offset = ipHeaderLength + 8 + 12 // IP 头 + UDP 头 + DNS 头
        val sb = StringBuilder(64)
        while (offset < length) {
            val labelLen = rawPacket[offset].toInt() and 0xFF
            if (labelLen == 0) break
            // RFC 1035: Label 长度最大为 63
            if (labelLen > 63) return null
            offset++
            if (offset + labelLen > length) return null

            if (sb.isNotEmpty()) {
                sb.append('.')
            }
            // 域名字符均为 ASCII，US_ASCII 比 UTF-8 更快且无多余内存消耗
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

            output.write(response)
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

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        var onDomainInspected: ((String) -> Unit)? = null
        var isDomainBlocked: ((String) -> Boolean)? = null
    }
}
