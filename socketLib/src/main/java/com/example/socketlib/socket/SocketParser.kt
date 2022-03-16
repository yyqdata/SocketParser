package com.example.socketlib.socket

import android.os.Process
import android.util.Log
import com.example.socketlib.constant.MessageConstant
import com.leador.ma.commonlibrary.containByteArray
import com.leador.ma.commonlibrary.limitArray
import com.leador.ma.commonlibrary.nativeUtil.CPUHelper
import com.leador.ma.commonlibrary.remainArray
import com.leador.ma.commonlibrary.utils.ByteBufferUtils
import java.io.BufferedInputStream
import java.io.InputStream
import java.lang.NullPointerException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.jvm.Throws
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaGetter

/**
 * socket消息解析器 根据 IProtocol 解析出 SocketBean返回上层
 * 运行时反射，会对性能产生一定的影响，目前只反射一次
 *
 * @TODO 优化成编译时反射，通过编译时反射生成对应的代码文件...
 */
object SocketParser {
    private const val TAG = "SocketParser"
    private const val DEFAULT_MAX_LOG_SIZE = 3 * 1024 // 默认2k日志大小
    private const val DEFAULT_LOG_MSG_COUNT = 2000 // 默认缓存1000条日志输出
    private const val DEFAULT_SIZE = 4 * 1024 //默认buffer大小
    private const val DEFAULT_READ_WRITE_TIME_OUT: Long = 1000 // 缓冲区读写默认超时
    private const val DEFAULT_MAX_STOP_BY_CACHE_EMPTY = 0 // 最大空缓存停止次数
    private const val DEFAULT_MAX_THREAD_COUNT = 6//最大线程数
    private val CPU_CORES = Runtime.getRuntime().availableProcessors() // 可用核心数
    private val MAX_MEMORY = Runtime.getRuntime().maxMemory() // 最大分配内存
    private val protocolMethods = Collections.synchronizedList(CopyOnWriteArrayList<KCallable<*>>())
    private val socketBeanFields =
        Collections.synchronizedList(CopyOnWriteArrayList<KProperty1<out SocketBean, *>>())
    private var checkCRC = false

    @Volatile
    var parseProgress = AtomicInteger(0) // 是否已经在解析一包数据 0 表示新数据包 其余表示解析进度 （假如消息很长，分两次发送过来）
        private set
    private var tempByteArray: LinkedList<Byte>? = null // 临时保存上一帧数据数组 （假设消息两次之内可以解析完）
    private var parsingBody = false // 是否在解析数据体  假设数据长度包之后就是数据体，中间不能添加其他的消息包
    private val socketBeanQueue = LinkedBlockingQueue<SocketBean>(100)

    // ArrayBlockingQueue 内部实现是一把锁，因此无论是take 还是 put 操作都需要去抢占锁，无法同时操作
//    private val byteCacheQueue = ArrayBlockingQueue<Byte>(DEFAULT_SIZE * 1024)// 缓冲区 默认4M
    // LinkedBlockingQueue 内部是两把锁 takeLock 和 putLock 允许take 和 put 同时操作
    private val byteCacheQueue = LinkedBlockingQueue<Byte>(DEFAULT_SIZE * 1024)// 缓冲区 默认4M
    private var logMsgQueue =
        LinkedBlockingQueue<SocketLogBean>(DEFAULT_LOG_MSG_COUNT) // 日志缓存 默认1000条

    @Volatile // 保证及时性和可见性
    private var parsingData = false // 是否在解析数据

    @Volatile
    private var readingData = false // 是否在读取数据

    private val emptyBean = SocketBean()

    private var bis: InputStream? = null
    private var head = MessageConstant.HEAD
    private var end = MessageConstant.END
    var socketBufferSize = DEFAULT_SIZE
        set(value) {
            field = value
            defaultByteBuffer = ByteBuffer.allocate(value)
        }
    var toggleLog = true

    var logMsgCount = DEFAULT_LOG_MSG_COUNT
        set(value) {
            field = value
            logMsgQueue = LinkedBlockingQueue(value)
        }

    var socketProtocol: IProtocol? = null
        set(value) {
            field = value
            if (value != null) {
                head = value.DEFAULT_HEAD
                end = value.DEFAULT_END
                protocolMethods.clear()
                val methods = value::class.declaredMemberProperties
                for (m: KProperty1<out IProtocol, *> in methods) {
                    val annotation: ParseIndex? =
                        m.javaGetter?.getAnnotation(ParseIndex::class.java)
                    if (annotation != null) {
                        if (annotation.isCRC) {
                            checkCRC = true // 如果有一个属性设置了crc(一般只允许设置一个属性crc)，则默认检查crc
                        }
                        protocolMethods.add(m)
                    }
                }

                protocolMethods.sortWith { o1, o2 ->
                    val index1 =
                        (o1 as KProperty1<*, *>).javaGetter?.getAnnotation(ParseIndex::class.java)?.index
                            ?: 0
                    val index2 =
                        (o2 as KProperty1<*, *>).javaGetter?.getAnnotation(ParseIndex::class.java)?.index
                            ?: 0
                    if (index1 < index2) {
                        -1
                    } else {
                        1
                    }
                }
            }
        }

