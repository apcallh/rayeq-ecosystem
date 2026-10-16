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
import app.rayeq.data.Store
import app.rayeq.data.Title

@Composable
fun LibraryScreen(settings: AppSettings, store: Store, onOpenDetails: (Title) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val items = if (tab == 0) store.favourites else store.bookmarks.values.map { it.title }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("المفضلة") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("قائمتي") })
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
                    showTitle = true,
                    onClick = { onOpenDetails(t) }
                )
            }
        }
    }
}
