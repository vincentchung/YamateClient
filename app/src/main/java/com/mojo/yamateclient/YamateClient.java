package com.mojo.yamateclient;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;


import com.spotify.sdk.android.Spotify;
import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationRequest;
import com.spotify.sdk.android.authentication.AuthenticationResponse;
import com.spotify.sdk.android.playback.Config;
import com.spotify.sdk.android.playback.ConnectionStateCallback;
import com.spotify.sdk.android.playback.Player;
import com.spotify.sdk.android.playback.PlayerNotificationCallback;
import com.spotify.sdk.android.playback.PlayerState;
import com.spotify.sdk.android.playback.PlayerStateCallback;

import org.alljoyn.bus.BusAttachment;
import org.alljoyn.bus.BusException;
import org.alljoyn.bus.BusListener;
import org.alljoyn.bus.Mutable;
import org.alljoyn.bus.ProxyBusObject;
import org.alljoyn.bus.SessionListener;
import org.alljoyn.bus.SessionOpts;
import org.alljoyn.bus.Status;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


public class YamateClient extends ActionBarActivity {

    /* Load the native alljoyn_java library. */
    static {
        System.loadLibrary("alljoyn_java");
    }


    private static final String TAG = "YamateClient";
    private static final String CLIENT_ID = "8b01f1da31ce4b5fa4459819e6d2951d";
    private static final String REDIRECT_URI = "yourcustomprotocol://callback";
    private static final int REQUEST_CODE = 1337;

    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    private static final String PLAYBACK_STATE_CHANGED = SPOTIFY_PACKAGE + ".playbackstatechanged";
    private static final String QUEUE_CHANGED = SPOTIFY_PACKAGE + ".queuechanged";
    private static final String METADATA_CHANGED = SPOTIFY_PACKAGE + ".metadatachanged";

    public static final String KEY_BACKGROUND_SERVICE_ENABLED = "background_service_enabled";
    protected String[] mMonths = new String[] {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dec"
    };

    private Player mPlayer;
    private EditText mEditText;
    private ArrayAdapter<String> mListViewArrayAdapter;
    private ListView mListView;
    private Menu menu;

    private BusHandler mBusHandler;
    private ProgressDialog mProgressDialog;
    private AlertDialog mDialog;
    private BroadcastReceiver mMediaNotificationsReceiver;
    private boolean mIsMediaNotificationsReceiverRegistered = false;


