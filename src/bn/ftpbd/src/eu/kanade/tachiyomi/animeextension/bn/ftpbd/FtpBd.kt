package eu.kanade.tachiyomi.animeextension.bn.ftpbd

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import extensions.utils.addEditTextPreference
import extensions.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder

class FtpBd : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "FtpBd"

    override val baseUrl = "https://server3.ftpbd.net"

    override val lang = "bn"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            key = "tmdb_api_key",
            title = "TMDb API Key",
            summary = "Enter TMDb API key for high quality images (Optional)",
            default = "",
        )
    }

    private val tmdbApiKey: String
        get() = preferences.getString("tmdb_api_key", "") ?: ""

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("https://server3.ftpbd.net/FTP-3/Hindi%20Movies/2025/")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("#fallback table tr, div.entry-content a").mapNotNull {
            val link = it.selectFirst("td.fb-n a") ?: if (it.tagName() == "a") it else null
            link?.let {
                val title = it.text().removeSuffix("/")
                if (isIgnored(title)) return@mapNotNull null
                SAnime.create().apply {
                    this.title = title
                    this.url = it.attr("abs:href")
                    this.thumbnail_url = "${this.url.let { u -> if (u.endsWith("/")) u else "$u/" }}a11.jpg"
                }
            }
        }
        return AnimesPage(animeList, false)
    }

    private fun isIgnored(text: String): Boolean {
        val ignored = listOf("Parent Directory", "modern browsers", "Name", "Last modified", "Size", "Description", "Index of")
        return ignored.any { text.contains(it, ignoreCase = true) }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.isNotBlank()) {
            val fuzzyQuery = query.replace(" ", ".")
            val servers = listOf(
                "https://server2.ftpbd.net" to "FTP-2",
                "https://server3.ftpbd.net" to "FTP-3",
                "https://server4.ftpbd.net" to "FTP-4",
                "https://server5.ftpbd.net" to "FTP-5"
            )

            val results = servers.map {
                withContext(Dispatchers.IO) {
                    async { searchOnServer(it.first, it.second, fuzzyQuery) }
                }
            }.awaitAll().flatten()

            AnimesPage(results, false)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private suspend fun searchOnServer(host: String, root: String, query: String): List<SAnime> {
        val url = "$host/_h5ai/public/index.php"
        val body = "{\"action\":\"get\",\"search\":{\"href\":\"/$root/\",\"pattern\":\"$query\",\"ignorecase\":true}}"
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        
        return try {
            val response = client.newCall(POST(url, headers, body)).awaitSuccess()
            val text = response.body.string()
            val regex = """"href":"([^"]+)"""".toRegex()
            regex.findAll(text).map {
                val rawPath = it.groupValues[1]
                val path = rawPath.replace("\", "/").replace("//+", "/")
                val cleanPath = path.removeSuffix("/")
                val title = URLDecoder.decode(cleanPath.substringAfterLast("/"), "UTF-8")
                
                SAnime.create().apply {
                    this.title = title
                    this.url = "$host$path"
                    this.thumbnail_url = "${this.url.let { u -> if (u.endsWith("/")) u else "$u/" }}a11.jpg"
                }
            }.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================== Details ===============================
    override fun animeDetailsParse(response: Response): SAnime {
        val anime = SAnime.create()
        val document = response.asJsoup()
        
        // Porting TMDb image enrichment
        val title = document.select("h1, h2, .movie-title").first()?.text() ?: ""
        anime.title = title
        
        val tmdbImg = getTmdbImageUrl(title)
        if (tmdbImg != null) {
            anime.thumbnail_url = tmdbImg
        }

        anime.description = document.select("p.storyline").text()
        anime.genre = document.select("div.ganre-wrapper a").joinToString { it.text() }
        
        return anime
    }

    private fun getTmdbImageUrl(title: String): String? {
        val key = tmdbApiKey
        if (key.isBlank()) return null
        
        val url = "https://api.themoviedb.org/3/search/multi?api_key=$key&query=${java.net.URLEncoder.encode(title, "UTF-8")}"
        return try {
            val response = client.newCall(GET(url)).execute()
            val json = response.body.string()
            val regex = """"poster_path":"([^"]+)"""".toRegex()
            regex.find(json)?.groupValues?.get(1)?.let { "https://image.tmdb.org/t/p/w500$it" }
        } catch (e: Exception) {
            null
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(GET(anime.url, headers)).awaitSuccess().asJsoup()
        val episodes = mutableListOf<SEpisode>()
        parseDirectoryRecursive(document, 4, episodes, mutableSetOf())
        return episodes
    }

    private suspend fun parseDirectoryRecursive(document: Document, depth: Int, episodes: MutableList<SEpisode>, visited: MutableSet<String>) {
        val currentUrl = document.location()
        if (!visited.add(currentUrl)) return

        val links = document.select("a[href]")
        for (link in links) {
            val href = link.attr("abs:href")
            val text = link.text()
            if (isIgnored(text) || href.contains("?")) continue
            
            if (href.endsWith(".mkv") || href.endsWith(".mp4") || href.endsWith(".avi")) {
                episodes.add(SEpisode.create().apply {
                    this.name = text
                    this.url = href
                    this.episode_number = -1f
                })
                // Optimization: stop recursion if video found in current dir
                // (though usually multiple files are together)
            } else if (depth > 0 && href.endsWith("/") && !href.contains("_h5ai")) {
                val subDoc = client.newCall(GET(href, headers)).awaitSuccess().asJsoup()
                parseDirectoryRecursive(subDoc, depth - 1, episodes, visited)
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return listOf(Video(episode.url, "Video", episode.url))
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()
}
