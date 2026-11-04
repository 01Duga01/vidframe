/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.vidframe

import android.content.Context
import android.content.res.AssetManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mozilla.components.browser.state.action.WebExtensionAction.UpdatePromptRequestWebExtensionAction
import mozilla.components.browser.state.state.extension.WebExtensionPromptRequest
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.feature.addons.Addon
import mozilla.components.feature.addons.AddonManager
import mozilla.components.support.test.robolectric.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UboProvisionerTest {
    private val testDispatcher = StandardTestDispatcher()
    private val addonManager: AddonManager = mockk(relaxed = true)
    private val assets: AssetManager = mockk()
    private lateinit var context: Context
    private lateinit var store: BrowserStore

    @Before
    fun setup() {
        context = spyk(testContext)
        every { context.assets } returns assets
        every { assets.open(VidframeConfig.UBO_ASSET) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        testContext.getSharedPreferences(UboProvisioner.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = BrowserStore()
    }

    private fun TestScope.provisioner() =
        UboProvisioner(context, store, addonManager, scope = backgroundScope, ioDispatcher = testDispatcher)

    @Test
    fun `GIVEN a first run WHEN started THEN the bundled XPI is installed from file and ready follows success`() =
        runTest(testDispatcher) {
            val url = slot<String>()
            val onSuccess = slot<(Addon) -> Unit>()
            every { addonManager.installAddon(capture(url), any(), capture(onSuccess), any()) } returns mockk()
            val provisioner = provisioner()

            provisioner.start()
            testScheduler.runCurrent()

            assertFalse(provisioner.ready.value)
            assertTrue(url.captured.startsWith("file://"))
            assertTrue(url.captured.endsWith("ublock_origin.xpi"))
            verify { addonManager.installAddon(any(), InstallationMethod.FROM_FILE, any(), any()) }

            onSuccess.captured(Addon(id = VidframeConfig.UBO_ID, version = "1.0"))

            assertTrue(provisioner.ready.value)
            val prefs = testContext.getSharedPreferences(UboProvisioner.PREFS_NAME, Context.MODE_PRIVATE)
            assertTrue(prefs.getBoolean(UboProvisioner.KEY_PROVISIONED, false))
        }

    @Test
    fun `GIVEN a first run WHEN the install succeeds THEN the onInstalled hook runs once, and not on failure`() =
        runTest(testDispatcher) {
            val onSuccess = slot<(Addon) -> Unit>()
            every { addonManager.installAddon(any(), any(), capture(onSuccess), any()) } returns mockk()
            var installed = 0
            UboProvisioner(
                    context,
                    store,
                    addonManager,
                    scope = backgroundScope,
                    ioDispatcher = testDispatcher,
                    onInstalled = { installed++ },
                )
                .start()
            testScheduler.runCurrent()

            assertEquals(0, installed)
            onSuccess.captured(Addon(id = VidframeConfig.UBO_ID, version = "1.0"))
            assertEquals(1, installed)
        }

    @Test
    fun `GIVEN uBlock Origin was provisioned before WHEN started THEN it is not reinstalled`() =
        runTest(testDispatcher) {
            testContext
                .getSharedPreferences(UboProvisioner.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(UboProvisioner.KEY_PROVISIONED, true)
                .commit()
            val provisioner = provisioner()

            assertTrue(provisioner.ready.value)
            provisioner.start()
            testScheduler.runCurrent()

            verify(exactly = 0) { addonManager.installAddon(any(), any(), any(), any()) }
        }

    @Test
    fun `GIVEN the XPI is missing from the APK WHEN started THEN the launcher is released without installing`() =
        runTest(testDispatcher) {
            every { assets.open(VidframeConfig.UBO_ASSET) } throws IOException("missing")
            val provisioner = provisioner()

            provisioner.start()
            testScheduler.runCurrent()

            assertTrue(provisioner.ready.value)
            verify(exactly = 0) { addonManager.installAddon(any(), any(), any(), any()) }
        }

    @Test
    fun `GIVEN the install fails WHEN started THEN the launcher is released`() =
        runTest(testDispatcher) {
            val onError = slot<(Throwable) -> Unit>()
            every { addonManager.installAddon(any(), any(), any(), capture(onError)) } returns mockk()
            val provisioner = provisioner()

            provisioner.start()
            testScheduler.runCurrent()
            onError.captured(IllegalStateException("signature"))

            assertTrue(provisioner.ready.value)
        }

    @Test
    fun `GIVEN a required-permissions prompt for uBlock Origin in the store THEN it is granted with private browsing and consumed`() =
        runTest(testDispatcher) {
            every { addonManager.installAddon(any(), any(), any(), any()) } returns mockk()
            var response: PermissionPromptResponse? = null
            provisioner().start()
            testScheduler.runCurrent()

            store.dispatch(
                UpdatePromptRequestWebExtensionAction(
                    WebExtensionPromptRequest.AfterInstallation.Permissions.Required(
                        mockk(relaxed = true) { every { id } returns VidframeConfig.UBO_ID },
                        listOf("storage"),
                        listOf("<all_urls>"),
                        emptyList(),
                    ) { response = it }
                )
            )
            testScheduler.runCurrent()

            assertEquals(PermissionPromptResponse(isPermissionsGranted = true, isPrivateModeGranted = true), response)
            assertNull(store.state.webExtensionPromptRequest)
        }

    @Test
    fun `GIVEN a required-permissions prompt for another add-on THEN it is left for the user`() =
        runTest(testDispatcher) {
            var answered = false
            provisioner().start()
            testScheduler.runCurrent()

            val request =
                WebExtensionPromptRequest.AfterInstallation.Permissions.Required(
                    mockk(relaxed = true) { every { id } returns "other@example.com" },
                    listOf("tabs"),
                    emptyList(),
                    emptyList(),
                ) { answered = true }
            store.dispatch(UpdatePromptRequestWebExtensionAction(request))
            testScheduler.runCurrent()

            assertFalse(answered)
            assertEquals(request, store.state.webExtensionPromptRequest)
        }

    @Test
    fun `autoAccept skips the post-install dialog for uBlock Origin but never handles optional permissions`() {
        val ubo = mockk<WebExtension>(relaxed = true)
        every { ubo.id } returns VidframeConfig.UBO_ID

        assertTrue(UboProvisioner.autoAccept(WebExtensionPromptRequest.AfterInstallation.PostInstallation(ubo)))
        val optional =
            WebExtensionPromptRequest.AfterInstallation.Permissions.Optional(
                ubo,
                listOf("tabs"),
                emptyList(),
                emptyList(),
            ) {}
        assertFalse(UboProvisioner.autoAccept(optional))
        assertFalse(UboProvisioner.isHandledByProvisioner(optional))
    }
}
