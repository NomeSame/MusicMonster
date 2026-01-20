package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.view.View
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class CircularVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val barCount = 120
    private val magnitudes = FloatArray(barCount)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f
    }
    private var visualizer: Visualizer? = null
    private var audioSessionId: Int = 0
    private var shader: SweepGradient? = null

    fun setAudioSessionId(id: Int) {
        if (id == audioSessionId) return
        audioSessionId = id
        setupVisualizer()
    }

    fun setVisualizerColor(accent: androidx.compose.ui.graphics.Color) {
        val colorInt = accent.toArgb()
        val alphaAccent = Color.argb(220, Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
        paint.color = alphaAccent
        invalidate()
    }

    private fun setupVisualizer() {
        visualizer?.release()
        visualizer = null
        if (audioSessionId == 0) return
        try {
            val v = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1]
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft == null) return
                        val bins = fft.size / 2
                        var maxMag = 1f
                        for (i in 0 until barCount) {
                            val index = (i * (bins - 1) / barCount).coerceAtLeast(1)
                            val re = fft[2 * index].toInt()
                            val im = fft[2 * index + 1].toInt()
                            val magnitude = sqrt((re * re + im * im).toDouble()).toFloat()
                            if (magnitude > maxMag) maxMag = magnitude
                            magnitudes[i] = magnitude
                        }
                        val scale = 1f / maxMag
                        for (i in 0 until barCount) {
                            val target = (magnitudes[i] * scale).coerceIn(0f, 1f)
                            val prev = magnitudes[i].coerceIn(0f, 1f)
                            val smoothed = if (target > prev) {
                                prev + (target - prev) * 0.45f
                            } else {
                                prev - (prev - target) * 0.2f
                            }
                            magnitudes[i] = smoothed
                        }
                        val half = barCount / 2
                        for (i in 0 until half) {
                            val j = i + half
                            val avg = (magnitudes[i] + magnitudes[j]) / 2f
                            magnitudes[i] = avg
                            magnitudes[j] = avg
                        }
                        postInvalidateOnAnimation()
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                enabled = true
            }
            visualizer = v
        } catch (_: Throwable) {
            visualizer = null
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        visualizer?.release()
        visualizer = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        shader = SweepGradient(
            cx,
            cy,
            intArrayOf(
                Color.parseColor("#FF7A00"),
                Color.parseColor("#FFD24A"),
                Color.parseColor("#6BFF7A"),
                Color.parseColor("#4FD6FF"),
                Color.parseColor("#4A7BFF"),
                Color.parseColor("#9B4AFF"),
                Color.parseColor("#FF7A00")
            ),
            null
        )
        paint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width == 0f || height == 0f) return

        val centerX = width / 2f
        val centerY = height / 2f
        val minDim = min(width, height)
        val baseRadius = minDim * 0.26f
        val maxLen = minDim * 0.22f

        for (i in 0 until barCount) {
            val angle = (i / barCount.toFloat()) * (Math.PI * 2.0) - (Math.PI / 2.0)
            val len = magnitudes[i].coerceIn(0f, 1f) * maxLen
            val startX = centerX + (baseRadius) * cos(angle).toFloat()
            val startY = centerY + (baseRadius) * sin(angle).toFloat()
            val endX = centerX + (baseRadius + len) * cos(angle).toFloat()
            val endY = centerY + (baseRadius + len) * sin(angle).toFloat()
            canvas.drawLine(startX, startY, endX, endY, paint)
        }

        if (visualizer != null) {
            postInvalidateOnAnimation()
        }
    }
}
