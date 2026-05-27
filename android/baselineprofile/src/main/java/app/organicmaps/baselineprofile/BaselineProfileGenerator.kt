package app.organicmaps.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "app.organicmaps",
        includeInStartupProfile = true
    ) {
        // Start the app
        pressHome()
        startActivityAndWait()

        // Give some time for the map to load features
        device.waitForIdle()
        Thread.sleep(2000)

        // 1. Map Navigation
        // Swipe around the map to trigger tile loading and rendering
        device.swipe(
            device.displayWidth / 2,
            device.displayHeight / 2,
            device.displayWidth / 4,
            device.displayHeight / 4,
            20
        )
        device.waitForIdle()
        Thread.sleep(1000)

        // 2. Navigate to Search
        // The app uses a HorizontalPager, so we swipe to the next page
        device.findObject(By.scrollable(true)).swipe(Direction.RIGHT, 1.0f)
        device.waitForIdle()
        Thread.sleep(500)

        // 3. Perform a Search
        // Find the "Search..." text field or the box containing it
        val searchField = device.wait(Until.findObject(By.text("Search...")), 5000)
        searchField?.let {
            it.click()
            it.text = "Restaurant"
            device.pressEnter()
            device.waitForIdle()
            Thread.sleep(2000)
        }

        // Wait for results and maybe scroll a bit
        val resultsList = device.wait(Until.findObject(By.scrollable(true)), 5000)
        resultsList?.scroll(Direction.DOWN, 0.5f)
        device.waitForIdle()
        Thread.sleep(1000)
    }
}
