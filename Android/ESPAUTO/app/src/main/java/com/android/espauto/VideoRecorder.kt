package com.android.espauto

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class VideoRecorder(private val context: Context) {

    var isRecording = false
        private set

    private var videoEncoder: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoFilePfd: ParcelFileDescriptor? = null
    private var muxerStartSuccess = false
    private var videoTrackIndex = -1

    private val bitmapEncodeQueue = LinkedBlockingQueue<Bitmap>(30)
    private var recordWorkThread: Thread? = null

    private val videoFrameRate = 20
    private val iFrameInterval = 1

    fun start(sampleFrame: Bitmap): Boolean {
        if (isRecording || sampleFrame.isRecycled) return false

        val width = sampleFrame.width
        val height = sampleFrame.height

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 800000)
                setInteger(MediaFormat.KEY_FRAME_RATE, videoFrameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            }

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }

            val fileName = "VID_${System.currentTimeMillis()}.mp4"
            val contentValue = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ESPAUTO")
            }

            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValue) ?: return false
            videoFilePfd = context.contentResolver.openFileDescriptor(uri, "w") ?: return false

            mediaMuxer = MediaMuxer(videoFilePfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            bitmapEncodeQueue.clear()
            isRecording = true
            muxerStartSuccess = false

            startEncodeThread(width, height)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            stop()
            return false
        }
    }

    fun feedFrame(bitmap: Bitmap) {
        if (!isRecording) return
        if (bitmapEncodeQueue.size >= 25) {
            bitmapEncodeQueue.poll()?.recycle()
        }
        bitmap.copy(Bitmap.Config.RGB_565, false)?.let {
            bitmapEncodeQueue.offer(it)
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false

        try {
            recordWorkThread?.interrupt()
            recordWorkThread?.join(1500)
        } catch (_: Exception) {}

        while (bitmapEncodeQueue.isNotEmpty()) {
            bitmapEncodeQueue.poll()?.recycle()
        }

        try {
            videoEncoder?.stop()
            videoEncoder?.release()
            mediaMuxer?.stop()
            mediaMuxer?.release()
            videoFilePfd?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            videoEncoder = null
            mediaMuxer = null
            videoFilePfd = null
            recordWorkThread = null
        }
    }

    private fun startEncodeThread(width: Int, height: Int) {
        recordWorkThread = Thread {
            val encoder = videoEncoder ?: return@Thread
            val muxer = mediaMuxer ?: return@Thread
            val yuvBuf = ByteArray(width * height * 3 / 2)

            try {
                encoder.start()
                while (isRecording || bitmapEncodeQueue.isNotEmpty()) {
                    val bmp = try {
                        bitmapEncodeQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    } catch (_: InterruptedException) { break }

                    if (bmp.isRecycled) continue
                    val timeUs = System.nanoTime() / 1000
                    convertBitmapToYuv420(bmp, yuvBuf, width, height)

                    val inIdx = encoder.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        encoder.getInputBuffer(inIdx)?.apply {
                            clear()
                            put(yuvBuf)
                        }
                        encoder.queueInputBuffer(inIdx, 0, yuvBuf.size, timeUs, 0)
                    }

                    val info = MediaCodec.BufferInfo()
                    var outIdx = encoder.dequeueOutputBuffer(info, 10000)
                    while (outIdx >= 0) {
                        val outBuf = encoder.getOutputBuffer(outIdx) ?: break
                        if (info.size > 0) {
                            if (!muxerStartSuccess) {
                                videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStartSuccess = true
                            }
                            muxer.writeSampleData(videoTrackIndex, outBuf, info)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        outIdx = encoder.dequeueOutputBuffer(info, 0)
                    }
                    bmp.recycle()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        recordWorkThread?.start()
    }

    private fun convertBitmapToYuv420(bitmap: Bitmap, yuvData: ByteArray, width: Int, height: Int) {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        var yIndex = 0
        var uvIndex = width * height

        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16 and 0xFF).toFloat()
                val g = (pixel shr 8 and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()

                val y = 0.299f * r + 0.587f * g + 0.114f * b
                yuvData[yIndex++] = y.coerceIn(0f, 255f).toInt().toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = (-0.14713f * r - 0.28886f * g + 0.436f * b) + 128f
                    val v = (0.615f * r - 0.51499f * g - 0.10001f * b) + 128f
                    yuvData[uvIndex++] = u.coerceIn(0f, 255f).toInt().toByte()
                    yuvData[uvIndex++] = v.coerceIn(0f, 255f).toInt().toByte()
                }
            }
        }
    }
}