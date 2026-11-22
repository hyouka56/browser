package com.search.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.util.Log
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.cefrium.CefriumBrowser
import com.cefrium.CefriumRenderHandler
import com.cefrium.CefriumRenderHandler.TouchHandle
import com.cefrium.CefriumTextInputMode
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/** Draws Cefrium's off-screen desktop frames into the browser content area. */
internal class OsrBrowserView(
    context: Context,
    private val browser: CefriumBrowser,
    private val scale: Float
) : View(context), CefriumRenderHandler {

    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frameLock = Any()
    private val touchHandles = linkedMapOf<Int, TouchHandle>()
    private var frameBitmap: Bitmap? = null
    private var popupBitmap: Bitmap? = null
    private var popupX = 0
    private var popupY = 0
    private var popupVisible = false
    private var released = false
    private var focusedEditable = false
    private var focusedNodeType = "text"
    private var inputMode = CefriumTextInputMode.NONE
    private var quickMenu: PopupWindow? = null
    private var quickMenuHandled = false
    private var firstFrameLogged = false

    private val inputMethodManager: InputMethodManager
        get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private val layoutListener = OnLayoutChangeListener { _, left, top, right, bottom,
        oldLeft, oldTop, oldRight, oldBottom ->
        val width = right - left
        val height = bottom - top
        if (!released && width > 0 && height > 0 &&
            (width != oldRight - oldLeft || height != oldBottom - oldTop)
        ) {
            runCatching { browser.setSize(width, height) }
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        addOnLayoutChangeListener(layoutListener)
    }

    override fun onPaint(width: Int, height: Int, pixels: ByteBuffer) {
        if (released || width <= 0 || height <= 0) return
        val expectedBytes = width.toLong() * height.toLong() * 4L
        if (expectedBytes > Int.MAX_VALUE || pixels.remaining().toLong() < expectedBytes) return
        try {
            synchronized(frameLock) {
                val target = frameBitmap?.takeIf {
                    !it.isRecycled && it.width == width && it.height == height
                } ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                // Cefrium supplies RGBA in the exact byte order Android ARGB_8888
                // Bitmap.copyPixelsFromBuffer expects; copy before the callback returns.
                target.copyPixelsFromBuffer(pixels.slice().apply { limit(expectedBytes.toInt()) })
                val old = frameBitmap
                frameBitmap = target
                if (old != null && old !== target && !old.isRecycled) old.recycle()
            }
        } catch (_: Throwable) {
            return
        }
        if (!firstFrameLogged) {
            firstFrameLogged = true
            Log.i("Browser", "DESKTOP_OSR_FIRST_FRAME width=$width height=$height")
        }
        postInvalidateOnAnimation()
    }

    override fun onPopupPaint(x: Int, y: Int, width: Int, height: Int, pixels: ByteBuffer) {
        if (released || width <= 0 || height <= 0) return
        val expectedBytes = width.toLong() * height.toLong() * 4L
        if (expectedBytes > Int.MAX_VALUE || pixels.remaining().toLong() < expectedBytes) return
        try {
            synchronized(frameLock) {
                val target = popupBitmap?.takeIf {
                    !it.isRecycled && it.width == width && it.height == height
                } ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                target.copyPixelsFromBuffer(pixels.slice().apply { limit(expectedBytes.toInt()) })
                val old = popupBitmap
                popupBitmap = target
                popupX = x
                popupY = y
                if (old != null && old !== target && !old.isRecycled) old.recycle()
            }
        } catch (_: Throwable) {
            return
        }
        invalidate()
    }

    override fun onPopupShow(show: Boolean) {
        popupVisible = show
        if (!show) {
            synchronized(frameLock) {
                popupBitmap?.recycle()
                popupBitmap = null
            }
        }
        invalidate()
    }

    override fun onPopupSize(x: Int, y: Int, width: Int, height: Int) {
        popupX = x
        popupY = y
    }

    override fun onFocusedNodeChanged(editable: Boolean, value: String, placeholder: String, type: String) {
        focusedEditable = editable
        focusedNodeType = type.lowercase()
        post {
            if (!released) inputMethodManager.restartInput(this)
        }
    }

    override fun onVirtualKeyboardRequested(mode: CefriumTextInputMode) {
        inputMode = mode
        if (mode == CefriumTextInputMode.NONE) {
            focusedEditable = false
            post { inputMethodManager.hideSoftInputFromWindow(windowToken, 0) }
        } else {
            focusedEditable = true
            post {
                if (!released) {
                    requestFocus()
                    inputMethodManager.restartInput(this)
                    inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    override fun onTouchHandleChanged(handle: TouchHandle) {
        synchronized(frameLock) {
            if (handle.enabled && handle.alpha > 0f) touchHandles[handle.id] = handle
            else touchHandles.remove(handle.id)
        }
        postInvalidateOnAnimation()
    }

    override fun onQuickMenu(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        menu: CefriumBrowser.QuickMenuCallback
    ) {
        quickMenu?.dismiss()
        quickMenuHandled = false
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(Color.WHITE)
        }
        fun addAction(label: String, enabled: Boolean, action: () -> Unit) {
            if (!enabled) return
            val item = TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(Color.BLACK)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                setOnClickListener {
                    quickMenuHandled = true
                    quickMenu?.dismiss()
                    action()
                }
            }
            row.addView(item)
        }
        addAction("Cut", menu.canCut()) { menu.cut() }
        addAction("Copy", menu.canCopy()) { menu.copy() }
        addAction("Paste", menu.canPaste()) { menu.paste() }
        if (row.childCount == 0) {
            menu.cancel()
            return
        }
        val popup = PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = true
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            elevation = dp(8).toFloat()
            setOnDismissListener {
                if (!quickMenuHandled) menu.cancel()
            }
        }
        quickMenu = popup
        val location = IntArray(2)
        getLocationOnScreen(location)
        popup.showAtLocation(
            this,
            Gravity.TOP or Gravity.START,
            location[0] + x,
            location[1] + y + height
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(frameLock) {
            frameBitmap?.takeUnless { it.isRecycled }?.let { bitmap ->
                canvas.drawBitmap(bitmap, 0f, 0f, framePaint)
            }
            if (popupVisible) {
                popupBitmap?.takeUnless { it.isRecycled }?.let { bitmap ->
                    canvas.drawBitmap(bitmap, popupX.toFloat(), popupY.toFloat(), framePaint)
                }
            }
            touchHandles.values.forEach { handle ->
                // Handle geometry uses sendTouchEvent coordinates (view pixels / scale).
                val size = TouchHandle.SIZE * scale
                val left = handle.x * scale
                val top = handle.y * scale
                handlePaint.color = Color.argb((handle.alpha.coerceIn(0f, 1f) * 255f).toInt(), 35, 116, 230)
                canvas.drawCircle(left + size / 2f, top + size / 2f, size * 0.36f, handlePaint)
                canvas.drawRect(
                    left + size * 0.40f,
                    top + size * 0.35f,
                    left + size * 0.60f,
                    top + size * 0.92f,
                    handlePaint
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (released) return false
        requestFocus()
        val actionIndex = event.actionIndex.coerceIn(0, max(0, event.pointerCount - 1))
        val mouseInput = event.isFromSource(InputDevice.SOURCE_MOUSE)
        val xPx = event.getX(actionIndex)
        val yPx = event.getY(actionIndex)
        val xDip = xPx / scale
        val yDip = yPx / scale

        if (mouseInput) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                    val button = mouseButtonFromEvent(event.actionButton.takeIf { it != 0 } ?: event.buttonState)
                    if (button != 0) browser.sendMouseDown(xDip.toInt(), yDip.toInt(), button, mouseModifiers(event.metaState))
                    else browser.sendMouseMove(xDip.toInt(), yDip.toInt(), mouseModifiers(event.metaState))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val button = mouseButtonFromEvent(event.actionButton.takeIf { it != 0 } ?: event.buttonState)
                    if (button != 0) browser.sendMouseUp(xDip.toInt(), yDip.toInt(), button, mouseModifiers(event.metaState))
                }
                MotionEvent.ACTION_MOVE -> browser.sendMouseMove(xDip.toInt(), yDip.toInt(), mouseModifiers(event.metaState))
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val handle = synchronized(frameLock) {
                    touchHandles.values.firstOrNull { candidate ->
                        candidate.enabled && xDip >= candidate.x && yDip >= candidate.y &&
                            xDip < candidate.x + TouchHandle.SIZE && yDip < candidate.y + TouchHandle.SIZE
                    }
                }
                // Cefrium tracks which selection handle the pointer hit; forward the original touch.
                browser.sendTouchEvent(0, xDip, yDip, event.getPointerId(actionIndex))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    browser.sendTouchEvent(
                        2,
                        event.getX(index) / scale,
                        event.getY(index) / scale,
                        event.getPointerId(index)
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                browser.sendTouchEvent(1, xDip, yDip, event.getPointerId(actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                for (index in 0 until event.pointerCount) {
                    browser.sendTouchEvent(
                        3,
                        event.getX(index) / scale,
                        event.getY(index) / scale,
                        event.getPointerId(index)
                    )
                }
            }
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (released || !event.isFromSource(InputDevice.SOURCE_MOUSE)) return super.onHoverEvent(event)
        val x = (event.x / scale).toInt()
        val y = (event.y / scale).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> browser.sendMouseMove(x, y, mouseModifiers(event.metaState))
            MotionEvent.ACTION_HOVER_EXIT -> browser.sendMouseLeave(x, y)
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (released || !event.isFromSource(InputDevice.SOURCE_MOUSE)) return super.onGenericMotionEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            val x = (event.x / scale).toInt()
            val y = (event.y / scale).toInt()
            val horizontal = (-event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120f).toInt()
            val vertical = (-event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120f).toInt()
            browser.sendMouseWheel(x, y, horizontal, vertical, mouseModifiers(event.metaState))
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun mouseButtonFromEvent(buttonState: Int): Int = when {
        buttonState and MotionEvent.BUTTON_PRIMARY != 0 -> CefriumBrowser.MOUSE_BUTTON_LEFT
        buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> CefriumBrowser.MOUSE_BUTTON_MIDDLE
        buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> CefriumBrowser.MOUSE_BUTTON_RIGHT
        else -> 0
    }

    private fun mouseModifiers(metaState: Int): Int {
        var modifiers = 0
        if (metaState and KeyEvent.META_SHIFT_ON != 0) modifiers = modifiers or CefriumBrowser.MOUSE_MODIFIER_SHIFT
        if (metaState and KeyEvent.META_CTRL_ON != 0) modifiers = modifiers or CefriumBrowser.MOUSE_MODIFIER_CTRL
        if (metaState and KeyEvent.META_ALT_ON != 0) modifiers = modifiers or CefriumBrowser.MOUSE_MODIFIER_ALT
        return modifiers
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (released) return false
        browser.sendKeyEvent(event)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (released) return false
        browser.sendKeyEvent(event)
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = focusedEditable && inputMode != CefriumTextInputMode.NONE

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!onCheckIsTextEditor() || released) return null
        outAttrs.inputType = when {
            focusedNodeType == "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            focusedNodeType == "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            focusedNodeType == "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            focusedNodeType == "tel" -> InputType.TYPE_CLASS_PHONE
            focusedNodeType == "number" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            focusedNodeType == "textarea" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            inputMode == CefriumTextInputMode.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            inputMode == CefriumTextInputMode.URL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            inputMode == CefriumTextInputMode.TEL -> InputType.TYPE_CLASS_PHONE
            inputMode == CefriumTextInputMode.NUMERIC -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            inputMode == CefriumTextInputMode.DECIMAL -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            else -> InputType.TYPE_CLASS_TEXT
        }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                browser.imeCommitText(text?.toString().orEmpty(), newCursorPosition)
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val composing = text?.toString().orEmpty()
                val caret = if (newCursorPosition > 0) {
                    composing.length + newCursorPosition - 1
                } else {
                    composing.length + newCursorPosition
                }.coerceIn(0, composing.length)
                browser.imeSetComposition(composing, caret, caret)
                return true
            }

            override fun finishComposingText(): Boolean {
                browser.imeFinishComposingText(true)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(min(beforeLength, 64).coerceAtLeast(0)) { sendDeleteKey(KeyEvent.KEYCODE_DEL) }
                repeat(min(afterLength, 64).coerceAtLeast(0)) { sendDeleteKey(KeyEvent.KEYCODE_FORWARD_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event != null) browser.sendKeyEvent(event)
                return true
            }
        }
    }

    private fun sendDeleteKey(code: Int) {
        browser.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        browser.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun release() {
        if (released) return
        released = true
        removeOnLayoutChangeListener(layoutListener)
        quickMenu?.setOnDismissListener(null)
        quickMenu?.dismiss()
        quickMenu = null
        runCatching { inputMethodManager.hideSoftInputFromWindow(windowToken, 0) }
        synchronized(frameLock) {
            frameBitmap?.recycle()
            popupBitmap?.recycle()
            frameBitmap = null
            popupBitmap = null
            touchHandles.clear()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
