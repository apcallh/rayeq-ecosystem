package app.rayeq

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rayeq.auth.Auth
import app.rayeq.auth.Gate
import app.rayeq.data.*
import app.rayeq.player.PlayerActivity
import app.rayeq.ui.*
import app.rayeq.ui.icons.RayeqIcons
import com.lagradost.cloudstream3.CommonActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CommonActivity.setActivityInstance(this)
        setContent {
            val settings by Prefs.settings.collectAsState()
            val store by Library.store.collectAsState()
            val authState by Auth.state.collectAsState()
            val appCfg by AppInfo.config.collectAsState()

            RayeqTheme(settings = settings) {
                AmbientBackground(style = settings.backgroundStyle) {
                    when {
                        appCfg.maintenance -> {
                            MaintenanceScreen(config = appCfg)
                        }
                        authState.gate == Gate.SIGNED_OUT -> {
                            WelcomeScreen(authState = authState)
                        }
                        authState.gate != Gate.APPROVED -> {
                            GateScreen(authState = authState)
                        }
                        else -> {
                            ViewerShell(
                                settings = settings,
                                store = store,
                                authState = authState,
                                onStartPlayer = { header, isLive ->
                                    startActivity(
                                        Intent(this@MainActivity, PlayerActivity::class.java)
                                            .putExtra("title_header", header)
                                            .putExtra("is_live", isLive)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        CommonActivity.setActivityInstance(null)
        super.onDestroy()
    }
}

@Composable
fun ViewerShell(
    settings: AppSettings,
    store: Store,
    authState: AuthState,
    onStartPlayer: (String, Boolean) -> Unit
) {
    var currentTab by remember { mutableStateOf(settings.startTab.coerceIn(0, 5)) }
    var activeDetailsTitle by remember { mutableStateOf<Title?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        val detail = activeDetailsTitle
        if (detail != null) {
            DetailsScreen(
                title = detail,
                store = store,
                onBack = { activeDetailsTitle = null },
                onPlay = { req ->
                    val hdr = if (req.title.isMovie) req.title.name
                    else "S${req.season ?: 1} · E${req.episode ?: 1} · ${req.title.name}"
                    onStartPlayer(hdr, false)
                }
            )
        } else {
            when (currentTab) {
                0 -> HomeScreen(
                    settings = settings,
                    store = store,
                    onOpenDetails = { activeDetailsTitle = it },
                    onPlayTitle = { t, s, e ->
                        val hdr = if (t.isMovie) t.name else "S${s ?: 1} · E${e ?: 1} · ${t.name}"
                        onStartPlayer(hdr, false)
                    }
                )
                1 -> LiveScreen(onPlayLive = { ch -> onStartPlayer(ch.name, true) })
                2 -> ExploreScreen(settings = settings, onOpenDetails = { activeDetailsTitle = it })
                3 -> SearchScreen(settings = settings, onOpenDetails = { activeDetailsTitle = it })
                4 -> LibraryScreen(settings = settings, store = store, onOpenDetails = { activeDetailsTitle = it })
                5 -> SettingsScreen(settings = settings, authState = authState)
            }
        }

        // الشريط السفلي العائم بارتفاع 64dp ثابت وفيه 6 خانات متساوية (§14.1)
        if (activeDetailsTitle == null) {
            val navItems = listOf(
                "الرئيسية" to RayeqIcons.Home,
                "مباشر" to RayeqIcons.Live,
                "استكشاف" to RayeqIcons.Explore,
                "بحث" to RayeqIcons.Search,
                "مكتبتي" to RayeqIcons.Library,
                "المزيد" to RayeqIcons.More
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    navItems.forEachIndexed { idx, (label, icon) ->
                        val selected = currentTab == idx
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { currentTab = idx },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                modifier = Modifier.size(24.dp),
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    }
                }
            }
        }
    }
}
