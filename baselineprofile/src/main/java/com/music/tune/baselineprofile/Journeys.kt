package com.music.tune.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/**
 * What people do in Tune, scripted with UiAutomator: the baseline profile is
 * recorded from these, and the benchmarks time them. Needs music on the device.
 */
const val PACKAGE = "com.music.tune"

/** Only the hub shows this (lists have their own "shuffle all"). */
private const val HUB = "collection"

/** A fresh install asks for these; granting them first skips the prompts. */
fun MacrobenchmarkScope.grantPermissions() {
    device.executeShellCommand("pm grant $PACKAGE android.permission.READ_MEDIA_AUDIO")
    device.executeShellCommand("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS")
}

fun MacrobenchmarkScope.waitForHub() {
    if (!device.wait(Until.hasObject(By.text(HUB)), 10_000)) lost("the music hub didn't show up")
}

/** Fails, leaving the screen in /data/local/tmp/tune-lost.{png,xml} to see where it got lost. */
fun MacrobenchmarkScope.lost(why: String): Nothing {
    device.executeShellCommand("screencap -p /data/local/tmp/tune-lost.png")
    device.executeShellCommand("uiautomator dump /data/local/tmp/tune-lost.xml")
    error(why)
}

fun MacrobenchmarkScope.open(text: String) {
    val item = device.wait(Until.findObject(By.text(text)), 5_000) ?: lost("no \"$text\" on screen")
    item.click()
    settle()
}

/** Lets a screen finish sliding in; until then the old one is still on screen too. */
fun MacrobenchmarkScope.settle() {
    device.waitForIdle()
    Thread.sleep(1_000)
}

/** Back to the hub, without leaving the app. */
fun MacrobenchmarkScope.backToHub() {
    // Each back can take a moment to animate; checking too soon would press
    // back once more, out of the app.
    repeat(6) {
        if (device.wait(Until.hasObject(By.text(HUB)), 2_000)) return
        device.pressBack()
    }
    waitForHub()
}

/** A few quick flings down the list on screen, and back up. */
fun MacrobenchmarkScope.fling(times: Int = 3) {
    val x = device.displayWidth / 2
    val low = device.displayHeight * 3 / 4
    val high = device.displayHeight / 4
    repeat(times) { device.swipe(x, low, x, high, 8); device.waitForIdle() }
    repeat(times) { device.swipe(x, high, x, low, 8); device.waitForIdle() }
}

fun MacrobenchmarkScope.tapAt(fx: Float, fy: Float) {
    device.click((device.displayWidth * fx).toInt(), (device.displayHeight * fy).toInt())
    settle()
}

/** Swipes the pivot (settings pages, an album's pages) sideways. */
fun MacrobenchmarkScope.swipePivot(times: Int) {
    val y = device.displayHeight / 2
    repeat(times) { device.swipe(device.displayWidth * 4 / 5, y, device.displayWidth / 5, y, 20); device.waitForIdle() }
}

/** Songs, albums, artists, playing something, now playing and settings. */
fun MacrobenchmarkScope.browse() {
    open("songs")
    fling()
    tapAt(0.4f, 0.55f) // a song: it starts playing
    backToHub()

    open("albums")
    fling()
    tapAt(0.25f, 0.45f) // an album
    swipePivot(1)
    backToHub()

    open("artists")
    fling()
    tapAt(0.4f, 0.94f) // the mini player along the bottom: now playing
    backToHub()

    open("settings")
    swipePivot(4)
    backToHub()
}
