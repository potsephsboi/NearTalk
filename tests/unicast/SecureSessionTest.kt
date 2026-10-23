package com.example.neartalk

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private class Events : SecureSession.Listener {
    val peers = LinkedBlockingQueue<String>()
    val messages = LinkedBlockingQueue<String>()
    val reasons = LinkedBlockingQueue<String>()
    val ready = CountDownLatch(1)
    val closed = CountDownLatch(1)
    override fun onReady(peerName: String) { peers.add(peerName); ready.countDown() }
    override fun onMessage(text: String) { messages.add(text) }
    override fun onClosed(reason: String) { reasons.add(reason); closed.countDown() }
}

private class PairTest(
    serverRequests: Boolean = true,
    wrongId: Boolean = false,
    private val fault: String = ""
) : AutoCloseable {
    val aEvents = Events()
    val bEvents = Events()
    val a: SecureSession
    val b: SecureSession
    val publicKeys = LinkedBlockingQueue<String>()
    val confirmationHeld = CountDownLatch(1)
    val releaseConfirmation = CountDownLatch(1)
    private val sockets = ArrayList<Socket>()
    private val threads = ArrayList<Thread>()

    init {
        val left = sockets()
        val right = sockets()
        sockets.addAll(left.toList())
        sockets.addAll(right.toList())
        a = SecureSession(left.first, true, serverRequests, "a".repeat(32), "Phone A", aEvents)
        b = SecureSession(right.second, false, !serverRequests,
            if (wrongId) "b".repeat(32) else "a".repeat(32), "Phone B", bEvents)
        start { forward(left.second, right.first, true) }
        start { forward(right.first, left.second, false) }
        start { a.run() }
        start { b.run() }
    }

    private fun sockets(): Pair<Socket, Socket> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val accepted = server.accept()
        server.close()
        return Pair(accepted, client)
    }

    private fun start(block: () -> Unit) {
        val thread = Thread(block)
        thread.isDaemon = true
        threads.add(thread)
        thread.start()
    }

    private fun forward(source: Socket, destination: Socket, fromServer: Boolean) {
        try {
            val input = DataInputStream(source.getInputStream())
            val output = DataOutputStream(destination.getOutputStream())
            var index = 0
            while (true) {
                val size = input.readInt()
                check(size in 1..8192)
                val bytes = ByteArray(size)
                input.readFully(bytes)
                if (fromServer && index == 1) publicKeys.add(hex(bytes))
                if (fromServer && fault == "version" && index == 0) bytes[7] = 1
                if (fromServer && fault == "length" && index == 0) {
                    output.writeInt(Int.MAX_VALUE)
                    output.flush()
                    return
                }
                if (fromServer && fault == "pause-confirmation" && index == 2) {
                    confirmationHeld.countDown()
                    check(releaseConfirmation.await(10, TimeUnit.SECONDS))
                }
                if ((fromServer && fault == "tag" && index == 3) ||
                    (fromServer && fault == "confirmation" && index == 2) ||
                    (!fromServer && fault == "kem" && index == 1)) {
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                }
                output.writeInt(size)
                output.write(bytes)
                if (fromServer && fault == "replay" && index == 3) {
                    output.writeInt(size)
                    output.write(bytes)
                }
                output.flush()
                index++
            }
        } catch (_: Exception) {
        } finally {
            try { destination.shutdownOutput() } catch (_: IOException) { }
        }
    }

    fun awaitReady() {
        check(aEvents.ready.await(5, TimeUnit.SECONDS))
        check(bEvents.ready.await(5, TimeUnit.SECONDS))
        check(aEvents.peers.poll() == "Phone B")
        check(bEvents.peers.poll() == "Phone A")
    }

    override fun close() {
        a.close()
        b.close()
        releaseConfirmation.countDown()
        for (socket in sockets) socket.close()
        for (thread in threads) thread.join(2000)
        check(threads.none { it.isAlive }) { "A socket thread survived cancellation" }
    }
}

private fun expectFailure(block: () -> Unit) {
    var failed = false
    try { block() } catch (_: Exception) { failed = true }
    check(failed) { "Invalid operation was accepted" }
}

