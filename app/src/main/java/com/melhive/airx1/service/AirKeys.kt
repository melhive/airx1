package com.melhive.airx1.service

import android.accessibilityservice.AccessibilityService

/**
 * AirX1's own big-key keyboard. Builds the tile layout and types through [TextInjector].
 */
class AirKeys(
    private val service: AccessibilityService,
    private val panel: TilePanelView,
    private val onHide: (useGboard: Boolean) -> Unit,
    private val onKey: () -> Unit,
) {
    private var symbols = false
    private var shift = false

    fun refresh() {
        shift = TextInjector.wantsCapital(service)
        build()
    }

    private fun build() {
        val rows = if (symbols) symbolRows() else letterRows()
        if (!TextInjector.hasField(service)) panel.message = "Tap a text field first, then type here"
        else if (panel.message?.startsWith("Tap a text") == true) panel.message = null
        panel.setRows(rows + listOf(controlRow()))
    }

    private fun key(label: String, text: String = label, weight: Float = 1f) =
        Tile("k_$label", label, weight = weight) { type(text) }

    private fun letterRows(): List<List<Tile>> {
        fun row(chars: String) = chars.map { c ->
            val s = if (shift) c.uppercaseChar().toString() else c.toString()
            key(s)
        }
        return listOf(
            row("qwertyuiop"),
            row("asdfghjkl"),
            listOf(Tile("shift", if (shift) "⇧ ON" else "⇧", weight = 1.5f, accent = shift) { shift = !shift; build() }) +
                row("zxcvbnm") +
                listOf(Tile("bksp", "⌫", weight = 1.5f) { backspace() }),
        )
    }

    private fun symbolRows(): List<List<Tile>> = listOf(
        "1234567890".map { key(it.toString()) },
        "@#\$_&-+()/".map { key(it.toString()) },
        "*\"':;!?".map { key(it.toString()) } + listOf(Tile("bksp", "⌫", weight = 1.5f) { backspace() }),
    )

    private fun controlRow(): List<Tile> = listOf(
        Tile("layer", if (symbols) "ABC" else "123", weight = 1.3f) { symbols = !symbols; build() },
        key(",", weight = 0.8f),
        Tile("space", "space", weight = 4f) { type(" ") },
        key(".", weight = 0.8f),
        Tile("enter", "⏎", weight = 1.3f, accent = true) { act { TextInjector.enter(service) } },
        Tile("gboard", "Gboard", weight = 1.5f) { onHide(true) },
        Tile("hide", "✕", weight = 1f) { onHide(false) },
    )

    private fun type(s: String) {
        act { TextInjector.insert(service, s) }
        if (shift && !symbols && s.length == 1 && s[0].isLetter()) {
            shift = false
        }
        if (s == " " || s == ".") shift = TextInjector.wantsCapital(service)
        build()
    }

    private fun backspace() {
        act { TextInjector.backspace(service) }
        build()
    }

    private fun act(block: () -> Boolean) {
        onKey()
        if (!block()) {
            panel.message = if (TextInjector.hasField(service)) {
                "This field doesn't accept AirKeys. Tap Gboard to type there."
            } else {
                "Tap a text field first, then type here"
            }
        }
    }
}
