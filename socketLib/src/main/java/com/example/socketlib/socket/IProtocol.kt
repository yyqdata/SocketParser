package com.example.socketlib.socket

import com.example.socketlib.constant.MessageConstant
import com.example.socketlib.socket.ParseIndex
import com.example.socketlib.socket.SocketBean

/**
 * socket 通讯协议接口 具体协议由子类实现 可扩展
 *
 * 使用注意：需要根据实际定义的协议来实现该接口，并覆写接口属性的注解，注解中确定属性的解析顺序（由具体协议确定）
 */
interface IProtocol {
    /**
     * 协议默认的头字节
     */
    val DEFAULT_HEAD: ByteArray
        get() = MessageConstant.HEAD

    /**
     * 协议默认的尾字节
     */
    val DEFAULT_END: ByteArray
        get() = MessageConstant.END

    /**
     * 消息协议头所占字节数 长度为0表示不存在协议头
     *
     * @return 字节数
     */
    @ParseIndex(0)
    val messageHeaderLength: Int

    /**
     * 消息协议类型所占字节数 长度为0表示不存在协议类型
     *
     * @return 字节数
     */
    @ParseIndex(1)
    val messageTypeLength: Int

    /**
     * 消息协议消息体长度所占字节数 长度为0表示不存在协议消息体长度 即消息体长度未知
     *
     * @return 字节数
     */
    @ParseIndex(2)
    val messageBodyLength: Int

//    /**
//     * 消息协议消息体所占字节数 长度为0表示不存在协议消息体 即消息体为空
//     *
//     * @return 字节数
//     */
//    @ParseIndex(3)
//    val bodyLength: Int

    /**
     * 消息协议CRC校验所占字节数 长度为0表示不存在协议CRC校验 即无需校验
     *
     * @return 字节数
     */
    @ParseIndex(4, true)
    val messageCRCLength: Int

    /**
     * 消息协议尾所占字节数 长度为0表示不存在协议尾
     *
     * @return 字节数
     */
    @ParseIndex(5)
    val messageEndLength: Int

    /**
     * 获取默认的socketBean 对象
     */
    val newSocketBean: SocketBean
        get() = SocketBean()
}