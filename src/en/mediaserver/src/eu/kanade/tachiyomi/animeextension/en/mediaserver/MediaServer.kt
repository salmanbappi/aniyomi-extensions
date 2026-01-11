package eu.kanade.tachiyomi.animeextension.en.mediaserver

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import uy.kohesive.injekt.injectLazy

class MediaServer : AnimeHttpSource() {

    override val name = "MediaServer"

    override val baseUrl = "http://103.225.94.27/mediaserver"

    override val lang = "en"

    override val supportsLatest = true

    override val id: Long = 3615736726452648083L

    private val json: Json by injectLazy()

    override fun popularAnimeRequest(page: Int): Request {
        // Using "Recent" or default index as popular. 
        // Test used "movies/english" but let's try broader if possible.
        // The site structure seems to be WordPress StreamTube.
        // ?orderby=views&order=DESC usually works for popular.
        return GET("$baseUrl/index.php/?orderby=views&order=DESC&paged=$page")
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("div.post-item")
        val animeList = elements.map { element ->
            SAnime.create().apply {
                val aTag = element.selectFirst("a.post-permalink")!!
                setUrlWithoutDomain(aTag.attr("href"))
                title = aTag.attr("title").ifEmpty { aTag.text() }
                thumbnail_url = element.selectFirst("img")?.attr("src")
            }
        }
        val hasNextPage = document.selectFirst("a.next") != null
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/index.php/?orderby=date&order=DESC&paged=$page")
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/index.php/?s=$query&paged=$page")
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("h1.post-title")?.text() ?: "Unknown"
            description = document.select("div.post-content").text()
            genre = document.select("div.categories a").joinToString { it.text() }
            status = SAnime.COMPLETED // Assuming movies mostly
            thumbnail_url = document.selectFirst("div.post-thumbnail img")?.attr("src")
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        // StreamTube structure: usually one "episode" per post for movies.
        // Sometimes series have multiple.
        val document = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        
        // Check for playlist/series
        // If not, it's a single video (Movie)
        val episode = SEpisode.create().apply {
            name = "Full Movie"
            episode_number = 1f
            url = response.request.url.toString() // We use the page URL and parse video in videoListParse
        }
        episodes.add(episode)
        
        return episodes
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        
        // 1. Try extracting from video-js tag (StreamTube native)
        document.select("video-js").forEach { videoTag ->
            val settingsJson = videoTag.attr("data-settings")
            if (settingsJson.isNotEmpty()) {
                try {
                    val settings = json.parseToJsonElement(settingsJson).jsonObject
                    val sources = settings["sources"]?.jsonArray
                    sources?.forEach { source ->
                        val src = source.jsonObject["src"]?.jsonPrimitive?.contentOrNull
                        val type = source.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                        if (src != null) {
                            videoList.add(Video(src, "Stream ($type)", src))
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        // 2. Try iframe embed
        if (videoList.isEmpty()) {
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains("embed")) {
                     // If it's an internal embed, we might need to recurse or it's just a wrapper.
                     // For now, let's treat it as a potential source if it looks like a file.
                }
            }
        }

        return videoList
    }
}
