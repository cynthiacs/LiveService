package org.camera.pusher;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
public class StreamingHandler {
    private static String TAG = "streaminghandler";
    private static Context mContext;
    private static final String STREAMING_ERROR_ACTION = "org.camera.pusher.action.error";
    public static final int publish_success = 0;
    public static final int publish_handshake_error = -1;
    public static final int publish_connect_error = -2;
    public static final int publish_stream_error = -3;
    public static final int publish_creat_error = -4;
    public static final int publish_thread_error = -5;
    public static final int publish_packet_null  =-6;
    public static final int publish_url_null  =-7;
    public static final int publish_handler_error = -8;
    public static final int publish_channelID_isexcuting = -9;
    public static final int publish_invalid_parameter = -10;
    public static final int publish_device_error = -11;

    static {
        System.loadLibrary("streampusher");
    }

    public StreamingHandler(Context context) {
        mContext = context;
    }
    public static native long init();
    public static native int setparameter(long pusherObj, int nChannelID, int type, int value);
    public static native int start(long pusherObj, int nChannelID, String url);
    public static native void stop(long pusherObj, int nChannelID);
    public static native void release(long pusherObj);
    public static void onReceiveNotify(int streamtype, int channealID, int code) {
        Log.d(TAG, "onReceiveNotify: streamtype = " + streamtype);
        Log.d(TAG, "onReceiveNotify: channealID = " + channealID);
        Log.d(TAG, "onReceiveNotify: code = " + code);
        if(code == 1009) {
            Intent intent = new Intent(STREAMING_ERROR_ACTION);
            intent.putExtra("channelId", channealID);
            mContext.sendBroadcast(intent);
        }
    }
}