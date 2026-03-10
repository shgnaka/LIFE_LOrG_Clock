package com.example.orgclock.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.orgclock.presentation.OrgClockPresentationState
import com.example.orgclock.presentation.RootReference
import com.example.orgclock.presentation.Screen
import com.example.orgclock.presentation.StatusMessageKey
import com.example.orgclock.presentation.StatusText
import com.example.orgclock.presentation.StatusTone
import com.example.orgclock.presentation.UiStatus
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Org Clock Desktop",
    ) {
        DesktopApp()
    }
}

@Composable
private fun DesktopApp() {
    val state = OrgClockPresentationState(
        screen = Screen.RootSetup,
        rootReference = RootReference("/home/demo/org"),
        status = UiStatus(
            text = StatusText(StatusMessageKey.SelectOrgDirectory),
            tone = StatusTone.Info,
        ),
    )
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFF4F1E8),
                                Color(0xFFD9E5D6),
                                Color(0xFFB9D3C2),
                            ),
                        ),
                    )
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF16302B),
                        contentColor = Color(0xFFF8F5ED),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = "Org Clock Desktop MVP",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Shared presentation contract loads on desktop without Android framework types.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "screen=${state.screen} | root=${state.rootReference?.rawValue}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFD3E6D6),
                        )
                    }
                }
            }
        }
    }
}
