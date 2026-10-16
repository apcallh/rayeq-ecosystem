package app.rayeq.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.rayeq.data.AppSettings
import app.rayeq.data.Title
import app.rayeq.data.Tmdb

@Composable
fun ExploreScreen(settings: AppSettings, onOpenDetails: (Title) -> Unit) {
    var type by remember { mutableStateOf("tv") }
    var items by remember { mutableStateOf<List<Title>>(emptyList()) }

    LaunchedEffect(type) {
        items = Tmdb.fetchList("discover/$type", "sort_by=popularity.desc", forcedType = type)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = type == "tv", onClick = { type = "tv" }, label = { Text("مسلسلات") })
            FilterChip(selected = type == "movie", onClick = { type = "movie" }, label = { Text("أفلام") })
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(settings.gridColumns.coerceIn(2, 5)),
            contentPadding = PaddingValues(bottom = dockSpace()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.key }) { t ->
                PosterCard(
                    title = t,
                    showRating = settings.showRatings,
                    showTitle = settings.showPosterTitles,
                    onClick = { onOpenDetails(t) }
                )
            }
        }
    }
}
