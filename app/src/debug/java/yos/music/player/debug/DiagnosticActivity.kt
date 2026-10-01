package yos.music.player.debug

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import yos.music.player.ui.theme.YosMusicTheme
import yos.music.player.ui.widgets.basic.BottomNavigator
import yos.music.player.ui.widgets.liquid.LiquidBottomTab

class DiagnosticActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DiagnosticScreen() }
    }
}

private enum class DiagnosticMode(val label: String) {
    A("A: fixed background"),
    B("B: Flamingo tools"),
    C("C: static page"),
    D("D: host-shaped"),
    E("E: real Discovery")
}

@androidx.compose.runtime.Composable
private fun DiagnosticPage(label: String) {
    Text(label, modifier = Modifier.padding(32.dp), color = Color.White)
}

@androidx.compose.runtime.Composable
private fun DiagnosticScreen() {
    var modeIndex by remember { mutableIntStateOf(0) }
    var highlight by remember { mutableStateOf(false) }
    var producerGrid by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    val mode = DiagnosticMode.entries[modeIndex]
    val backdrop = rememberLayerBackdrop()
    Log.d("GLASS-DIAG", "mode=${mode.name} highlight=$highlight selected=$selected producerGrid=$producerGrid")

    YosMusicTheme {
        Box(Modifier.fillMaxSize().background(Color(0xFF10131C))) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            ) {
                if (producerGrid) {
                    drawRect(Color.Red.copy(alpha = 0.8f))
                    val step = 48.dp.toPx()
                    for (x in 0..(size.width / step).toInt()) {
                        for (y in 0..(size.height / step).toInt()) {
                            if ((x + y) % 2 == 0) drawCircle(Color.White, 10f, Offset(x * step, y * step))
                        }
                    }
                } else {
                    drawRect(Brush.linearGradient(listOf(Color(0xFFEF476F), Color(0xFF118AB2), Color(0xFFFFD166))))
                }
            }
            if (mode == DiagnosticMode.C) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 210.dp),
                    color = Color.Transparent
                ) {
                    Text(
                        "Static page content",
                        modifier = Modifier.padding(32.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
            if (mode == DiagnosticMode.D) {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 210.dp)
                        .onGloballyPositioned { coordinates ->
                            val position = coordinates.positionInWindow()
                            Log.d(
                                "GLASS-DIAG",
                                "producer=NavHost size=${coordinates.size} position=$position"
                            )
                        }
                ) {
                    composable("home") { DiagnosticPage("NavHost home") }
                    composable("library") { DiagnosticPage("NavHost library") }
                }
            }
            if (mode == DiagnosticMode.E) {
                val realNavController = rememberNavController()
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = 210.dp)
                ) {
                    yos.music.player.ui.pages.discovery.Discovery(realNavController)
                }
            }

            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Backdrop diagnostic", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("${mode.label} · highlight=$highlight", color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { modeIndex = (modeIndex + 1) % DiagnosticMode.entries.size }) { Text("Next mode") }
                        Button(onClick = { highlight = !highlight }) { Text("Highlight") }
                        Button(onClick = { producerGrid = !producerGrid }) { Text("Producer") }
                    }
                }
                BottomNavigator(
                    initialIndex = selected,
                    externalIndex = { selected },
                    onIndexChange = {
                        selected = it
                        Log.d("GLASS-DIAG", "mode=${mode.name} selected=$it")
                    },
                    items = listOf("Home", "Library", "Search").map { yos.music.player.ui.widgets.basic.NavItem(it, android.R.drawable.ic_menu_view) },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    backdrop = backdrop
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
