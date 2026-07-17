package com.hcusbsdk.jni

class USB_FRAME_INFO {
    @JvmField var dwFrameType: Int = 0
    @JvmField var dwStreamType: Int = 0
    @JvmField var dwDataType: Int = 0
    @JvmField var dwWidth: Int = 0
    @JvmField var dwHeight: Int = 0
    @JvmField var dwBufSize: Int = 0
    @JvmField var nStamp: Int = 0
    @JvmField var nFrameNum: Int = 0
    @JvmField var dwFrameRate: Int = 0
    @JvmField var pBuf: ByteArray? = null
    @JvmField var byRes: ByteArray? = null
}
