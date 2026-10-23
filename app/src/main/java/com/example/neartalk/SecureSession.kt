package com.example.neartalk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class SecureSession(
    private val socket: Socket,
    private val server: Boolean,
    private val requester: Boolean,
    private val requestId: String,
    private val localName: String,
    private val listener: Listener
) {
    interface Listener {
        fun onReady(peerName: String)
        fun onMessage(text: String)
        fun onClosed(reason: String)
    }

    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())
    private val writeLock = Any()
    @Volatile private var ready = false
    @Volatile private var closed = false
    private var sendKey = ByteArray(0)
    private var receiveKey = ByteArray(0)
    private var transcript = ByteArray(0)
    private var sendCounter = 0L
    private var receiveCounter = 0L

    fun run() {
        var reason = "Peer disconnected."
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 20_000
            handshake()
            while (!closed) {
                val record = readEncrypted()
                if (record[0] != MESSAGE) throw IOException("Unexpected secure record")
                val bytes = record.copyOfRange(1, record.size)
                val text = bytes.toString(Charsets.UTF_8)
                if (bytes.size > MAX_MESSAGE_BYTES || text.isBlank() ||
                    !text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
                    throw IOException("Invalid message")
                }
                listener.onMessage(text)
            }
        } catch (_: GeneralSecurityException) {
            reason = "Cryptographic verification failed. Disconnected."
        } catch (_: java.net.SocketTimeoutException) {
            reason = "Secure handshake timed out."
        } catch (_: java.io.EOFException) {
            reason = "Peer disconnected."
        } catch (e: Exception) {
            reason = "Connection ended: ${e.message ?: "I/O error"}"
        } catch (_: LinkageError) {
            reason = "Native crypto is missing. Rebuild and reinstall the APK."
        } finally {
            close()
            synchronized(writeLock) {
                sendKey.fill(0)
                receiveKey.fill(0)
            }
            listener.onClosed(reason)
        }
    }

    private fun handshake() {
        val ownHello = makeHello()
        writePacket(ownHello)
        val peerHello = readPacket(512)
        val peer = DataInputStream(ByteArrayInputStream(peerHello))
        if (peer.readInt() != MAGIC) throw IOException("Not a NearTalk connection")
        if (peer.readInt() != VERSION) {
            throw IOException("Incompatible NearTalk version. Update both phones.")
        }
        if (peer.readBoolean() == server || peer.readBoolean() == requester ||
            peer.readUTF() != requestId) {
            throw IOException("Wrong NearTalk peer")
        }
        val peerName = peer.readUTF()
        if (peerName.isBlank() || peerName.length > 64 || peer.available() != 0) {
            throw IOException("Invalid peer name")
        }

        val publicKey: ByteArray
        val ciphertext: ByteArray
        val secret: ByteArray
        if (server) {
            val seed = randomBytes(64)
            val keys: Array<ByteArray>
            try {
                keys = NativeKem.keyPair(seed)
            } finally {
                seed.fill(0)
            }
            publicKey = keys[0]
            try {
                writePacket(publicKey)
                ciphertext = readExactPacket(1088)
                secret = NativeKem.decapsulate(keys[1], ciphertext)
            } finally {
                keys[1].fill(0)
            }
        } else {
            publicKey = readExactPacket(1184)
            val seed = randomBytes(32)
            val result: Array<ByteArray>
            try {
                result = NativeKem.encapsulate(publicKey, seed)
            } finally {
                seed.fill(0)
            }
            ciphertext = result[0]
            secret = result[1]
        }

        try {
            if (!server) writePacket(ciphertext)
            val hash = MessageDigest.getInstance("SHA-256")
            val serverHello = if (server) ownHello else peerHello
            val clientHello = if (server) peerHello else ownHello
            for (part in arrayOf(serverHello, clientHello, publicKey, ciphertext)) {
                hash.update(ByteBuffer.allocate(4).putInt(part.size).array())
                hash.update(part)
            }
            transcript = hash.digest()
            val extracted = hmac(transcript, secret)
            try {
                val serverKey = expand(extracted, "server to client")
                val clientKey = expand(extracted, "client to server")
                sendKey = if (server) serverKey else clientKey
                receiveKey = if (server) clientKey else serverKey
            } finally {
                extracted.fill(0)
            }
        } finally {
            secret.fill(0)
        }

        // Check matching keys automatically; this does not verify a person's identity.
        writeEncrypted(byteArrayOf(KEY_CONFIRM) + transcript)
        val confirmation = readEncrypted()
        if (!MessageDigest.isEqual(confirmation, byteArrayOf(KEY_CONFIRM) + transcript)) {
            throw GeneralSecurityException("Key confirmation failed")
        }
        socket.soTimeout = 0
        ready = true
        listener.onReady(peerName)
    }

    private fun makeHello(): ByteArray {
        val buffer = ByteArrayOutputStream()
        val hello = DataOutputStream(buffer)
        hello.writeInt(MAGIC)
        hello.writeInt(VERSION)
        hello.writeBoolean(server)
        hello.writeBoolean(requester)
        hello.writeUTF(requestId)
        hello.writeUTF(localName.take(64))
        return buffer.toByteArray()
    }

    fun sendMessage(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(text.isNotBlank() && bytes.size <= MAX_MESSAGE_BYTES)
        if (!ready || closed) throw IOException("Encrypted connection is not ready")
        writeEncrypted(byteArrayOf(MESSAGE) + bytes)
    }

    private fun writeEncrypted(plaintext: ByteArray) {
        synchronized(writeLock) {
            if (closed || sendCounter >= MAX_RECORDS) throw IOException("Session ended")
            val counter = sendCounter
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sendKey, "AES"),
                GCMParameterSpec(128, nonce(counter)))
            cipher.updateAAD(transcript)
            cipher.updateAAD(ByteBuffer.allocate(8).putLong(counter).array())
            val encrypted = cipher.doFinal(plaintext)
            val packet = ByteBuffer.allocate(8 + encrypted.size)
            packet.putLong(counter).put(encrypted)
            sendCounter++
            writePacket(packet.array())
        }
    }

    private fun readEncrypted(): ByteArray {
        val packet = readPacket(MAX_MESSAGE_BYTES + 25)
        if (packet.size < 25 || receiveCounter >= MAX_RECORDS) throw IOException("Invalid record length")
        val counter = ByteBuffer.wrap(packet, 0, 8).long
        if (counter != receiveCounter) throw GeneralSecurityException("Unexpected sequence number")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(receiveKey, "AES"),
            GCMParameterSpec(128, nonce(counter)))
        cipher.updateAAD(transcript)
        cipher.updateAAD(ByteBuffer.allocate(8).putLong(counter).array())
        val plaintext = cipher.doFinal(packet, 8, packet.size - 8)
        receiveCounter++
        return plaintext
    }

    private fun nonce(counter: Long): ByteArray {
        return ByteBuffer.allocate(12).putInt(0).putLong(counter).array()
    }

    private fun writePacket(bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun readPacket(maxBytes: Int): ByteArray {
        val size = input.readInt()
        if (size < 1 || size > maxBytes) throw IOException("Invalid packet length")
        val result = ByteArray(size)
        input.readFully(result)
        return result
    }

    private fun readExactPacket(size: Int): ByteArray {
        val result = readPacket(size)
        if (result.size != size) throw IOException("Invalid handshake length")
        return result
    }

    fun close() {
        closed = true
        try { socket.close() } catch (_: IOException) { }
    }

    companion object {
        const val MAX_MESSAGE_BYTES = 4096
        private const val MAGIC = 0x4e544b31
        private const val VERSION = 2
        private const val MAX_RECORDS = 1_000_000L
        private const val KEY_CONFIRM: Byte = 1
        private const val MESSAGE: Byte = 3

        private fun randomBytes(size: Int): ByteArray {
            val bytes = ByteArray(size)
            SecureRandom().nextBytes(bytes)
            return bytes
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        // HKDF-Expand: each output is 32 bytes, so only its first block is needed.
        private fun expand(key: ByteArray, purpose: String): ByteArray {
            val info = "NearTalk unicast v2: $purpose".toByteArray(Charsets.UTF_8)
            return hmac(key, info + byteArrayOf(1))
        }
    }
}
