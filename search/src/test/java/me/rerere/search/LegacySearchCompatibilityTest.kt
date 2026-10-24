package me.rerere.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class LegacySearchCompatibilityTest {
    @Test fun `removed upstream search still decodes inside a legacy provider list`() {
        val data = """[{"type":"rikkahub","apiKey":"saved-secret","depth":"deep"},{"type":"tavily","apiKey":"other-secret"}]"""
        val options = Json.decodeFromString<List<SearchServiceOptions>>(data)
        assertEquals(2, options.size)
        assertEquals("saved-secret", (options[0] as SearchServiceOptions.RikkaHubOptions).apiKey)
        assertEquals("other-secret", (options[1] as SearchServiceOptions.TavilyOptions).apiKey)
        assertFalse(SearchServiceOptions.TYPES.containsKey(SearchServiceOptions.RikkaHubOptions::class))
        assertEquals(options, Json.decodeFromString<List<SearchServiceOptions>>(Json.encodeToString(options)))
    }

    @Test fun `legacy service fails locally without a network client or tool schema`() = runBlocking {
        val options = SearchServiceOptions.RikkaHubOptions(apiKey = "do-not-send")
        val service = SearchService.getService(options)
        assertNull(service.parameters(options))
        val result = service.search(buildJsonObject {}, SearchCommonOptions(), options)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("unavailable"))
    }
}
