package com.leadcore.cameraservice;

import org.camera.pusher.StreamingHandler;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

public class LiveService extends Service implements Runnable{

    private volatile LiveListener mListener;
    private LSBinder mBinder = new LSBinder();
    private long mPushId = 0;
    private volatile StreamingHandler mStreamingHandler;
    private volatile Looper mServiceLooper;
    private volatile ServiceHandler mHandler;
    private final static int LIVE_DEVICE_ERROR = -1;
    private final static int LIVE_CONNECT_ERROR = -2;
    private final static String TAG = "LiveService";
    private static final String STREAMING_ERROR_ACTION = "org.camera.pusher.action.error";
    private static final int MSG_START_PUSH = 100;
    private static final int MSG_SET_PARAM = 101;
    private static final int MSG_STOP_PUSH = 102;

    @Override
    public void onCreate() {
        // TODO Auto-generated method stub
        super.onCreate();
        Thread th = new Thread(null, this, "LiveService");
        th.start();
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
//        Log.d(TAG, "onBind:mPushId = "+mPushId);
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

    @Override
    public void run() {
        Log.d(TAG, "run begin");
        Looper.prepare();
        mServiceLooper = Looper.myLooper();
        mHandler = new ServiceHandler();
        Looper.loop();
        Log.d(TAG, "run end");
    }

    private void waitServiceHanler() {
        while (mHandler == null) {
            Log.d(TAG, "wait for ServiceHandler");
            synchronized (this) {
                try {
                    wait(100);
                }catch (InterruptedException e) {

                }
            }
        }
    }

    private class MessageObject {
        int channelId;
        String url;
        int paraType;
        int value;
    }

    private final class ServiceHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "handleMessage");
            MessageObject obj = (MessageObject) msg.obj;
            int result;
            switch (msg.what) {
                case MSG_START_PUSH:
                    Log.d(TAG, "receive MSG_START_PUSH");
                    result = mStreamingHandler.start(mPushId, obj.channelId, obj.url);
                    if (result == StreamingHandler.publish_device_error) {
                        result = LIVE_DEVICE_ERROR;
                    }else if (result < 0){
                        result = LIVE_CONNECT_ERROR;
                    }
                    Log.d(TAG, "start push on channel id:"+obj.channelId+", result:"+result);
                    mListener.onLiveResult(obj.channelId, result);
                    break;
                case MSG_SET_PARAM:
                    Log.d(TAG, "receive MSG_SET_PARAM");
                    result = mStreamingHandler.setparameter(mPushId, obj.channelId, obj.paraType, obj.value);
                    Log.d(TAG, "setparameter on channel id:"+obj.channelId+", result:"+result);
                    break;
                case MSG_STOP_PUSH:
                    Log.d(TAG, "receive MSG_STOP_PUSH");
                    mStreamingHandler.stop(mPushId, obj.channelId);
                    Log.d(TAG, "receive MSG_STOP_PUSH end");
                    break;
            }
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
            Log.d(TAG, "new StreamingHandler");
            mStreamingHandler = new StreamingHandler(this);
        }
        if(mPushId == 0) {
            mPushId = mStreamingHandler.init();
            Log.d(TAG, "init:mPushId = "+mPushId);
        }
    }

    public void setparameter(int channelId, int type, int value) {
        Log.d(TAG, "setparameter");
        waitServiceHanler();
        Log.d(TAG, "setparameter:mPushId = "+mPushId+", mHandler = "+mHandler);
        MessageObject object = new MessageObject();
        object.channelId = channelId;
        object.paraType = type;
        object.value = value;
        int result = StreamingHandler.publish_device_error;

        if(mPushId > 0) {
            Log.d(TAG, "setparameter: send msg");
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_PARAM, object));
        }else {
            Log.d(TAG, "setparameter:mPushId <= 0 ");
        }
    }

    public void startLive(int channelId, String url) {
        Log.d(TAG, "startLive:mPushId = "+mPushId);
        waitServiceHanler();
        int result = StreamingHandler.publish_device_error;
        MessageObject object = new MessageObject();
        object.channelId = channelId;
        object.url = url;

        if(mPushId > 0) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_START_PUSH, object));
        }else {
            result = LIVE_DEVICE_ERROR;
            mListener.onLiveResult(channelId, result);
        }
    }

    public void notifySDRemoved() {
        Log.d(TAG, "notifySDRemoved");
        Intent intent = new Intent(STREAMING_ERROR_ACTION);
        intent.putExtra("channelId", -1);//stop all channel's live
        intent.putExtra("error_type", LIVE_DEVICE_ERROR);
        sendBroadcast(intent);
    }

    public void stopLive(int channelId) {
        Log.d(TAG, "stop channelID "+channelId+",mPushId = "+mPushId);
        waitServiceHanler();
        MessageObject object = new MessageObject();
        object.channelId = channelId;
        if(mPushId > 0) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_PUSH, object));
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
        while (mServiceLooper == null) {
            synchronized (this) {
                try{
                    wait(100);
                }catch (InterruptedException e){

                }
            }
        }
        mServiceLooper.quit();
        super.onDestroy();
    }
}
