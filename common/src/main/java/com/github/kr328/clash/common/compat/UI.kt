@file:Suppress("DEPRECATION")

package com.github.kr328.clash.common.compat

import android.annotation.TargetApi
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat

var Window.isSystemBarsTranslucentCompat: Boolean
    get() {
        throw UnsupportedOperationException("set value only")
    }
    set(value) {
        WindowCompat.setDecorFitsSystemWindows(this, !value)

        if (Build.VERSION.SDK_INT >= 30) {
            // SHORT_EDGES is deprecated from Android 15, where ALWAYS is what edge-to-edge enforces.
            attributes.layoutInDisplayCutoutMode = if (value) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        } else if (Build.VERSION.SDK_INT >= 28) {
            attributes.layoutInDisplayCutoutMode = if (value) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
    }

var Window.isLightStatusBarsCompat: Boolean
    get() {
        throw UnsupportedOperationException("set value only")
    }
    @TargetApi(23)
    set(value) {
        WindowCompat.getInsetsController(this, decorView).isAppearanceLightStatusBars = value
    }

var Window.isLightNavigationBarCompat: Boolean
    get() {
        throw UnsupportedOperationException("set value only")
    }
    @TargetApi(27)
    set(value) {
        WindowCompat.getInsetsController(this, decorView).isAppearanceLightNavigationBars = value
    }

var Window.isAllowForceDarkCompat: Boolean
    get() {
        return if (Build.VERSION.SDK_INT >= 29) {
            decorView.isForceDarkAllowed
        } else {
            false
        }
    }
    set(value) {
        if (Build.VERSION.SDK_INT >= 29) {
            decorView.isForceDarkAllowed = value
        }
    }