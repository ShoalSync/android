package org.tidefell.android.ui.screenshots

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tidefell.android.Peer
import org.tidefell.android.ui.AddFolderScreen
import org.tidefell.android.ui.HomeScreen
import org.tidefell.android.ui.Page
import org.tidefell.android.ui.PairingScreen
import org.tidefell.android.ui.SettingsScreen
import org.tidefell.android.ui.StatusPage
import org.tidefell.android.ui.TidefellTheme
import java.io.File
import org.tidefell.android.ui.AddFolderViewModel.Step as AddStep
import org.tidefell.android.ui.PairingViewModel.Step as PairStep

/**
 * The main screens on tablets and the inner screens of foldables, beside [ScreenshotTest]'s phone,
 * so a layout that only works at phone width shows. The images are in
 * `src/test/screenshots/large/`, one per screen and [Device].
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain application: the app's own starts the native engine, which the JVM cannot load.
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalRoborazziApi::class)
class LargeScreenTest(private val device: Device) {
    /** A screen size, named in the images' file names. */
    enum class Device(val qualifiers: String) {
        /** Pixel Tablet, held sideways. */
        Tablet(RobolectricDeviceQualifiers.PixelTablet),

        /** Pixel Tablet, held upright. */
        TabletPortrait("w800dp-h1280dp-large-notlong-notround-port-xhdpi-keyshidden-nonav"),

        /** Pixel Fold open: wide and short. */
        FoldOpen(RobolectricDeviceQualifiers.PixelFold),

        /** Pixel 9 Pro Fold open: nearly square. */
        FoldOpenSquare(RobolectricDeviceQualifiers.Pixel9ProFold),
    }

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers(device.qualifiers)
        Peer.init(ApplicationProvider.getApplicationContext())
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { TidefellTheme(dark = false, content = content) }
    }

    private fun home(home: FakeHome, add: FakeAddFolder = FakeAddFolder(AddStep.Place)) = show {
        HomeScreen(
            model = home,
            settings = { SettingsScreen(model = FakeSettings()) },
            status = { StatusPage(model = FakeStatus()) },
            addFolder = { onDone, onCancel -> AddFolderScreen(offer = null, onDone = onDone, onCancel = onCancel, model = add) },
        )
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val file = name + "-" + device.name.replace(Regex("(?<=.)([A-Z])"), "-$1").lowercase()
        captureScreenRoboImage("src/test/screenshots/large/$file.png")
    }

    @Test
    fun folders() {
        home(FakeHome())
        capture("01-folders")
    }

    @Test
    fun files() {
        home(FakeHome(Page.Files(Sample.documents.id)))
        capture("10-files")
    }

    @Test
    fun fileActions() {
        home(FakeHome(Page.Files(Sample.documents.id)))
        compose.onNodeWithText("Notes.md").performClick()
        capture("13-file-actions")
    }

    @Test
    fun conflict() {
        home(FakeHome(Page.Conflict(Sample.documents.id, "Budget 2026.ods")).apply { versions.value = Sample.conflict })
        capture("50-conflict")
    }

    @Test
    fun settings() {
        home(FakeHome(Page.Settings))
        capture("60-settings")
    }

    @Test
    fun status() {
        home(FakeHome(Page.Status))
        capture("70-status")
    }

    @Test
    fun addChoose() {
        home(FakeHome(Page.AddFolder), add = FakeAddFolder(AddStep.Choose(File("/storage/emulated/0/Documents"))))
        capture("84-add-choose")
    }

    @Test
    fun pairingCode() {
        show {
            PairingScreen(
                onPaired = {},
                model = FakePairing(PairStep.ShowCode("tidefell1qy3x7k2m9w4p8r6t5v0n2c8z7h4j6f3d1s9a5g2l")),
                joinFolder = { offer, onDone, onCancel, modifier ->
                    AddFolderScreen(offer, onDone, onCancel, modifier, model = FakeAddFolder(AddStep.Place))
                },
            )
        }
        capture("91-pairing-code")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun devices(): List<Array<Any>> = Device.entries.map { arrayOf(it) }
    }
}
