/*
 * ESPAUTO
 * Copyright (c) 2026 honoz
 * Licensed under the MIT License.
 */

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

    // 协议魔数定义：校验 BLE 传输的数据包是否为合法图像流首部的 Magic Number (0xABCD)
    private val VIDEO_FRAME_HEAD = byteArrayOf(0xAB.toByte(), 0xCD.toByte())

    // 串行队列线程池：确保单点流入的 BLE 碎片化网络包严格按照时序进行报文拼接与粘包处理
    private val packetAssemblerExecutor = Executors.newSingleThreadExecutor()

    // 并行计算线程池：由于 JPEG 分离解码是 CPU 密集型任务，故采用多核并发，加速单帧矩阵的位图反序列化
    private val cpuCount = Runtime.getRuntime().availableProcessors()
    private val decoderExecutor = ThreadPoolExecutor(
        cpuCount, cpuCount * 2,
        1L, TimeUnit.SECONDS,
        LinkedBlockingQueue(8),
        // 饱和策略重写：当高频流造成积压时，抛弃历史最陈旧的未处理帧，保证图传画面在弱网环境下的绝对低延时
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

            // 头部边界判定：若数据包契合魔数，则提取后续的双字节作为整张 JPEG 帧的长宽大端整数（Length Indicator）
            if (data.size >= 4 && h1 == VIDEO_FRAME_HEAD[0] && h2 == VIDEO_FRAME_HEAD[1]) {
                val lh = data[2].toInt() and 0xFF
                val ll = data[3].toInt() and 0xFF
                synchronized(frameBufferStream) {
                    frameBufferStream.reset()
                    expectFrameTotalLen = (lh shl 8) or ll
                }
                return@execute
            }

            // 数据负载拼接：进入流式写入环节，直至流内缓冲区总字节数到达协议头内预期的长度
            synchronized(frameBufferStream) {
                if (expectFrameTotalLen <= 0) {
                    frameBufferStream.reset()
                    return@execute
                }

                frameBufferStream.write(data)

                if (frameBufferStream.size() >= expectFrameTotalLen) {
                    val jpegBytes = frameBufferStream.toByteArray()

                    // 脱离当前排队线程，将完整帧异步塞入解码器线程池，防止因解码耗时卡死网络包接收
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
            // 将二进制 JPEG 数据还原为系统可渲染的位图对象（此处必须每次生成全新实例，规避与 UI 线程重绘引用的只读冲突）
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
            if (bmp.width <= 0 || bmp.height <= 0) {
                return
            }

            // 滑动窗口式 FPS 计数：在一秒的物理时间跨度内计算成功渲染的帧总数
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
        // 强行关闭两级线程管理管线，清空底层任务栈并阻断可能正在运行的 CPU 密集型任务
        packetAssemblerExecutor.shutdownNow()
        decoderExecutor.shutdownNow()
    }
}