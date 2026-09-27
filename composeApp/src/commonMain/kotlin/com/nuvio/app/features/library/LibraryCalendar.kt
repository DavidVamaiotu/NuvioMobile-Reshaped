package com.nuvio.app.features.library

// Adapted from yesnt10/NuvioMobile-Enhanced, enhanced branch at 894499115fb1a69cd4bb34d6969a74832f0e5636.

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import com.nuvio.app.core.time.isEpisodeReleaseAired
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.core.ui.PlatformBackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.i18n.localizedMonthName
import com.nuvio.app.core.i18n.localizedShortMonthName
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryCalendar(
    items: List<LibraryItem>,
    onDismiss: () -> Unit,
    onPosterClick: ((LibraryItem) -> Unit)?,
) {
    val profileId = ProfileRepository.activeProfileId
    var snapshot by remember(profileId) { mutableStateOf(LibraryCalendarSnapshot()) }
    ScreenActivityEffect(profileId, items) { active ->
        if (!active) return@ScreenActivityEffect
        while (isActive) {
            loadLibraryCalendar(items, profileId).flowOn(Dispatchers.Default).collect { snapshot = it }
            delay(LibraryCalendarCacheTtlMs)
        }
    }
    PlatformBackHandler(enabled = LocalScreenActive.current, onBack = onDismiss)
    key(profileId) {
        LibraryReleaseCalendarPage(snapshot, onDismiss, onPosterClick)
    }
}