    var socketBean: SocketBean = SocketBean()
        set(value) {
            field = value
            socketBeanFields.clear()
            val fields = value::class.declaredMemberProperties
            for (f in fields) {
                f.isAccessible = true
                val annotation =
                    f.findAnnotation<ParseIndex>()
                if (annotation != null) {
                    f.isAccessible = true
                    socketBeanFields.add(f)
                }
            }
            socketBeanFields.sortWith { o1, o2 ->
                val index1 =
                    o1.findAnnotation<ParseIndex>()?.index ?: 0
                val index2 =
                    o2.findAnnotation<ParseIndex>()?.index ?: 0
                if (index1 < index2) {
                    -1
                } else {
                    1
                }
            }
        }

    private lateinit var parsingExecutor: ThreadPoolExecutor
    private var submitParsing: Future<*>? = null
    private var submitReading: Future<*>? = null

    private var defaultByteBuffer = ByteBuffer.allocate(socketBufferSize / 4) // 1KB

    // 循环检查剩余数据使用
    private val checkLeftDataBuffer = ByteBuffer.allocate(DEFAULT_SIZE / 4) // 1KB
    private val leftDataBuffer =
        ByteBuffer.allocate(socketBufferSize + DEFAULT_SIZE / 4)// 2KB

    private var parsingTempBuffer: ByteBuffer? = null

    @Volatile
    private var parsingThreadId: Long = -1L // 解析线程ID，保证只有一条解析线程工作

    @Volatile
    private var readingThreadId: Long = -1L // 读取数据线程ID

    private var parsingBodyStartTime: Long = 0//解析Body开始时间
    private var parsingBodyStopTime: Long = 0//解析Body结束时间
    private val readSubmitLock = LinkedBlockingQueue<Int>(1) // 读取任务提交锁
    private val parseSubmitLock = LinkedBlockingQueue<Int>(1) // 解析任务提交锁

    @Volatile
    private var stopParseByCacheCount = AtomicInteger(0) // 因为缓存为空停止解析的次数，防止读取线程无法启动

    /**
     * 结束解析类型
     */
    enum class StopParseType {
        NORMAL,
        CACHE_EMPTY,
        STREAM_END,
        EXCEPTION
    }

    private var stopParseType = StopParseType.NORMAL

    /**
     * 停止解析任务
     */
    fun stopParse() {
        logE(
            "stopParse----结束解析:$stopParseType , " +
                    "socketBeansSize:${socketBeanQueue.size}, " +
                    "parseProgress = ${parseProgress.get()}"
        )
        if (stopParseType == StopParseType.CACHE_EMPTY) {
            // 解析完成缓存为空 重置解析进度
            stopParseByCacheCount.incrementAndGet()
            if (parseProgress.get() == protocolMethods.size) parseProgress.set(0)
        }
        if (submitParsing != null && !submitParsing!!.isDone && !submitParsing!!.isCancelled) {
            submitParsing?.cancel(true) // 连续cancel两次task将永远不会执行
            parsingExecutor.remove(parsingTask)
            submitParsing = null
            parsingThreadId = -1
            parsingData = false
        }
        emptyBean.stopParseType = stopParseType

        try {
            if (stopParseType == StopParseType.EXCEPTION) {
                parsingBody = false
                parseProgress.set(0)
                socketBean.clear()
                logMsgQueue.clear()
                defaultByteBuffer.clear()
                parsingTempBuffer?.clear()
                parsingTempBuffer = null
                tempByteArray?.clear()
                tempByteArray = null
                parsingExecutor.shutdownNow()
            }
        } catch (e: Exception) {
            logE("stopParse 异常")
            e.printStackTrace()
        }
        wakeParsing()
    }

    /**
     * 停止读取任务
     */
    fun stopReading(clear: Boolean = false) {
        logE(
            "stopReading----结束读取 , " +
                    "socketBeansSize:${socketBeanQueue.size}, " +
                    "parseProgress = ${parseProgress.get()}"
        )
        if (submitReading != null && !submitReading!!.isDone && !submitReading!!.isCancelled) {
            submitReading?.cancel(true)
            parsingExecutor.remove(readingTask)
            readingData = false
            readingThreadId = -1
        }
        if (clear) byteCacheQueue.clear()
        logMsgQueue.clear()
    }

    /**
     * 唤醒读取任务
     */
    private fun wakeParsing() {
        if (!parsingData && (stopParseType == StopParseType.CACHE_EMPTY || stopParseType == StopParseType.STREAM_END)) {
            // 缓存为空的时候停止解析需要保证解析任务能再次顺利执行
            if (socketBeanQueue.isEmpty()) {
                emptyBean.stopParseType = stopParseType
                socketBeanQueue.add(emptyBean)
            }
        }
    }