private fun hex(bytes: ByteArray): String {
    return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

private fun testVectors(file: String) {
    val vectors = Properties()
    File(file).inputStream().use { vectors.load(it) }
    fun bytes(name: String): ByteArray {
        val text = vectors.getProperty(name)
        return ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
    fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    val keys = NativeKem.keyPair(bytes("keygen.seed"))
    check(hash(keys[0]) == vectors.getProperty("keygen.public.sha256"))
    check(hash(keys[1]) == vectors.getProperty("keygen.private.sha256"))
    val encapsulated = NativeKem.encapsulate(bytes("encap.public"), bytes("encap.seed"))
    check(hash(encapsulated[0]) == vectors.getProperty("encap.ciphertext.sha256"))
    check(encapsulated[1].contentEquals(bytes("encap.secret")))
    check(NativeKem.decapsulate(bytes("decap.private"), bytes("decap.ciphertext"))
        .contentEquals(bytes("decap.secret")))
    expectFailure { NativeKem.keyPair(ByteArray(63)) }
    expectFailure { NativeKem.encapsulate(ByteArray(1184) { -1 }, ByteArray(32)) }
    expectFailure { NativeKem.decapsulate(ByteArray(2400), ByteArray(1087)) }
    println("PASS: NIST ML-KEM-768 vectors and native input validation")
}

fun main(args: Array<String>) {
    testVectors(args[0])
    val publicKeys = HashSet<String>()
    for (serverRequests in listOf(true, false)) {
        PairTest(serverRequests).use { pair ->
            pair.awaitReady()
            check(publicKeys.add(pair.publicKeys.poll())) { "ML-KEM public keys were reused" }
            pair.a.sendMessage("Γεια σου 👋")
            check(pair.bEvents.messages.poll(5, TimeUnit.SECONDS) == "Γεια σου 👋")
            pair.b.sendMessage("reply")
            check(pair.aEvents.messages.poll(5, TimeUnit.SECONDS) == "reply")
            pair.a.sendMessage("x".repeat(4096))
            check(pair.bEvents.messages.poll(5, TimeUnit.SECONDS)?.length == 4096)
            expectFailure { pair.a.sendMessage("x".repeat(4097)) }
            expectFailure { pair.a.sendMessage("  ") }
            for (i in 0..49) pair.a.sendMessage("message $i")
            for (i in 0..49) check(pair.bEvents.messages.poll(5, TimeUnit.SECONDS) == "message $i")
        }
    }
    println("PASS: automatic connection, either group owner, UTF-8, limits, ordered messages, fresh keys")
    PairTest(fault = "pause-confirmation").use { pair ->
        check(pair.confirmationHeld.await(5, TimeUnit.SECONDS))
        check(pair.bEvents.ready.count == 1L)
        expectFailure { pair.b.sendMessage("before key confirmation") }
        pair.releaseConfirmation.countDown()
        pair.awaitReady()
        pair.b.sendMessage("automatic reply")
        check(pair.aEvents.messages.poll(5, TimeUnit.SECONDS) == "automatic reply")
    }
    println("PASS: sending waits for machine key confirmation without human approval")
    PairTest(wrongId = true).use { pair ->
        check(pair.aEvents.closed.await(5, TimeUnit.SECONDS))
        check(pair.bEvents.closed.await(5, TimeUnit.SECONDS))
        check(pair.aEvents.ready.count == 1L && pair.bEvents.ready.count == 1L)
    }
    for (fault in listOf("version", "length", "kem")) {
        PairTest(fault = fault).use { pair ->
            check(pair.aEvents.closed.await(5, TimeUnit.SECONDS))
            check(pair.bEvents.closed.await(5, TimeUnit.SECONDS))
            check(pair.aEvents.ready.count == 1L && pair.bEvents.ready.count == 1L)
            if (fault == "version") {
                check(pair.bEvents.reasons.poll()?.contains("Update both phones") == true)
            }
        }
    }
    println("PASS: wrong request, protocol version, oversized frame, altered ML-KEM ciphertext")
    PairTest(fault = "confirmation").use { pair ->
        check(pair.bEvents.closed.await(5, TimeUnit.SECONDS))
        check(pair.bEvents.ready.count == 1L)
        check(pair.bEvents.messages.isEmpty())
    }
    println("PASS: invalid key confirmation never enables the receiving peer's chat")
    for (fault in listOf("tag", "replay")) {
        PairTest(fault = fault).use { pair ->
            pair.awaitReady()
            pair.a.sendMessage("test message")
            check(pair.bEvents.closed.await(5, TimeUnit.SECONDS))
            if (fault == "replay") check(pair.bEvents.messages.poll() == "test message")
            check(pair.bEvents.messages.isEmpty())
        }
    }
    println("PASS: tampered messages rejected; replay delivered only once; socket threads close")
}
