package app.rayeq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rayeq.data.AppSettings

@Composable
fun SubtitleStylePreview(settings: AppSettings) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1E293B)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            text = "معاينة حية لتنسيق الترجمة العربية",
            color = Color(settings.subColorArgb),
            fontSize = settings.subSizeSp.sp,
            fontWeight = if (settings.subBold) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(bottom = 14.dp)
        )
    }
}
