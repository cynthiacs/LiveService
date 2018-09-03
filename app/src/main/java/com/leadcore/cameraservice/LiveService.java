package com.leadcore.cameraservice;

import org.camera.pusher.StreamingHandler;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.Log;

import com.cmteam.cloudmedia.PushNode;
import com.cmteam.cloudmedia.CloudMedia;


public class LiveService extends Service implements Runnable {
    private volatile LiveListener mListener = null;
    private LSBinder mBinder = new LSBinder();
    private long mPushId = 0;
    private volatile StreamingHandler mStreamingHandler;
    private volatile Looper mServiceLooper;
    private volatile ServiceHandler mHandler;
    private final static int LIVE_DEVICE_ERROR = -1;
    private final static int LIVE_CONNECT_ERROR = -2;
    private final static String TAG = "LiveService";
    private static final String STREAMING_ERROR_ACTION = "org.camera.pusher.action.error";
    private static final String START_LIVE_ACTION = "com.leadcore.edr.action.startliveshow";
    private static final String STOP_LIVE_ACTION = "com.leadcore.edr.action.stopliveshow";
    private static final String SYSTEM_SETTINGS_CAMERA_STATUS = "camera_status";
    private static final String SYSTEM_SETTINGS_PLATE = "plate";
    private final static String mDomainName = "www.yangxudong.com";

    private static final int MSG_START_PUSH = 100;
    private static final int MSG_SET_PARAM = 101;
    private static final int MSG_STOP_PUSH = 102;

    private static final int MSG_LOGIN_START = 200;
    private static final int MSG_LOGIN_RESULT = 201;
    private static final int MSG_LOGOUT_START = 202;
    private static final int MSG_CAMERA_STATUS_CHANGE = 203;

    private static final int MSG_PLATE_CHANGE = 300;

    private static final int CAMERA_NUM_0 = 0;
    private static final int CAMERA_NUM_1 = 1;
    private static final int CAMERA_NUM_2 = 2;
    private static final int CAMERA_NUM_3 = 3;
    private static final int CAMERA_NUM_4 = 4;
    private static final int CAMERA_NUM_5 = 5;
    private static final int CAMERA_NUM_MAX = 6;

    private String mAccount = "A505113";
    private String mPassword = "1234567890";
    private String mDeviceName;
    private CloudMedia mCloudMedia;
    private PushNode mPushNode[] = new PushNode[CAMERA_NUM_MAX];
    private String mRtmpUrl[] = new String[CAMERA_NUM_MAX];
    private String mNodeIDSet[] = new String[CAMERA_NUM_MAX];
    private int nCameraStatus;
    private boolean bNetConnected = false;
    private boolean nUVCTEST = false;

    @Override
    public void onCreate() {
        // TODO Auto-generated method stub
        super.onCreate();

        getContentResolver().registerContentObserver(Settings.System.getUriFor(SYSTEM_SETTINGS_CAMERA_STATUS),
                false, mCameraStatusOberserver);
        getContentResolver().registerContentObserver(Settings.System.getUriFor(SYSTEM_SETTINGS_PLATE),
                false, mPlateOberserver);

        final IntentFilter filter = new IntentFilter();
        filter.addAction("android.net.conn.CONNECTIVITY_CHANGE");
        filter.addAction("android.net.wifi.WIFI_STATE_CHANGED");
        filter.addAction("android.net.wifi.STATE_CHANGE");
        filter.addAction("org.camera.pusher.action.error");
        registerReceiver(mNetworkConnectChangedReceiver, filter);

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
                } catch (InterruptedException e) {

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
                    } else if (result < 0) {
                        result = LIVE_CONNECT_ERROR;
                    }
                    Log.d(TAG, "start push on channel id:" + obj.channelId + ", result:" + result);
                    if (mListener != null) {
                        mListener.onLiveResult(obj.channelId, result);
                    }
                    break;
                case MSG_SET_PARAM:
                    Log.d(TAG, "receive MSG_SET_PARAM");
                    result = mStreamingHandler.setparameter(mPushId, obj.channelId, obj.paraType, obj.value);
                    Log.d(TAG, "setparameter on channel id:" + obj.channelId + ", result:" + result);
                    break;
                case MSG_STOP_PUSH:
                    Log.d(TAG, "receive MSG_STOP_PUSH");
                    mStreamingHandler.stop(mPushId, obj.channelId);
                    Log.d(TAG, "receive MSG_STOP_PUSH end");
                    break;
                case MSG_LOGIN_START:
                    Log.d(TAG, "receive MSG_LOGIN_START");
                    if (mCloudMedia == null) {
                        mCloudMedia = CloudMedia.get();
                        if (mCloudMedia == null) {
                            Log.d(TAG, "Get CloudMedia Object NULL");
                            return;
                        }
                    }
                    getCameraStatus();
                    getPlate();
                    if ((nCameraStatus & 0x1) == 0) {
                        loginAndNodeConnect(CAMERA_NUM_0);
                    }
                    if ((nCameraStatus & 0x2) == 0) {
                        loginAndNodeConnect(CAMERA_NUM_1);
                    }
                    if ((nCameraStatus & 0x4) == 0) {
                        loginAndNodeConnect(CAMERA_NUM_2);
                    }
                    if ((nCameraStatus & 0x8) == 0) {
                        loginAndNodeConnect(CAMERA_NUM_3);
                    }

