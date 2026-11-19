package com.archie.alarm.quiet

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 產生畫面預覽圖（app/build/shots），交付前用來檢查對齊。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w384dp-h1150dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { QuietTheme { content() } }
        rule.waitForIdle()
        // captureToImage 在 Robolectric 會卡住，改成直接把畫面畫進 Bitmap
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File("build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun quietOn() = shot("1_quiet_on") {
        MainScreen(Samples.state(on = true, calls = Samples.calls, skipped = Samples.skipped), ScreenActions())
    }

    @Test
    fun quietOnPowerSave() = shot("2_quiet_on_power") {
        MainScreen(Samples.state(on = true, powerSave = true), ScreenActions())
    }

    @Test
    fun firstRun() = shot("3_first_run") {
        MainScreen(
            Samples.state(on = false, perms = Quiet.Perms(dnd = true, brightness = false, contacts = false, screening = false)),
            ScreenActions(),
        )
    }

    @Test
    fun afterQuiet() = shot("4_after_quiet") {
        MainScreen(
            Samples.state(on = false, calls = Samples.calls, skipped = Samples.skipped, powerLeftOn = true),
            ScreenActions(),
        )
    }
}
