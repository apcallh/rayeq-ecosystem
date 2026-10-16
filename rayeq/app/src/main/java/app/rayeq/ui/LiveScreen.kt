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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rayeq.data.Live
import app.rayeq.data.LiveItem
import app.rayeq.data.LiveProviderSection
import app.rayeq.data.SourceConfig

@Composable
fun LiveScreen(onPlayLive: (LiveItem) -> Unit) {
    val srcVersion by SourceConfig.version.collectAsState()
    var sections by remember { mutableStateOf<List<LiveProviderSection>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(srcVersion, searchQuery) {
        sections = if (searchQuery.isBlank()) {
            Live.loadSections()
        } else {
            Live.searchLive(searchQuery)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentPadding = PaddingValues(bottom = dockSpace()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("بحث في القنوات المباشرة…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        items(sections, key = { it.providerName }) { sec ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(sec.providerName)
                for ((rowName, channels) in sec.rows) {
                    Text(rowName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(channels, key = { it.url }) { ch ->
                            // بديل الصورة: بلاطة بالحرف الأول، مثلاً قناة اسمها «الجزيرة» تظهر «ج» (§12)
                            Card(
                                modifier = Modifier
                                    .width(120.dp)
                                    .height(84.dp)
                                    .clickable { onPlayLive(ch) },
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = ch.fallbackInitial,
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(ch.name, fontSize = 11.sp, maxLines = 1)
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
