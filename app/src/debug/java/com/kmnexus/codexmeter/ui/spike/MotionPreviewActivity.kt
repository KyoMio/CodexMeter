package com.kmnexus.codexmeter.ui.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kmnexus.codexmeter.ui.components.CodexMeterBackdrop
import com.kmnexus.codexmeter.ui.theme.CodexMeterColors
import com.kmnexus.codexmeter.ui.theme.CodexMeterSpacing
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import kotlinx.coroutines.delay

class MotionPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CodexMeterTheme {
                MotionPreviewScreen()
            }
        }
    }
}

@Composable
private fun MotionPreviewScreen() {
    var scenarioIndex by remember { mutableStateOf(0) }
    var pulseKey by remember { mutableStateOf(0) }
    val scenario = MotionPreviewScenarios[scenarioIndex]

    LaunchedEffect(Unit) {
        while (true) {
            delay(2600)
            scenarioIndex = (scenarioIndex + 1) % MotionPreviewScenarios.size
            pulseKey += 1
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CodexMeterBackdrop(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CodexMeterSpacing.xl, vertical = CodexMeterSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(CodexMeterSpacing.lg),
        ) {
            Text(
                text = "CodexMeter Motion Preview",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = MotionPreviewFonts.geistSans,
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Debug-only 样板：假数据、无真实账号、无网络、无凭据。用于确认字体、数字滚动、状态点、Tab 胶囊滑动和刷新回光。",
                style = MaterialTheme.typography.bodyMedium,
                color = CodexMeterColors.secondary,
            )
            PreviewControlRow(
                onRefreshClick = { pulseKey += 1 },
                onNextClick = {
                    scenarioIndex = (scenarioIndex + 1) % MotionPreviewScenarios.size
                    pulseKey += 1
                },
            )
            MotionPreviewHero(
                scenario = scenario,
                pulseKey = pulseKey,
            )
            QuotaMotionSection(scenario = scenario)
            BottomBarMotionPreview(selectedIndex = scenarioIndex % 3)
            TrendMotionPreview(
                points = scenario.trend,
                scenarioKey = scenarioIndex,
            )
            FontComparisonSection()
            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}