    fun destroy() {
        stopParseType = StopParseType.EXCEPTION
        stopParse()
        stopReading(true)
        stopParseByCacheCount.set(0)
        parsingExecutor.shutdownNow()
    }

    /**
     * 解析数据，带缓冲输入流
     */
    fun parseData(bs: InputStream?): SocketBean? {
        if (socketProtocol == null || bs == null) {
            logE("Socket协议未设置!")
            return null
        }
        if (protocolMethods.isEmpty() || socketBeanFields.isEmpty() || protocolMethods.size != socketBeanFields.size) {
            logE("协议解析内容与预期不匹配! protocolMethods :${protocolMethods.size}, socketBeanFields:${socketBeanFields.size}")
            return null
        }

        val freeMemory = Runtime.getRuntime().freeMemory() // 未分配内存
        val totalMemory = Runtime.getRuntime().totalMemory() // 已分配内存
        val mb = 1024 * 1024
        logE("JVM剩余未分配内存: ${freeMemory / mb} MB; 总内存: ${totalMemory / mb} MB； 最大内存: ${MAX_MEMORY / mb} MB;最大核心数：${CPUHelper.cpuCounts()}")

        bis = bs

        if (parsingExecutor.isShutdown) { // 手动关闭解析器
            initParsingExecutor()
        }

        try {
            //开启读取数据任务
            if (!readingData) {
                logD("开启读取数据任务...")
                submitReading = parsingExecutor.submit(readingTask)
                val startLock = System.currentTimeMillis()
                try {
                    //保证任务开启成功，并避免死循环
                    readSubmitLock.take()
                    val endLock = System.currentTimeMillis()
                    logD("开启读取数据任务耗时：${endLock - startLock}")
                    readingData = true
                } catch (e: Exception) {
                    logE("开启读取数据任务失败！！！!")
                    val endLock = System.currentTimeMillis()
                    logD("开启读取数据任务耗时：${endLock - startLock}")
                }
            }

            if (stopParseByCacheCount.get() > DEFAULT_MAX_STOP_BY_CACHE_EMPTY && readingData) {
                logE("空缓存停止次数过多，停止线程循环等待读取数据1s...")
                stopParseByCacheCount.set(0)
                // 防止读取线程无法从阻塞中恢复
                Thread.sleep(DEFAULT_READ_WRITE_TIME_OUT)
                logE("恢复工作，当前读取到的数据长度: ${byteCacheQueue.size}")
            }

            // 开启解析数据任务
            if (!parsingData) {
                logD("开启解析数据任务...")
                submitParsing = parsingExecutor.submit(parsingTask)// 可能提交失败
                val startLock = System.currentTimeMillis()
                try {
                    //保证任务开启成功，并避免死循环
                    parseSubmitLock.take()// 可能耗时较久 采用take
                    val endLock = System.currentTimeMillis()
                    logD("开启解析数据任务耗时：${endLock - startLock}")
                    parsingData = true
                } catch (e: Exception) {
                    logE("开启解析数据任务失败！！！")
                    val endLock = System.currentTimeMillis()
                    logD("开启解析数据任务耗时：${endLock - startLock}")
                }
            }
        } catch (e: RejectedExecutionException) {
            stopParseType = StopParseType.EXCEPTION
            logE("解析任务被拒绝！解析器可能被关闭！")
            e.printStackTrace()
        } catch (e: NullPointerException) {
            stopParseType = StopParseType.EXCEPTION
            logE("解析任务为null！")
            e.printStackTrace()
        } catch (e: InterruptedException) {
            stopParseType = StopParseType.EXCEPTION
            logE("解析线程被终止！")
            e.printStackTrace()
        }

        return try {
            logE("开始等待数据发送,当前解析完成数据有：${socketBeanQueue.size}，当前剩余CacheSize：${byteCacheQueue.size}")
            wakeParsing()
            val take = socketBeanQueue.take()
            logE("发送成功一包数据,当前解析完成数据有：${socketBeanQueue.size}，当前剩余CacheSize：${byteCacheQueue.size}")
            take
        } catch (e: InterruptedException) {
            logE("数据队列 take 失败！解析线程被中断！")
            stopParseType = StopParseType.EXCEPTION
            e.printStackTrace()
            stopParse()
            emptyBean
        }
    }

