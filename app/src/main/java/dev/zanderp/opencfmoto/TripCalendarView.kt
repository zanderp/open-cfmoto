package dev.zanderp.opencfmoto

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Month
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class CalendarViewState {
    DAYS, MONTHS, YEARS
}

@Composable
fun TripCalendarView(
    trips: List<Trip>,
    selectedDayMs: Long,
    isLoading: Boolean,
    onDaySelected: (Long) -> Unit
) {
    var viewState by remember { mutableStateOf(CalendarViewState.DAYS) }

    var displayedCalendar by remember(selectedDayMs) {
        mutableStateOf(Calendar.getInstance().apply { timeInMillis = selectedDayMs })
    }

    // Trip dates sets for performance optimization
    val tripDays = remember(trips) { trips.map { TripsListActivity.startOfDay(it.start) }.toSet() }
    val tripMonths = remember(trips) {
        trips.map {
            val cal = Calendar.getInstance().apply { timeInMillis = it.start }
            cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.MONTH)
        }.toSet()
    }
    val tripYears = remember(trips) {
        trips.map {
            val cal = Calendar.getInstance().apply { timeInMillis = it.start }
            cal.get(Calendar.YEAR)
        }.toSet()
    }

    // Navigation logic extracted to be used by both buttons and swipe gestures
    val handlePrevClick = {
        val cal = displayedCalendar.clone() as Calendar
        when (viewState) {
            CalendarViewState.DAYS -> cal.add(Calendar.MONTH, -1)
            CalendarViewState.MONTHS -> cal.add(Calendar.YEAR, -1)
            CalendarViewState.YEARS -> cal.add(Calendar.YEAR, -10)
        }
        displayedCalendar = cal
    }

    val handleNextClick = {
        val cal = displayedCalendar.clone() as Calendar
        when (viewState) {
            CalendarViewState.DAYS -> cal.add(Calendar.MONTH, 1)
            CalendarViewState.MONTHS -> cal.add(Calendar.YEAR, 1)
            CalendarViewState.YEARS -> cal.add(Calendar.YEAR, 10)
        }
        displayedCalendar = cal
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = colorResource(id = R.color.surface)
    ) {
        if (isLoading) {
            // Loading Spinner State
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = colorResource(id = R.color.brand_orange))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(id = R.string.trips_loading),
                        color = colorResource(id = R.color.text_primary),
                        fontSize = 15.sp
                    )
                }
            }
        } else {
            // Main Calendar Content
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    // Horizontal swipe gesture detector for changing months/years
                    .pointerInput(viewState) {
                        var dragAccumulated = 0f
                        var triggered = false
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragAccumulated = 0f
                                triggered = false
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (triggered) return@detectHorizontalDragGestures
                                change.consume()
                                dragAccumulated += dragAmount
                                if (dragAccumulated > 120) {
                                    handlePrevClick()
                                    triggered = true
                                } else if (dragAccumulated < -120) {
                                    handleNextClick()
                                    triggered = true
                                }
                            },
                            onDragEnd = {
                                dragAccumulated = 0f
                                triggered = false
                            }
                        )
                    }
            ) {
                CalendarHeader(
                    viewState = viewState,
                    calendar = displayedCalendar,
                    onHeaderClick = {
                        viewState = when (viewState) {
                            CalendarViewState.DAYS -> CalendarViewState.MONTHS
                            CalendarViewState.MONTHS -> CalendarViewState.YEARS
                            CalendarViewState.YEARS -> CalendarViewState.YEARS
                        }
                    },
                    onPrevClick = handlePrevClick,
                    onNextClick = handleNextClick
                )

                Spacer(modifier = Modifier.height(16.dp))

                // OUTER ANIMATION: Depth scale transition for view state changes (Zoom In / Zoom Out)
                AnimatedContent(
                    targetState = viewState,
                    transitionSpec = {
                        val isZoomingIn = initialState.ordinal > targetState.ordinal
                        if (isZoomingIn) {
                            (fadeIn(tween(250)) + scaleIn(initialScale = 0.85f, animationSpec = tween(250))) togetherWith
                                    (fadeOut(tween(250)) + scaleOut(targetScale = 1.15f, animationSpec = tween(250)))
                        } else {
                            (fadeIn(tween(250)) + scaleIn(initialScale = 1.15f, animationSpec = tween(250))) togetherWith
                                    (fadeOut(tween(250)) + scaleOut(targetScale = 0.85f, animationSpec = tween(250)))
                        }
                    },
                    label = "calendar_depth_animation"
                ) { state ->
                    // INNER ANIMATION: Horizontal slide transition for navigating time within the same view state
                    AnimatedContent(
                        targetState = displayedCalendar.timeInMillis,
                        transitionSpec = {
                            if (targetState > initialState) {
                                // Moving forward in time (slide left)
                                (slideInHorizontally(animationSpec = tween(250)) { width -> width } + fadeIn(tween(250))) togetherWith
                                        (slideOutHorizontally(animationSpec = tween(250)) { width -> -width } + fadeOut(tween(250)))
                            } else {
                                // Moving backward in time (slide right)
                                (slideInHorizontally(animationSpec = tween(250)) { width -> -width } + fadeIn(tween(250))) togetherWith
                                        (slideOutHorizontally(animationSpec = tween(250)) { width -> width } + fadeOut(tween(250)))
                            }
                        },
                        label = "calendar_slide_animation"
                    ) { timeInMillis ->
                        val animatedCalendar = Calendar.getInstance().apply { this.timeInMillis = timeInMillis }
                        when (state) {
                            CalendarViewState.DAYS -> {
                                DaysGrid(
                                    displayedCalendar = animatedCalendar,
                                    tripDays = tripDays,
                                    onDayClick = { dayMs -> onDaySelected(dayMs) }
                                )
                            }
                            CalendarViewState.MONTHS -> {
                                MonthsGrid(
                                    displayedCalendar = animatedCalendar,
                                    tripMonths = tripMonths,
                                    onMonthSelected = { monthIndex ->
                                        val cal = animatedCalendar.clone() as Calendar
                                        cal.set(Calendar.MONTH, monthIndex)
                                        displayedCalendar = cal
                                        viewState = CalendarViewState.DAYS
                                    }
                                )
                            }
                            CalendarViewState.YEARS -> {
                                YearsGrid(
                                    displayedCalendar = animatedCalendar,
                                    tripYears = tripYears,
                                    onYearSelected = { year ->
                                        val cal = animatedCalendar.clone() as Calendar
                                        cal.set(Calendar.YEAR, year)
                                        displayedCalendar = cal
                                        viewState = CalendarViewState.MONTHS
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = colorResource(id = R.color.surface_high), thickness = 1.dp)
                Spacer(modifier = Modifier.height(12.dp))

                // Stats for the selected period and last ride info
                CalendarFooter(
                    trips = trips,
                    displayedCalendar = displayedCalendar,
                    viewState = viewState
                )
            }
        }
    }
}

@Composable
private fun CalendarHeader(
    viewState: CalendarViewState,
    calendar: Calendar,
    onHeaderClick: () -> Unit,
    onPrevClick: () -> Unit,
    onNextClick: () -> Unit
) {
    val title = remember(calendar.timeInMillis, viewState) {
        when (viewState) {
            CalendarViewState.DAYS -> {
                // Using java.time.Month with FULL_STANDALONE guarantees nominative case (e.g. "Wrzesień" instead of "Września")
                val monthName = Month.of(calendar.get(Calendar.MONTH) + 1)
                    .getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                "$monthName ${calendar.get(Calendar.YEAR)}"
            }
            CalendarViewState.MONTHS -> "${calendar.get(Calendar.YEAR)}"
            CalendarViewState.YEARS -> {
                val startYear = (calendar.get(Calendar.YEAR) / 10) * 10
                "$startYear – ${startYear + 9}"
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Crossfade animation for the title text
        AnimatedContent(
            targetState = title,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
            label = "header_title_animation"
        ) { animatedTitle ->
            Text(
                text = animatedTitle,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colorResource(id = R.color.text_primary),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onHeaderClick() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Row {
            IconButton(onClick = onPrevClick) {
                Text("‹", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = colorResource(id = R.color.text_primary))
            }
            IconButton(onClick = onNextClick) {
                Text("›", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = colorResource(id = R.color.text_primary))
            }
        }
    }
}

@Composable
private fun DaysGrid(
    displayedCalendar: Calendar,
    tripDays: Set<Long>,
    onDayClick: (Long) -> Unit
) {
    // Automatically localized day of week abbreviations
    val daysOfWeek = remember {
        DayOfWeek.values().map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }

    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            daysOfWeek.forEach { day ->
                Text(
                    text = day.uppercase(Locale.getDefault()),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorResource(id = R.color.text_secondary),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val cal = displayedCalendar.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)

        val firstDayOfWeek = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val cellItems = mutableListOf<Long?>()
        repeat(firstDayOfWeek) { cellItems.add(null) }
        for (d in 1..maxDays) {
            cal.set(Calendar.DAY_OF_MONTH, d)
            cellItems.add(TripsListActivity.startOfDay(cal.timeInMillis))
        }

        // Custom grid with wrap content height
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cellItems.chunked(7).forEach { rowDays ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    for (dayMs in rowDays) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (dayMs != null) {
                                val dateCal = Calendar.getInstance().apply { timeInMillis = dayMs }
                                val dayNum = dateCal.get(Calendar.DAY_OF_MONTH)

                                val hasTrips = tripDays.contains(dayMs)
                                val isToday = TripsListActivity.startOfDay(System.currentTimeMillis()) == dayMs

                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(if (hasTrips) colorResource(id = R.color.surface_high) else Color.Transparent)
                                        .clickable { onDayClick(dayMs) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = dayNum.toString(),
                                            fontSize = 15.sp,
                                            fontWeight = if (hasTrips || isToday) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isToday) colorResource(id = R.color.brand_orange) else colorResource(id = R.color.text_primary)
                                        )
                                        if (hasTrips) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 2.dp)
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(colorResource(id = R.color.brand_orange))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val emptySpots = 7 - rowDays.size
                    repeat(emptySpots) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthsGrid(
    displayedCalendar: Calendar,
    tripMonths: Set<Int>,
    onMonthSelected: (Int) -> Unit
) {
    // System localization for month names
    val months = remember {
        (0..11).map {
            Month.of(it + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault())
                .replaceFirstChar { char -> char.titlecase(Locale.getDefault()) }
        }
    }
    val currentYear = displayedCalendar.get(Calendar.YEAR)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        months.indices.chunked(3).forEach { rowIndices ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (monthIndex in rowIndices) {
                    val hasTrips = tripMonths.contains(currentYear * 100 + monthIndex)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (hasTrips) colorResource(id = R.color.surface_high) else Color.Transparent)
                            .clickable { onMonthSelected(monthIndex) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = months[monthIndex],
                                fontSize = 15.sp,
                                fontWeight = if (hasTrips) FontWeight.Bold else FontWeight.Normal,
                                color = colorResource(id = R.color.text_primary)
                            )
                            if (hasTrips) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(width = 12.dp, height = 4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(colorResource(id = R.color.brand_orange))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun YearsGrid(
    displayedCalendar: Calendar,
    tripYears: Set<Int>,
    onYearSelected: (Int) -> Unit
) {
    val startYear = (displayedCalendar.get(Calendar.YEAR) / 10) * 10
    val years = (startYear - 1..(startYear + 10)).toList()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        years.chunked(3).forEach { rowYears ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (year in rowYears) {
                    val hasTrips = tripYears.contains(year)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (hasTrips) colorResource(id = R.color.surface_high) else Color.Transparent)
                            .clickable { onYearSelected(year) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = year.toString(),
                                fontSize = 15.sp,
                                fontWeight = if (hasTrips) FontWeight.Bold else FontWeight.Normal,
                                color = colorResource(id = R.color.text_primary)
                            )
                            if (hasTrips) {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(width = 12.dp, height = 4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(colorResource(id = R.color.brand_orange))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarFooter(
    trips: List<Trip>,
    displayedCalendar: Calendar,
    viewState: CalendarViewState
) {
    // Crossfade animation for the stats to keep up with sliding grids
    AnimatedContent(
        targetState = displayedCalendar.timeInMillis,
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
        label = "footer_animation"
    ) { timeInMillis ->
        val animatedCalendar = Calendar.getInstance().apply { this.timeInMillis = timeInMillis }

        val activeTrips = remember(trips, animatedCalendar, viewState) {
            trips.filter { trip ->
                val cal = Calendar.getInstance().apply { this.timeInMillis = trip.start }
                val matchYear = cal.get(Calendar.YEAR) == animatedCalendar.get(Calendar.YEAR)
                when (viewState) {
                    CalendarViewState.DAYS -> matchYear && cal.get(Calendar.MONTH) == animatedCalendar.get(Calendar.MONTH)
                    else -> matchYear
                }
            }
        }

        val ridesCount = activeTrips.size
        val totalKm = activeTrips.sumOf { it.distanceKm }
        val totalSeconds = activeTrips.sumOf { it.movingTimeMs } / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60

        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ridesCount > 0) {
                Text(
                    text = pluralStringResource(
                        id = R.plurals.trips_calendar_stats,
                        count = ridesCount,
                        ridesCount, totalKm, hours, minutes
                    ),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorResource(id = R.color.text_primary)
                )
            } else {
                Text(
                    text = stringResource(id = R.string.trips_calendar_no_rides),
                    fontSize = 13.sp,
                    color = colorResource(id = R.color.text_secondary)
                )
            }

            val todayCal = Calendar.getInstance()
            val isCurrentMonth = animatedCalendar.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
                    animatedCalendar.get(Calendar.MONTH) == todayCal.get(Calendar.MONTH)

            if (!isCurrentMonth && viewState == CalendarViewState.DAYS) {
                val latestTrip = trips.maxByOrNull { it.start }
                if (latestTrip != null) {
                    val dateStr = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(latestTrip.start))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(id = R.string.trips_calendar_last_trip, dateStr),
                        fontSize = 11.sp,
                        color = colorResource(id = R.color.text_secondary)
                    )
                }
            }
        }
    }
}