                    if (nUVCTEST) {
                        loginAndNodeConnect(CAMERA_NUM_4);
                        loginAndNodeConnect(CAMERA_NUM_5);
                    }
                    break;

                case MSG_CAMERA_STATUS_CHANGE:
                    Log.d(TAG, "receive MSG_CAMERA_STATUS_CHANGE");
                    int mCameraStatus_Old = obj.paraType;
                    int mCameraStatus_New = obj.value;

                    Log.d(TAG, "mCameraStatus_Old: " + mCameraStatus_Old +
                            ", mCameraStatus_New: " + mCameraStatus_New +
                            ", bNetConnected: " + bNetConnected);
                    if (!bNetConnected || mCloudMedia == null) {
                        return;
                    }
                    if ((mCameraStatus_New & 0x1) != ((mCameraStatus_Old & 0x1))) {
                        if ((mCameraStatus_New & 0x1) != 0) {
                            disconnectNodeAndLogout(CAMERA_NUM_0);
                        } else {
                            loginAndNodeConnect(CAMERA_NUM_0);
                        }
                    }
                    if ((mCameraStatus_New & 0x2) != ((mCameraStatus_Old & 0x2))) {
                        if ((mCameraStatus_New & 0x2) != 0) {
                            disconnectNodeAndLogout(CAMERA_NUM_1);
                        } else {
                            loginAndNodeConnect(CAMERA_NUM_1);
                        }
                    }
                    if ((mCameraStatus_New & 0x4) != ((mCameraStatus_Old & 0x4))) {
                        if ((mCameraStatus_New & 0x4) != 0) {
                            disconnectNodeAndLogout(CAMERA_NUM_2);
                        } else {
                            loginAndNodeConnect(CAMERA_NUM_2);
                        }
                    }
                    if ((mCameraStatus_New & 0x8) != ((mCameraStatus_Old & 0x8))) {
                        if ((mCameraStatus_New & 0x8) != 0) {
                            disconnectNodeAndLogout(CAMERA_NUM_3);
                        } else {
                            loginAndNodeConnect(CAMERA_NUM_3);
                        }
                    }
                    break;
                case MSG_LOGIN_RESULT:
                    break;
                case MSG_LOGOUT_START:
                    disconnectNodeAndLogoutAll();
                    break;
                case MSG_PLATE_CHANGE:
                    logout();
                    login();
                    break;
                default:
                    Log.d(TAG, "default");
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
        if (mStreamingHandler == null) {
            Log.d(TAG, "new StreamingHandler");
            mStreamingHandler = new StreamingHandler(this);
        }
        if (mPushId == 0) {
            mPushId = mStreamingHandler.init();
            Log.d(TAG, "init:mPushId = " + mPushId);
        }
    }

    public void setparameter(int channelId, int type, int value) {
        Log.d(TAG, "setparameter");
        waitServiceHanler();
        Log.d(TAG, "setparameter:mPushId = " + mPushId + ", mHandler = " + mHandler);
        MessageObject object = new MessageObject();
        object.channelId = channelId;
        object.paraType = type;
        object.value = value;
        int result = StreamingHandler.publish_device_error;

        if (mPushId > 0) {
            Log.d(TAG, "setparameter: send msg");
            mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_PARAM, object));
        } else {
            Log.d(TAG, "setparameter:mPushId <= 0 ");
            if (mListener != null) {
                mListener.onLiveResult(channelId, result);
            }
        }
    }

    public void startLive(int channelId, String url) {
        Log.d(TAG, "startLive:mPushId = " + mPushId);
        waitServiceHanler();
        int result = StreamingHandler.publish_device_error;
        MessageObject object = new MessageObject();
        object.channelId = channelId;
        object.url = url;

        if (mPushId > 0) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_START_PUSH, object));
        } else {
            result = LIVE_DEVICE_ERROR;
            if (mListener != null) {
                mListener.onLiveResult(channelId, result);
            }
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
        Log.d(TAG, "stop channelID " + channelId + ",mPushId = " + mPushId);
        waitServiceHanler();
        MessageObject object = new MessageObject();
        object.channelId = channelId;
        if (mPushId > 0) {
            mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_PUSH, object));
        }
    }

    public void release() {
        Log.d(TAG, "release:mPushId = " + mPushId);
        if (mPushId > 0) {
            mStreamingHandler.release(mPushId);
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        logout();
        unregisterReceiver(mNetworkConnectChangedReceiver);
        getContentResolver().unregisterContentObserver(mCameraStatusOberserver);
        getContentResolver().unregisterContentObserver(mPlateOberserver);
        while (mServiceLooper == null) {
            synchronized (this) {
                try {
                    wait(100);
                } catch (InterruptedException e) {

                }
            }
        }
        mServiceLooper.quit();
        super.onDestroy();
    }

    private void login() {
        if (bNetConnected) {
            mHandler.sendEmptyMessage(MSG_LOGIN_START);
        }
    }

    private void logout() {
        if (bNetConnected) {
            mHandler.sendEmptyMessage(MSG_LOGOUT_START);
        }
    }

    private void sendBroadcastStartPush(int channelId, String url, int lasttime, int quality) {
        Intent intent = new Intent(START_LIVE_ACTION);
        intent.putExtra("channelId", String.valueOf(channelId));
        intent.putExtra("quality", String.valueOf(quality));
        intent.putExtra("lastTime", String.valueOf(lasttime));
        intent.putExtra("suggestion_addr", url);
        intent.putExtra("suggestion_addrLen", String.valueOf(url.length()));
        getApplicationContext().sendBroadcast(intent);
    }

    private void sendBroadcastStopPush(int channelId) {
        Intent intent = new Intent(STOP_LIVE_ACTION);
        intent.putExtra("channelId", String.valueOf(channelId));
        getApplicationContext().sendBroadcast(intent);
    }

    private void startPush(int channelId, String url) {
        sendBroadcastStartPush(channelId, url, 1800, 0);
    }

    public void stoppush(int channelId) {
        sendBroadcastStopPush(channelId);
    }

    private void connectCloudMedia(String devicename, String nodeID) {
        String mNodeNick = "Camera-1";
        String mDeviceName = devicename;
        String mNodeID = nodeID;
        final int mChannelID = CAMERA_NUM_0;

        if (mPushNode[mChannelID] == null) {
            mPushNode[mChannelID] = mCloudMedia.declarePushNode(getApplicationContext(),
                    mNodeNick, mDeviceName);
        }
        mPushNode[mChannelID].setOnStartPushMediaActor(new PushNode.OnStartPushMedia() {
            @Override
            public boolean onStartPushMedia(String params) {
                try {
                    JSONObject mJSOONObj = new JSONObject(params);
                    mRtmpUrl[mChannelID] = mJSOONObj.getString("url");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                if (mRtmpUrl[mChannelID] == null) {
                    return false;
                }

                startPush(mChannelID, mRtmpUrl[mChannelID]);
                mPushNode[mChannelID].updateStreamStatus(CloudMedia.CMStreamStatus.PUSHING,
                        new CloudMedia.RPCResultListener() {
                            @Override
                            public void onSuccess(String s) {
                                Log.d(TAG, "updateStreamStatus onSuccess");
                            }

                            @Override
                            public void onFailure(String s) {
                                Log.d(TAG, "updateStreamStatus onFailure");
                            }
                        });
                return true;
            }
        });
        mPushNode[mChannelID].setOnStopPushMediaActor(new PushNode.OnStopPushMedia() {
            @Override
            public boolean onStopPushMedia(String s) {
                if (mRtmpUrl[mChannelID] != null) {
                    mRtmpUrl[mChannelID] = null;
                }
                stoppush(mChannelID);
                return true;
            }
        });
        mPushNode[mChannelID].connect(mCloudMedia.getUser(mNodeID),
                new CloudMedia.RPCResultListener() {
                    @Override
                    public void onSuccess(String s) {
                        Log.d(TAG, "connect onSuccess");
                    }

                    @Override
                    public void onFailure(String s) {
                        Log.d(TAG, "connect onFailure");
                    }
                });
    }

    private void connectCloudMedia_One(String devicename, String nodeID) {
        String mNodeNick = "Camera-2";
        String mDeviceName = devicename;
        String mNodeID = nodeID;
        final int mChannelID = CAMERA_NUM_1;

        if (mPushNode[mChannelID] == null) {
            mPushNode[mChannelID] = mCloudMedia.declarePushNode(getApplicationContext(),
                    mNodeNick, mDeviceName);
        }
        mPushNode[mChannelID].setOnStartPushMediaActor(new PushNode.OnStartPushMedia() {
            @Override
            public boolean onStartPushMedia(String params) {
                try {
                    JSONObject mJSOONObj = new JSONObject(params);
                    mRtmpUrl[mChannelID] = mJSOONObj.getString("url");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                if (mRtmpUrl[mChannelID] == null) {
                    return false;
                }

                startPush(mChannelID, mRtmpUrl[mChannelID]);
                mPushNode[mChannelID].updateStreamStatus(CloudMedia.CMStreamStatus.PUSHING,
                        new CloudMedia.RPCResultListener() {
                            @Override
                            public void onSuccess(String s) {
                                Log.d(TAG, "updateStreamStatus onSuccess");
                            }

                            @Override
                            public void onFailure(String s) {
                                Log.d(TAG, "updateStreamStatus onFailure");
                            }
                        });
                return true;
            }
        });
        mPushNode[mChannelID].setOnStopPushMediaActor(new PushNode.OnStopPushMedia() {
            @Override
            public boolean onStopPushMedia(String s) {
                if (mRtmpUrl[mChannelID] != null) {
                    mRtmpUrl[mChannelID] = null;
                }
                stoppush(mChannelID);
                return true;
            }
        });
        mPushNode[mChannelID].connect(mCloudMedia.getUser(mNodeID),
                new CloudMedia.RPCResultListener() {
                    @Override
                    public void onSuccess(String s) {
                        Log.d(TAG, "connect onSuccess");
                    }

                    @Override
                    public void onFailure(String s) {
                        Log.d(TAG, "connect onFailure");
                    }
                });
    }

    private void connectCloudMedia_Two(String devicename, String nodeID) {
        String mNodeNick = "Camera-3";
        String mDeviceName = devicename;
        String mNodeID = nodeID;
        final int mChannelID = CAMERA_NUM_2;

        if (mPushNode[mChannelID] == null) {
            mPushNode[mChannelID] = mCloudMedia.declarePushNode(getApplicationContext(),
                    mNodeNick, mDeviceName);
        }
        mPushNode[mChannelID].setOnStartPushMediaActor(new PushNode.OnStartPushMedia() {
            @Override
            public boolean onStartPushMedia(String params) {
                try {
                    JSONObject mJSOONObj = new JSONObject(params);
                    mRtmpUrl[mChannelID] = mJSOONObj.getString("url");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                if (mRtmpUrl[mChannelID] == null) {
                    return false;
                }

                startPush(mChannelID, mRtmpUrl[mChannelID]);
                mPushNode[mChannelID].updateStreamStatus(CloudMedia.CMStreamStatus.PUSHING,
                        new CloudMedia.RPCResultListener() {
                            @Override
                            public void onSuccess(String s) {
                                Log.d(TAG, "updateStreamStatus onSuccess");
                            }

                            @Override
                            public void onFailure(String s) {
                                Log.d(TAG, "updateStreamStatus onFailure");
                            }
                        });
                return true;
            }
        });
        mPushNode[mChannelID].setOnStopPushMediaActor(new PushNode.OnStopPushMedia() {
            @Override
            public boolean onStopPushMedia(String s) {
                if (mRtmpUrl[mChannelID] != null) {
                    mRtmpUrl[mChannelID] = null;
                }
                stoppush(mChannelID);
                return true;
            }
        });
        mPushNode[mChannelID].connect(mCloudMedia.getUser(mNodeID),
                new CloudMedia.RPCResultListener() {
                    @Override
                    public void onSuccess(String s) {
                        Log.d(TAG, "connect onSuccess");
                    }

                    @Override
                    public void onFailure(String s) {
                        Log.d(TAG, "connect onFailure");
                    }
                });
    }

    private void connectCloudMedia_Three(String devicename, String nodeID) {
        String mNodeNick = "Camera-4";
        String mDeviceName = devicename;
        String mNodeID = nodeID;
        final int mChannelID = CAMERA_NUM_3;

        if (mPushNode[mChannelID] == null) {
            mPushNode[mChannelID] = mCloudMedia.declarePushNode(getApplicationContext(),
                    mNodeNick, mDeviceName);
        }
        mPushNode[mChannelID].setOnStartPushMediaActor(new PushNode.OnStartPushMedia() {
            @Override
            public boolean onStartPushMedia(String params) {
                try {
                    JSONObject mJSOONObj = new JSONObject(params);
                    mRtmpUrl[mChannelID] = mJSOONObj.getString("url");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                if (mRtmpUrl[mChannelID] == null) {
                    return false;
                }

                startPush(mChannelID, mRtmpUrl[mChannelID]);
                mPushNode[mChannelID].updateStreamStatus(CloudMedia.CMStreamStatus.PUSHING,
                        new CloudMedia.RPCResultListener() {
                            @Override
                            public void onSuccess(String s) {
                                Log.d(TAG, "updateStreamStatus onSuccess");
                            }

                            @Override
                            public void onFailure(String s) {
                                Log.d(TAG, "updateStreamStatus onFailure");
                            }
                        });
                return true;
            }
        });
        mPushNode[mChannelID].setOnStopPushMediaActor(new PushNode.OnStopPushMedia() {
            @Override
            public boolean onStopPushMedia(String s) {
                if (mRtmpUrl[mChannelID] != null) {
                    mRtmpUrl[mChannelID] = null;
                }
                stoppush(mChannelID);
                return true;
            }
        });
        mPushNode[mChannelID].connect(mCloudMedia.getUser(mNodeID),
                new CloudMedia.RPCResultListener() {
                    @Override
                    public void onSuccess(String s) {
                        Log.d(TAG, "connect onSuccess");
                    }

                    @Override
                    public void onFailure(String s) {
                        Log.d(TAG, "connect onFailure");
                    }
                });
    }

    private void connectCloudMedia_Four(String devicename, String nodeID) {
        String mNodeNick = "Camera-5";
        String mDeviceName = devicename;
        String mNodeID = nodeID;
        final int mChannelID = CAMERA_NUM_4;

        if (mPushNode[mChannelID] == null) {
            mPushNode[mChannelID] = mCloudMedia.declarePushNode(getApplicationContext(),
                    mNodeNick, mDeviceName);
        }
        mPushNode[mChannelID].setOnStartPushMediaActor(new PushNode.OnStartPushMedia() {
            @Override
            public boolean onStartPushMedia(String params) {
                try {
                    JSONObject mJSOONObj = new JSONObject(params);
                    mRtmpUrl[mChannelID] = mJSOONObj.getString("url");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                if (mRtmpUrl[mChannelID] == null) {
                    return false;
                }

                startPush(mChannelID, mRtmpUrl[mChannelID]);
                mPushNode[mChannelID].updateStreamStatus(CloudMedia.CMStreamStatus.PUSHING,
                        new CloudMedia.RPCResultListener() {
                            @Override
                            public void onSuccess(String s) {
                                Log.d(TAG, "updateStreamStatus onSuccess");
                            }

                            @Override
                            public void onFailure(String s) {
                                Log.d(TAG, "updateStreamStatus onFailure");
                            }
                        });
                return true;
            }
        });
        mPushNode[mChannelID].setOnStopPushMediaActor(new PushNode.OnStopPushMedia() {
            @Override
            public boolean onStopPushMedia(String s) {
                if (mRtmpUrl[mChannelID] != null) {
                    mRtmpUrl[mChannelID] = null;
                }
                stoppush(mChannelID);
                return true;
            }
        });
        mPushNode[mChannelID].connect(mCloudMedia.getUser(mNodeID),
                new CloudMedia.RPCResultListener() {
                    @Override
                    public void onSuccess(String s) {
                        Log.d(TAG, "connect onSuccess");
                    }

                    @Override
                    public void onFailure(String s) {
                        Log.d(TAG, "connect onFailure");
                    }
                });
    }

    private void connectCloudMedia_Five(String devicename, String nodeID) {
        String mNodeNick = "Camera-6";
        String mDeviceName = devicename;
        String mNodeID = nodeID;
        final int mChannelID = CAMERA_NUM_5;

        if (mPushNode[mChannelID] == null) {
            mPushNode[mChannelID] = mCloudMedia.declarePushNode(getApplicationContext(),
                    mNodeNick, mDeviceName);
        }
        mPushNode[mChannelID].setOnStartPushMediaActor(new PushNode.OnStartPushMedia() {
            @Override
            public boolean onStartPushMedia(String params) {
                try {
                    JSONObject mJSOONObj = new JSONObject(params);
                    mRtmpUrl[mChannelID] = mJSOONObj.getString("url");
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                if (mRtmpUrl[mChannelID] == null) {
                    return false;
                }

                startPush(mChannelID, mRtmpUrl[mChannelID]);
                mPushNode[mChannelID].updateStreamStatus(CloudMedia.CMStreamStatus.PUSHING,
                        new CloudMedia.RPCResultListener() {
                            @Override
                            public void onSuccess(String s) {
                                Log.d(TAG, "updateStreamStatus onSuccess");
                            }

                            @Override
                            public void onFailure(String s) {
                                Log.d(TAG, "updateStreamStatus onFailure");
                            }
                        });
                return true;
            }
        });
        mPushNode[mChannelID].setOnStopPushMediaActor(new PushNode.OnStopPushMedia() {
            @Override
            public boolean onStopPushMedia(String s) {
                if (mRtmpUrl[mChannelID] != null) {
                    mRtmpUrl[mChannelID] = null;
                }
                stoppush(mChannelID);
                return true;
            }
        });
        mPushNode[mChannelID].connect(mCloudMedia.getUser(mNodeID), new CloudMedia.RPCResultListener() {
            @Override
            public void onSuccess(String s) {
                Log.d(TAG, "connect onSuccess");
            }

            @Override
            public void onFailure(String s) {
                Log.d(TAG, "connect onFailure");
            }
        });
    }

    private void loginAndNodeConnect(int index) {
        String mNodeID;
        mDeviceName = mAccount;
        switch (index) {
            case CAMERA_NUM_0:
                mNodeID = mCloudMedia.login(mDomainName, mAccount, mPassword);
                if (mNodeID != null) {
                    mNodeIDSet[CAMERA_NUM_0] = mNodeID;
                    connectCloudMedia(mDeviceName, mNodeID);
                }
                break;
            case CAMERA_NUM_1:
                mNodeID = mCloudMedia.login(mDomainName, mAccount, mPassword);
                if (mNodeID != null) {
                    mNodeIDSet[CAMERA_NUM_1] = mNodeID;
                    connectCloudMedia_One(mDeviceName, mNodeID);
                }
                break;
            case CAMERA_NUM_2:
                mNodeID = mCloudMedia.login(mDomainName, mAccount, mPassword);
                if (mNodeID != null) {
                    mNodeIDSet[CAMERA_NUM_2] = mNodeID;
                    connectCloudMedia_Two(mDeviceName, mNodeID);
                }
                break;
            case CAMERA_NUM_3:
                mNodeID = mCloudMedia.login(mDomainName, mAccount, mPassword);
                if (mNodeID != null) {
                    mNodeIDSet[CAMERA_NUM_3] = mNodeID;
                    connectCloudMedia_Three(mDeviceName, mNodeID);
                }
                break;
            case CAMERA_NUM_4:
                mNodeID = mCloudMedia.login(mDomainName, mAccount, mPassword);
                if (mNodeID != null) {
                    mNodeIDSet[CAMERA_NUM_4] = mNodeID;
                    connectCloudMedia_Four(mDeviceName, mNodeID);
                }
                break;
            case CAMERA_NUM_5:
                mNodeID = mCloudMedia.login(mDomainName, mAccount, mPassword);
                if (mNodeID != null) {
                    mNodeIDSet[CAMERA_NUM_5] = mNodeID;
                    connectCloudMedia_Five(mDeviceName, mNodeID);
                }
                break;
            default:
                break;
        }
    }

    private void disconnectNodeAndLogoutAll() {
        for (int i = 0; i < CAMERA_NUM_MAX; i++) {
            disconnectNodeAndLogout(i);
        }
    }

    private void disconnectNodeAndLogout(int index) {
        if (mNodeIDSet[index] != null) {
            disconnetNode(index);
            logoutNode(index);
            mNodeIDSet[index] = null;
        }
    }

    private void disconnetNode(int index) {
        if (mPushNode[index] != null) {
            mPushNode[index].disconnect();
            mPushNode[index] = null;
        }
        if (mRtmpUrl[index] != null) {
            mRtmpUrl[index] = null;
            stoppush(index);
        }
    }

    private void logoutNode(int index) {
        if (mCloudMedia != null && mPushNode[index] != null) {
            mCloudMedia.logout(mAccount, mPushNode[index].getMyNode().getID());
        }
    }

    private void getPlate() {
        String mPlate = Settings.System.getString(getContentResolver(), SYSTEM_SETTINGS_PLATE);
        Log.d(TAG, "getPlate mPlate: " + mPlate);
        if (mPlate != null) {
            //mAccount = mPlate.substring(2);
            mAccount = mPlate.substring(mPlate.indexOf("A"), mPlate.length());
            Log.d(TAG, "getPlate mAccount: " + mAccount);
        }
    }

    private ContentObserver mPlateOberserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange) {
            String mPlate = Settings.System.getString(getContentResolver(), SYSTEM_SETTINGS_PLATE);
            Log.d(TAG, "onChange mPlate: " + mPlate);
            if (mPlate != null) {
                //mAccount = mPlate.substring(2);
                mAccount = mPlate.substring(mPlate.indexOf("A"), mPlate.length());
                Log.d(TAG, "getPlate mAccount: " + mAccount);
                mHandler.sendEmptyMessage(MSG_PLATE_CHANGE);
            }
        }
    };

    private void getCameraStatus() {
        try {
            nCameraStatus = Settings.System.getInt(getContentResolver(),
                    SYSTEM_SETTINGS_CAMERA_STATUS);
            Log.d(TAG, "getCameraStatus nCameraStatus: " + nCameraStatus);
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }
    }

    private ContentObserver mCameraStatusOberserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange) {
            try {
                MessageObject object = new MessageObject();
                int nCameraStatus_Old = nCameraStatus;
                nCameraStatus = Settings.System.getInt(getContentResolver(),
                        SYSTEM_SETTINGS_CAMERA_STATUS);
                object.paraType = nCameraStatus_Old;
                object.value = nCameraStatus;
                Log.d(TAG, "nCameraStatus_Old: " + nCameraStatus_Old
                        + ", nCameraStatus: " + nCameraStatus);
                mHandler.sendMessage(mHandler.obtainMessage(MSG_CAMERA_STATUS_CHANGE, object));
            } catch (Settings.SettingNotFoundException e) {
                e.printStackTrace();
            }
        }
    };

    private BroadcastReceiver mNetworkConnectChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                ConnectivityManager manager = (ConnectivityManager) context
                        .getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo networkInfo = manager.getActiveNetworkInfo();
                if (networkInfo != null && networkInfo.isConnected()) { // connected to the internet
                    Log.d(TAG, "NetWork Type: " + networkInfo.getType()
                            + "， Subtype Name:" + networkInfo.getSubtypeName());
                    bNetConnected = true;
                    login();
                } else {
                    bNetConnected = false;
                }
            }
        }
    };
}
