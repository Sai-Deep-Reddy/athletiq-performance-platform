package com.athletiq.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.athletiq.app.ui.theme.AthletiqColors

enum class PillTone {
    VALID,
    WARNING,
    ERROR,
    INFO,
    NEUTRAL,
}

@Composable
fun StatusPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = when (tone) {
        PillTone.VALID -> AthletiqColors.EmeraldBg to AthletiqColors.EmeraldText
        PillTone.WARNING -> AthletiqColors.AmberBg to AthletiqColors.AmberText
        PillTone.ERROR -> AthletiqColors.CrimsonBg to AthletiqColors.CrimsonText
        PillTone.INFO -> AthletiqColors.PrimarySoftBg to AthletiqColors.PrimaryDark
        PillTone.NEUTRAL -> AthletiqColors.SurfaceSoftBlue to AthletiqColors.TextSecondary
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(999.dp),
        modifier = modifier,
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
fun AthletiqTopBar(
    title: String,
    subtitle: String,
    pendingSyncCount: Int,
    onBackClick: (() -> Unit)? = null,
    onSyncClick: (() -> Unit)? = null,
) {
    Surface(
        color = AthletiqColors.SurfaceCard,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBackClick != null) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = AthletiqColors.TextPrimary,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AthletiqColors.PrimaryCobalt),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = "AthletiQ",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = AthletiqColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = AthletiqColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (onSyncClick != null) {
                Surface(
                    onClick = onSyncClick,
                    color = if (pendingSyncCount == 0) AthletiqColors.EmeraldBg else AthletiqColors.AmberBg,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = "Sync status",
                            tint = if (pendingSyncCount == 0) AthletiqColors.EmeraldText else AthletiqColors.AmberText,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (pendingSyncCount == 0) "Synced" else "$pendingSyncCount Pending",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (pendingSyncCount == 0) AthletiqColors.EmeraldText else AthletiqColors.AmberText,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AthletiqColors.SurfaceCard),
        border = BorderStroke(1.dp, AthletiqColors.SurfaceBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

@Composable
fun DarkTelemetryContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = AthletiqColors.SlateNavy),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

@Composable
fun MetricReadout(
    label: String,
    valueText: String,
    unitText: String,
    uncertaintyText: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AthletiqColors.TextMuted,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.displayLarge,
                color = AthletiqColors.TextPrimary,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = unitText,
                style = MaterialTheme.typography.titleMedium,
                color = AthletiqColors.TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (uncertaintyText != null) {
            Text(
                text = uncertaintyText,
                style = MaterialTheme.typography.bodyMedium,
                color = AthletiqColors.TextSecondary,
            )
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector = Icons.Rounded.Info,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    TelemetryCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(AthletiqColors.SurfaceSoftBlue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AthletiqColors.PrimaryCobalt,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AthletiqColors.TextPrimary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = AthletiqColors.TextSecondary,
            )
            if (action != null) {
                Spacer(modifier = Modifier.height(12.dp))
                action()
            }
        }
    }
}

@Composable
fun UnavailableBanner(
    title: String,
    message: String,
    tone: PillTone = PillTone.WARNING,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = when (tone) {
        PillTone.ERROR -> AthletiqColors.CrimsonBg to AthletiqColors.CrimsonText
        PillTone.INFO -> AthletiqColors.SurfaceSoftBlue to AthletiqColors.PrimaryDark
        else -> AthletiqColors.AmberBg to AthletiqColors.AmberText
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (tone == PillTone.INFO) Icons.Rounded.Info else Icons.Rounded.Warning,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AthletiqColors.TextPrimary,
                )
            }
        }
    }
}
