package com.example.socketlib.socket

import com.leador.ma.commonlibrary.utils.ByteUtils

/**
 * socket 解析结果bean 根据协议解析出数据bean 可扩展
 * 被解析属性必须是字节数组
 */
open class SocketBean {
    @ParseIndex(0)
    open var header: ByteArray = ByteArray(0)
        set(value) {
            field = value
            empty = value.isEmpty()
        }

    @ParseIndex(1)
    open var type: ByteArray = ByteArray(0)
        set(value) {
            field = value
            empty = value.isEmpty()
        }

    @ParseIndex(2)
    open var bodyLength: ByteArray = ByteArray(0)
        set(value) {
            field = value
            empty = value.isEmpty()
        }

    var body: ByteArray = ByteArray(0)
        set(value) {
            field = value
            empty = value.isEmpty()
        }

    @ParseIndex(3, true)
    open var crc32: ByteArray = ByteArray(0)
        set(value) {
            field = value
            empty = value.isEmpty()
        }

    @ParseIndex(4)
    open var end: ByteArray = ByteArray(0)
        set(value) {
            field = value
            empty = value.isEmpty()
        }

    var crcChecked = false // 通过校验数据才能使用

    var empty = true // 当前bean是否是空
        private set

    var stopParseType = SocketParser.StopParseType.NORMAL // 当前流是否结束

    open fun checkCRC(): Short {// 默认按无符号方式 校验
        var crc = 0
//        var crc: UInt = 0u
        if (header.isNotEmpty()) {
            empty = false
            for (i in header.indices) {
                val tmp: Int = header[i].toInt() and 0xff
//                val tmp: UByte = header[i].toUByte()
                crc += tmp
            }
        }
        if (type.isNotEmpty()) {
            empty = false
            for (i in type.indices) {
                val tmp: Int = type[i].toInt() and 0xff
//                val tmp: UByte = type[i].toUByte()
                crc += tmp
            }
        }
        if (bodyLength.isNotEmpty()) {
            empty = false
            for (i in bodyLength.indices) {
                val tmp: Int = bodyLength[i].toInt() and 0xff
//                val tmp: UByte = bodyLength[i].toUByte()
                crc += tmp
            }
        }
        if (body.isNotEmpty()) {
            empty = false
            for (i in body.indices) {
                val tmp: Int = body[i].toInt() and 0xff
//                val tmp: UByte = body[i].toUByte()
                crc += tmp
            }
        }
        if (end.isNotEmpty()) {
            empty = false
            for (i in end.indices) {
                val tmp: Int = end[i].toInt() and 0xff
//                val tmp: UByte = end[i].toUByte()
                crc += tmp
            }
        }
        return crc.toShort()
//        return crc.toUShort().toShort()
    }

    open fun totalBeanSize(): Long =
        (header.size + type.size + bodyLength.size + body.size + crc32.size + end.size).toLong()

    open fun clear() {
        header = ByteArray(0)
        type = ByteArray(0)
        bodyLength = ByteArray(0)
        body = ByteArray(0)
        crc32 = ByteArray(0)
        end = ByteArray(0)
        crcChecked = false
        empty = true
        stopParseType = SocketParser.StopParseType.NORMAL
    }

    open fun copyOf(): SocketBean {
        val socketBean = SocketBean()
        socketBean.header = header.copyOf()
        socketBean.type = type.copyOf()
        socketBean.bodyLength = bodyLength.copyOf()
        socketBean.body = body.copyOf()
        socketBean.crc32 = crc32.copyOf()
        socketBean.end = end.copyOf()
        socketBean.crcChecked = crcChecked
        socketBean.empty = empty
        socketBean.stopParseType = stopParseType
        return socketBean
    }

    //type.contentToString()
    override fun toString(): String {
        return "SocketBean(header=${header.contentToString()}, " +
                "type=${ByteUtils.byteArrToHexString(type)}, bodyLength=${bodyLength.contentToString()}, " +
                "body=${String(body)}, crc32=${ByteUtils.byteArrToHexString(crc32)}, " +
                "end=${end.contentToString()}, crcChecked=$crcChecked, empty=$empty, stopParseType=$stopParseType)"
    }
}