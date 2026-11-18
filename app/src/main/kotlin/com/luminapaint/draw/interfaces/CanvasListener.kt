package com.luminapaint.draw.interfaces

interface CanvasListener {
    fun toggleUndoVisibility(visible: Boolean)

    fun toggleRedoVisibility(visible: Boolean)

    fun toggleHasContent(hasContent: Boolean)
}
