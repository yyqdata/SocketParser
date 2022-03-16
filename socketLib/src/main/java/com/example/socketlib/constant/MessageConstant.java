package com.example.socketlib.constant;

/**
 * Created by yanyuqi on 2018/12/25.
 */

public class MessageConstant {
    //    0x0F0FF0F0
    public static final byte[] HEAD = {0x0F & 0xff, (byte) (0x0F & 0xff), (byte) (0xF0 & 0xff), (byte) (0xF0 & 0xff)};
    //    0x0E0EE0E0
    public static final byte[] END = {0x0E & 0xff, 0x0E & 0xff, (byte) (0xE0 & 0xff), (byte) (0xE0 & 0xff)};

    public static final byte[] HEART = {0x54 & 0xff, (byte) (0xAA & 0xff), (byte) (0xff)};

    private static byte[] getCRC16(byte[] data) {
        byte[] crc16 = new byte[]{0x00, 0x00};
        byte crc = 0x00;
        if (data.length > 0) {
            for (byte aData : data) {
                crc ^= aData;
            }
        }
        crc16[1] = crc;
        return crc16;
    }

    public static short getCRC32(byte[] data) {
        short crc = 0x00;
        if (data.length > 0) {
            for (byte aData : data) {
                crc += aData;
            }
        }
        return crc;
    }

    public static class CommandCode {

        public static final short IMAGE_MSG = 101;//图像信息

        public static final short POS_MSG = 102;//位姿信息

    }
}
