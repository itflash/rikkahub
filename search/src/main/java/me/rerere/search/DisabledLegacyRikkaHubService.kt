package me.rerere.search

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema

/** Compatibility only. Never contacts the upstream backend or transfers saved credentials. */
internal object DisabledLegacyRikkaHubService : SearchService<SearchServiceOptions.RikkaHubOptions> {
    override val name: String = "RikkaHub (legacy, unavailable)"
    override fun parameters(options: SearchServiceOptions.RikkaHubOptions): InputSchema? = null
    override fun scrapingParameters(options: SearchServiceOptions.RikkaHubOptions): InputSchema? = null
    @Composable override fun Description() = Unit

    private fun unavailable() = IllegalStateException("This upstream search service is unavailable in this fork. Select another search provider.")

    override suspend fun search(params: JsonObject, commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.RikkaHubOptions): Result<SearchResult> = Result.failure(unavailable())

    override suspend fun scrape(params: JsonObject, commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.RikkaHubOptions): Result<ScrapedResult> = Result.failure(unavailable())
}
