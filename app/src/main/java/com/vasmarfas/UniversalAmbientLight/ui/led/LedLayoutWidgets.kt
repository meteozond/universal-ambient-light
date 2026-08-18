package com.vasmarfas.UniversalAmbientLight.ui.led

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R

/**
 * Мелкие элементы экрана раскладки: пункт легенды и подписи.
 */
@Composable
fun LegendItem(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun getCornerName(corner: String): String {
    return when (corner) {
        "top_left" -> stringResource(R.string.led_corner_top_left)
        "top_right" -> stringResource(R.string.led_corner_top_right)
        "bottom_right" -> stringResource(R.string.led_corner_bottom_right)
        "bottom_left" -> stringResource(R.string.led_corner_bottom_left)
        else -> corner
    }
}

@Composable
fun getDirectionName(direction: String): String {
    return when (direction) {
        "clockwise" -> stringResource(R.string.led_direction_clockwise)
        "counterclockwise" -> stringResource(R.string.led_direction_counterclockwise)
        else -> direction
    }
}
