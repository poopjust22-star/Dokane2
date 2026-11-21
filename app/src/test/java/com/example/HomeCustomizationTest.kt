package com.example

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.SavedBusinessStrategy
import com.example.data.model.StoreMember
import com.example.data.model.StorePermissions
import com.example.data.model.StrategyActionAdvice
import com.example.data.repository.StoreRepository
import com.example.ui.components.ShopNavTab
import com.example.ui.screens.HomePermissionSnapshot
import com.example.ui.screens.HomeShortcutCatalog
import com.example.ui.screens.MainShopScreen
import com.example.ui.screens.ShortcutAddResult
import com.example.ui.theme.StoreOSTheme
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.BusinessNavDestination
import com.example.ui.viewmodel.StoreViewModel
import com.example.util.AppLanguage
import com.example.util.HomeLayoutMode
import com.example.util.HomeLayoutPreferences
import com.example.util.LocalAppLanguage
import com.example.util.NetworkMonitor
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xhdpi")
class HomeCustomizationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FirebaseApp.clearInstancesForTest()
        listOf(
            "storeos_home_layout_prefs",
            "storeos_demo_home_layout_prefs",
            "storeos_stock_prefs",
            "storeos_demo_stock_prefs",
            "storeos_app_prefs",
            "user_mode_prefs"
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    private fun createControllerPair(
        storeId: String = "demo_store",
        userId: String = "demo_user",
        repository: StoreRepository = StoreRepository(db = null, appContext = context)
    ): Triple<StoreRepository, AuthViewModel, StoreViewModel> {
        val authVm = AuthViewModel(repository)
        val storeVm = StoreViewModel(
            storeId = storeId,
            repository = repository,
            networkMonitor = NetworkMonitor(context),
            currentUserId = userId
        )
        return Triple(repository, authVm, storeVm)
    }

    private fun waitForShortFlowSync() {
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    // ---------------------------------------------------------------------
    // 1. SAVE: Local-only per signed-in user & per mode + unavailable screen
    // ---------------------------------------------------------------------

    @Test
    fun `saving Home shortcuts persists per mode Detailed vs Simple and per signed-in user locally without Firestore`() {
        assertTrue(FirebaseApp.getApps(context).isEmpty())

        val prefsA = HomeLayoutPreferences.forStore(context, "store_1")
        prefsA.saveShortcuts("user_alpha", HomeLayoutMode.DETAILED, listOf("cash_bank", "expenses"))
        prefsA.saveShortcuts("user_alpha", HomeLayoutMode.SIMPLE, listOf("payment_in", "low_stock"))
        prefsA.saveShortcuts("user_beta", HomeLayoutMode.DETAILED, listOf("suppliers"))

        assertEquals(listOf("cash_bank", "expenses"), prefsA.loadShortcuts("user_alpha", HomeLayoutMode.DETAILED))
        assertEquals(listOf("payment_in", "low_stock"), prefsA.loadShortcuts("user_alpha", HomeLayoutMode.SIMPLE))
        assertEquals(listOf("suppliers"), prefsA.loadShortcuts("user_beta", HomeLayoutMode.DETAILED))
        assertNull(prefsA.loadShortcuts("user_beta", HomeLayoutMode.SIMPLE))

        // Verify ViewModel loads user_alpha's Detailed & Simple layouts separately and writes locally on mutation
        val (_, _, vmAlpha1) = createControllerPair(storeId = "store_1", userId = "user_alpha")
        vmAlpha1.setSimpleMode(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("cash_bank", "expenses"), vmAlpha1.visibleHomeShortcuts.value.map { it.id })
        assertEquals(ShortcutAddResult.ADDED, vmAlpha1.addHomeShortcut("notes"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("cash_bank", "expenses", "notes"), vmAlpha1.visibleHomeShortcuts.value.map { it.id })

        // Switching to Simple mode reads Simple layout independently without overwriting Detailed
        vmAlpha1.setSimpleMode(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("payment_in", "low_stock"), vmAlpha1.visibleHomeShortcuts.value.map { it.id })
        assertEquals(ShortcutAddResult.ADDED, vmAlpha1.addHomeShortcut("alerts"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("payment_in", "low_stock", "alerts"), vmAlpha1.visibleHomeShortcuts.value.map { it.id })

        // Recreate ViewModel for same user -> both modes persisted on this device
        val (_, _, vmAlpha2) = createControllerPair(storeId = "store_1", userId = "user_alpha")
        vmAlpha2.setSimpleMode(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("cash_bank", "expenses", "notes"), vmAlpha2.visibleHomeShortcuts.value.map { it.id })
        vmAlpha2.setSimpleMode(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("payment_in", "low_stock", "alerts"), vmAlpha2.visibleHomeShortcuts.value.map { it.id })

        // Different signed-in user on the same device keeps separate layout
        val (_, _, vmBeta) = createControllerPair(storeId = "store_1", userId = "user_beta")
        vmBeta.setSimpleMode(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("suppliers"), vmBeta.visibleHomeShortcuts.value.map { it.id })
        vmBeta.setSimpleMode(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HomeShortcutCatalog.DEFAULT_SIMPLE_IDS, vmBeta.visibleHomeShortcuts.value.map { it.id })

        // Zero Firestore / Firebase initialization ever occurred
        assertTrue(FirebaseApp.getApps(context).isEmpty())
    }

    @Test
    fun `demo mode Home layout is isolated from real user prefs and cleared on demo exit`() {
        val (repo, _, demoVm) = createControllerPair(storeId = "demo_store", userId = "demo_user")
        demoVm.setSimpleMode(false)
        demoVm.removeHomeShortcut("payment_out")
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(demoVm.visibleHomeShortcuts.value.any { it.id == "payment_out" })

        val realPrefs = HomeLayoutPreferences.forStore(context, "real_store_9")
        realPrefs.saveShortcuts("real_uid_1", HomeLayoutMode.DETAILED, listOf("cash_bank"))

        repo.clearDemoLocalPrefs()

        // Demo prefs cleared, real user prefs untouched
        val demoPrefs = HomeLayoutPreferences.forStore(context, "demo_store")
        assertNull(demoPrefs.loadShortcuts("demo_user", HomeLayoutMode.DETAILED))
        assertEquals(listOf("cash_bank"), realPrefs.loadShortcuts("real_uid_1", HomeLayoutMode.DETAILED))
    }

    @Test
    fun `unavailable or deleted shortcut ids in saved preferences are hidden without crashing`() {
        val prefs = HomeLayoutPreferences.forStore(context, "demo_store")
        prefs.saveShortcuts(
            "demo_user",
            HomeLayoutMode.DETAILED,
            listOf("non_existent_screen", "payment_in", "legacy_removed_tool", "expenses")
        )

        val (_, authVm, storeVm) = createControllerPair(storeId = "demo_store", userId = "demo_user")
        storeVm.setLanguage(AppLanguage.EN)
        storeVm.setSimpleMode(false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("payment_in", "expenses"), storeVm.visibleHomeShortcuts.value.map { it.id })

        composeRule.setContent {
            StoreOSTheme {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                    MainShopScreen(authViewModel = authVm, storeViewModel = storeVm)
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home_shortcut_payment_in").assertIsDisplayed()
        composeRule.onNodeWithTag("home_shortcut_expenses").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("home_shortcut_non_existent_screen").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("home_shortcut_legacy_removed_tool").fetchSemanticsNodes().size)
    }

    // ---------------------------------------------------------------------
    // 2. LIMITS: 8 Detailed, 6 Simple, "Remove one first / আগে একটি সরান"
    // ---------------------------------------------------------------------

    @Test
    fun `Detailed mode enforces 8 shortcut limit and Simple mode enforces 6 shortcut limit`() {
        val (_, _, storeVm) = createControllerPair()

        // Detailed mode starts with 6 defaults -> can add 2 more (8 total), 9th fails with LIMIT_REACHED
        storeVm.setSimpleMode(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(6, storeVm.visibleHomeShortcuts.value.size)
        assertEquals(ShortcutAddResult.ADDED, storeVm.addHomeShortcut("cash_bank"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ShortcutAddResult.ADDED, storeVm.addHomeShortcut("notes"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(8, storeVm.visibleHomeShortcuts.value.size)
        assertEquals(ShortcutAddResult.LIMIT_REACHED, storeVm.addHomeShortcut("suppliers"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(8, storeVm.visibleHomeShortcuts.value.size)

        // Simple mode starts with 4 defaults -> can add 2 more (6 total), 7th fails with LIMIT_REACHED
        storeVm.setSimpleMode(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(4, storeVm.visibleHomeShortcuts.value.size)
        assertEquals(ShortcutAddResult.ADDED, storeVm.addHomeShortcut("stock"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ShortcutAddResult.ADDED, storeVm.addHomeShortcut("alerts"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(6, storeVm.visibleHomeShortcuts.value.size)
        assertEquals(ShortcutAddResult.LIMIT_REACHED, storeVm.addHomeShortcut("sell"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(6, storeVm.visibleHomeShortcuts.value.size)

        // Removing one allows adding again
        storeVm.removeHomeShortcut("alerts")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(5, storeVm.visibleHomeShortcuts.value.size)
        assertEquals(ShortcutAddResult.ADDED, storeVm.addHomeShortcut("sell"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(6, storeVm.visibleHomeShortcuts.value.size)
    }

    @Test
    fun `Edit Home shows Remove one first at limit in EN and BN for Simple and Detailed modes`() {
        val (_, authVm, storeVm) = createControllerPair()
        storeVm.setLanguage(AppLanguage.BN)
        storeVm.setSimpleMode(true)
        // Fill Simple mode to 6 shortcuts
        assertEquals(ShortcutAddResult.ADDED, storeVm.addHomeShortcut("stock"))
        assertEquals(ShortcutAddResult.ADDED, storeVm.addHomeShortcut("alerts"))
        assertEquals(6, storeVm.visibleHomeShortcuts.value.size)

        composeRule.setContent {
            StoreOSTheme {
                val lang = storeVm.currentLanguage.value
                CompositionLocalProvider(LocalAppLanguage provides lang) {
                    MainShopScreen(authViewModel = authVm, storeViewModel = storeVm)
                }
            }
        }
        composeRule.waitForIdle()

        // Open Edit Home in Simple Mode (Bangla)
        composeRule.onNodeWithTag("home_dashboard_list").performScrollToKey("simple_shortcuts_section")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("হোম সাজান").assertIsDisplayed()
        composeRule.onNodeWithTag("home_edit_btn").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home_shortcut_limit_msg").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("আগে একটি সরান").assertIsDisplayed()

        // Tap "শর্টকাট যোগ করুন" -> picker shows "আগে একটি সরান" and tapping 7th ("sell") keeps limit 6
        composeRule.onNodeWithTag("home_add_shortcut_btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_add_shortcut_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("home_add_limit_msg").assertIsDisplayed()
        composeRule.onNodeWithTag("home_add_shortcut_list").performScrollToKey("sell")
        composeRule.onNodeWithTag("home_picker_item_sell").performClick()
        composeRule.waitForIdle()
        assertEquals(6, storeVm.visibleHomeShortcuts.value.size)

        // Close picker, remove one shortcut -> limit warning clears and count becomes 5
        composeRule.onNodeWithTag("home_add_shortcut_close_btn").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_edit_remove_alerts").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(5, storeVm.visibleHomeShortcuts.value.size)
        assertEquals(0, composeRule.onAllNodesWithTag("home_shortcut_limit_msg").fetchSemanticsNodes().size)

        // Switch to Detailed mode + English at limit 8 -> shows "Remove one first"
        composeRule.runOnUiThread {
            storeVm.setLanguage(AppLanguage.EN)
            storeVm.setSimpleMode(false)
            storeVm.addHomeShortcut("cash_bank")
            storeVm.addHomeShortcut("notes")
        }
        composeRule.waitForIdle()
        assertEquals(8, storeVm.visibleHomeShortcuts.value.size)

        composeRule.onNodeWithTag("home_dashboard_list").performScrollToKey("detailed_shortcuts_section")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edit Home").assertIsDisplayed()
        composeRule.onNodeWithTag("home_edit_btn").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home_shortcut_limit_msg").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Remove one first").assertIsDisplayed()
    }

    // ---------------------------------------------------------------------
    // 3. PERMISSIONS: Mode & Staff permission filtering, no bypass
    // ---------------------------------------------------------------------

    @Test
    fun `Simple mode never shows or opens owner-only or detailed-only screens`() {
        val prefs = HomeLayoutPreferences.forStore(context, "real_store_perm")
        // Attempt to inject owner-only & detailed-only shortcuts directly into Simple storage
        prefs.saveShortcuts(
            "owner_user",
            HomeLayoutMode.SIMPLE,
            listOf("staff_permissions", "data_health", "feature_checklist", "cash_bank", "financial_reports", "settings", "payment_in")
        )

        val repo = StoreRepository(db = null, appContext = context)
        repo.seedDemoMemberForTest(
            StoreMember(
                userId = "owner_user",
                role = "owner",
                roleLabel = "Owner",
                permissionLevel = "Full",
                permissions = StorePermissions.defaultOwnerPermissions(),
                status = "Active"
            )
        )
        val (_, _, storeVm) = createControllerPair(storeId = "real_store_perm", userId = "owner_user", repository = repo)
        storeVm.setSimpleMode(true)
        waitForShortFlowSync()

        // Only payment_in survives in visible shortcuts
        assertEquals(listOf("payment_in"), storeVm.visibleHomeShortcuts.value.map { it.id })

        // Add shortcut picker in Simple Mode must NEVER expose owner-only or detailed-only screens
        val pickerIds = storeVm.availableAddHomeShortcuts.value.map { it.id }.toSet()
        val forbiddenInSimple = setOf(
            "staff_permissions",
            "data_health",
            "feature_checklist",
            "cash_bank",
            "payment_out",
            "expenses",
            "other_income",
            "add_party",
            "suppliers",
            "purchases",
            "record_purchase",
            "categories",
            "product_bin",
            "notes",
            "greeting_cards",
            "financial_reports",
            "monthly_report",
            "sub_sales_report",
            "sub_day_book",
            "sub_pnl",
            "settings"
        )
        assertTrue(
            "Simple picker leaked restricted screens: ${pickerIds.intersect(forbiddenInSimple)}",
            pickerIds.intersect(forbiddenInSimple).isEmpty()
        )

        // Direct API calls to add restricted screens must be rejected
        forbiddenInSimple.forEach { restrictedId ->
            assertEquals(
                "addHomeShortcut($restrictedId) must return NOT_ALLOWED in Simple Mode",
                ShortcutAddResult.NOT_ALLOWED,
                storeVm.addHomeShortcut(restrictedId)
            )
        }
    }

    @Test
    fun `losing staff permission immediately hides saved shortcut and removes it from picker`() {
        val repo = StoreRepository(db = null, appContext = context)
        val staffId = "staff_perm_test"
        val initialPerms = StorePermissions.forLevel("full")
        repo.seedDemoMemberForTest(
            StoreMember(
                userId = staffId,
                role = "staff",
                roleLabel = "Staff",
                permissionLevel = "Full",
                permissions = initialPerms,
                status = "Active"
            )
        )

        val (_, _, storeVm) = createControllerPair(storeId = "real_store_staff", userId = staffId, repository = repo)
        storeVm.setSimpleMode(false)
        waitForShortFlowSync()

        // Owner-only screens are hidden even for full-permission staff
        val pickerInitial = storeVm.availableAddHomeShortcuts.value.map { it.id }.toSet()
        assertFalse(pickerInitial.contains("staff_permissions"))
        assertFalse(pickerInitial.contains("data_health"))
        assertFalse(pickerInitial.contains("feature_checklist"))

        // Staff currently has Cash & Bank, Expenses, Receive Payment
        assertEquals(ShortcutAddResult.ADDED, storeVm.addHomeShortcut("cash_bank"))
        assertTrue(storeVm.visibleHomeShortcuts.value.any { it.id == "cash_bank" })
        assertTrue(storeVm.visibleHomeShortcuts.value.any { it.id == "expenses" })
        assertTrue(storeVm.visibleHomeShortcuts.value.any { it.id == "payment_in" })

        // Staff loses Cash & Bank and Receive Payment permissions
        val reducedPerms = initialPerms.toMutableMap().apply {
            this[StorePermissions.CASH_AND_BANK] = false
            this[StorePermissions.RECEIVE_PAYMENT] = false
            this[StorePermissions.EXPENSES_AND_OTHER_INCOME] = false
        }
        repo.seedDemoMemberForTest(
            StoreMember(
                userId = staffId,
                role = "staff",
                roleLabel = "Staff",
                permissionLevel = "Selected",
                permissions = reducedPerms,
                status = "Active"
            )
        )
        composeRule.waitForIdle()

        val visibleAfter = storeVm.visibleHomeShortcuts.value.map { it.id }.toSet()
        val pickerAfter = storeVm.availableAddHomeShortcuts.value.map { it.id }.toSet()
        assertFalse(visibleAfter.contains("cash_bank"))
        assertFalse(visibleAfter.contains("payment_in"))
        assertFalse(visibleAfter.contains("expenses"))
        assertFalse(pickerAfter.contains("cash_bank"))
        assertFalse(pickerAfter.contains("payment_in"))
        assertFalse(pickerAfter.contains("expenses"))
        assertEquals(ShortcutAddResult.NOT_ALLOWED, storeVm.addHomeShortcut("cash_bank"))
    }

    @Test
    fun `opening shortcut in Simple and Detailed mode enforces same More and route guard checks`() {
        val (_, authVm, storeVm) = createControllerPair()
        storeVm.setLanguage(AppLanguage.EN)
        storeVm.setSimpleMode(true)

        composeRule.setContent {
            StoreOSTheme {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                    MainShopScreen(authViewModel = authVm, storeViewModel = storeVm)
                }
            }
        }
        composeRule.waitForIdle()

        // In Simple Mode, tapping Customer Baki opens the guarded BAKI tab (never Business/More backstack)
        composeRule.onNodeWithTag("home_dashboard_list").performScrollToKey("simple_shortcuts_section")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_shortcut_customers_baki").performClick()
        composeRule.waitForIdle()
        assertEquals(ShopNavTab.BAKI, storeVm.currentTab.value)
        assertEquals(BusinessNavDestination.DASHBOARD, storeVm.moreNavDestination.value)

        // Return Home, switch to Detailed Mode, tap Expenses -> opens MORE / EXPENSES_SCREEN
        composeRule.runOnUiThread {
            storeVm.navigateToTab(ShopNavTab.HOME)
            storeVm.setSimpleMode(false)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home_dashboard_list").performScrollToKey("detailed_shortcuts_section")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_shortcut_expenses").performClick()
        composeRule.waitForIdle()
        assertEquals(ShopNavTab.MORE, storeVm.currentTab.value)
        assertEquals(BusinessNavDestination.EXPENSES_SCREEN, storeVm.moreNavDestination.value)
    }

    // ---------------------------------------------------------------------
    // 4. FATHER MODE FIXED: No edit button, no long-press, mutations ignored
    // ---------------------------------------------------------------------

    @Test
    fun `Father mode has fixed layout with no edit button, no shortcuts customization, and ignores edits`() {
        val (_, authVm, storeVm) = createControllerPair(userId = "father_fixed_user")
        storeVm.setFatherModeForMember(memberId = "father_fixed_user", enabled = true)
        waitForShortFlowSync()

        assertTrue(storeVm.isFatherMode.value)
        assertTrue(storeVm.visibleHomeShortcuts.value.isEmpty())
        assertTrue(storeVm.availableAddHomeShortcuts.value.isEmpty())
        assertEquals(ShortcutAddResult.NOT_ALLOWED, storeVm.addHomeShortcut("payment_in"))
        storeVm.moveHomeShortcutUp("payment_in")
        storeVm.moveHomeShortcutDown("payment_in")
        storeVm.removeHomeShortcut("payment_in")
        storeVm.resetHomeShortcutsToDefault()

        composeRule.setContent {
            StoreOSTheme {
                MainShopScreen(authViewModel = authVm, storeViewModel = storeVm)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("father_home_screen").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithTag("home_edit_btn").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("home_shortcuts_section").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithTag("home_empty_space_target").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Edit Home").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("হোম সাজান").fetchSemanticsNodes().size)

        // Long-press on Father screen never opens edit mode
        composeRule.onNodeWithTag("father_home_screen").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("home_edit_done_btn").fetchSemanticsNodes().size)
    }

    // ---------------------------------------------------------------------
    // 5. RESET TO DEFAULT + 6. EDIT INTERACTIONS & CLEAN HOME ORDER
    // ---------------------------------------------------------------------

    @Test
    fun `Reset to default asks for confirmation, Cancel keeps edits, Confirm restores defaults`() {
        val (_, authVm, storeVm) = createControllerPair()
        storeVm.setLanguage(AppLanguage.EN)
        storeVm.setSimpleMode(false)

        composeRule.setContent {
            StoreOSTheme {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                    MainShopScreen(authViewModel = authVm, storeViewModel = storeVm)
                }
            }
        }
        composeRule.waitForIdle()

        // Enter Edit mode via "Edit Home" button
        composeRule.onNodeWithTag("home_dashboard_list").performScrollToKey("detailed_shortcuts_section")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_edit_btn").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Reorder (move payment_out up to index 0) & remove expenses & add cash_bank
        composeRule.onNodeWithTag("home_edit_up_payment_out").performScrollTo().performClick()
        composeRule.onNodeWithTag("home_edit_remove_expenses").performScrollTo().performClick()
        composeRule.onNodeWithTag("home_add_shortcut_btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_add_shortcut_list").performScrollToKey("cash_bank")
        composeRule.onNodeWithTag("home_picker_item_cash_bank").performClick()
        composeRule.waitForIdle()

        val customizedOrder = storeVm.visibleHomeShortcuts.value.map { it.id }
        assertNotEquals(HomeShortcutCatalog.DEFAULT_DETAILED_IDS, customizedOrder)
        assertEquals("payment_out", customizedOrder.first())
        assertTrue(customizedOrder.contains("cash_bank"))
        assertFalse(customizedOrder.contains("expenses"))

        // Tap Reset to default -> asks for confirmation
        composeRule.onNodeWithTag("home_edit_reset_btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_reset_confirm_dialog").assertIsDisplayed()

        // Tap Cancel -> customized shortcuts remain intact
        composeRule.onNodeWithTag("home_edit_reset_cancel_btn").performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("home_reset_confirm_dialog").fetchSemanticsNodes().size)
        assertEquals(customizedOrder, storeVm.visibleHomeShortcuts.value.map { it.id })

        // Tap Reset again and Confirm -> defaults restored
        composeRule.onNodeWithTag("home_edit_reset_btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_edit_reset_confirm_btn").performClick()
        composeRule.waitForIdle()

        assertEquals(HomeShortcutCatalog.DEFAULT_DETAILED_IDS, storeVm.visibleHomeShortcuts.value.map { it.id })
        val prefs = HomeLayoutPreferences.forStore(context, "demo_store")
        assertNull(prefs.loadShortcuts("demo_user", HomeLayoutMode.DETAILED))
    }

    @Test
    fun `long-pressing empty Home space enters edit mode and Clean Home places strategy card at the bottom`() {
        val repo = StoreRepository(db = null, appContext = context)
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        runBlocking {
            repo.saveBusinessStrategy(
                "demo_store",
                SavedBusinessStrategy(
                    dateKey = todayKey,
                    generatedAtMs = System.currentTimeMillis(),
                    diagnosis = "আজকে বিক্রি ভালো হয়েছে।",
                    focusLine = "বাকি আদায়ে মন দিন।",
                    actions = listOf(
                        StrategyActionAdvice(text = "বকেয়া গ্রাহকদের কল করুন", target = "BAKI")
                    )
                )
            )
        }

        val (_, authVm, storeVm) = createControllerPair(storeId = "demo_store", userId = "demo_user", repository = repo)
        storeVm.setLanguage(AppLanguage.EN)
        storeVm.setSimpleMode(false)

        composeRule.setContent {
            StoreOSTheme {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.EN) {
                    MainShopScreen(authViewModel = authVm, storeViewModel = storeVm)
                }
            }
        }
        composeRule.waitForIdle()

        // Detailed Clean Home: SELL button, today's key numbers, shortcuts, and strategy card at the very bottom
        composeRule.onNodeWithTag("home_new_sale_btn").assertIsDisplayed()
        composeRule.onNodeWithTag("home_todays_summary_card").assertIsDisplayed()

        composeRule.onNodeWithTag("home_dashboard_list").performScrollToKey("detailed_business_strategy")
        composeRule.waitForIdle()
        val strategyCard = composeRule.onNodeWithTag("home_business_strategy_card")
        strategyCard.assertIsDisplayed()
        val shortcutsBounds = composeRule.onNodeWithTag("home_shortcuts_section").fetchSemanticsNode().boundsInRoot
        val strategyBounds = strategyCard.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Strategy card (${strategyBounds.top}) must be below shortcuts section (${shortcutsBounds.bottom}) on Detailed Home",
            strategyBounds.top >= shortcutsBounds.bottom - 1f
        )

        // Long-press empty Home space in Detailed mode -> enters Edit mode
        composeRule.onNodeWithTag("home_dashboard_list").performScrollToKey("home_empty_space_target")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_empty_space_target").performTouchInput { longClick() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home_edit_done_btn").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("home_edit_up_payment_in").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("home_edit_down_payment_in").performScrollTo().assertIsDisplayed()

        // Tap Done exits Edit mode
        composeRule.onNodeWithTag("home_edit_done_btn").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("home_edit_done_btn").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("home_edit_btn").performScrollTo().assertIsDisplayed()

        // Simple Clean Home: strategy tip at the very bottom & long-press empty space enters Edit mode
        composeRule.runOnUiThread { storeVm.setSimpleMode(true) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home_dashboard_list").performScrollToKey("simple_business_strategy")
        composeRule.waitForIdle()
        val simpleStrategy = composeRule.onNodeWithTag("simple_business_strategy")
        simpleStrategy.assertIsDisplayed()
        val simpleShortcutsBounds = composeRule.onNodeWithTag("home_shortcuts_section").fetchSemanticsNode().boundsInRoot
        val simpleStrategyBounds = simpleStrategy.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Strategy tip (${simpleStrategyBounds.top}) must be below shortcuts section (${simpleShortcutsBounds.bottom}) on Simple Home",
            simpleStrategyBounds.top >= simpleShortcutsBounds.bottom - 1f
        )

        composeRule.onNodeWithTag("home_dashboard_list").performScrollToKey("home_empty_space_target")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_empty_space_target").performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home_edit_done_btn").performScrollTo().assertIsDisplayed()
    }
}