    /**
     * 解析任务
     */
    private val parsingTask = Runnable {
        logD("start parsing Thread:${Thread.currentThread().id}, parseProgress =${parseProgress.get()}")
        parsingThreadId = Thread.currentThread().id
        parseSubmitLock.clear()
        parseSubmitLock.put(0)
        if (CPUHelper.cpuCounts() > 2) {
            CPUHelper.bindToCpu(1)
        }
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        stopParseType = StopParseType.NORMAL
        emptyBean.stopParseType = stopParseType
        try {
            Thread.sleep(100) // 缓冲100ms
            // 检查第一次读取的流中是否包含head
            if (parseProgress.get() == 0) { // 如果是新一包数据, 需要阻塞读取，防止死循环占用CPU资源
                logD("开始解析数据，残留数据：$parsingTempBuffer")
                if (parsingTempBuffer == null || parsingTempBuffer?.hasRemaining() == false) {
                    parsingTempBuffer = defaultByteBuffer
                    parsingTempBuffer?.clear()
                }
                parsingTempBuffer?.mark()
                val len = parsingTempBuffer?.let { readCacheToBuffer(it, false) } ?: 0
                logD("开始解析数据，读取到的长度：$len")
                parsingTempBuffer?.limit(parsingTempBuffer?.position() ?: 0)
                parsingTempBuffer?.reset() // 恢复mark
                if (len == 0 && byteCacheQueue.size == 0 && parsingTempBuffer?.hasRemaining() == false) {
                    logE("stop parsing Thread at Head")
                    stopParseType = StopParseType.CACHE_EMPTY
                    stopParse()
                    return@Runnable
                }

                val headIndex = parsingTempBuffer?.limitArray()?.containByteArray(head) ?: -1
                logD("socket读取数据headIndex: $headIndex , cacheSize:${byteCacheQueue.size}")
                if (headIndex == -1) { // 不包含头
                    stopParseType = StopParseType.STREAM_END // 不包含头帧则返回 重新开启解析任务
                    stopParse()
                    return@Runnable
                }
                if (headIndex < parsingTempBuffer?.limit() ?: 0) {
                    val p = parsingTempBuffer?.position() ?: 0
                    parsingTempBuffer?.position(headIndex)// 跳过包头起始脏数据
                }
                socketBean.clear()
                tempByteArray = null
            }

            // 循环解析协议内容
            while (parseProgress.get() < protocolMethods.size) {
                val method: KProperty1<IProtocol, Any> =
                    protocolMethods[parseProgress.get()] as KProperty1<IProtocol, Any>
                val field: KMutableProperty1<SocketBean, Any> =
                    socketBeanFields[parseProgress.get()] as KMutableProperty1<SocketBean, Any>
                val length: Int = method.get(socketProtocol!!) as Int
                logD("属性的数据长度：$length ,当前解析进度：$parseProgress, 是否是解析Body:$parsingBody , cacheSize:${byteCacheQueue.size}")
                if (length == 0) { // 某个协议值为0表示该协议值未设置
                    parseProgress.incrementAndGet()
                    continue
                }

                if (!parsingBody) { // 不是解析body数据
                    // 读取缓存数据并解析
                    val detectData = readCacheAndParse(length)
                    if (detectData != null) { // 读取完整数据项
                        field.set(socketBean, detectData)
//                        logD("socket设置属性值：${field.name} , data: ${detectData.contentToString()}")
                    } else {
                        if (stopParseType != StopParseType.NORMAL) return@Runnable
                        continue
                    }
                }

                // 解析进度增加
                parseProgress.incrementAndGet()

                // bodyLength不为空同时body为空说明刚开始准备解析body  tempByteArray 不为null表示body数据过大未解析完
                // 注意  当前假设 bodyLength 之后就是 body ， 因此 bodyLength 解析完成后将直接开始顺序解析 body
                if ((socketBean.bodyLength.isNotEmpty() && socketBean.body.isEmpty()) || tempByteArray != null) {
                    if (!parsingBody) {
                        parsingBodyStartTime = System.currentTimeMillis()
                    }
                    parsingBody = true // 开始解析 body 数据包
                }

                if (parsingBody && socketBean.bodyLength.isNotEmpty()) { // body长度特殊处理
                    val bodyLength =
                        ByteBufferUtils.byteArray2Int(socketBean.bodyLength)
                    // 读取缓存数据并解析
                    val detectData = readCacheAndParse(bodyLength, true)
                    parsingBodyStopTime = System.currentTimeMillis()
                    logD("解析当前Body耗时：${parsingBodyStopTime - parsingBodyStartTime} ms ")
                    if (detectData != null) {
                        socketBean.body = detectData // 设置body 数据
//                        logD("socket设置body值, data: ${detectData.contentToString()}")
                        parsingBody = false
                    } else {
                        logD(
                            "socket拼接数据体,tempByteArray实际长度：${tempByteArray?.size}"
                        )
                        if (stopParseType != StopParseType.NORMAL) return@Runnable
                        continue
                    }
                    parseProgress.incrementAndGet() // body解析完成进度完成
                }

                if (parseProgress.get() == protocolMethods.size) { // 解析流程结束
                    if (checkCRC) { // 检查CRC
                        val checkCRC1 = socketBean.checkCRC()
                        val crc =
                            ByteBufferUtils.byteArray2Short(socketBean.crc32) // 无符号byte解析
                        if (crc == checkCRC1) {
                            socketBean.crcChecked = true
                            socketBeanQueue.put(socketBean.copyOf())
                            socketBean.clear()
                        } else {
                            // crc 校验不通过
                            logE("checkCRC1:$checkCRC1, crc:$crc")
                            socketBeanQueue.put(socketBean.copyOf())
                            socketBean.clear()
                        }
                    }
                    logD("socket当前数据包解析完成，当前待发送socketBean ：${socketBeanQueue.size} 个。")
                    parsingTempBuffer = bufferingLeftData(parsingTempBuffer)
                    logD("socket当前剩余数据长度: ${parsingTempBuffer?.remaining()},$parsingTempBuffer")
                    if (parsingTempBuffer != null && stopParseType == StopParseType.NORMAL) {
                        parseProgress.set(0) // 循环解析
                        continue
                    } else {
                        stopParse()
                        return@Runnable
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            stopParseType = StopParseType.EXCEPTION
            logE("解析数据失败！IO读取异常！")
            stopParse()
        }
    }

    /**
     * 读取数据任务
     */
    private val readingTask = Runnable {
        val tmpBuf = ByteBuffer.allocate(DEFAULT_SIZE * 2) // 8KB
        val bufferInputStream = BufferedInputStream(bis!!)
        var cacheSize = 0
        try {
            logD("start reading Thread:${Thread.currentThread().id}")
            readSubmitLock.clear()
            readSubmitLock.put(0)
            Thread.sleep(5)
            readingThreadId = Thread.currentThread().id
            if (CPUHelper.cpuCounts() > 3) {
                CPUHelper.bindToCpu(2)
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            while (readingData) {
                tmpBuf.clear()
                val readLen =
                    bufferInputStream.read(tmpBuf.array(), 0, tmpBuf.capacity())// 提高效率，一次读取8k
                if (readLen == -1) {
                    logE("socket读取数据长度为-1,socket关闭!")
                    stopParseType = StopParseType.STREAM_END
                    stopReading()
                    stopParse()
                    return@Runnable
                }
                tmpBuf.limit(readLen)
                logD("读取到的原始数据(len= $readLen)：${String(tmpBuf.limitArray())}")
                for (i in 0 until tmpBuf.limit()) {
                    if (byteCacheQueue.remainingCapacity() == 0) {
                        logE("缓存满了，请等待一会吧...")
                        wakeParsing()
                    }
                    cacheSize = byteCacheQueue.size
                    byteCacheQueue.put(tmpBuf.get())
                }
                logD("缓冲区剩余数据长度: ${byteCacheQueue.size}")
            }
            logE("readingTask finished!!!")
        } catch (e: Exception) {
            logE("Socket 缓冲区读取数据失败! CacheSize:${byteCacheQueue.size}，beansSize:${socketBeanQueue.size}")
            if (stopParseType != StopParseType.CACHE_EMPTY) {
                e.printStackTrace()
                stopParseType = StopParseType.EXCEPTION
                stopReading()
            } else {
                if (cacheSize == byteCacheQueue.size) { // 中断线程时没有入队 tmpBuf 往前移动一位
                    var position = tmpBuf.position() - 1
                    if (position < 0) position = 0
                    tmpBuf.position(position)
                }
                for (i in tmpBuf.position() until tmpBuf.remaining()) {
                    if (byteCacheQueue.remainingCapacity() == 0) {
                        logE("缓存满了，请等待一会吧...")
                    }
                    byteCacheQueue.put(tmpBuf.get())
                }
            }
        }
    }

    /**
     * 读取缓存并解析
     */
    private fun readCacheAndParse(length: Int, parsingBody: Boolean = false): ByteArray? {
        if (parsingBody) parseProgress.decrementAndGet() // 解析body需要额外的进度
        var arrSize = length
        if (tempByteArray != null) {
            arrSize -= tempByteArray!!.size
        }
        if (parsingBody) {
            logD(
                "socket读取data数据体长度bodyLength：$length ，" +
                        "${socketBean.bodyLength.contentToString()}, bodySize:$arrSize, byteBuffer:$parsingTempBuffer"
            )
        }
        // 实际读取的长度
        val arr = ByteArray(arrSize)
        val readLen = readActualLen(parsingTempBuffer, arr, arrSize)
        // byteBuffer 读取完，清空抛弃
        parsingTempBuffer = resetBuffer(parsingTempBuffer)
        if (parsingBody) {
            logD("socket读取data数据体实际长度：$readLen , bodySize:$arrSize")
        } else {
            logD("socket读取流长度：$readLen")
        }

        if (readLen == 0 && byteCacheQueue.isEmpty()) {
            // 缓存为空读取 0 数据需要暂停解析任务释放线程资源，否则可能会进入死循环（CPU核心数较少）抢占线程资源，读取线程无法工作
            stopParseType = StopParseType.CACHE_EMPTY
            stopParse()
            return null
        }
        if (readLen == -2) { // 异常结束
            if (parsingBody) parseProgress.incrementAndGet() // 恢复进度
            stopParse()
            return null
        }
        return detectIntegrality(arr, readLen, length)
    }

    /**
     * 打包剩下的bytebuffer 或者 缓存中的数据
     */
    private fun bufferingLeftData(byteBuffer: ByteBuffer?): ByteBuffer? {
        // 判断是否还有数据未读完 buffer 中数据
        if (byteBuffer != null && byteBuffer.hasRemaining()) {
            // 解完一包后 byteBuffer 仍有数据，无法判断数据重要性，不丢弃
            byteBuffer.compact()
            byteBuffer.flip()
            val headIndex = byteBuffer.limitArray().containByteArray(head)
            logD("socket剩余数据(长度：${byteBuffer.remaining()}, $byteBuffer)中发现包头，headIndex: $headIndex")
            if (headIndex != -1) {
                byteBuffer.position(headIndex) // 跳过包头之前的脏数据
                byteBuffer.compact()// position=remaining limit=capacity
                byteBuffer.flip()
                return byteBuffer
            }
        }
        // 缓存中是否有未读取数据
        val tmpBuffer = checkLeftDataBuffer
        tmpBuffer.clear()
        val readLen = try {
            val br = readCacheToBuffer(tmpBuffer)
            tmpBuffer.position(0)
            if (br == 0 && byteCacheQueue.size == 0) { //二次确认缓存中没有数据
                stopParseType = StopParseType.CACHE_EMPTY
                //buffer中没有数据返回null 否则返回buffer
                return resetBuffer(byteBuffer)

            }
            br
        } catch (e: Exception) {
            stopParseType = StopParseType.EXCEPTION
            e.printStackTrace()
            return resetBuffer(byteBuffer)
        }
        return if (readLen == 0) { // 缓存中没有数据 ， bytebuffer中残留的不包含头的数据就是脏数据， 除了第一次的脏数据后面的都不能丢弃
            logD("socket解析完一包完整数据发现多余脏数据，数据大小:${byteBuffer?.remaining()}！")
            byteBuffer?.compact()
            byteBuffer?.flip()
            if (byteBuffer?.hasRemaining() == true) {
//                byteBuffer.clear()
            }
            resetBuffer(byteBuffer) // buffer中没有数据返回 null 否则返回 buffer
        } else {
            tmpBuffer.limit(readLen)
            val size = readLen + (byteBuffer?.remaining() ?: 0)
            val buffer = ByteBuffer.allocate(size)
//            buffer.clear()
            logD(
                "bufferingLeftData：buffer=$buffer, size=$size, " +
                        "readLen=$readLen, byteBufferRemain=${byteBuffer?.remaining()} ," +
                        "tmpBufferRemain=${tmpBuffer.remaining()}"
            )
            if (byteBuffer != null) {// 遗留数据
                buffer.put(byteBuffer.remainArray())
            }
            buffer.put(tmpBuffer.remainArray())
            buffer.position(0)
            bufferingLeftData(buffer) // 继续检查数据包头并读取缓存数据
        }
    }

    /**
     * 实际读取的长度, 不能阻塞循环读取
     */
    private fun readActualLen(
        byteBuffer: ByteBuffer?,
        arr: ByteArray,
        arrSize: Int
    ): Int {
        val startTime = System.currentTimeMillis()
        val res = if (byteBuffer == null) {
            try {
                val br = readCacheToArray(arr, 0, arrSize)
                val endTime = System.currentTimeMillis()
                logD("readActualLen null---耗时：${endTime - startTime} ms")
                br
            } catch (e: Exception) {
                e.printStackTrace()
                stopParseType = StopParseType.EXCEPTION
                -2
            }

        } else {
            val bs = byteBuffer.remaining().coerceAtMost(arrSize) // 取小
            byteBuffer.get(arr, 0, bs)
            var rl = 0
            if (bs < arrSize) {
                rl = try {
                    val br = readCacheToArray(arr, bs, arrSize)
                    val endTime = System.currentTimeMillis()
                    logD("readActualLen 耗时：${endTime - startTime} ms")
                    br
                } catch (e: Exception) {
                    stopParseType = StopParseType.EXCEPTION
                    e.printStackTrace()
                    return -2
                }
            }
            bs + rl
        }
        return res
    }

    /**
     * 读取缓存到 buffer 中，缓存中没有就返回 0
     */
    @Throws(Exception::class)
    private fun readCacheToBuffer(tmpBuffer: ByteBuffer, blocking: Boolean = false): Int {
        val startTime = System.currentTimeMillis()
        var br = -1
        val cacheSize = byteCacheQueue.size
        logD("readCacheToBuffer  cacheSize:$cacheSize")
        val bufferValid = tmpBuffer.remaining()
        val len = bufferValid.coerceAtMost(cacheSize)
        if (blocking) {
            for (i in 0 until bufferValid) {
                tmpBuffer.put(byteCacheQueue.take())
//                byteCacheQueue.poll()?.let { tmpBuffer.put(it) }
                br++
            }
        } else {
            for (i in 0 until len) {
//            tmpBuffer.put(byteCacheQueue.take())
                byteCacheQueue.poll()?.let { tmpBuffer.put(it) }
                br++
            }
        }

        val endTime = System.currentTimeMillis()
        logD("readCacheToBuffer 耗时：${endTime - startTime} ms----BufferSize:${br + 1}")
        return ++br // 不再返回-1
    }

    /**
     * 读取缓存到数组中，缓存没有返回 0
     */
    @Throws(Exception::class)
    private fun readCacheToArray(arr: ByteArray, start: Int, arrSize: Int): Int {
        val startTime = System.currentTimeMillis()
        var br = -1
        val cacheSize = byteCacheQueue.size
        logD("readCacheToArray  cacheSize:$cacheSize ---- len:${arrSize - start}")
        val len = (arrSize - start).coerceAtMost(cacheSize)
        for (i in start until len) {
            byteCacheQueue.poll()?.let { arr[i] = it }
            br++
        }
        val endTime = System.currentTimeMillis()
        logD("readCacheToArray 耗时：${endTime - startTime} ms-----ArrSize:${br + 1}")
        return ++br // 不再返回-1
    }

    /**
     * 检查读取数据完整性, 完整就返回完整数据 否则返回null
     */
    private fun detectIntegrality(
        arr: ByteArray,
        readLen: Int,
        length: Int
    ): ByteArray? {
        val startTime = System.currentTimeMillis()
        val res = if ((readLen + (tempByteArray?.size ?: 0)) == length) { // 读取完整数据项
            val data = if (tempByteArray == null) arr.copyOfRange(
                0,
                readLen
            ) else {
                tempByteArray!!.addAll(
                    arr.copyOfRange(
                        0,
                        readLen
                    ).toList()
                )
                val at = tempByteArray!!.toByteArray()
                tempByteArray?.clear()
                tempByteArray = null
                at
            }
            data
        } else {
            val toList = arr.copyOfRange(
                0,
                readLen
            ).toList()
            tempByteArray = if (tempByteArray != null) {
                tempByteArray?.addAll(toList)
                tempByteArray
            } else {
                LinkedList(toList)
            }
            null
        }
        val endTime = System.currentTimeMillis()
        logD("detectIntegrality 耗时：${endTime - startTime} ms")
        return res
    }

    /**
     * 重置byteBuffer
     */
    private fun resetBuffer(bBuffer: ByteBuffer?): ByteBuffer? {
        var byteBuffer = bBuffer
        // byteBuffer 读取完，清空抛弃
        if (byteBuffer != null && !byteBuffer.hasRemaining()) {
            byteBuffer.clear()
            byteBuffer = null
        }
        return byteBuffer
    }

    /**
     * 日志输出任务
     * level: 1-D 0-E
     */
    private val logRunnable = Runnable {
        while (true) {
            try {
                val msgBean = logMsgQueue.take()
                val level = msgBean.level
                val msg = msgBean.msg
                var msgBytes = msg.toByteArray()
                if (msgBytes.size > DEFAULT_MAX_LOG_SIZE) {
                    // 分段打印计数
                    var count = 1

                    // 在数组范围内，则循环分段
                    while (DEFAULT_MAX_LOG_SIZE < msgBytes.size) {
                        // 按字节长度截取字符串
                        val subStr = cutStr(msgBytes, DEFAULT_MAX_LOG_SIZE)

                        // 打印日志
                        val desStr = String.format("分段打印(%s):%s", count++, subStr)
                        log(level, desStr)

                        // 截取出尚未打印字节数组
                        msgBytes =
                            msgBytes.copyOfRange(subStr?.toByteArray()?.size ?: 0, msgBytes.size);

                        // 可根据需求添加一个次数限制，避免有超长日志一直打印
                        /*if (count == 10) {
                            break;
                        }*/
                    }

                    // 打印剩余部分
                    log(level, String.format("分段打印(%s):%s", count, String(msgBytes)))
                } else {
                    log(level, msg)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    init {
        initParsingExecutor()
    }

    private fun initParsingExecutor() {
        Executors.newFixedThreadPool(DEFAULT_MAX_THREAD_COUNT) as ThreadPoolExecutor
        parsingExecutor = ThreadPoolExecutor(
            DEFAULT_MAX_THREAD_COUNT,
            DEFAULT_MAX_THREAD_COUNT * 2,
            DEFAULT_READ_WRITE_TIME_OUT * 10,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(DEFAULT_MAX_THREAD_COUNT),
            ThreadPoolExecutor.DiscardOldestPolicy()
        )
        // 开启日志任务两次
        parsingExecutor.submit(logRunnable)
        parsingExecutor.submit(logRunnable)
    }

    /**
     * 根据等级输出对应 log
     */
    private fun log(level: Int, msg: String) {
        when (level) {
            1 -> {
                Log.d(TAG, msg)
            }
            0 -> {
                Log.e(TAG, msg)
            }
        }
    }

    /**
     * 支持大文本字符串打印输出,可能会很耗时，需要在单独线程中执行
     */
    fun logD(msg: String) {
        if (toggleLog) {
            try {
                if (logMsgQueue.remainingCapacity() != 0) {
                    logMsgQueue.add(SocketLogBean(1, msg))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

//            if (msg.length > DEFAULT_MAX_LOG_SIZE) {
//                var print: String? =
//                    msg.substring(0, DEFAULT_MAX_LOG_SIZE).intern() // 迭代函数 subString 会导致 OOM
//                Log.d(TAG, print)
//                print = null
//                var leftMsg: String? = msg.substring(DEFAULT_MAX_LOG_SIZE, msg.length).intern()
//                if (leftMsg?.length ?: 0 > DEFAULT_MAX_LOG_SIZE) {
//                    if (leftMsg != null) {
//                        logD(leftMsg)
//                    }
//                } else {
//                    Log.d(TAG, leftMsg)
//                    leftMsg = null
//                }
//            } else {
//                Log.d(TAG, msg)
//            }
//            System.gc()
        }
    }

    /**
     * 支持大文本字符串打印输出
     */
    fun logE(msg: String) {
        if (toggleLog) {
            try {
                if (logMsgQueue.remainingCapacity() != 0) {
                    logMsgQueue.add(SocketLogBean(0, msg))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 按字节长度截取字节数组为字符串
     *
     * @param bytes
     * @param subLength
     * @return
     */
    private fun cutStr(byteArray: ByteArray, subLen: Int): String? {
        if (byteArray.isEmpty() || subLen < 1) {
            return null
        }
        if (subLen >= byteArray.size) {
            return String(byteArray)
        }
        val str = String(byteArray.copyOf(subLen))
        return str.substring(0, str.length - 1)//减一避免最后一位是被阶段的字符
    }

    /////////////////////END//////////////////////////

    /**
     * 打包剩下的bytebuffer 或者 bis 流中的数据
     */
    private fun bufferingLeftData1(byteBuffer: ByteBuffer?): ByteBuffer? {
        // 判断是否还有数据未读完  byteBuffer 或者 bis流
        if (byteBuffer != null && byteBuffer.hasRemaining()) {
            // 解完一包后 byteBuffer 仍有数据，无法判断数据重要性，不丢弃
            byteBuffer.compact()
            byteBuffer.flip()
            val headIndex = byteBuffer.limitArray().containByteArray(head)
            if (headIndex != -1) {
                byteBuffer.position(headIndex) // 跳过包头之前的脏数据
                return byteBuffer
            }
        }
        // bis 是否有未读取数据
        val tmpBuffer = defaultByteBuffer
        tmpBuffer.clear()
        val readLen = try {
            val br = bis?.read(tmpBuffer.array()) ?: -1
            if (br == -1) stopParseType = StopParseType.STREAM_END
            br
        } catch (e: Exception) {
            stopParseType = StopParseType.EXCEPTION
            e.printStackTrace()
            return null
        }
        return if (readLen == -1) { // 流已经断开 ， bytebuffer中残留的不包含头的数据就是脏数据， 抛弃
            logD("socket解析完一包完整数据发现多余脏数据，丢弃！")
            byteBuffer?.compact()
            byteBuffer?.flip()
            resetBuffer(byteBuffer) // null
        } else {
            if (readLen < socketBufferSize) tmpBuffer.limit(readLen)
            val size = readLen + (byteBuffer?.remaining() ?: 0)
            val buffer = ByteBuffer.allocate(size)
            if (byteBuffer != null) {// 遗留数据
                buffer.put(byteBuffer.remainArray())
            }
            buffer.put(tmpBuffer.remainArray())
            buffer.position(0)
            bufferingLeftData1(buffer)
        }
    }

    /**
     * 实际读取的长度
     */
    private fun readActualLen1(
        byteBuffer: ByteBuffer?,
        arr: ByteArray,
        arrSize: Int
    ): Int {
        return if (byteBuffer == null) {
            try {
                val br = bis?.read(arr, 0, arrSize) ?: -1
                if (br == -1) stopParseType = StopParseType.STREAM_END
                br
            } catch (e: Exception) {
                e.printStackTrace()
                stopParseType = StopParseType.EXCEPTION
                -2
            }
        } else {
            val bs = byteBuffer.remaining().coerceAtMost(arrSize) // 取小
            byteBuffer.get(arr, 0, bs)
            var rl = 0
            if (bs < arrSize) {
                rl = try {
                    val br = bis?.read(arr, bs, arrSize - bs) ?: -1
                    if (br == -1) stopParseType = StopParseType.STREAM_END
                    br
                } catch (e: Exception) {
                    stopParseType = StopParseType.EXCEPTION
                    e.printStackTrace()
                    return -2
                }
                if (rl == -1) {
                    logD("socket读取流中数据剩余长度为-1,socket关闭")
                    return -1
                }
            }
            bs + rl
        }
    }
}