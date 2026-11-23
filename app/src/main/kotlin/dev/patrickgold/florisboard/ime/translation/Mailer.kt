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

    fun send(
        @Suppress("UNUSED_PARAMETER") context: Context,
        subject: String,
        body: String,
        attachment: Attachment? = null,
    ): Boolean {
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
            io.expect("220")                             // server greeting
            io.cmd("EHLO yijian", "250")

            if (!implicitSsl) {
                io.cmd("STARTTLS", "220")
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(io.socket, host, port, true) as SSLSocket
                ssl.startHandshake()
                io.replaceSocket(ssl)
                io.cmd("EHLO yijian", "250")
            }

            io.cmd("AUTH LOGIN", "334")
            io.cmd(b64(user), "334")
            io.cmd(b64(pass), "235")                     // 235 = authentication successful
            io.cmd("MAIL FROM:<$user>", "250")
            io.cmd("RCPT TO:<$to>", "250")
            io.cmd("DATA", "354")

            val text = buildMessage(user, to, subject, body, attachment)
            io.writeRaw(escapeBody(text) + "\r\n.\r\n")
            io.expect("250")
            runCatching { io.cmd("QUIT", "221") }
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

    /**
     * SMTP dot-stuffing: a line starting with "." would terminate the DATA block, so such lines
     * get an extra dot. TTS/report bodies are base64 so this is mostly defensive.
     */
    private fun escapeBody(text: String): String =
        text.split("\r\n").joinToString("\r\n") { if (it.startsWith(".")) "." + it else it }

    private fun b64(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /**
     * Builds the MIME message. When [attachment] is present the body becomes multipart/mixed
     * (plain-text summary + base64 PDF), otherwise it stays a simple base64 text message.
     */
    private fun buildMessage(
        user: String,
        to: String,
        subject: String,
        body: String,
        attachment: Attachment? = null,
    ): String {
        val date = SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
        val encodedSubject = "=?UTF-8?B?" + b64(subject) + "?="
        val boundary = "----yijian" + System.nanoTime()

        fun encodeText(text: String): String =
            Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                .chunked(76).joinToString("\r\n")

        return buildString {
            appendLine("Date: $date")
            appendLine("From: =?UTF-8?B?${b64("译键")}?= <$user>")
            appendLine("To: <$to>")
            appendLine("Subject: $encodedSubject")
            appendLine("MIME-Version: 1.0")
            if (attachment == null) {
                appendLine("Content-Type: text/plain; charset=UTF-8")
                appendLine("Content-Transfer-Encoding: base64")
                appendLine()
                append(encodeText(body))
            } else {
                appendLine("Content-Type: multipart/mixed; boundary=\"$boundary\"")
                appendLine()
                appendLine("--$boundary")
                appendLine("Content-Type: text/plain; charset=UTF-8")
                appendLine("Content-Transfer-Encoding: base64")
                appendLine()
                appendLine(encodeText(body))
                appendLine()
                appendLine("--$boundary")
                appendLine("Content-Type: ${attachment.mimeType}; name=\"${attachment.fileName}\"")
                appendLine("Content-Transfer-Encoding: base64")
                appendLine("Content-Disposition: attachment; filename=\"${attachment.fileName}\"")
                appendLine()
                appendLine(
                    Base64.encodeToString(attachment.bytes, Base64.NO_WRAP).chunked(76).joinToString("\r\n")
                )
                appendLine()
                append("--$boundary--")
            }
        }
    }

    /** A file to attach to the report email. */
    data class Attachment(val fileName: String, val mimeType: String, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
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

        /** Sends a command and asserts the expected reply code prefix. */
        fun cmd(line: String, expectCode: String): String {
            writeRaw(line + "\r\n")
            val reply = read()
            if (!reply.startsWith(expectCode)) error("SMTP $reply")
            return reply
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
