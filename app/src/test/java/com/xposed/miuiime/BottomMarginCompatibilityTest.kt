package com.xposed.miuiime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BottomMarginCompatibilityTest {
    @Test
    fun newImeOnNewSystemUsesLegacyBottomMarginPath() {
        assertEquals(34, resolveBottomMarginTargetSdk(deviceSdkVersion = 37, imeTargetSdkVersion = 35))
    }

    @Test
    fun newImeOnOldSystemKeepsItsTargetSdk() {
        assertEquals(35, resolveBottomMarginTargetSdk(deviceSdkVersion = 34, imeTargetSdkVersion = 35))
    }

    @Test
    fun oldImeOnNewSystemKeepsItsTargetSdk() {
        assertEquals(34, resolveBottomMarginTargetSdk(deviceSdkVersion = 37, imeTargetSdkVersion = 34))
    }

    @Test
    fun gestureNavigationDoesNotMoveBottomAreaPastItsVendorOverflow() {
        assertEquals(
            -51f,
            resolveBottomAreaTranslationY(
                isNavigationHandleShown = true,
                navigationInsetBottom = 60,
                bottomAreaOverflow = 51,
            ) ?: Float.NaN,
            0f,
        )
    }

    @Test
    fun smallerNavigationInsetRemainsTheTranslationLimit() {
        assertEquals(
            -60f,
            resolveBottomAreaTranslationY(
                isNavigationHandleShown = true,
                navigationInsetBottom = 60,
                bottomAreaOverflow = 80,
            ) ?: Float.NaN,
            0f,
        )
    }

    @Test
    fun buttonNavigationRestoresVendorPosition() {
        assertEquals(
            0f,
            resolveBottomAreaTranslationY(
                isNavigationHandleShown = false,
                navigationInsetBottom = 60,
                bottomAreaOverflow = 51,
            ) ?: Float.NaN,
            0f,
        )
    }

    @Test
    fun missingInsetsWaitsForPostedRetry() {
        assertNull(
            resolveBottomAreaTranslationY(
                isNavigationHandleShown = true,
                navigationInsetBottom = null,
                bottomAreaOverflow = 51,
            )
        )
    }

    @Test
    fun missingBottomAreaLayoutWaitsForPostedRetry() {
        assertNull(
            resolveBottomAreaTranslationY(
                isNavigationHandleShown = true,
                navigationInsetBottom = 60,
                bottomAreaOverflow = null,
            )
        )
    }
}
