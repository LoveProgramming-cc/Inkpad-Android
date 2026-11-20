package io.github.inkpad.editor

import io.github.inkpad.core.Item
import io.github.inkpad.core.Page

/** What the user copied (items of a selection, or a whole page), for as long as the app runs.
 *  Pasting puts copies of them in: what is held here is never itself placed on a page, so the
 *  same thing can be pasted again, here or in another notebook. A picture keeps its data with
 *  it, so it can still be pasted after the notebook it came from is closed. */
object Clipboard {
    var items: List<Item> = emptyList()
    var page: Page? = null
}
