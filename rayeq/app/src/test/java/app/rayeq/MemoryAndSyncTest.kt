package app.rayeq

import app.rayeq.data.*
import org.junit.Assert.*
import org.junit.Test

class MemoryAndSyncTest {

    @Test
    fun testListMemoryRestoreAndScrollGuard() {
        val saved = ListPos(season = 2, range = 1, tab = 0, scrollIndex = 12, scrollOffset = 40, at = 5000L)
        assertEquals(2, ListMemory.restoreSeason(saved, lastWatchedSeason = 1, lastWatchedAt = 3000L, availableSeasons = listOf(1, 2, 3)))
        // إذا شاهد المستخدم بعد الحفظ حلقة من موسم آخر يُفتح ذلك الموسم
        assertEquals(3, ListMemory.restoreSeason(saved, lastWatchedSeason = 3, lastWatchedAt = 6000L, availableSeasons = listOf(1, 2, 3)))

        // لا يُستعاد التمرير قبل اكتمال تحميل عناصر القائمة
        assertFalse(ListMemory.canRestoreScroll(saved, currentSeason = 2, currentRange = 1, currentTab = 0, loadedItemCount = 0))
        assertTrue(ListMemory.canRestoreScroll(saved, currentSeason = 2, currentRange = 1, currentTab = 0, loadedItemCount = 40))
    }

    @Test
    fun testLinkFeedTtlAndCloudSyncMerge() {
        val now = 1_000_000L
        assertTrue(LinkFeed.reusable(completedAtMs = now - 60_000L, hasLinks = true, isRunning = false, nowMs = now))
        assertFalse(LinkFeed.reusable(completedAtMs = now - 16 * 60_000L, hasLinks = true, isRunning = false, nowMs = now))
        assertFalse(LinkFeed.reusable(completedAtMs = now - 100_000L, hasLinks = false, isRunning = false, nowMs = now))

        val t1 = Title(id = 1, type = "movie", name = "Movie A")
        val t2 = Title(id = 2, type = "tv", name = "Show B")
        val local = SyncBlob(
            favourites = listOf(t1),
            stickyPicks = mapOf("tv:2" to StickyPick("SrcA", "host1", 720, "Host1", at = 1000L)),
            settings = AppSettings(palette = "ink", settingsAt = 1000L),
            settingsAt = 1000L
        )
        val remote = SyncBlob(
            favourites = listOf(t2),
            stickyPicks = mapOf("tv:2" to StickyPick("SrcB", "host2", 1080, "Host2", at = 2000L)),
            settings = AppSettings(palette = "emerald", settingsAt = 2500L),
            settingsAt = 2500L
        )
        val merged = CloudSync.mergeBlobs(local, remote)
        assertEquals(2, merged.favourites.size)
        assertEquals("SrcB", merged.stickyPicks["tv:2"]?.source)
        assertEquals("emerald", merged.settings.palette)
    }
}
