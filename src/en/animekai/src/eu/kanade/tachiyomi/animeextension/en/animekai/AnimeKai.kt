package eu.kanade.tachiyomi.animeextension.en.animekai

import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme

class AnimeKai : ZoroTheme(
    "en",
    "AnimeKai",
    "https://animekai.to",
    hosterNames = listOf(
        "VidSrc",
        "MegaCloud",
    ),
) {
    // Generate a stable ID for the extension
    override val id = 4567890123456L 

    // Zoro clones usually use /v2 for AJAX
    override val ajaxRoute = "/v2"

    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers, BuildConfig.MEGACLOUD_API) }

    override fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "VidSrc", "MegaCloud" -> megaCloudExtractor.getVideosFromUrl(server.link, server.type, server.name)
            else -> emptyList()
        }
    }
}
