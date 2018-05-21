package com.leadcore.cameraservice;

import org.camera.pusher.StreamingHandler;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class LiveService extends Service {

    private LiveListener mListener;
    private LSBinder mBinder = new LSBinder();
    private long mPushId = 0;
    private StreamingHandler mStreamingHandler;
    private final static int LIVE_DEVICE_ERROR = -1;
    private final static int LIVE_CONNECT_ERROR = -2;
    private final static String TAG = "LiveService";

    @Override
    public void onCreate() {
        // TODO Auto-generated method stub
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind");
//        mStreamingHandler = new StreamingHandler();
//        mPushId = mStreamingHandler.init();
        Log.d(TAG, "onBind:mPushId = "+mPushId);
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.d(TAG, "onUnbind");
        return super.onUnbind(intent);
    }

    public class LSBinder extends Binder {
        public LiveService getService() {
            Log.d(TAG, "getService");
            return LiveService.this;
        }
    }

    public interface LiveListener {
        public void onLiveResult(int channelId, int result);
    }

    public void setListener(LiveListener listener) {
        Log.d(TAG, "setListener");
        mListener = listener;
    }

    public void init() {
        Log.d(TAG, "init");
        if(mStreamingHandler == null) {
            mStreamingHandler = new StreamingHandler(this);
        }
        if(mPushId == 0) {
            mPushId = mStreamingHandler.init();
            Log.d(TAG, "init:mPushId = "+mPushId);
        }
    }

    public void setparameter(int channelId, int type, int value) {
        Log.d(TAG, "setparameter");
        int result = StreamingHandler.publish_device_error;
        if(mPushId > 0) {
            result = mStreamingHandler.setparameter(mPushId, channelId, 0, value);
        }
        Log.d(TAG, "setparameter:result = "+result);
    }

    public void startLive(int channelId, String url) {
        Log.d(TAG, "startLive");
        int result = StreamingHandler.publish_device_error;
        if(mPushId > 0) {
            result = mStreamingHandler.start(mPushId, channelId, url);
        }
        Log.d(TAG, "start:result = "+result);
        if(mListener != null) {
            if(result == -11) {
                result = LIVE_DEVICE_ERROR;
            }else if(result < 0) {
                result = LIVE_CONNECT_ERROR;
            }
            mListener.onLiveResult(channelId,result);
        }
    }

    public void notifySDRemoved() {
        Log.d(TAG, "notifySDRemoved");
        Intent intent = new Intent(STREAMING_ERROR_ACTION);
        intent.putExtra("channelId", -1);//stop all channel's live
        intent.putExtra("error_type", LIVE_DEVICE_ERROR);
        sendBroadcast(intent);
    }

    public void stopLive(int channelID) {
        Log.d(TAG, "stop channelID "+channelID+",mPushId = "+mPushId);
        if(mPushId > 0) {
            mStreamingHandler.stop(mPushId, channelID);
        }
    }

    public void release() {
        Log.d(TAG, "release:mPushId = "+mPushId);
        if(mPushId > 0) {
            mStreamingHandler.release(mPushId);
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
    }
}
