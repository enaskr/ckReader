package com.ckchoi.elibrary.common

import android.app.Activity

/**
 * Runtime registry populated once by the single e-Library Application.
 * Targets are Class objects from modules packaged into the same APK; no package lookup is used.
 */
object AppNavigationRegistry {
    private val targets = linkedMapOf<String, Class<out Activity>>()

    @Synchronized
    fun register(title: String, clazz: Class<out Activity>) {
        targets[title] = clazz
    }

    @Synchronized
    fun target(title: String): Class<out Activity> =
        targets[title] ?: error("등록되지 않은 내부 화면입니다: $title")
}
