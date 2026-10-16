package me.rerere.rikkahub.data.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class OkHttpResourceDownloader(client: OkHttpClient, private val maxBytes: Long = 5L * 1024 * 1024) : ResourceDownloader {
    private val client = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    override suspend fun download(url: String): Downloaded? = withContext(Dispatchers.IO) {
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36").build())
        try {
            call.await().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body
                if (body.contentLength() > maxBytes) throw ResourceTooLargeException()
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size().toLong() + count > maxBytes) throw ResourceTooLargeException()
                        output.write(buffer, 0, count)
                    }
                }
                Downloaded(output.toByteArray(), body.contentType()?.toString())
            }
        } catch (e: CancellationException) { call.cancel(); throw e
        } catch (e: ResourceTooLargeException) { throw e
        } catch (_: Exception) { null }
    }
}
