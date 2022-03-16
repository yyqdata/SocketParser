package com.example.socketlib.socket

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.socketlib.constant.MessageConstant
import com.leador.ma.commonlibrary.LogAR
import com.leador.ma.commonlibrary.utils.ByteUtils
import com.leador.ma.commonlibrary.utils.MToast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * //                    .::::.
 * //                  .::::::::.
 * //                 :::::::::::
 * //             ..:::::::::::'
 * //           '::::::::::::'
 * //             .::::::::::
 * //        '::::::::::::::..
 * //             ..::::::::::::.
 * //           ``::::::::::::::::
 * //            ::::``:::::::::'        .:::.
 * //           ::::'   ':::::'       .::::::::.
 * //         .::::'      ::::     .:::::::'::::.
 * //        .:::'       :::::  .:::::::::' ':::::.
 * //       .::'        :::::.:::::::::'      ':::::.
 * //      .::'         ::::::::::::::'         ``::::.
 * //  ...:::           ::::::::::::'              ``::.
 * // ```` ':.          ':::::::::'                  ::::..
 * //                    '.:::::'                    ':'````..
 *
 *
 * @description Socket 接收线程处理基类  T: 待解析生成数据类型
 * @author Create by YanYuQi on 2021/5/21 10:50 AM
 * @fileName AbsSocketThread
 */
abstract class AbsSocketThread<T>(protected val host: String, protected val port: Int) : Thread() {
    protected var receiveSocket: Socket? = null
    protected var isConnected = false
    protected var bis: BufferedInputStream? = null
    protected var bos: BufferedOutputStream? = null
    protected var sendThread: SocketSendThread? = null

    override fun run() {
        super.run()
        if (!connect()) {
            SocketParser.logD("连接失败!")
            runOnUiThread { MToast.show("连接失败!", Toast.LENGTH_SHORT) }
            return
        }
        while (isConnected) {
            if (!receiveSocket!!.isClosed) {
                if (receiveSocket!!.isConnected) {
                    if (!receiveSocket!!.isInputShutdown) {
                        try {
                            if (toggleParse()) {
                                parseData(generateDao())
                            }
                            SocketParser.logD("receive...")
                        } catch (e: IOException) {
                            SocketParser.logE("SocketThread 线程终止： ${e.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * 开启数据解析
     */
    abstract fun toggleParse(): Boolean

    /**
     * 生成带解析BeanDao对象
     */
    abstract fun generateDao(): T

    /**
     * 解析数据
     */
    @Throws(IOException::class)
    abstract fun parseData(dataInfo: T)

    private fun runOnUiThread(runnable: () -> Unit) {
        Handler(Looper.getMainLooper()).post(runnable)
    }

    open fun connect(): Boolean {
        if (isConnected) {
            return true
        }
        val b = booleanArrayOf(false)
        try {
            receiveSocket = Socket()
            SocketParser.logD("connecting...")
            receiveSocket!!.connect(InetSocketAddress(host, port), 5000)
            bis = BufferedInputStream(receiveSocket!!.getInputStream())
            bos = BufferedOutputStream(receiveSocket!!.getOutputStream())
            sendThread = SocketSendThread(bos, false)
            sendThread!!.start()
            isConnected = true
            b[0] = true
            return b[0]
        } catch (e: IOException) {
            closeSocket()
            SocketParser.logE("找不到对应的主机..." + e.message!!)
        }
        return b[0]
    }

    open fun sendData(buff: ByteArray?): Boolean {
        if (sendThread != null && !sendThread!!.isInterrupted) {
            sendThread!!.setData(buff)
            return true
        }
        return false
    }

    open fun cancelSend() {
        if (sendThread != null && !sendThread!!.isInterrupted) {
            sendThread!!.cancel()
        }
    }

    open fun closeSocket() {
        isConnected = false
        if (sendThread != null && sendThread!!.isAlive) {
            sendThread!!.stopSendHeart()
            sendThread!!.interrupt()
            sendThread = null
        }
        this.interrupt()
        ByteUtils.closeStream(bis)
        ByteUtils.closeStream(bos)
        ByteUtils.closeStream(receiveSocket)
    }
}