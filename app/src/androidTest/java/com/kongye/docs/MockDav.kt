package com.kongye.docs

import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Local, synthetic WebDAV fixture. Never contacts a user's server. */
class MockDav : AutoCloseable {
    private val server=ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort
    val files=ConcurrentHashMap<String,ByteArray>()
    val tamper=AtomicBoolean(false)
    val deletes=AtomicInteger(0)
    val rejectDelete=AtomicBoolean(false)
    private val running=AtomicBoolean(true)
    private val worker=thread(isDaemon=true,name="Synthetic-DAV") {
        while(running.get()) {
            try { server.accept().use { respond(it) } } catch(error: Exception) { if(running.get()) throw error }
        }
    }
    private fun respond(socket: Socket) {
        socket.soTimeout=10_000
        val input=socket.getInputStream()
        fun line(): String {
            val buffer=java.io.ByteArrayOutputStream()
            while(true) { val value=input.read(); if(value<0 || value==10) break; if(value!=13) buffer.write(value) }
            return buffer.toString("UTF-8")
        }
        val request=line().split(' ')
        val headers=mutableMapOf<String,String>()
        while(true) { val line=line(); if(line.isEmpty()) break; headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim() }
        val body=ByteArray(headers["content-length"]?.toIntOrNull() ?: 0)
        var offset=0
        while(offset<body.size) { val count=input.read(body,offset,body.size-offset); check(count>0); offset+=count }
        val path=URLDecoder.decode(request[1],"UTF-8").trimEnd('/')
        val name=path.substringAfterLast('/')
        var code=200
        val data=when(request[0]) {
            "MKCOL" -> { code=405; byteArrayOf() }
            "PUT" -> { files[name]=body; code=201; byteArrayOf() }
            "GET" -> if(tamper.get()) "CORRUPTED".toByteArray() else files[name] ?: byteArrayOf()
            "DELETE" -> { if(rejectDelete.get()) code=403 else { files.remove(name); deletes.incrementAndGet() }; byteArrayOf() }
            "PROPFIND" -> {
                code=207
                ("<d:multistatus xmlns:d=\"DAV:\">"+files.entries.joinToString("") { (name,bytes) ->
                    "<d:response><d:href>/backup/$name</d:href><d:propstat><d:prop><d:getcontentlength>${bytes.size}</d:getcontentlength></d:prop></d:propstat></d:response>"
                }+"</d:multistatus>").toByteArray()
            }
            else -> error("Unexpected request")
        }
        socket.getOutputStream().apply {
            write("HTTP/1.1 $code OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\nContent-Type: application/json; charset=utf-8\r\n\r\n".toByteArray())
            write(data); flush()
        }
    }
    override fun close() { running.set(false); server.close(); worker.join(2000) }
}
