package app.rayeq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rayeq.data.*
import coil3.compose.AsyncImage

@Composable
fun HomeScreen(
    settings: AppSettings,
    store: Store,
    onOpenDetails: (Title) -> Unit,
    onPlayTitle: (Title, Int?, Int?) -> Unit
) {
    var trending by remember { mutableStateOf<List<Title>>(emptyList()) }
    var popularMovies by remember { mutableStateOf<List<Title>>(emptyList()) }
    var popularTv by remember { mutableStateOf<List<Title>>(emptyList()) }
    var selectedChip by remember { mutableStateOf(settings.homeSection) }

    LaunchedEffect(selectedChip) {
        val path = when (selectedChip) {
            "tv" -> "trending/tv/week"
            "movie" -> "trending/movie/week"
            else -> "trending/all/week"
        }
        trending = Tmdb.fetchList(path)
        popularMovies = Tmdb.fetchList("movie/popular", forcedType = "movie")
        popularTv = Tmdb.fetchList("tv/popular", forcedType = "tv")
    }

    val visibleSections = remember(settings.homeOrder, settings.homeHidden) {
        HomeSections.visibleSections(settings.homeOrder, settings.homeHidden)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = dockSpace()),
        verticalArrangement = Arrangement.spacedBy(28.dp) // إيقاع الأقسام 28dp (§14.1)
    ) {
        // الشريط العلوي العائم مع chips الأنواع الدائمة (§14.3)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "رايق",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chips = listOf(
                        "all" to "الكل",
                        "tv" to "مسلسلات",
                        "movie" to "أفلام",
                        "anime" to "أنمي"
                    )
                    for ((key, lbl) in chips) {
                        FilterChip(
                            selected = selectedChip == key,
                            onClick = {
                                selectedChip = key
                                Prefs.update { it.copy(homeSection = key) }
                            },
                            label = { Text(lbl, fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        for (sec in visibleSections) {
            when (sec) {
                "hero" -> {
                    val heroTitle = trending.firstOrNull()
                    if (heroTitle != null) {
                        item(key = "hero") {
                            // الدرس المستفاد #19 و#20: ارتفاع يلتف حول المحتوى وقص الغسلة داخل البطل
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { onOpenDetails(heroTitle) }
                            ) {
                                if (!heroTitle.backdropUrl.isNullOrBlank() || !heroTitle.posterUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = heroTitle.backdropUrl ?: heroTitle.posterUrl,
                                        contentDescription = heroTitle.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(340.dp)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(340.dp)
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color.Transparent,
                                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                                )
                                            )
                                        )
                                )
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = heroTitle.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = Color.White
                                    )
                                    if (heroTitle.overview.isNotBlank()) {
                                        Text(
                                            text = heroTitle.overview,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            color = Color.White.copy(alpha = 0.85f)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(
                                            onClick = { onPlayTitle(heroTitle, 1, 1) },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text("تشغيل الآن", fontWeight = FontWeight.Bold)
                                        }
                                        OutlinedButton(
                                            onClick = { onOpenDetails(heroTitle) },
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text("التفاصيل")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "continue" -> {
                    if (store.history.isNotEmpty()) {
                        item(key = "continue") {
                            Column {
                                SectionHeader("أكمل المشاهدة", store.history.size)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(store.history, key = { it.title.key }) { prog ->
                                        PosterCard(
                                            title = prog.title,
                                            showRating = settings.showRatings,
                                            showTitle = true,
                                            onClick = { onPlayTitle(prog.title, prog.season, prog.episode) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                "top10" -> {
                    if (trending.isNotEmpty()) {
                        item(key = "top10") {
                            Column {
                                SectionHeader("الأفضل ١٠", 10)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(trending.take(10), key = { it.key }) { item ->
                                        PosterCard(
                                            title = item,
                                            showRating = settings.showRatings,
                                            showTitle = settings.showPosterTitles,
                                            onClick = { onOpenDetails(item) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                "rows1" -> {
                    if (popularTv.isNotEmpty()) {
                        item(key = "rows1") {
                            Column {
                                SectionHeader("مسلسلات شائعة", popularTv.size)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(popularTv, key = { it.key }) { item ->
                                        PosterCard(
                                            title = item,
                                            showRating = settings.showRatings,
                                            showTitle = settings.showPosterTitles,
                                            onClick = { onOpenDetails(item) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                "rows2" -> {
                    if (popularMovies.isNotEmpty()) {
                        item(key = "rows2") {
                            Column {
                                SectionHeader("أفلام شائعة", popularMovies.size)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(popularMovies, key = { it.key }) { item ->
                                        PosterCard(
                                            title = item,
                                            showRating = settings.showRatings,
                                            showTitle = settings.showPosterTitles,
                                            onClick = { onOpenDetails(item) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
