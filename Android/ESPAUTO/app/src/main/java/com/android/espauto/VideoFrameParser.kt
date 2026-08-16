package com.android.espauto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class VideoFrameParser(private val listener: FrameParseListener) {

    interface FrameParseListener {
        fun onFrameParsed(bitmap: Bitmap, fps: Int)
    }

    private val videoFrameHead = byteArrayOf(0xAB.toByte(), 0xCD.toByte())
    private val packetAssemblerExecutor = Executors.newSingleThreadExecutor()
    private val cpuCount = Runtime.getRuntime().availableProcessors()
    private val decoderExecutor = ThreadPoolExecutor(
        cpuCount, cpuCount * 2,
        1L, TimeUnit.SECONDS,
        LinkedBlockingQueue(8),
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    private val frameBufferStream = ByteArrayOutputStream(16 * 1024)
    private var expectFrameTotalLen = 0

    private var frameCountFps = 0
    private var lastFpsRefreshTime = 0L
    private var currentFps = 0

    fun onRawDataReceived(data: ByteArray) {
        if (data.size < 2) {
            clearBuffer()
            return
        }

        packetAssemblerExecutor.execute {
            val h1 = data[0]
            val h2 = data[1]

            if (data.size >= 4 && h1 == videoFrameHead[0] && h2 == videoFrameHead[1]) {
                val lh = data[2].toInt() and 0xFF
                val ll = data[3].toInt() and 0xFF
                synchronized(frameBufferStream) {
                    frameBufferStream.reset()
                    expectFrameTotalLen = (lh shl 8) or ll
                }
                return@execute
            }

            synchronized(frameBufferStream) {
                if (expectFrameTotalLen <= 0) {
                    frameBufferStream.reset()
                    return@execute
                }

                frameBufferStream.write(data)

                if (frameBufferStream.size() >= expectFrameTotalLen) {
                    val jpegBytes = frameBufferStream.toByteArray()

                    decoderExecutor.execute {
                        decodeAndNotify(jpegBytes)
                    }

                    frameBufferStream.reset()
                    expectFrameTotalLen = 0
                }
            }
        }
    }

    private fun decodeAndNotify(jpeg: ByteArray) {
        try {
            if (jpeg.size < 4) return
            if (jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = true
            }
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return
            if (bmp.width <= 0 || bmp.height <= 0) {
                bmp.recycle()
                return
            }

            frameCountFps++
            val now = System.currentTimeMillis()
            if (now - lastFpsRefreshTime >= 1000) {
                currentFps = frameCountFps
                frameCountFps = 0
                lastFpsRefreshTime = now
            }

            listener.onFrameParsed(bmp, currentFps)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearBuffer() {
        synchronized(frameBufferStream) {
            frameBufferStream.reset()
            expectFrameTotalLen = 0
        }
    }

    fun release() {
        packetAssemblerExecutor.shutdownNow()
        decoderExecutor.shutdownNow()
    }
}