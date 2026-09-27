package com.nuvio.app.features.library

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "en-rUS-w420dp-h900dp")
class LibraryCalendarSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectedMonthAndDaySurviveProgressiveResultsAndStateRestoration() {
        val today = requireNotNull(parseLibraryCalendarDate(CurrentDateProvider.todayIsoDate()))
        val nextMonth = LibraryCalendarMonth(today.year, today.month).next()
        val selectedDate = LibraryCalendarDate(nextMonth.year, nextMonth.month, 12)
        val item = LibraryItem(id = "series", type = "series", name = "Series", savedAtEpochMs = 1L)
        val initial = LibraryCalendarEvent("initial", today, today.iso, item, "Series")
        val snapshot = mutableStateOf(LibraryCalendarSnapshot(mapOf(today.iso to listOf(initial)), isLoading = false))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            NuvioTheme { LibraryReleaseCalendarPage(snapshot.value, onDismiss = {}, onPosterClick = null) }
        }
        compose.onNodeWithContentDescription("Next month").performClick()
        compose.onNodeWithText("12").performClick()
        compose.onNodeWithText(displayLibraryCalendarEventDate(selectedDate)).assertIsDisplayed()
        compose.runOnIdle {
            snapshot.value = LibraryCalendarSnapshot(
                mapOf(today.iso to listOf(initial), selectedDate.iso to listOf(initial.copy(key = "new", date = selectedDate))),
                isLoading = false,
            )
        }
        compose.onNodeWithText(nextMonth.displayTitle).assertIsDisplayed()
        compose.onNodeWithText(displayLibraryCalendarEventDate(selectedDate)).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(nextMonth.displayTitle).assertIsDisplayed()
        compose.onNodeWithText(displayLibraryCalendarEventDate(selectedDate)).assertIsDisplayed()
    }
}
