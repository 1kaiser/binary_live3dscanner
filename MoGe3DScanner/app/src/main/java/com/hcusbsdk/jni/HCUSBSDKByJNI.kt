package com.hcusbsdk.jni

import android.util.Log

class HCUSBSDKByJNI private constructor() {

    init {
        try {
            System.loadLibrary("HCUSBSDK")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("HCUSBSDKByJNI", "load hcusbsdk failed, err info: ${e.message}")
        }
    }

    external fun USB_StartStreamCallback(
        channel: Int,
        param: USB_STREAM_CALLBACK_PARAM,
        callback: StreamCallBack_JNI
    ): Int

    external fun USB_StopChannel(channel: Int, reserved: Int): Boolean

    external fun USB_GetDeviceConfig(
        p1: Int, p2: Int,
        c1: Any?, c2: Any?, c3: Any?
    ): Boolean

    companion object {
        @Volatile private var instance: HCUSBSDKByJNI? = null

        fun getInstance(): HCUSBSDKByJNI = instance ?: synchronized(this) {
            instance ?: HCUSBSDKByJNI().also { instance = it }
        }
    }
}
