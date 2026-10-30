package com.tb.vpngate.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * Covers the offline behaviour the app is expected to have: the cached list must survive a
 * failed refresh, and a failure must never present as "there are no servers".
 */
class ServerRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fixture(): String =
        requireNotNull(javaClass.getResourceAsStream("/vpngate_sample.csv")).bufferedReader().readText()

    private class FakeSource(
        var result: Result<String>,
        var calls: Int = 0,
    ) : FeedSource {
        override suspend fun fetchCsv(): String {
            calls++
            return result.getOrThrow()
        }
    }

    private fun cache() = ServerCache.inDirectory(tempFolder.newFolder())

    @Test
    fun `successful refresh populates servers and writes the cache`() = runTest {
        val cache = cache()
        val repo = ServerRepository(FakeSource(Result.success(fixture())), cache)

        repo.refresh()

        assertEquals(97, repo.state.value.servers.size)
        assertNull(repo.state.value.error)
        assertTrue(!repo.state.value.isStale)
        assertNotNull("cache should have been written", cache.read())
    }

    @Test
    fun `a failed refresh keeps the cached list and reports the error`() = runTest {
        val cache = cache()
        cache.write(fixture())

        val repo = ServerRepository(FakeSource(Result.failure(IOException("no network"))), cache)
        repo.loadCache()
        assertEquals("cache should load first", 97, repo.state.value.servers.size)
        assertTrue("cache-sourced data is stale until a refresh succeeds", repo.state.value.isStale)

        repo.refresh()

        // The important property: an error never blanks good data.
        assertEquals(97, repo.state.value.servers.size)
        assertEquals("no network", repo.state.value.error)
        assertTrue(!repo.state.value.isRefreshing)
    }

    @Test
    fun `a failed refresh with no cache yields an error and an empty list`() = runTest {
        val repo = ServerRepository(FakeSource(Result.failure(IOException("boom"))), cache())
        repo.loadCache()
        repo.refresh()

        assertEquals(0, repo.state.value.servers.size)
        assertEquals("boom", repo.state.value.error)
    }

    @Test
    fun `loadCache is a no-op once servers are present`() = runTest {
        val cache = cache()
        cache.write(fixture())
        val source = FakeSource(Result.success(fixture()))
        val repo = ServerRepository(source, cache)

        repo.refresh()
        repo.loadCache()

        assertEquals(97, repo.state.value.servers.size)
        assertTrue("fresh network data must not be marked stale", !repo.state.value.isStale)
    }

    @Test
    fun `a feed that parses to nothing does not destroy the existing list`() = runTest {
        val cache = cache()
        cache.write(fixture())
        val repo = ServerRepository(FakeSource(Result.success("*vpn_servers\n*\n")), cache)
        repo.loadCache()

        repo.refresh()

        assertEquals(97, repo.state.value.servers.size)
        assertNotNull(repo.state.value.error)
    }

    @Test
    fun `cache survives a round trip and records a timestamp`() = runTest {
        val cache = cache()
        cache.write("*vpn_servers\nhello\n*")
        val cached = requireNotNull(cache.read())
        assertTrue(cached.csv.contains("hello"))
        assertTrue(cached.fetchedAtMillis > 0)
    }

    @Test
    fun `reading an absent cache returns null rather than throwing`() = runTest {
        assertNull(cache().read())
    }

    @Test
    fun `write reports success so a silent cache failure cannot go unnoticed`() = runTest {
        assertTrue(cache().write("*vpn_servers\nrow\n*"))
    }

    @Test
    fun `write creates the cache directory if it is missing`() = runTest {
        val missing = java.io.File(tempFolder.root, "not-created-yet")
        val c = ServerCache.inDirectory(missing)
        assertTrue(c.write("*vpn_servers\nrow\n*"))
        assertNotNull(c.read())
    }
}
