package com.github.kr328.clash.widget

internal enum class WidgetToggleAction { Start, Stop, ChooseProfile, GrantPermission }

internal fun widgetToggleAction(running: Boolean, profileReady: Boolean, permissionRequired: Boolean): WidgetToggleAction = when {
    running -> WidgetToggleAction.Stop
    !profileReady -> WidgetToggleAction.ChooseProfile
    permissionRequired -> WidgetToggleAction.GrantPermission
    else -> WidgetToggleAction.Start
}
