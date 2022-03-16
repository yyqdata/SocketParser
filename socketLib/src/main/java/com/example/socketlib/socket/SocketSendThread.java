package com.example.socketlib.socket;

import com.leador.ma.commonlibrary.LogAR;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Vector;
import java.util.concurrent.PriorityBlockingQueue;


/**
 * 发送Socket数据或或者心跳包
 * Created by yanyuqi on 2018/12/25.
 */

public class SocketSendThread extends Thread {

    private final boolean isHeart;
    private boolean sendHeart;
    private boolean sendData;
    private final OutputStream bos;
    private final PriorityBlockingQueue<Integer> mQueue;
    private final Vector<byte[]> vectorBytes;

    SocketSendThread(OutputStream outputStream, boolean isHeart) {
        this.isHeart = isHeart;
        this.bos = outputStream;
        mQueue = new PriorityBlockingQueue<>();
        vectorBytes = new Vector<>();
        if (isHeart) {
            sendHeart = true;
        } else {
            sendData = true;
        }
    }

    public void setData(byte[] buff) {
        vectorBytes.add(buff);
        mQueue.add(vectorBytes.size() - 1);
    }

    public void cancel() {
        vectorBytes.clear();
        mQueue.clear();
    }

    @Override
    public void run() {
        super.run();
        if (isHeart) {
            //发送心跳包，应该是一直发送
            while (sendHeart) {
                //TODO
                ByteBuffer heart = ByteBuffer.allocate(3);
//                heart.put(MessageConstant.HEART);
                OutputStream outputStream = bos;
                try {
                    outputStream.write(heart.array());
                    outputStream.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

        } else {
            //发送数据包
            while (sendData) {
                int index;
                try {
                    index = mQueue.take();
                } catch (InterruptedException e) {
                    LogAR.d("发送线程被中断...");
                    return;
                }
                byte[] buff = vectorBytes.get(index);
                OutputStream outputStream;
                try {
                    outputStream = bos;
                    outputStream.write(buff);
                    outputStream.flush();
                    LogAR.d("发送成功...");
                } catch (IOException e) {
                    LogAR.d("发送线程被中断..." + e.getMessage());
                }
            }
        }
    }

    public void stopSendHeart() {
        if (isHeart) {
            sendHeart = false;
        } else {
            sendData = false;
            mQueue.clear();
            vectorBytes.clear();
        }
        this.interrupt();
    }


}
