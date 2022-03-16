package com.example.socketlib.socket

import com.example.socketlib.constant.MessageConstant
import com.leador.ma.commonlibrary.LogAR
import com.leador.ma.commonlibrary.utils.ByteBufferUtils
import com.leador.ma.commonlibrary.utils.ByteUtils
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*

/**
 * 接收Socket数据
 * Created by yanyuqi on 2018/12/25.
 */
class SocketThread(host: String, port: Int) : AbsSocketThread<SocketBean>(host, port) {
    private val lock: Any = Any()
    private lateinit var byteBuffer: ByteBuffer
    private var ignoreLast = false

    @Volatile
    private var immediatelyRequest: Boolean? = null
        set(value) = synchronized(lock) {
            field = value
        }

    @Volatile
    private var currentPosInfo: SocketBean? = null//外部线程调用，不能直接用

    override fun toggleParse(): Boolean = !isIgnoreLast()

    override fun generateDao(): SocketBean = SocketBean()

    //TODO 优化ByteBuffer的创建
    @Throws(IOException::class)
    override fun parseData(dataInfo: SocketBean) {

    }

    /**
     * 解析接收数据包
     *
     * @param type
     * @param array
     * @param posInfo
     */
    @Throws(IOException::class)
    private fun parseDataByType(type: Short, array: ByteArray, posInfo: SocketBean) {

    }

    override fun sendData(buff: ByteArray?): Boolean {
        if (sendThread != null && !sendThread!!.isInterrupted) {
            ignoreLast = false
            sendThread!!.setData(buff)
            return true
        }
        return false
    }

    override fun cancelSend() {
        if (sendThread != null && !sendThread!!.isInterrupted) {
            sendThread!!.cancel()
            ignoreLast = true
        }
    }

    private fun ignore() {
        if (ignoreLast) {
            immediatelyRequest = null
        }
    }

    fun isIgnoreLast() = ignoreLast
}