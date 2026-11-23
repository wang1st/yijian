/*
 * Copyright (C) 2026 The yijian Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.translation

import android.content.Context
import android.util.Base64
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal SMTP client (plain socket + STARTTLS/SSL). The Android runtime has no javax.mail, and
 * pulling a mail library in just to send one text report is not worth it.
 *
 * Supports what QQ/163/Gmail SMTP need: AUTH LOGIN over TLS, UTF-8 subject and body.
 */
object Mailer {
    @Suppress("unused")
    private val prefs by FlorisPreferenceStore

    /** Last failure reason, null after a successful send. Shown to the user. */
    @Volatile
    var lastError: String? = null
        private set

    fun send(@Suppress("UNUSED_PARAMETER") context: Context, subject: String, body: String): Boolean {
        val to = prefs.teacher.emailTo.get().trim()
        val host = prefs.teacher.smtpHost.get().trim()
        val port = prefs.teacher.smtpPort.get()
        val user = prefs.teacher.smtpUser.get().trim()
        val pass = prefs.teacher.smtpPassword.get()

        if (to.isBlank() || host.isBlank() || user.isBlank() || pass.isBlank()) {
            lastError = "邮件未配置（需要收件人、SMTP 服务器、账号、授权码）"
            return false
        }

        return try {
            val implicitSsl = port == 465
            var socket: Socket = Socket()
            socket.connect(InetSocketAddress(host, port), 20000)
            socket.soTimeout = 30000
            if (implicitSsl) {
                socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, host, port, true)
            }
            val io = SmtpIo(socket)
            io.read()                                   // greeting
            io.cmd("EHLO yijian")

            if (!implicitSsl) {
                io.cmd("STARTTLS")
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(io.socket, host, port, true)
                io.replaceSocket(ssl)
                io.cmd("EHLO yijian")
            }

            io.cmd("AUTH LOGIN")
            io.cmd(b64(user))
            io.cmd(b64(pass))
            io.cmd("MAIL FROM:<$user>")
            io.cmd("RCPT TO:<$to>")
            io.cmd("DATA")

            val text = buildMessage(user, to, subject, body)
            io.writeRaw(text + "\r\n.\r\n")
            io.expect("250")
            runCatching { io.cmd("QUIT") }
            io.close()
            lastError = null
            true
        } catch (e: Exception) {
            lastError = friendlier(e)
            flogError { "yijian mailer: ${e.message}" }
            false
        }
    }

    private fun friendlier(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("535") -> "账号或授权码不对（535）"
            m.contains("530") -> "服务器要求先登录（530）"
            m.contains("5.7") -> "被服务器拒绝，可能需要开启 SMTP 服务"
            e is java.net.UnknownHostException -> "SMTP 服务器地址不对"
            e is java.net.SocketTimeoutException -> "SMTP 连接超时（检查端口 465/587）"
            e is javax.net.ssl.SSLException -> "SSL 失败（465 用 SSL，587 用 STARTTLS）"
            else -> m.ifBlank { e.javaClass.simpleName }
        }
    }

    private fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun buildMessage(user: String, to: String, subject: String, body: String): String {
        val date = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
        val encodedSubject = "=?UTF-8?B?" + b64(subject) + "?="
        // base64 body keeps Chinese safe regardless of line length rules
        val encodedBody = Base64.encodeToString(body.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val bodyLines = encodedBody.chunked(76).joinToString("\r\n")
        return buildString {
            appendLine("Date: $date")
            appendLine("From: 译键 <$user>")
            appendLine("To: <$to>")
            appendLine("Subject: $encodedSubject")
            appendLine("MIME-Version: 1.0")
            appendLine("Content-Type: text/plain; charset=UTF-8")
            appendLine("Content-Transfer-Encoding: base64")
            appendLine()
            append(bodyLines)
        }
    }

    /** Tiny line-oriented SMTP transport. */
    private class SmtpIo(var socket: Socket) {
        private var reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private var writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)

        fun replaceSocket(newSocket: Socket) {
            socket = newSocket
            reader = BufferedReader(InputStreamReader(newSocket.getInputStream(), Charsets.UTF_8))
            writer = OutputStreamWriter(newSocket.getOutputStream(), Charsets.UTF_8)
        }

        /** Reads a (possibly multi-line) SMTP reply. */
        fun read(): String {
            val sb = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                sb.append(line).append('\n')
                if (line.length < 4 || line[3] != '-') break
            }
            return sb.toString()
        }

        fun expect(code: String) {
            val reply = read()
            if (!reply.startsWith(code)) error("SMTP $reply")
        }

        fun cmd(line: String): String {
            writeRaw(line + "\r\n")
            return read()
        }

        fun writeRaw(s: String) {
            writer.write(s)
            writer.flush()
        }

        fun close() {
            runCatching { socket.close() }
        }
    }
}
