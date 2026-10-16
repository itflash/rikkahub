package me.rerere.rikkahub.data.export

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class HtmlInlinerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun write(root: File, path: String, text: String): File = File(root, path).apply { parentFile?.mkdirs(); writeText(text) }

    @Test fun `local CSS resolves assets relative to CSS and imports preserve media`() = runBlocking {
        val root = temp.newFolder("project")
        write(root, "index.html", """<link rel="stylesheet" href="css/style.css" media="screen"><a href="page2.html">next</a>""")
        write(root, "css/style.css", """@import "base.css" print; .hero{background:url(../img/a.png?v=1#x)}""")
        write(root, "css/base.css", """@import "style.css"; body{color:red}""")
        File(root, "img").mkdirs(); File(root, "img/a.png").writeBytes(byteArrayOf(1, 2, 3))
        val result = HtmlInliner(ResourceDownloader { error("no network") }).inline(root, "index.html")
        val doc = Jsoup.parse(result.html)
        assertTrue(doc.select("link[rel=stylesheet]").isEmpty())
        assertEquals("screen", doc.selectFirst("style")!!.attr("media"))
        assertTrue(result.html.contains("@media print"))
        assertTrue(result.html.contains("data:image/png;base64,AQID"))
        assertEquals("page2.html", doc.selectFirst("a")!!.attr("href"))
        assertTrue(result.failedUrls.isEmpty())
    }

    @Test fun `scripts are escaped and media srcset and inline styles become portable`() = runBlocking {
        val root = temp.newFolder("project")
        write(root, "index.html", """<script src="js/app.js" async data-test="yes"></script><img src="a.png" srcset="a.png 1x, b.png 2x"><video poster="b.png"></video><div style="background:url(a.png)"></div>""")
        write(root, "js/app.js", "const x='</script>';alert(x)")
        File(root, "a.png").writeBytes(byteArrayOf(1)); File(root, "b.png").writeBytes(byteArrayOf(2))
        val result = HtmlInliner(ResourceDownloader { null }).inline(root, "index.html")
        assertTrue(result.html.contains("<\\/script>"))
        val doc = Jsoup.parse(result.html)
        val script = doc.selectFirst("script")!!
        assertFalse(script.hasAttr("src")); assertFalse(script.hasAttr("async"))
        assertEquals("yes", script.attr("data-test"))
        val srcset = doc.selectFirst("img")!!.attr("srcset")
        assertTrue(srcset.contains("data:image/png;base64,AQ== 1x"))
        assertTrue(srcset.contains("data:image/png;base64,Ag== 2x"))
        assertTrue(doc.selectFirst("div")!!.attr("style").contains("data:image/png"))
    }

    @Test fun `remote resources cache successes failures and oversized downloads`() = runBlocking {
        val root = temp.newFolder("project")
        write(root, "index.html", """<img src="https://assets/good.png"><img src="https://assets/good.png"><img src="https://assets/missing"><img src="https://assets/large">""")
        val calls = mutableMapOf<String, Int>()
        val result = HtmlInliner(ResourceDownloader { url ->
            calls[url] = (calls[url] ?: 0) + 1
            when {
                url.endsWith("good.png") -> Downloaded(byteArrayOf(1), "image/png")
                url.endsWith("large") -> Downloaded(ByteArray(6 * 1024 * 1024), "image/png")
                else -> null
            }
        }).inline(root, "index.html")
        assertEquals(1, calls["https://assets/good.png"])
        assertEquals(listOf("https://assets/missing"), result.failedUrls)
        assertEquals(listOf("https://assets/large"), result.skippedForSize)
        assertTrue(result.html.contains("src=\"https://assets/missing\""))
        assertTrue(result.html.contains("src=\"https://assets/large\""))
    }

    @Test fun `remote stylesheet imports and fonts resolve from the remote URL`() = runBlocking {
        val root = temp.newFolder("project")
        write(root, "index.html", """<link rel="stylesheet" href="https://assets/css/style.css">""")
        val calls = mutableListOf<String>()
        val result = HtmlInliner(ResourceDownloader { url ->
            calls.add(url)
            when (url) {
                "https://assets/css/style.css" -> Downloaded("@import 'base.css'; @font-face{src:url(fonts/x.woff2)}".toByteArray(), "text/css")
                "https://assets/css/base.css" -> Downloaded("body{color:red}".toByteArray(), "text/css")
                "https://assets/css/fonts/x.woff2" -> Downloaded(byteArrayOf(3), "font/woff2")
                else -> null
            }
        }).inline(root, "index.html")
        assertTrue(result.html.contains("data:font/woff2;base64,Aw=="))
        assertTrue(result.failedUrls.isEmpty())
        assertEquals(3, calls.size)
    }

    @Test fun `traversal and symlinks never disclose files outside the project`() = runBlocking {
        val root = temp.newFolder("project")
        val secret = temp.newFile("secret.txt").apply { writeText("TOP_SECRET") }
        Files.createSymbolicLink(File(root, "alias.txt").toPath(), secret.toPath())
        write(root, "index.html", """<script src="../../secret.txt"></script><script src="alias.txt"></script>""")
        val result = HtmlInliner(ResourceDownloader { null }).inline(root, "index.html")
        assertFalse(result.html.contains("TOP_SECRET"))
        assertEquals(2, result.failedUrls.size)
    }

    @Test fun `total budget preserves excess references and ignored schemes`() = runBlocking {
        val root = temp.newFolder("project")
        write(root, "index.html", """<img src="a.png"><img src="b.png"><img src="data:image/png;base64,AQ=="><a href="#part">go</a>""")
        File(root, "a.png").writeBytes(ByteArray(600)); File(root, "b.png").writeBytes(ByteArray(600))
        val result = HtmlInliner(ResourceDownloader { null }, maxResourceBytes = 1000, maxTotalBytes = 1000).inline(root, "index.html")
        assertEquals(1, result.skippedForSize.size)
        assertTrue(result.html.contains("src=\"b.png\""))
        assertTrue(result.html.contains("href=\"#part\""))
    }
}
