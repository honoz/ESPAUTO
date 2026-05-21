/*
 * ESPAUTO
 * Copyright (c) 2026 honoz
 * Licensed under the MIT License.
 */

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

    private val VIDEO_FRAME_RATE = 20
    private val I_FRAME_INTERVAL = 1

    fun start(sampleFrame: Bitmap): Boolean {
        if (isRecording || sampleFrame.isRecycled) return false

        val width = sampleFrame.width
        val height = sampleFrame.height

        try {
            // 初始化外设编码媒体格式，配置 AVC (H.264) 编码器参数
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                // 必须使用指定格式，由于 YUV420Flexible 具备高度兼容性，可适配大多数硬件编码器底层
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 800000)
                setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
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

            // 通过 MediaStore API 在沙盒外部（公共媒体库）创建文件占位符，规避 Android 10+ 存储权限受限问题
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValue) ?: return false
            videoFilePfd = context.contentResolver.openFileDescriptor(uri, "w") ?: return false

            // 构造封装器，利用底层系统的 FileDescriptor 直接将媒体流复用到 MP4 容器文件中
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
        // 动态限流机制：防止上游高频回调导致阻塞队列积压、内存溢出 (OOM)
        if (bitmapEncodeQueue.size >= 25) {
            bitmapEncodeQueue.poll()?.recycle()
        }
        // 进行强制色彩空间规整（降维至RGB_565节省空间），深拷贝内存以防止上游 ImageView 在渲染时发生图形上下文冲突
        bitmap.copy(Bitmap.Config.RGB_565, false)?.let {
            bitmapEncodeQueue.offer(it)
        }
    }

    fun stop() {
        if (!isRecording) return
        isRecording = false

        // 强行中断工作线程的阻塞等待状态（如 poll 的超时阻断），并给 1.5 秒时限让其优雅收尾
        try {
            recordWorkThread?.interrupt()
            recordWorkThread?.join(1500)
        } catch (_: Exception) {}

        // 清空缓存队列并立即解分配 Bitmap 持有的 C++ 底层像素内存，杜绝内存泄漏
        while (bitmapEncodeQueue.isNotEmpty()) {
            bitmapEncodeQueue.poll()?.recycle()
        }

        // 按照反向初始化顺序，依次关闭编码管线，释放硬件编解码核心资源
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
            // 根据 YUV420 标准（Y: 1, U: 0.25, V: 0.25），分配 w * h * 1.5 倍尺寸的连续内存缓冲区
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

                    // 阶段一：向 MediaCodec 硬件环形输入队列申请可用空闲槽位 (Input Buffer)
                    val inIdx = encoder.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        encoder.getInputBuffer(inIdx)?.apply {
                            clear()
                            put(yuvBuf)
                        }
                        encoder.queueInputBuffer(inIdx, 0, yuvBuf.size, timeUs, 0)
                    }

                    // 阶段二：同步轮询媒体输出队列，抽取编码压缩后的 H.264 原始NAL单元字节流
                    val info = MediaCodec.BufferInfo()
                    var outIdx = encoder.dequeueOutputBuffer(info, 10000)
                    while (outIdx >= 0) {
                        val outBuf = encoder.getOutputBuffer(outIdx) ?: break
                        if (info.size > 0) {
                            // 动态轨道初始化：由于 AVC 格式的特定配置帧（如SPS/PPS）在启动时产生，故在初次拿到数据流时再提取格式并激活封装器
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

        // 嵌套遍历矩阵，实现色彩空间从 RGB/ARGB 空间向 YUV (NV21/YUV420SP) 采样标准转换的数学映射
        for (j in 0 until height) {
            for (i in 0 until width) {
                val pixel = argb[j * width + i]
                val r = (pixel shr 16 and 0xFF).toFloat()
                val g = (pixel shr 8 and 0xFF).toFloat()
                val b = (pixel and 0xFF).toFloat()

                // 亮度权重计算公式：提取符合 ITU-R BT.601 标准的 Luma (Y) 分量
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                yuvData[yIndex++] = y.coerceIn(0f, 255f).toInt().toByte()

                // 色度空间下采样：每 2x2 像素块共享一组 U/V 信号，实现 4:2:0 的空间数据压缩
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