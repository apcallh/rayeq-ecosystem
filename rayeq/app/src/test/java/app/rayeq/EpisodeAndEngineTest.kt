package app.rayeq

import app.rayeq.data.*
import com.lagradost.cloudstream3.TvType
import org.junit.Assert.*
import org.junit.Test

class EpisodeAndEngineTest {

    @Test
    fun testScoreTitleAnimeVsLiveAction() {
        val onePieceAnime = Title(
            id = 37854,
            type = "tv",
            name = "One Piece",
            originalName = "ワンピース",
            arabicName = "ون بيس",
            year = 1999,
            isAnime = true
        )
        val req = PlayRequest(
            title = onePieceAnime,
            season = 1,
            episode = 5,
            names = listOf("One Piece", "ون بيس"),
            seasonYears = listOf(1999)
        )

        // أنمي حقيقي (1999) يجتاز العتبة (88)
        val (animeScore, animeOk) = Engine.scoreTitle("انمي One Piece مترجم", 1999, TvType.Anime, req)
        assertTrue("Expected anime score >= 88, got $animeScore", animeOk)

        // نسخة واقعية Live Action (2023) تُرفض بشدة
        val (laScore, laOk) = Engine.scoreTitle("مسلسل One Piece Live Action", 2023, TvType.TvSeries, req)
        assertFalse("Expected live-action to be rejected, got $laScore", laOk)
    }

    @Test
    fun testEpisodeMatchMultiSeasonAndContinuousAnime() {
        val multiEps = listOf(
            EpCandidate("s1e5", season = 1, episode = 5),
            EpCandidate("s2e5", season = 2, episode = 5)
        )
        val pickedMulti = EpisodeMatch.choose(
            episodes = multiEps,
            targetSeason = 2,
            targetEpisode = 5,
            targetAbsEpisode = 17,
            tmdbSeasonCount = 2,
            tmdbSeasonEpisodes = 12
        )
        assertEquals("s2e5", pickedMulti?.data)

        // صفحة أنمي متصلة بموسم واحد ترقيمها يتجاوز عدد حلقات موسم TMDB ويصل للرقم المطلق
        val continuousEps = (1..30).map { EpCandidate("ep_$it", season = 1, episode = null, name = "الحلقة $it") }
        val pickedAbs = EpisodeMatch.choose(
            episodes = continuousEps,
            targetSeason = 2,
            targetEpisode = 3,
            targetAbsEpisode = 15,
            tmdbSeasonCount = 2,
            tmdbSeasonEpisodes = 12
        )
        assertEquals("ep_15", pickedAbs?.data)
    }
}
