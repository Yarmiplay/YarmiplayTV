package com.yarmiplaytv.relay

import com.yarmiplaytv.syncplay.ServerTlsFormat
import com.yarmiplaytv.syncplay.YarmiplaySession
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** HTTP to the Syncplay server's relay: the server ends a read when no data arrived for 60 s. */
internal fun relayHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .dns(SessionDns)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(75, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .build()

/**
 * Resolves the session's host name to the address the Syncplay connection reached ([YarmiplaySession.resolveTo]),
 * e.g. the server's LAN address at home, where the name leads to the router. HTTPS still verifies the certificate
 * against the name; every other name resolves as usual. The relay, the media servers (the host's Jellyfin on the
 * Syncplay port is on that name) and images use it.
 */
object SessionDns : Dns {
    @Volatile private var override: Pair<String, String>? = null

    fun use(session: YarmiplaySession?) {
        override = session?.resolveTo?.let { address ->
            session.host.takeIf { it.isNotEmpty() && !ServerTlsFormat.isIpLiteral(it) }?.let { it.lowercase() to address }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val o = override
        if (o != null && o.first == hostname.lowercase()) return listOf(InetAddress.getByName(o.second))
        return Dns.SYSTEM.lookup(hostname)
    }

    /** [url] on the address its host name resolves to here, for the player, which can't be given this lookup. */
    fun direct(url: String): String {
        val (host, address) = override ?: return url
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.host == host } ?: return url
        return parsed.newBuilder().host(address).build().toString()
    }
}

/** Runs the call; cancelling the coroutine cancels it, also while its body is read. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
