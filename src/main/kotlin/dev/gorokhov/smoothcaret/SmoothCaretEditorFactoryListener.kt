package dev.gorokhov.smoothcaret

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.awt.Color

class SmoothCaretEditorFactoryListener : EditorFactoryListener {
    private val settings = service<SmoothCaretService>().getSettings()

    // Use WeakReference to avoid memory leaks
    private val highlighters = mutableMapOf<Editor, HighlighterData>()

    private data class HighlighterData(
        val highlighter: RangeHighlighter,
        val renderer: SmoothCaretRenderer,
        val caretListener: CaretListener,
        val documentListener: DocumentListener
    )

    override fun editorCreated(event: EditorFactoryEvent) {
        setupSmoothCaret(event.editor)
    }

    private fun setupSmoothCaret(editor: Editor) {
        if (!settings.isEnabled) return

        if (shouldSkipEditor(editor)) return

        // Clean up any existing setup for this editor (shouldn't happen, but safety first)
        cleanupEditor(editor)

        editor.settings.apply {
            isBlinkCaret = false
            isBlockCursor = false
            isRightMarginShown = false
            lineCursorWidth = 0
            if (settings.replaceDefaultCaret) {
                isCaretRowShown = false
            }
        }

        editor.colorsScheme.setColor(EditorColors.CARET_COLOR, Color(0, 0, 0, 0))

        val markupModel = editor.markupModel
        val docLength = editor.document.textLength.coerceAtLeast(1) // Ensure minimum length
        val highlighter = markupModel.addRangeHighlighter(
            0, docLength, HighlighterLayer.LAST + 1, null, HighlighterTargetArea.EXACT_RANGE
        )

        val renderer = SmoothCaretRenderer(settings)
        highlighter.customRenderer = renderer

        val caretListener = object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (settings.isEnabled && !editor.isDisposed && editor.contentComponent.isShowing) {
                    // Use invokeLater to avoid potential EDT issues
                    javax.swing.SwingUtilities.invokeLater {
                        if (!editor.isDisposed) {
                            editor.contentComponent.repaint()
                        }
                    }
                }
            }
        }

        val documentListener = object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (editor.isDisposed) return

                val newLength = editor.document.textLength.coerceAtLeast(1)
                val highlighterData = highlighters[editor] ?: return

                try {
                    val currentHighlighter = highlighterData.highlighter
                    val startOffset = currentHighlighter.startOffset
                    val endOffset = currentHighlighter.endOffset

                    if (startOffset == 0 && endOffset != newLength) {
                        // Remove old highlighter
                        editor.markupModel.removeHighlighter(currentHighlighter)

                        // Create new highlighter
                        val newHighlighter = editor.markupModel.addRangeHighlighter(
                            0, newLength, HighlighterLayer.LAST + 1, null, HighlighterTargetArea.EXACT_RANGE
                        )
                        newHighlighter.customRenderer = renderer

                        // Update our tracking
                        highlighters[editor] = highlighterData.copy(highlighter = newHighlighter)
                    }
                } catch (e: Exception) {
                    // If something goes wrong, clean up this editor
                    cleanupEditor(editor)
                }
            }
        }

        editor.document.addDocumentListener(documentListener)
        editor.caretModel.addCaretListener(caretListener)

        // Store all components for cleanup
        highlighters[editor] = HighlighterData(highlighter, renderer, caretListener, documentListener)
    }

    private fun shouldSkipEditor(editor: Editor): Boolean {
        return editor.editorKind != EditorKind.MAIN_EDITOR
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        cleanupEditor(event.editor)
    }

    private fun cleanupEditor(editor: Editor) {
        val highlighterData = highlighters.remove(editor)

        if (highlighterData != null) {
            try {
                // Clean up renderer resources
                highlighterData.renderer.cleanup()

                // Remove listeners
                editor.document.removeDocumentListener(highlighterData.documentListener)
                editor.caretModel.removeCaretListener(highlighterData.caretListener)

                // Remove highlighter
                if (!editor.isDisposed) {
                    editor.markupModel.removeHighlighter(highlighterData.highlighter)
                }
            } catch (e: Exception) {
                // Ignore cleanup errors, editor might already be disposed
            }
        }

        // Restore original caret color
        try {
            if (!editor.isDisposed) {
                editor.colorsScheme.setColor(EditorColors.CARET_COLOR, null)
            }
        } catch (e: Exception) {
            // Ignore errors during cleanup
        }

        // Clean up any orphaned entries (editors that might have been disposed without proper cleanup)
        cleanupOrphanedEntries()
    }

    private fun cleanupOrphanedEntries() {
        val iterator = highlighters.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.isDisposed) {
                try {
                    entry.value.renderer.cleanup()
                } catch (e: Exception) {
                    // Ignore cleanup errors
                }
                iterator.remove()
            }
        }
    }
}