@Composable
internal fun LibraryReleaseCalendarPage(
    snapshot: LibraryCalendarSnapshot,
    onDismiss: () -> Unit,
    onPosterClick: ((LibraryItem) -> Unit)?,
) {
    var nowEpochMs by remember { mutableStateOf(LibraryClock.nowEpochMs()) }
    var todayIso by remember { mutableStateOf(CurrentDateProvider.todayIsoDate()) }
    ScreenActivityEffect { active ->
        if (!active) return@ScreenActivityEffect
        while (isActive) {
            nowEpochMs = LibraryClock.nowEpochMs()
            todayIso = CurrentDateProvider.todayIsoDate()
            delay(30_000L)
        }
    }
    val today = parseLibraryCalendarDate(todayIso) ?: LibraryCalendarDate(1970, 1, 1)
    // Store user intent independently of asynchronously arriving events.
    var selectedDateIso by rememberSaveable { mutableStateOf(todayIso) }
    val selectedDateValue = parseLibraryCalendarDate(selectedDateIso) ?: today
    val visibleMonth = LibraryCalendarMonth(selectedDateValue.year, selectedDateValue.month)
    val eventsByDate = snapshot.eventsByDate
    val isLoading = snapshot.isLoading
    val monthEventCount = snapshot.countsByMonth[visibleMonth.key] ?: 0

    val selectedEvents = eventsByDate[selectedDateIso].orEmpty()
    val selectedDate = parseLibraryCalendarDate(selectedDateIso)
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = safeInsets.calculateTopPadding() + 10.dp,
                end = 20.dp,
                bottom = safeInsets.calculateBottomPadding() + 28.dp,
            ),
        ) {
            item {
                LibraryCalendarTopBar(
                    title = stringResource(Res.string.library_calendar_title),
                    subtitle = stringResource(Res.string.library_calendar_exact_dates_only),
                    onBack = onDismiss,
                )
            }

            if (eventsByDate.isEmpty() && isLoading) {
                item {
                    LibraryCalendarLoadingState()
                }
            } else if (eventsByDate.isEmpty()) {
                item {
                    LibraryCalendarEmptyState()
                }
            } else {
                item {
                    LibraryCalendarCard(
                        month = visibleMonth,
                        monthEventCount = monthEventCount,
                        eventsByDate = eventsByDate,
                        selectedDateIso = selectedDateIso,
                        todayIso = todayIso,
                        onPrevious = {
                            val month = visibleMonth.previous()
                            selectedDateIso = LibraryCalendarDate(month.year, month.month, 1).iso
                        },
                        onNext = {
                            val month = visibleMonth.next()
                            selectedDateIso = LibraryCalendarDate(month.year, month.month, 1).iso
                        },
                        onToday = { selectedDateIso = todayIso },
                        onDateSelected = { date -> selectedDateIso = date.iso },
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(26.dp))
                    LibraryCalendarAgendaHeader(
                        selectedDate = selectedDate,
                        eventCount = selectedEvents.size,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (selectedEvents.isEmpty()) {
                    item {
                        LibraryCalendarNoDayEvents()
                    }
                } else {
                    items(
                        items = selectedEvents,
                        key = { event -> event.key },
                    ) { event ->
                        LibraryCalendarEventRow(
                            event = event,
                            nowEpochMs = nowEpochMs,
                            onClick = onPosterClick?.let { posterClick ->
                                {
                                    onDismiss()
                                    posterClick(event.item)
                                }
                            },
                        )
                    }
                }

                if (isLoading) {
                    item {
                        LibraryCalendarInlineLoading()
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun LibraryCalendarTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 22.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(Res.string.action_back),
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LibraryCalendarGlyph(
    modifier: Modifier = Modifier,
    tint: Color,
    cutoutColor: Color,
) {
    Canvas(modifier = modifier) {
        val scale = size.minDimension / 14f
        fun x(value: Float) = value * scale
        fun y(value: Float) = value * scale

        drawRoundRect(
            color = tint,
            topLeft = Offset(x(1.5f), y(2.5f)),
            size = Size(x(11f), y(10f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(1.1f), y(1.1f)),
        )
        drawRect(
            color = cutoutColor,
            topLeft = Offset(x(2.6f), y(5.1f)),
            size = Size(x(8.8f), y(0.9f)),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(x(3.5f), y(1.3f)),
            size = Size(x(1.5f), y(3.1f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(0.7f), y(0.7f)),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(x(9f), y(1.3f)),
            size = Size(x(1.5f), y(3.1f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(0.7f), y(0.7f)),
        )

        val cell = x(1.15f)
        val gap = x(1.05f)
        val startX = x(4.1f)
        val startY = y(7.25f)
        repeat(3) { column ->
            repeat(2) { row ->
                drawRoundRect(
                    color = cutoutColor,
                    topLeft = Offset(startX + column * (cell + gap), startY + row * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(0.22f), y(0.22f)),
                )
            }
        }
    }
}

@Composable
private fun LibraryCalendarLoadingState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NuvioLoadingIndicator(
            modifier = Modifier.size(28.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(Res.string.library_calendar_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryCalendarInlineLoading() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioLoadingIndicator(
            modifier = Modifier.size(18.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LibraryCalendarEmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.library_calendar_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(Res.string.library_calendar_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryCalendarCard(
    month: LibraryCalendarMonth,
    monthEventCount: Int,
    eventsByDate: Map<String, List<LibraryCalendarEvent>>,
    selectedDateIso: String?,
    todayIso: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onDateSelected: (LibraryCalendarDate) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = stringResource(Res.string.library_calendar_previous_month),
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                ) {
                    Text(
                        text = month.displayTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = libraryCalendarReleaseCountText(monthEventCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    modifier = Modifier.clickable(onClick = onToday),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = stringResource(Res.string.library_calendar_today),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(Res.string.library_calendar_next_month),
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            LibraryCalendarWeekdayHeader()
            LibraryCalendarMonthGrid(
                month = month,
                eventsByDate = eventsByDate,
                selectedDateIso = selectedDateIso,
                todayIso = todayIso,
                onDateSelected = onDateSelected,
            )
        }
    }
}

@Composable
private fun LibraryCalendarWeekdayHeader() {
    val labels = listOf(
        stringResource(Res.string.library_calendar_weekday_sun),
        stringResource(Res.string.library_calendar_weekday_mon),
        stringResource(Res.string.library_calendar_weekday_tue),
        stringResource(Res.string.library_calendar_weekday_wed),
        stringResource(Res.string.library_calendar_weekday_thu),
        stringResource(Res.string.library_calendar_weekday_fri),
        stringResource(Res.string.library_calendar_weekday_sat),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LibraryCalendarMonthGrid(
    month: LibraryCalendarMonth,
    eventsByDate: Map<String, List<LibraryCalendarEvent>>,
    selectedDateIso: String?,
    todayIso: String,
    onDateSelected: (LibraryCalendarDate) -> Unit,
) {
    val cells = remember(month) { libraryCalendarCells(month) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        cells.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                        )
                    } else {
                        val dayEvents = eventsByDate[date.iso].orEmpty()
                        val hasEvents = dayEvents.isNotEmpty()
                        val isSelected = selectedDateIso == date.iso
                        val isToday = todayIso == date.iso
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clickable { onDateSelected(date) },
                            contentAlignment = Alignment.Center,
                        ) {
                            val dayColor = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                hasEvents || isToday -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    )
                                    .then(
                                        if (!isSelected && isToday) {
                                            Modifier.border(
                                                BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary),
                                                RoundedCornerShape(13.dp),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = date.day.toString(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = dayColor,
                                    fontWeight = if (hasEvents) FontWeight.Bold else FontWeight.Normal,
                                    modifier = if (hasEvents && isSelected) {
                                        Modifier.padding(bottom = 6.dp)
                                    } else {
                                        Modifier
                                    },
                                )
                                if (hasEvents && !isToday) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = if (isSelected) 5.dp else 2.dp)
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) {
                                                    MaterialTheme.colorScheme.onPrimary
                                                } else {
                                                    MaterialTheme.colorScheme.primary
                                                },
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCalendarAgendaHeader(
    selectedDate: LibraryCalendarDate?,
    eventCount: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = stringResource(Res.string.library_calendar_agenda),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = selectedDate?.let(::displayLibraryCalendarEventDate).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = libraryCalendarReleaseCountText(eventCount),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun libraryCalendarReleaseCountText(count: Int): String =
    if (count == 1) {
        stringResource(Res.string.library_calendar_release_count_single)
    } else {
        stringResource(Res.string.library_calendar_release_count, count)
    }

@Composable
private fun LibraryCalendarNoDayEvents() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LibraryCalendarGlyph(
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        cutoutColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(Res.string.library_calendar_no_day_events_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(Res.string.library_calendar_no_day_events_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LibraryCalendarEventRow(
    event: LibraryCalendarEvent,
    nowEpochMs: Long,
    onClick: (() -> Unit)?,
) {
    val isUpcoming = isEpisodeReleaseAired(event.rawReleaseInfo, nowEpochMs) != true
    val modifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    } else {
        Modifier.fillMaxWidth()
    }
    Surface(
        modifier = modifier.padding(bottom = 10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LibraryCalendarEventArtwork(event = event)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                event.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(
                        if (isUpcoming) {
                            Res.string.library_calendar_upcoming
                        } else {
                            Res.string.library_calendar_available
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LibraryCalendarEventArtwork(event: LibraryCalendarEvent) {
    Box(
        modifier = Modifier
            .size(width = 104.dp, height = 62.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (!event.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = event.imageUrl,
                contentDescription = event.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${event.date.day} ${localizedShortMonthName(event.date.month)}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
