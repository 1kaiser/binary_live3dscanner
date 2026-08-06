package com.hcusbsdk.jni

abstract class StreamCallBack_JNI {
    abstract fun fStreamCallback_JNI(nDataType: Int, frameInfo: USB_FRAME_INFO)
}