    class SpotifyAudioStreamMessage {
        public String accessToken;
        public String playlistUri;
        public String trackUri;
        public int positionInMs;
    }

/*
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_START_PROGRESS_DIALOG:
                    showProgressDialog(getString(R.string.service_finding));
                    break;
                case MESSAGE_START_SYNC_SPOTIFY_TRACK_PROGRESS_DIALOG:
                    showProgressDialog(getString(R.string.sync_spotify_track));
                    break;
                case MESSAGE_STOP_PROGRESS_DIALOG:
                    dismissProgressDialog();
                    break;

                case MESSAGE_EXCEPTION_DIALOG:
                    showDialog(R.string.alljoyn_result, (String) msg.obj);
                case MESSAGE_TRACK_INFO_DIALOG:
                    showDialog(R.string.track_info, (String) msg.obj);
                    break;
                default:
                    break;
            }
        }
    };
*/


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yamate_client);

        HandlerThread busThread = new HandlerThread("BusHandler");
        busThread.start();
        mBusHandler = new BusHandler(busThread.getLooper());

        /* Connect to an AllJoyn object. */
        mBusHandler.sendEmptyMessage(BusHandler.CONNECT);
        mMediaNotificationsReceiver = new MediaNotificationsReceiver();

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_yamate_client, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }


    class BusHandler extends Handler {
        private static final String SERVICE_NAME = "com.mojo.Yamate.server";
        private static final short CONTACT_PORT=42;

        private BusAttachment mBus;
        private ProxyBusObject mProxyObj;
        private SpotifyInterface mSpotifyInterface;

        private int     mSessionId;
        private boolean mIsInASession;
        private boolean mIsConnected;
        private boolean mIsStoppingDiscovery;

        /* These are the messages sent to the BusHandler from the UI. */
        public static final int CONNECT = 1;
        public static final int JOIN_SESSION = 2;
        public static final int DISCONNECT = 3;
        public static final int GET_LAST_NIGHT = 4;
        public static final int SPOTIFY_PLAY_CURRENT_TRACK = 5;
        public static final int SPOTIFY_TOGGLE_PLAY = 6;
        public static final int SPOTIFY_TRACK_INFO = 7;

        public BusHandler(Looper looper) {
            super(looper);

            mIsInASession = false;
            mIsConnected = false;
            mIsStoppingDiscovery = false;
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
            /* Connect to a remote instance of an object implementing the SleepInterface. */
                case CONNECT: {
                    org.alljoyn.bus.alljoyn.DaemonInit.PrepareDaemon(getApplicationContext());
                    mBus = new BusAttachment(getPackageName(), BusAttachment.RemoteMessage.Receive);

                    Log.d(TAG, "mBus.registerBusListener");
                    mBus.registerBusListener(new BusListener() {
                        @Override
                        public void foundAdvertisedName(String name, short transport, String namePrefix) {
                            Log.d(TAG, "foundAdvertisedName name:" + name);
                            Log.d(TAG, "foundAdvertisedName transport:" + transport);
                            Log.d(TAG, "foundAdvertisedName namePrefix:" + namePrefix);
                            if(!mIsConnected) {
                                Message msg = obtainMessage(JOIN_SESSION);
                                msg.arg1 = transport;
                                msg.obj = name;
                                sendMessage(msg);
                            }
                        }
                    });

                /* To communicate with AllJoyn objects, we must connect the BusAttachment to the bus. */
                    Status status = mBus.connect();
                    logStatus("BusAttachment.connect()", status);
                    if (Status.OK != status) {
                        return;
                    }

                    status = mBus.findAdvertisedName(SERVICE_NAME);
                    logStatus(String.format("BusAttachement.findAdvertisedName(%s)", SERVICE_NAME), status);
                    if (Status.OK != status) {
                        return;
                    }

                    break;
                }
                case (JOIN_SESSION): {
                    if (mIsStoppingDiscovery) {
                        break;
                    }

                    short contactPort = CONTACT_PORT;
                    SessionOpts sessionOpts = new SessionOpts();
                    sessionOpts.transports = (short)msg.arg1;
                    Mutable.IntegerValue sessionId = new Mutable.IntegerValue();

                    Log.d(TAG, "mBus.joinSession");
                    Status status = mBus.joinSession((String) msg.obj, contactPort, sessionId, sessionOpts, new SessionListener() {
                        @Override
                        public void sessionLost(int sessionId, int reason) {
                            mIsConnected = false;
                            Log.d(TAG, String.format("MyBusListener.sessionLost(sessionId = %d, reason = %d)", sessionId,reason));
                            // Toast.makeText(BackgroundService.this, R.string.find_service, Toast.LENGTH_SHORT).show();
                            //mHandler.sendEmptyMessage(MESSAGE_START_PROGRESS_DIALOG);
                        }
                    });
                    Log.d(TAG, "mBus.joinSession sessionId:" + sessionId);
                    Log.d(TAG, "mBus.joinSession status:" + status);

                    if (status == Status.OK) {
                    /*
                     * To communicate with an AllJoyn object, we create a ProxyBusObject.
                     * A ProxyBusObject is composed of a name, path, sessionID and interfaces.
                     *
                     * This ProxyBusObject is located at the well-known SERVICE_NAME, under path
                     * "/SleepService", uses sessionID of CONTACT_PORT, and implements the SleepInterface.
                     */
                        mProxyObj =  mBus.getProxyBusObject(SERVICE_NAME,
                                "/SleepService",
                                sessionId.value,
                                new Class<?>[]{SpotifyInterface.class});

                    /* We make calls to the methods of the AllJoyn object through one of its interfaces. */
                        mSpotifyInterface =  mProxyObj.getInterface(SpotifyInterface.class);

                        mSessionId = sessionId.value;
                        Log.d(TAG, "mBus.joinSession mSessionId:" + mSessionId);
                        mIsConnected = true;
                        // Toast.makeText(BackgroundService.this, R.string.service_connected, Toast.LENGTH_LONG).show();
                        //mHandler.sendEmptyMessage(MESSAGE_STOP_PROGRESS_DIALOG);
                    }
                    break;
                }

            /* Release all resources acquired in the connect. */
                case DISCONNECT: {
                    mIsStoppingDiscovery = true;
                    if (mIsConnected) {
                        Status status = mBus.leaveSession(mSessionId);
                        logStatus("BusAttachment.leaveSession()", status);
                    }
                    mBus.disconnect();
                    getLooper().quit();
                    break;
                }


                case SPOTIFY_PLAY_CURRENT_TRACK: {
                    try {
                        Log.d(TAG, "case SPOTIFY_PLAY_CURRENT_TRACK");
                        if (mSpotifyInterface != null) {
                            //mHandler.sendEmptyMessage(MESSAGE_START_SYNC_SPOTIFY_TRACK_PROGRESS_DIALOG);
                            SpotifyAudioStreamMessage spotifyMessage = (SpotifyAudioStreamMessage) msg.obj;
                            mSpotifyInterface.PlayCurrentTrack(spotifyMessage.trackUri, spotifyMessage.positionInMs);
                            //mHandler.sendEmptyMessage(MESSAGE_STOP_PROGRESS_DIALOG);
                            Log.d(TAG, "mSpotifyInterface PlayCurrentTrack trakUrl:" + spotifyMessage.trackUri);
                            Log.d(TAG, "mSpotifyInterface PlayCurrentTrack positionInMs:" + spotifyMessage.positionInMs);
                        }
                    } catch (BusException ex) {
                        Log.d(TAG, "SPOTIFY_PLAY_CURRENT_TRACK BusException ex:" + ex);
                    }
                    break;
                }
                case SPOTIFY_TOGGLE_PLAY: {
                    try {
                        Log.d(TAG, "case SPOTIFY_TOGGLE_PLAY");
                        if (mSpotifyInterface != null) {
                            mSpotifyInterface.TogglePlay();
                            Log.d(TAG, "mSpotifyInterface TogglePlay");
                        }
                    } catch (BusException ex) {
                        Log.d(TAG, "SPOTIFY_TOGGLE_PLAY BusException ex:" + ex);
                    }
                    break;
                }
                case SPOTIFY_TRACK_INFO: {
                    try {
                        Log.d(TAG, "case SPOTIFY_TRACK_INFO");
                        if (mSpotifyInterface != null) {
                            String trackInfo = mSpotifyInterface.GetTrackInfo();

                            try {
                                JSONObject object = new JSONObject(trackInfo);
                                Log.d(TAG, "trackInfo object:" + TrackInfo.deserializeFromObj(object));
                                //Message message = mHandler.obtainMessage(MESSAGE_TRACK_INFO_DIALOG);
                                //message.obj = TrackInfo.deserializeFromObj(object).toString();
                                //mHandler.sendMessage(message);
                            } catch (Exception e) {
                            }
                        }
                    } catch (BusException ex) {
                        Log.d(TAG, "SPOTIFY_TRACK_INFO BusException ex:" + ex);
                    }
                    break;
                }

                default:
                    break;
            }
        }

        public boolean isConnected() {
            return mIsConnected;
        }
    }



    private void playPlaylistUri(final String trackUri, final int positionInMs) {
        Log.d(TAG, "playPlaylistUri");
        SpotifyAudioStreamMessage spotifyMessage = new SpotifyAudioStreamMessage();
        spotifyMessage.trackUri = trackUri;
        spotifyMessage.positionInMs = positionInMs;
        Message msg = mBusHandler.obtainMessage(BusHandler.SPOTIFY_PLAY_CURRENT_TRACK, spotifyMessage);
        mBusHandler.sendMessage(msg);
    }


    private void registerMediaNotificationsReceiver() {
        if (mIsMediaNotificationsReceiverRegistered) {
            return;
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(PLAYBACK_STATE_CHANGED);
        intentFilter.addAction(QUEUE_CHANGED);
        intentFilter.addAction(METADATA_CHANGED);
        registerReceiver(mMediaNotificationsReceiver, intentFilter);
        mIsMediaNotificationsReceiverRegistered = true;
        Log.d(TAG, "registerMediaNotificationsReceiver");
    }


    private void unregisterMediaNotificationsReceiver() {
        if (mMediaNotificationsReceiver != null && mIsMediaNotificationsReceiverRegistered) {
            unregisterReceiver(mMediaNotificationsReceiver);
            mIsMediaNotificationsReceiverRegistered = false;
        }
    }

    private final class MediaNotificationsReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            long timeSentInMs = intent.getLongExtra("timeSent", 0L);
            String action = intent.getAction();

            Log.d(TAG, "MediaNotificationsReceiver onReceive action:" + action);
            if (action.equals(PLAYBACK_STATE_CHANGED)) {
                boolean playing = intent.getBooleanExtra("playing", false);
                int positionInMs = intent.getIntExtra("playbackPosition", 0);
            } else if (action.equals(QUEUE_CHANGED)) {
            } else if (action.equals(METADATA_CHANGED)) {
                String trackId = intent.getStringExtra("id");
                String artistName = intent.getStringExtra("artist");
                String albumName = intent.getStringExtra("album");
                String trackName = intent.getStringExtra("track");
                int trackLengthInSec = intent.getIntExtra("length", 0);
                int trackPlaybackPositionInMs = (int) (System.currentTimeMillis() - timeSentInMs);
                Log.d(TAG, "MediaNotificationsReceiver onReceive trackId:" + trackId);
                Log.d(TAG, "MediaNotificationsReceiver onReceive trackName:" + trackName);
                playPlaylistUri(trackId, Math.max(trackPlaybackPositionInMs, 0));
            }
        }
    }


    private void logStatus(String msg, Status status) {
        String log = String.format("%s: %s", msg, status);
        Log.i(TAG, log);
    }
}
