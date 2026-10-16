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
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(settings: AppSettings, onOpenDetails: (Title) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Title>>(emptyList()) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(350L)
        // البحث الذكي أولاً بحد 6 ثوانٍ، ثم نتائج TMDB، ولا يستدعي أي إضافة أبداً (§14.5)
        results = Tmdb.searchWithSmartFirst(query)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("ابحث عن فيلم أو مسلسل أو ممثل…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(settings.gridColumns.coerceIn(2, 5)),
            contentPadding = PaddingValues(bottom = dockSpace()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(results, key = { it.key }) { t ->
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
