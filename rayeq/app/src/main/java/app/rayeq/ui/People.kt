package app.rayeq.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PersonScreen(personId: Int, personName: String) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(personName)
    }
}
