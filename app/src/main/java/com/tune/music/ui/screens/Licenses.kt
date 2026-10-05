package com.tune.music.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tune.music.MainViewModel
import com.tune.music.Screen
import com.tune.music.ui.components.MText
import com.tune.music.ui.components.MetroButton
import com.tune.music.ui.components.PageHeader
import com.tune.music.ui.components.VSpace
import com.tune.music.ui.components.metroClick
import com.tune.music.ui.theme.Metro
import com.tune.music.ui.theme.MetroType

/** One piece of software in the app, with its licence text (an asset under licenses/). */
data class Component(val name: String, val by: String, val license: String, val files: List<String>, val note: String = "")

const val SOURCE_URL = "https://github.com/Gabrielcarvfer/tune"

/** Everything shipped in the app, with the licence each is used under. */
val COMPONENTS = listOf(
    Component("Tune", "Tune's contributors", "GPL-3.0", listOf("GPL-3.0.txt")),
    Component(
        "Chromaprint 1.5.1", "Lukas Lalinsky (includes code from FFmpeg)", "LGPL-2.1", listOf("Chromaprint-LICENSE.txt", "LGPL-2.1.txt"),
        "Unmodified, statically linked. Source: github.com/acoustid/chromaprint",
    ),
    Component("KISS FFT", "Mark Borgerding (bundled with Chromaprint)", "BSD-3-Clause", listOf("KissFFT-BSD-3-Clause.txt")),
    Component(
        "TagLib 2.0.2", "TagLib developers", "LGPL-2.1", listOf("LGPL-2.1.txt"),
        "Unmodified, statically linked. Source: github.com/taglib/taglib",
    ),
    Component("UTF8-CPP 4.0.5", "Nemanja Trifunovic", "BSL-1.0", listOf("utfcpp-LICENSE.txt")),
    Component("Selawik font", "Selawik font authors", "OFL-1.1", listOf("OFL-1.1-Selawik.txt")),
    Component("AndroidX, Jetpack Compose, Media3, Material icons", "The Android Open Source Project", "Apache-2.0", listOf("Apache-2.0.txt")),
    Component("Kotlin, kotlinx.coroutines", "JetBrains", "Apache-2.0", listOf("Apache-2.0.txt")),
    Component("Coil", "Coil contributors", "Apache-2.0", listOf("Apache-2.0.txt")),
    Component("Guava", "The Guava Authors", "Apache-2.0", listOf("Apache-2.0.txt")),
)

/** The open-source licences page (settings → about). */
@Composable
fun LicensesScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val c = Metro.colors
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            PageHeader("about", "open-source licences")
            Column(Modifier.padding(horizontal = 24.dp)) {
                MText(
                    "Tune is free software under the GNU GPL 3.0. Its complete source code, including how the " +
                        "libraries below are built in, is at $SOURCE_URL, so you can rebuild it with your own " +
                        "versions of them.",
                    MetroType.small, color = c.subtle, maxLines = 6,
                )
                VSpace(10)
                MetroButton("source code") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }
                VSpace(16)
            }
        }
        items(COMPONENTS, key = { it.name }) { comp ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag("license:${comp.name}")
                    .metroClick { vm.navigate(Screen.LicenseText(comp.name)) }
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                MText(comp.name, MetroType.medium)
                MText("${comp.license} • ${comp.by}", MetroType.small, color = c.subtle, maxLines = 2)
                if (comp.note.isNotEmpty()) MText(comp.note, MetroType.small, color = c.subtle, maxLines = 3)
            }
        }
    }
}

/** A component's licence text(s), read from the app's assets. */
@Composable
fun LicenseTextScreen(name: String) {
    val ctx = LocalContext.current
    val comp = COMPONENTS.firstOrNull { it.name == name } ?: return
    val text = remember(name) {
        comp.files.joinToString("\n\n————————\n\n") { f ->
            ctx.assets.open("licenses/$f").bufferedReader().use { it.readText() }
        }
    }
    // Licences are long; one item per paragraph keeps the list lazy.
    val paragraphs = remember(text) { text.split(Regex("\n\\s*\n")) }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 48.dp)) {
        item { PageHeader(comp.license, comp.name, Modifier.padding(start = 0.dp)) }
        items(paragraphs.size) { i ->
            MText(paragraphs[i].trim(), MetroType.small, maxLines = Int.MAX_VALUE, modifier = Modifier.padding(bottom = 10.dp))
        }
    }
}
