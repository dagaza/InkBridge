package com.inkbridge

import android.view.MotionEvent
import android.view.View
import android.util.Log
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TouchListener(
    private val outputStream: OutputStream,
    private val stylusOnly: Boolean = false  // read once at construction, not per-event
) : View.OnTouchListener, View.OnGenericMotionListener {

    // -------------------------------------------------------------------------
    // PACKET TYPE CONSTANTS — must match protocol.h
    // -------------------------------------------------------------------------
    private val PACKET_TYPE_PEN:   Byte = 0x01
    private val PACKET_TYPE_TOUCH: Byte = 0x02

    // -------------------------------------------------------------------------
    // PRE-ALLOCATED BUFFERS
    //
    // penBuffer: 1 type byte + 22 payload bytes = 23 bytes total.
    //   Reused for every stylus event. The extra byte vs. the old 22-byte
    //   buffer is the framing type prefix.
    //
    // touchBuffer: 1 type byte + 1 fingerCount byte + up to 10 slots × 10 bytes
    //   = 102 bytes max. Realistically always 22 bytes for 2-finger gestures.
    //   Pre-allocating for the maximum avoids any allocation on the hot path.
    // -------------------------------------------------------------------------
    private val penBuffer   = ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN)
    private val touchBuffer = ByteBuffer.allocate(2 + 10 * 10).order(ByteOrder.LITTLE_ENDIAN)

    // Separate lock objects so stylus events and touch events can never block
    // each other. Each buffer is only ever accessed under its own lock.
    private val penLock   = Any()
    private val touchLock = Any()

    private val TAG = "InkBridgeTouch"

    // -------------------------------------------------------------------------
    // 1. INTERCEPT BLOCKING (fixes Samsung edge-gesture interference)
    // -------------------------------------------------------------------------
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            v.parent?.requestDisallowInterceptTouchEvent(true)
        }
        return processEvent(v, event)
    }

    override fun onGenericMotion(v: View, event: MotionEvent): Boolean {
        v.parent?.requestDisallowInterceptTouchEvent(true)
        return processEvent(v, event)
    }

    // -------------------------------------------------------------------------
    // 2. TOP-LEVEL DISPATCHER
    //
    // Routing rules:
    //   • If ANY pointer is a stylus tool → route to the pen path for pointer 0
    //     (stylus buttons, pressure, tilt). Multi-pointer stylus events are not
    //     a real use-case and are intentionally ignored here.
    //   • If ALL pointers are fingers AND pointerCount >= 2 → route to the MT
    //     touch path regardless of the stylusOnly setting (fingers in a
    //     multi-touch gesture are always forwarded so Krita can handle them).
    //   • If pointerCount == 1 AND stylusOnly == true → consume but suppress.
    //   • Otherwise (single finger, stylusOnly == false) → also MT path so a
    //     single finger moving on the canvas works as a pan tool in Krita.
    //
    // Note: we check pointer 0's tool type as the discriminator. On Samsung
    // devices the stylus is always pointer 0 when it is in range.
    // -------------------------------------------------------------------------
    private fun processEvent(view: View, event: MotionEvent): Boolean {
        Log.d(TAG, "toolType=${event.getToolType(0)} action=${event.actionMasked} pointerCount=${event.pointerCount}")
        val pointerCount = event.pointerCount
        val toolType0    = event.getToolType(0)
        val w = view.width.toFloat()
        val h = view.height.toFloat()

        // Determine whether this event belongs to the stylus path or the MT path.
        val hasStylusPointer = (0 until pointerCount).any { i ->
            event.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER ||
            event.getToolType(i) == MotionEvent.TOOL_TYPE_UNKNOWN
        }

        return if (hasStylusPointer) {
            // --- STYLUS PATH ---
            // Single-pointer stylus event. Process exactly as before.
            processStylusEvent(event, w, h)
        } else {
            // --- MULTI-TOUCH FINGER PATH ---
            // stylusOnly gating: if enabled, silently consume single-finger
            // touch so OS gestures are still blocked. Two or more fingers
            // always pass through (the user deliberately wants to gesture).
            if (stylusOnly && pointerCount < 2) {
                return true
            }
            processTouchEvent(event, w, h)
        }
    }

    // -------------------------------------------------------------------------
    // 3. STYLUS PATH (unchanged logic, now prepends 0x01 type byte)
    // -------------------------------------------------------------------------
    private fun processStylusEvent(event: MotionEvent, w: Float, h: Float): Boolean {
        val toolType        = event.getToolType(0)
        val action          = event.actionMasked
        val isButtonPressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val actionWithButton = if (isButtonPressed) action or 32 else action

        // Drain historical batched samples first (preserves stroke fidelity).
        // Historical events only exist on MOVE/HOVER_MOVE; historySize == 0
        // for all other actions so the loop is a no-op there.
        val historySize = event.historySize
        for (i in 0 until historySize) {
            sendPenSample(
                toolType         = toolType,
                actionWithButton = actionWithButton,
                x                = event.getHistoricalX(i),
                y                = event.getHistoricalY(i),
                pressure         = event.getHistoricalPressure(i),
                tiltRad          = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i),
                orientationRad   = event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, i),
                w                = w,
                h                = h
            )
        }

        // Current (most recent) sample.
        sendPenSample(
            toolType         = toolType,
            actionWithButton = actionWithButton,
            x                = event.x,
            y                = event.y,
            pressure         = event.pressure,
            tiltRad          = event.getAxisValue(MotionEvent.AXIS_TILT),
            orientationRad   = event.getAxisValue(MotionEvent.AXIS_ORIENTATION),
            w                = w,
            h                = h
        )

        return true
    }

    // -------------------------------------------------------------------------
    // 4. MULTI-TOUCH FINGER PATH
    //
    // We route to this path when two or more finger pointers are present.
    // Rather than using ScaleGestureDetector (which only gives us an abstract
    // scaleFactor), we send the raw per-pointer XY coordinates directly to the
    // desktop. Linux Protocol B + Krita's touch input handler do the geometry.
    //
    // Key Android pointer lifecycle events we handle:
    //   ACTION_DOWN            — first finger lands (pointerCount always == 1)
    //   ACTION_POINTER_DOWN    — additional finger lands (pointerCount >= 2)
    //   ACTION_MOVE            — one or more fingers moved (all active pointers
    //                            are reported in the same MotionEvent)
    //   ACTION_POINTER_UP      — one finger lifts (others remain active)
    //   ACTION_UP / ACTION_CANCEL — last finger lifts or gesture cancelled
    //
    // For ACTION_POINTER_UP we must send a final packet for the lifting finger
    // with state=0 BEFORE it disappears from getPointerId(); after the event
    // it is gone. All other still-active fingers are also re-reported so the
    // desktop slot state stays coherent.
    //
    // Historical batching: Android batches intermediate MOVE positions into the
    // MotionEvent. We drain historicals here too so fast two-finger pan doesn't
    // stutter. For non-MOVE actions historySize is 0 — the loop is a no-op.
    // -------------------------------------------------------------------------
    private fun processTouchEvent(event: MotionEvent, w: Float, h: Float): Boolean {
        val action       = event.actionMasked
        val pointerCount = event.pointerCount

        when (action) {
            MotionEvent.ACTION_MOVE -> {
                // Drain historicals for all active pointers.
                val historySize = event.historySize
                for (h_idx in 0 until historySize) {
                    val slots = (0 until pointerCount).map { pIdx ->
                        buildSlot(
                            slotId = event.getPointerId(pIdx),
                            state  = 1,
                            x      = event.getHistoricalX(pIdx, h_idx),
                            y      = event.getHistoricalY(pIdx, h_idx),
                            w      = w,
                            h      = h
                        )
                    }
                    sendTouchPacket(slots)
                }
                // Current frame — all active pointers, all state=1.
                val slots = (0 until pointerCount).map { pIdx ->
                    buildSlot(
                        slotId = event.getPointerId(pIdx),
                        state  = 1,
                        x      = event.getX(pIdx),
                        y      = event.getY(pIdx),
                        w      = w,
                        h      = h
                    )
                }
                sendTouchPacket(slots)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // getActionIndex() tells us which pointer is lifting.
                val liftingPointerIndex = event.actionIndex
                val liftingPointerId    = event.getPointerId(liftingPointerIndex)

                // Build one packet that reports:
                //   • The lifting finger as state=0 (tells desktop to set
                //     ABS_MT_TRACKING_ID = -1 for that slot).
                //   • All other still-active fingers as state=1.
                // Sending them together in one packet keeps the desktop's slot
                // table coherent without an extra round-trip.
                val slots = (0 until pointerCount).map { pIdx ->
                    val isLifting = (pIdx == liftingPointerIndex)
                    buildSlot(
                        slotId = event.getPointerId(pIdx),
                        state  = if (isLifting) 0 else 1,
                        x      = event.getX(pIdx),
                        y      = event.getY(pIdx),
                        w      = w,
                        h      = h
                    )
                }
                sendTouchPacket(slots)
            }

            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Report all currently active pointers as down/moving.
                // For ACTION_DOWN this is always just pointer 0 — a single
                // finger contact. The desktop needs this to open the MT slot.
                val slots = (0 until pointerCount).map { pIdx ->
                    buildSlot(
                        slotId = event.getPointerId(pIdx),
                        state  = 1,
                        x      = event.getX(pIdx),
                        y      = event.getY(pIdx),
                        w      = w,
                        h      = h
                    )
                }
                sendTouchPacket(slots)
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                // Last (or all) fingers lifted. Mark everything as state=0.
                // After ACTION_UP pointerCount is 1; after ACTION_CANCEL it
                // may be > 1. We lift every pointer that is still in the event.
                val slots = (0 until pointerCount).map { pIdx ->
                    buildSlot(
                        slotId = event.getPointerId(pIdx),
                        state  = 0,
                        x      = event.getX(pIdx),
                        y      = event.getY(pIdx),
                        w      = w,
                        h      = h
                    )
                }
                sendTouchPacket(slots)
            }

            else -> { /* Ignore any other action codes */ }
        }

        return true
    }

    // -------------------------------------------------------------------------
    // 5. SLOT BUILDER HELPER
    //
    // Normalises raw pixel coordinates to the 0–32767 range and packages them
    // into an anonymous data class. Keeping this separate from the buffer-write
    // step makes the ACTION_POINTER_UP logic readable.
    // -------------------------------------------------------------------------
    private data class FingerSlotData(
        val slotId: Int,
        val state: Int,   // 1 = active, 0 = lifted
        val normX: Int,
        val normY: Int
    )

    private fun buildSlot(slotId: Int, state: Int, x: Float, y: Float, w: Float, h: Float): FingerSlotData {
        val normX = ((x / w) * 32767).toInt().coerceIn(0, 32767)
        val normY = ((y / h) * 32767).toInt().coerceIn(0, 32767)
        return FingerSlotData(slotId, state, normX, normY)
    }

    // -------------------------------------------------------------------------
    // 6. PACKET ENCODERS
    // -------------------------------------------------------------------------

    // Encodes and writes a 0x01 PenPacket (23 bytes on the wire).
    private fun sendPenSample(
        toolType: Int,
        actionWithButton: Int,
        x: Float,
        y: Float,
        pressure: Float,
        tiltRad: Float,
        orientationRad: Float,
        w: Float,
        h: Float
    ) {
        val finalX      = ((x / w) * 32767).toInt().coerceIn(0, 32767)
        val finalY      = ((y / h) * 32767).toInt().coerceIn(0, 32767)
        val pressureInt = (pressure * 4096).toInt().coerceIn(0, 4096)

        // Convert spherical tilt (Android) → Cartesian degrees (Linux stylus).
        val sinTilt      = Math.sin(tiltRad.toDouble())
        val tiltXDeg     = (sinTilt * Math.sin(orientationRad.toDouble()) * 90).toInt()
        val tiltYDeg     = (sinTilt * Math.cos(orientationRad.toDouble()) * 90).toInt()

        synchronized(penLock) {
            penBuffer.clear()
            penBuffer.put(PACKET_TYPE_PEN)         // [1B] framing type byte
            penBuffer.put(toolType.toByte())        // [1B] toolType
            penBuffer.put(actionWithButton.toByte()) // [1B] action (with button flag)
            penBuffer.putInt(finalX)               // [4B] x
            penBuffer.putInt(finalY)               // [4B] y
            penBuffer.putInt(pressureInt)          // [4B] pressure
            penBuffer.putInt(tiltXDeg)             // [4B] tiltX
            penBuffer.putInt(tiltYDeg)             // [4B] tiltY
            // Total: 1 + 22 = 23 bytes

            try {
                outputStream.write(penBuffer.array(), 0, penBuffer.capacity())
            } catch (e: Exception) {
                Log.e(TAG, "Pen write failed", e)
            }
        }
    }

    // Encodes and writes a 0x02 TouchPacket.
    // Wire layout: [1B type=0x02][1B fingerCount][fingerCount × 10B slots]
    //
    // For 2 fingers: 1 + 1 + 20 = 22 bytes payload.
    // We write exactly (2 + slots.size * 10) bytes, not the full buffer capacity,
    // so the receiver doesn't need to know the max-slot buffer size.
    private fun sendTouchPacket(slots: List<FingerSlotData>) {
        if (slots.isEmpty()) return
        val fingerCount = slots.size.coerceAtMost(10)

        synchronized(touchLock) {
            touchBuffer.clear()
            touchBuffer.put(PACKET_TYPE_TOUCH)          // [1B] framing type byte
            touchBuffer.put(fingerCount.toByte())       // [1B] number of slots

            for (i in 0 until fingerCount) {
                val slot = slots[i]
                touchBuffer.put(slot.slotId.toByte())   // [1B] slot/pointer id
                touchBuffer.put(slot.state.toByte())    // [1B] 1=active, 0=lifted
                touchBuffer.putInt(slot.normX)          // [4B] x
                touchBuffer.putInt(slot.normY)          // [4B] y
            }

            val bytesToWrite = 2 + fingerCount * 10
            try {
                outputStream.write(touchBuffer.array(), 0, bytesToWrite)
            } catch (e: Exception) {
                Log.e(TAG, "Touch write failed", e)
            }
        }
    }
}