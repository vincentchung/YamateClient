package com.mojo.yamateclient;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import org.alljoyn.bus.BusAttachment;
import org.alljoyn.bus.BusException;
import org.alljoyn.bus.BusListener;
import org.alljoyn.bus.Mutable;
import org.alljoyn.bus.ProxyBusObject;
import org.alljoyn.bus.SessionListener;
import org.alljoyn.bus.SessionOpts;
import org.alljoyn.bus.Status;

import java.util.Collection;

public class BackgroundService extends Service {
    private static final String TAG = BackgroundService.class.getSimpleName();

    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    private static final String PLAYBACK_STATE_CHANGED = SPOTIFY_PACKAGE + ".playbackstatechanged";
    private static final String QUEUE_CHANGED = SPOTIFY_PACKAGE + ".queuechanged";
    private static final String METADATA_CHANGED = SPOTIFY_PACKAGE + ".metadatachanged";

    private static final int MESSAGE_CONTACT_SUBMISSION = 1;
    private static final long MILLIS_IN_SECOND = 1000L;
    private static final double PROXIMITY_RANGE = 1.0;

    private Context mContext;
    private NotificationManager mNM;
    private BroadcastReceiver mMediaNotificationsReceiver;
    private boolean mIsMediaNotificationsReceiverRegistered = false;
    private boolean mIsCatalogContactsEnabled;
    /* Handler used to make calls to AllJoyn methods. See onCreate(). */
    private BusHandler mBusHandler;

    // Unique Identification Number for the Notification.
    // We use it on Notification start, and to cancel it.
    private int NOTIFICATION = R.string.local_service_started;

    static {
        System.loadLibrary("alljoyn_java");
    }

    class SpotifyAudioStreamMessage {
        public String accessToken;
        public String playlistUri;
        public String trackUri;
        public int positionInMs;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "enable_catalog_contacts");
        mContext = this;
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
//        mBeaconManager = BeaconManager.getInstanceForApplication(this);
//        mBeaconManager.bind(this);
        mMediaNotificationsReceiver = new MediaNotificationsReceiver();

        // Display a notification about us starting.  We put an icon in the status bar.
        showNotification();
        setBackgroundEventsMonitoringEnabled(true);

        /* Make all AllJoyn calls through a separate handler thread to prevent blocking the UI. */
        HandlerThread busThread = new HandlerThread("BusHandler");
        busThread.start();
        mBusHandler = new BusHandler(busThread.getLooper());

        /* Connect to an AllJoyn object. */
        mBusHandler.sendEmptyMessage(BusHandler.CONNECT);

        registerMediaNotificationsReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);
        // if (null != intent && intent.getBooleanExtra(LoginReceiver.EXTRA_SUBMISSION, false)) {
        //     submitCabDocument(100);
        // }

        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "disable_catalog_contacts");
        // Cancel the persistent notification.
        mNM.cancel(NOTIFICATION);

        // Tell the user we stopped.
        Toast.makeText(this, R.string.local_service_stopped, Toast.LENGTH_SHORT).show();
        setBackgroundEventsMonitoringEnabled(false);
