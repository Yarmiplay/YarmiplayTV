package com.yarmiplaytv

enum class DeviceKind {
    TV, PHONE, TABLET, DESKTOP;

    /** Tablets and desktop windows: navigation rail and multi-column layouts. */
    val isLarge: Boolean get() = this == TABLET || this == DESKTOP
}
