package com.tb.vpngate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fetches the raw VPN Gate server list.
 *
 * Deliberately returns the raw CSV rather than parsed servers: the repository caches the exact
 * bytes it received, so caching and parsing stay independent and the cache can be re-parsed by
 * a newer parser without a refetch.
 */
/** The seam the repository depends on, so it can be exercised without a network. */
interface FeedSource {
    suspend fun fetchCsv(): String
}

class VpnGateApi(
    private val client: OkHttpClient = defaultClient(),
    private val url: String = ENDPOINT,
) : FeedSource {
    override suspend fun fetchCsv(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("VPN Gate returned HTTP ${response.code}")
            }
            val body = response.body.readCapped(MAX_FEED_BYTES)

            // A 200 is not proof we got the feed. Captive portals and VPN Gate's own
            // rate-limit page both answer 200 with HTML, which would otherwise parse to an
            // empty list and look like "there are no servers".
            if (!VpnGateCsvParser.looksLikeFeed(body)) {
                throw IOException(
                    "Response was not the VPN Gate feed (rate limited, or a captive portal intercepted it)"
                )
            }
            body
        }
    }

    /**
     * Reads at most [limit] bytes instead of slurping the whole body.
     *
     * `body.string()` is unbounded, so a compromised endpoint, a broken mirror or a captive
     * portal serving a large file could exhaust memory. The real feed is ~1.3 MB.
     */
    private fun okhttp3.ResponseBody.readCapped(limit: Long): String {
        contentLength().let { declared ->
            if (declared > limit) throw IOException("Feed declares ${declared} bytes, over the ${limit} limit")
        }
        val sink = Buffer()
        source().use { source ->
            var total = 0L
            while (true) {
                val read = source.read(sink, COPY_CHUNK_BYTES)
                if (read == -1L) break
                total += read
                if (total > limit) throw IOException("Feed exceeded the ${limit} byte limit")
            }
        }
        return sink.readUtf8()
    }

    companion object {
        const val ENDPOINT = "https://www.vpngate.net/api/iphone/"

        /** ~6x the observed feed size: generous for growth, far below an OOM. */
        private const val MAX_FEED_BYTES = 8L * 1024 * 1024

        private const val COPY_CHUNK_BYTES = 64L * 1024

        private const val USER_AGENT = "VpnGateClient/1.0 (Android)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // The feed is ~1.3 MB and the origin is frequently slow, so the read budget is
            // generous relative to the connect budget.
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // OkHttp defaults this to true, which would follow a redirect down to cleartext.
            // targetSdk 37 already blocks cleartext so such a request fails rather than leaks,
            // but refusing the downgrade outright keeps that from being the only thing saving us.
            .followSslRedirects(false)
            .build()
    }
}