//        mBeaconManager.unbind(this);

        /* Disconnect to prevent resource leaks. */
        mBusHandler.sendEmptyMessage(BusHandler.DISCONNECT);

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind, return null");
        return null;
    }

    /**
     * Show a notification while this service is running.
     */
    private void showNotification() {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        CharSequence text = getText(R.string.local_service_started);

        // Set the icon, scrolling text and timestamp
        Notification notification = new Notification(R.drawable.appicon32, text,
                System.currentTimeMillis());

        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, YamateClient.class), 0);

        // Set the info for the views that show in the notification panel.
        notification.setLatestEventInfo(this, getText(R.string.local_service_label),
                       text, contentIntent);

        // Send the notification.
        mNM.notify(NOTIFICATION, notification);
    }


    /* This class will handle all AllJoyn calls. See onCreate(). */
    class BusHandler extends Handler {
        /*
         * Name used as the well-known name and the advertised name of the service this client is
         * interested in.  This name must be a unique name both to the bus and to the network as a
         * whole.
         *
         * The name uses reverse URL style of naming, and matches the name used by the service.
         */
        private static final String SERVICE_NAME = "org.alljoyn.bus.samples.simple";
        private static final short CONTACT_PORT=42;

        private BusAttachment mBus;
        private ProxyBusObject mProxyObj;
        private SpotifyInterface mSimpleInterface;

        private int     mSessionId;
        private boolean mIsInASession;
        private boolean mIsConnected;
        private boolean mIsConnecting;
        private boolean mIsStoppingDiscovery;

        /* These are the messages sent to the BusHandler from the UI. */
        public static final int CONNECT = 1;
        public static final int JOIN_SESSION = 2;
        public static final int DISCONNECT = 3;
        public static final int PING = 4;
        public static final int SPOTIFY_ACCESS_TOKEN = 5;
        public static final int TOGGLE_PLAY = 6;
        public static final int SPOTIFY_CURRENT_TRACK = 7;
        public static final int SPOTIFY_STATS_PAUSE = 8;
        public static final int SPOTIFY_STATS_RESUME = 9;

        public BusHandler(Looper looper) {
            super(looper);

            mIsInASession = false;
            mIsConnected = false;
            mIsStoppingDiscovery = false;
            mIsConnecting=false;
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
            /* Connect to a remote instance of an object implementing the SimpleInterface. */
            case CONNECT: {

                if(mIsConnecting || mIsConnected)
                    return;

                mIsConnecting=true;

                org.alljoyn.bus.alljoyn.DaemonInit.PrepareDaemon(getApplicationContext());
                /*
                 * All communication through AllJoyn begins with a BusAttachment.
                 *
                 * A BusAttachment needs a name. The actual name is unimportant except for internal
                 * security. As a default we use the class name as the name.
                 *
                 * By default AllJoyn does not allow communication between devices (i.e. bus to bus
                 * communication). The second argument must be set to Receive to allow communication
                 * between devices.
                 */
                mBus = new BusAttachment(getPackageName(), BusAttachment.RemoteMessage.Receive);

                /*
                 * Create a bus listener class
                 */
                mBus.registerBusListener(new BusListener() {
                    @Override
                    public void foundAdvertisedName(String name, short transport, String namePrefix) {
                        logInfo(String.format("MyBusListener.foundAdvertisedName(%s, 0x%04x, %s)", name, transport, namePrefix));
                        /*
                         * This client will only join the first service that it sees advertising
                         * the indicated well-known name.  If the program is already a member of
                         * a session (i.e. connected to a service) we will not attempt to join
                         * another session.
                         * It is possible to join multiple session however joining multiple
                         * sessions is not shown in this sample.
                         */
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

                /*
                 * Now find an instance of the AllJoyn object we want to call.  We start by looking for
                 * a name, then connecting to the device that is advertising that name.
                 *
                 * In this case, we are looking for the well-known SERVICE_NAME.
                 */
                status = mBus.findAdvertisedName(SERVICE_NAME);
                logStatus(String.format("BusAttachement.findAdvertisedName(%s)", SERVICE_NAME), status);
                if (Status.OK != status) {
                    return;
                }

                break;
            }
            case (JOIN_SESSION): {
                /*
                 * If discovery is currently being stopped don't join to any other sessions.
                 */
                if (mIsStoppingDiscovery) {
                    break;
                }

                /*
                 * In order to join the session, we need to provide the well-known
                 * contact port.  This is pre-arranged between both sides as part
                 * of the definition of the chat service.  As a result of joining
                 * the session, we get a session identifier which we must use to
                 * identify the created session communication channel whenever we
                 * talk to the remote side.
                 */
                short contactPort = CONTACT_PORT;
                SessionOpts sessionOpts = new SessionOpts();
                sessionOpts.transports = (short)msg.arg1;
                Mutable.IntegerValue sessionId = new Mutable.IntegerValue();

                Status status = mBus.joinSession((String) msg.obj, contactPort, sessionId, sessionOpts, new SessionListener() {
                    @Override
                    public void sessionLost(int sessionId, int reason) {
                        mIsConnected = false;
                        logInfo(String.format("MyBusListener.sessionLost(sessionId = %d, reason = %d)", sessionId, reason));
                        Toast.makeText(BackgroundService.this, R.string.find_service, Toast.LENGTH_SHORT).show();
                        // mHandler.sendEmptyMessage(MESSAGE_START_PROGRESS_DIALOG);
                    }
                });
                logStatus("BusAttachment.joinSession() - sessionId: " + sessionId.value, status);

                if (status == Status.OK) {
                    /*
                     * To communicate with an AllJoyn object, we create a ProxyBusObject.
                     * A ProxyBusObject is composed of a name, path, sessionID and interfaces.
                     *
                     * This ProxyBusObject is located at the well-known SERVICE_NAME, under path
                     * "/SimpleService", uses sessionID of CONTACT_PORT, and implements the SimpleInterface.
                     */
                    mProxyObj =  mBus.getProxyBusObject(SERVICE_NAME,
                                                        "/SimpleService",
                                                        sessionId.value,
                                                        new Class<?>[] { SpotifyInterface.class });

                    /* We make calls to the methods of the AllJoyn object through one of its interfaces. */
                    mSimpleInterface =  mProxyObj.getInterface(SpotifyInterface.class);

                    mSessionId = sessionId.value;
                    mIsConnected = true;
                    Toast.makeText(BackgroundService.this, R.string.service_connected, Toast.LENGTH_LONG).show();
                    // mHandler.sendEmptyMessage(MESSAGE_STOP_PROGRESS_DIALOG);
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

            /*
             * Call the service's Ping method through the ProxyBusObject.
             *
             * This will also print the String that was sent to the service and the String that was
             * received from the service to the user interface.
             */
            case PING: {
                try {
                    if (mSimpleInterface != null) {
                        String reply = mSimpleInterface.Ping((String) msg.obj);
                    }
                } catch (BusException ex) {
                    logException("SimpleInterface.Ping()", ex);
                }
                break;
            }
            case SPOTIFY_ACCESS_TOKEN: {
                try {
                    if (mSimpleInterface != null) {
                        SpotifyAudioStreamMessage spotifyMessage = (SpotifyAudioStreamMessage) msg.obj;
                        mSimpleInterface.PlaySpotifyAudioStream(spotifyMessage.accessToken,
                                spotifyMessage.playlistUri);
                    }
                } catch (BusException ex) {
                    logException("SimpleInterface.PlaySpotifyAudioStream()", ex);
                }
                break;
            }
            case SPOTIFY_CURRENT_TRACK: {
                try {
                    if (mSimpleInterface != null) {
                        Toast.makeText(BackgroundService.this, "Send SPOTIFY_CURRENT_TRACK command to Service",
                                Toast.LENGTH_LONG).show();
                        SpotifyAudioStreamMessage spotifyMessage = (SpotifyAudioStreamMessage) msg.obj;
                        mSimpleInterface.PlaySpotifyCurrentTrack(spotifyMessage.trackUri, spotifyMessage.positionInMs);
                    }
                } catch (BusException ex) {
                    logException("SimpleInterface.PlaySpotifyCurrentTrack()", ex);
                }
                break;
            }
            case TOGGLE_PLAY: {
                try {
                    if (mSimpleInterface != null) {
                        mSimpleInterface.TogglePlay();
                    }
                } catch (BusException ex) {
                    logException("SimpleInterface.TogglePlay()", ex);
                }
                break;
            }

                case SPOTIFY_STATS_PAUSE: {
                    try {
                        if (mSimpleInterface != null) {
                            mSimpleInterface.ChangeSpotifyStats(0);
                        }
                    } catch (BusException ex) {
                        logException("SimpleInterface.TogglePlay()", ex);
                    }
                    break;
                }

                case SPOTIFY_STATS_RESUME: {
                    try {
                        if (mSimpleInterface != null) {
                            mSimpleInterface.ChangeSpotifyStats(1);
                        }
                    } catch (BusException ex) {
                        logException("SimpleInterface.TogglePlay()", ex);
                    }
                    break;
                }

            default:
                break;
            }
        }

        /* Helper function to send a message to the UI thread. */
        private void sendUiMessage(int what, Object obj) {
            // mHandler.sendMessage(mHandler.obtainMessage(what, obj));
        }

        public boolean isConnected() {
            return mIsConnected;
        }
    }

    static boolean mSpotDevicePlaying=false;

    private final class MediaNotificationsReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            long timeSentInMs = intent.getLongExtra("timeSent", 0L);
            String action = intent.getAction();


            if(!mBusHandler.isConnected())
            {
                mBusHandler.sendEmptyMessage(BusHandler.CONNECT);
                return;
            }

            Log.d(TAG, "onReceive:" + action);

            if (action.equals(PLAYBACK_STATE_CHANGED)) {
                boolean playing = intent.getBooleanExtra("playing", false);
                int positionInMs = intent.getIntExtra("playbackPosition", 0);
                //changing player status..
                if(!playing)
                pausePlay();
                else
                    resumePlay();

            } else if (action.equals(QUEUE_CHANGED)) {
            } else if (action.equals(METADATA_CHANGED)) {
                if(mSpotDevicePlaying)
                {
                    String trackId = intent.getStringExtra("id");
                    String artistName = intent.getStringExtra("artist");
                    String albumName = intent.getStringExtra("album");
                    String trackName = intent.getStringExtra("track");
                    int trackLengthInSec = intent.getIntExtra("length", 0);
                    Log.d(TAG, "playPlaylistUri:" + trackLengthInSec);
                    int trackPlaybackPositionInMs = (int) (System.currentTimeMillis() - timeSentInMs);
                    playPlaylistUri(trackId, Math.max(trackPlaybackPositionInMs, 0));
                }
            }
        }
    }

    private void playPlaylistUri(final String trackUri, final int positionInMs) {
        mSpotDevicePlaying=true;
        Log.d(TAG, "playPlaylistUri:" + positionInMs);
        SpotifyAudioStreamMessage spotifyMessage = new SpotifyAudioStreamMessage();
        spotifyMessage.trackUri = trackUri;
        spotifyMessage.positionInMs = positionInMs;
        Message msg = mBusHandler.obtainMessage(BusHandler.SPOTIFY_CURRENT_TRACK, spotifyMessage);
        mBusHandler.sendMessage(msg);
    }

    private void togglePlay() {
        Message msg = mBusHandler.obtainMessage(BusHandler.TOGGLE_PLAY);
        mBusHandler.sendMessage(msg);
    }

    private void pausePlay() {
        mSpotDevicePlaying=false;
        Log.d(TAG, "pausePlay");
        Message msg = mBusHandler.obtainMessage(BusHandler.SPOTIFY_STATS_PAUSE);
        mBusHandler.sendMessage(msg);
    }

    private void resumePlay() {
        mSpotDevicePlaying=true;
        Log.d(TAG, "resumePlay");
        Message msg = mBusHandler.obtainMessage(BusHandler.SPOTIFY_STATS_RESUME);
        mBusHandler.sendMessage(msg);
    }

    private void setBackgroundEventsMonitoringEnabled(boolean isEnabled) {
        if (isEnabled) {
            unregisterMediaNotificationsReceiver();
        }
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

    private void logStatus(String msg, Status status) {
        String log = String.format("%s: %s", msg, status);
        if (status == Status.OK) {
            Log.i(TAG, log);
        } else {
            Log.e(TAG, log);
        }
    }

    private void logException(String msg, BusException ex) {
        String log = String.format("%s: %s", msg, ex);
        Log.e(TAG, log, ex);
    }

    /*
     * print the status or result to the Android log. If the result is the expected
     * result only print it to the log.  Otherwise print it to the error log and
     * Sent a Toast to the users screen.
     */
    private void logInfo(String msg) {
        Log.i(TAG, msg);
    }


}
