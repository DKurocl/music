package ua.olex.music.soulseek

import android.app.Application
import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DownloadServiceTest {
    @Test fun systemRestartWithoutAnOwnerCommandDoesNotOpenTheTransferClient() {
        val service = Robolectric.buildService(DownloadService::class.java).create()
        try {
            // A plain Application has no transfer graph. Starting it here would fail this fixture.
            assertEquals(Service.START_NOT_STICKY, service.get().onStartCommand(null, 0, 1))
        } finally {
            service.destroy()
        }
    }
}
