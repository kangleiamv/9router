package ai.ninerouter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.ninerouter.ui.theme.AppSurfaceHigh
import ai.ninerouter.ui.theme.Emerald
import ai.ninerouter.ui.theme.Rose
import ai.ninerouter.ui.theme.TextMuted
import ai.ninerouter.ui.theme.TextSecondary
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Day divider for the flat request feeds. Shows the entry count and either an
 * error count or the day's success rate.
 */
@Composable
fun RequestDayHeader(
    day: LocalDate,
    total: Int,
    errors: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatDayLabel(day),
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary
            )
            Spacer(Modifier.width(7.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(AppSurfaceHigh)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    "$total",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
            Spacer(Modifier.weight(1f))
            if (errors > 0) {
                Text(
                    "$errors errors",
                    style = MaterialTheme.typography.labelSmall,
                    color = Rose
                )
            } else if (total > 0) {
                Text(
                    "${roundPercent((total - errors) * 100.0 / total)}% ok",
                    style = MaterialTheme.typography.labelSmall,
                    color = Emerald
                )
            }
        }
        HairLine(Modifier.padding(horizontal = 8.dp))
    }
}

/** Equal-width status filter used by the request feed screens. */
@Composable
fun RequestFilterPill(
    label: String,
    count: Int,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .height(38.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.15f) else AppSurfaceHigh)
            .border(1.dp, if (selected) accent.copy(alpha = 0.52f) else Color.Transparent, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) accent else TextSecondary,
                maxLines = 1
            )
            Spacer(Modifier.width(5.dp))
            Text(
                formatCount(count),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) accent.copy(alpha = 0.78f) else TextMuted
            )
        }
    }
}

private fun formatDayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
}
