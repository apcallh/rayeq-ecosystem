package app.rayeq.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rayeq.data.*

@Composable
fun DetailsScreen(
    title: Title,
    store: Store,
    onBack: () -> Unit,
    onPlay: (PlayRequest) -> Unit
) {
    var details by remember { mutableStateOf<TmdbDetails?>(null) }
    var selectedSeason by remember { mutableStateOf(1) }
    var selectedRange by remember { mutableStateOf(0) }
    var episodes by remember { mutableStateOf<List<TmdbEpisode>>(emptyList()) }
    var scrollRestored by remember { mutableStateOf(false) }

    val savedPos = store.listPos[title.key]
    val listState = rememberLazyListState()

    LaunchedEffect(title.key) {
        Presence.onTitleScreenOpened()
        val d = Tmdb.fetchDetails(title.type, title.id)
        details = d
        if (d != null && d.seasons.isNotEmpty()) {
            val avail = d.seasons.map { it.seasonNumber }
            selectedSeason = ListMemory.restoreSeason(
                saved = savedPos,
                lastWatchedSeason = null,
                lastWatchedAt = 0L,
                availableSeasons = avail
            )
        }
    }

    LaunchedEffect(title.id, selectedSeason, details) {
        val d = details ?: return@LaunchedEffect
        if (!title.isMovie) {
            val expectedCount = d.seasons.find { it.seasonNumber == selectedSeason }?.episodeCount ?: 0
            episodes = Tmdb.seasonEpisodes(title.id, selectedSeason, expectedCount)
        }
    }

    // الدرس المستفاد #21: موضع التمرير يُستعاد فقط بعد أن تحتوي القائمة على الفهرس المحفوظ
    LaunchedEffect(episodes.size, selectedSeason, selectedRange) {
        if (!scrollRestored &&
            ListMemory.canRestoreScroll(savedPos, selectedSeason, selectedRange, 0, episodes.size)
        ) {
            scrollRestored = true
            savedPos?.let { listState.scrollToItem(it.scrollIndex, it.scrollOffset) }
        }
    }

    val isFav = store.favourites.any { it.key == title.key }
    val pageEpisodes = remember(episodes, selectedRange) {
        val start = selectedRange * ListMemory.PAGE_SIZE
        episodes.drop(start).take(ListMemory.PAGE_SIZE)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, dockSpace()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowForward, contentDescription = "رجوع")
                }
                IconButton(onClick = { Library.toggleFavourite(title) }) {
                    Icon(
                        imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "المفضلة",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            Text(title.name, style = MaterialTheme.typography.headlineMedium)
            if (title.overview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(title.overview, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val d = details
                    onPlay(
                        PlayRequest(
                            title = d?.title ?: title,
                            season = if (title.isMovie) null else selectedSeason,
                            episode = if (title.isMovie) null else 1,
                            names = d?.searchNames ?: listOf(title.name),
                            seasonYears = d?.seasons?.mapNotNull { it.airYear }.orEmpty(),
                            seasonCount = d?.seasons?.size ?: 1,
                            seasonEpisodes = episodes.size.takeIf { it > 0 }
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("تشغيل", fontWeight = FontWeight.Bold)
            }
        }

        val d = details
        if (d != null && !title.isMovie && d.seasons.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(d.seasons) { s ->
                        FilterChip(
                            selected = selectedSeason == s.seasonNumber,
                            onClick = {
                                selectedSeason = s.seasonNumber
                                selectedRange = 0
                            },
                            label = { Text("الموسم ${ArabicDigits.toEasternArabic(s.seasonNumber.toString())}") }
                        )
                    }
                }
            }

            if (episodes.size > ListMemory.PAGE_SIZE) {
                val rangeCount = (episodes.size + ListMemory.PAGE_SIZE - 1) / ListMemory.PAGE_SIZE
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rangeCount) { rIdx ->
                            val fromEp = rIdx * ListMemory.PAGE_SIZE + 1
                            val toEp = minOf((rIdx + 1) * ListMemory.PAGE_SIZE, episodes.size)
                            FilterChip(
                                selected = selectedRange == rIdx,
                                onClick = { selectedRange = rIdx },
                                label = {
                                    Text(
                                        "${ArabicDigits.toEasternArabic(fromEp.toString())} - ${ArabicDigits.toEasternArabic(toEp.toString())}",
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }
                }
            }

            items(pageEpisodes, key = { "${it.seasonNumber}:${it.episodeNumber}" }) { ep ->
                val epKey = Library.epKey(title.key, ep.seasonNumber, ep.episodeNumber)
                val watched = epKey in store.watched
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onPlay(
                                PlayRequest(
                                    title = d.title,
                                    season = ep.seasonNumber,
                                    episode = ep.episodeNumber,
                                    names = d.searchNames,
                                    seasonYears = d.seasons.mapNotNull { it.airYear },
                                    seasonCount = d.seasons.size,
                                    seasonEpisodes = episodes.size
                                )
                            )
                        },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "الحلقة ${ArabicDigits.toEasternArabic(ep.episodeNumber.toString())} · ${ep.name}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            if (!ep.airDate.isNullOrBlank()) {
                                Text(ep.airDate, fontSize = 11.sp)
                            }
                        }
                        if (watched) {
                            Text("✓ شوهدت", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
