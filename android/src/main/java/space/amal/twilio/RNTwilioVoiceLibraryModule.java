package space.amal.twilio;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import space.amal.twilio.BuildConfig;
import com.facebook.react.bridge.JSApplicationIllegalArgumentException;
import com.facebook.react.bridge.AssertionException;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReadableMap;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableMap;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import com.google.firebase.FirebaseApp;
import com.google.firebase.iid.FirebaseInstanceId;
import com.twilio.voice.AudioCodec;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.LogLevel;
import com.twilio.voice.OpusCodec;
import com.twilio.voice.RegistrationException;
import com.twilio.voice.RegistrationListener;
import com.twilio.voice.Voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


import static space.amal.twilio.EventManager.EVENT_CONNECTION_DID_CONNECT;
import static space.amal.twilio.EventManager.EVENT_CONNECTION_DID_DISCONNECT;
import static space.amal.twilio.EventManager.EVENT_DEVICE_DID_RECEIVE_INCOMING;
import static space.amal.twilio.EventManager.EVENT_DEVICE_NOT_READY;
import static space.amal.twilio.EventManager.EVENT_DEVICE_READY;
import static space.amal.twilio.EventManager.EVENT_CALL_RINGING;
import static space.amal.twilio.EventManager.EVENT_CONNECTION_DID_RECONNECT;
import static space.amal.twilio.EventManager.EVENT_CONNECTION_RECONNECTING;



public class RNTwilioVoiceLibraryModule extends ReactContextBaseJavaModule implements ActivityEventListener, LifecycleEventListener {

    public static String TAG = "RNTwilioVoiceLibrary";

    private static final int MIC_PERMISSION_REQUEST_CODE = 1;

    private AudioManager audioManager;
    private int originalAudioMode = AudioManager.MODE_NORMAL;

    private boolean isReceiverRegistered = false;
    private VoiceBroadcastReceiver voiceBroadcastReceiver;

    // Empty HashMap, contains parameters for the Outbound call
    private HashMap<String, String> twiMLParams = new HashMap<>();

    public static final String INCOMING_CALL_INVITE          = "INCOMING_CALL_INVITE";
    public static final String INCOMING_CALL_NOTIFICATION_ID = "INCOMING_CALL_NOTIFICATION_ID";
    public static final String CANCELLED_CALL_SSID = "CANCELLED_CALL_SSID";
    public static final String CANCELLED_CALL_INVITE = "CANCELLED_CALL_INVITE";
    public static final String NOTIFICATION_TYPE             = "NOTIFICATION_TYPE";

    public static final String ACTION_INCOMING_CALL = "space.amal.twilio.INCOMING_CALL";
    public static final String ACTION_FCM_TOKEN     = "space.amal.twilio.ACTION_FCM_TOKEN";
    public static final String ACTION_MISSED_CALL   = "space.amal.twilio.MISSED_CALL";
    public static final String ACTION_CANCELLED_CALL   = "space.amal.twilio.CANCELLED_CALL";
    public static final String ACTION_ANSWER_CALL   = "space.amal.twilio.ANSWER_CALL";
    public static final String ACTION_REJECT_CALL   = "space.amal.twilio.REJECT_CALL";
    public static final String ACTION_HANGUP_CALL   = "space.amal.twilio.HANGUP_CALL";
    public static final String ACTION_CLEAR_MISSED_CALLS_COUNT = "space.amal.twilio.CLEAR_MISSED_CALLS_COUNT";

    public static final String CALL_SID_KEY = "CALL_SID";
    public static final String INCOMING_NOTIFICATION_PREFIX = "Incoming_";
    public static final String MISSED_CALLS_GROUP = "MISSED_CALLS";
    public static final int MISSED_CALLS_NOTIFICATION_ID = 1;
    public static final int HANGUP_NOTIFICATION_ID = 11;
    public static final int CLEAR_MISSED_CALLS_NOTIFICATION_ID = 21;

    public static final String PREFERENCE_KEY = "space.amal.twilio.PREFERENCE_FILE_KEY";

    private NotificationManager notificationManager;
    private CallNotificationManager callNotificationManager;
    private ProximityManager proximityManager;

    private String accessToken;

    private String toNumber = "";
    private String toName = "";

    static Map<String, Integer> callNotificationMap;

    private RegistrationListener registrationListener = registrationListener();
    private Call.Listener callListener = callListener();

    private CallInvite activeCallInvite;
    private Call activeCall;

    // this variable determines when to create missed calls notifications
    private Boolean callAccepted = false;

    private AudioFocusRequest focusRequest;
    private HeadsetManager headsetManager;
    private EventManager eventManager;

    public RNTwilioVoiceLibraryModule(ReactApplicationContext reactContext,
                                      boolean shouldAskForMicPermission) {
        super(reactContext);
        if (BuildConfig.DEBUG) {
            Voice.setLogLevel(LogLevel.DEBUG);
        } else {
            Voice.setLogLevel(LogLevel.ERROR);
        }
        reactContext.addActivityEventListener(this);
        reactContext.addLifecycleEventListener(this);

        eventManager = new EventManager(reactContext);
        callNotificationManager = new CallNotificationManager();
        proximityManager = new ProximityManager(reactContext, eventManager);
        headsetManager = new HeadsetManager(eventManager);

        notificationManager = (android.app.NotificationManager) reactContext.getSystemService(Context.NOTIFICATION_SERVICE);

        /*
         * Setup the broadcast receiver to be notified of GCM Token updates
         * or incoming call messages in this Activity.
         */
        voiceBroadcastReceiver = new VoiceBroadcastReceiver();
        registerReceiver();

        RNTwilioVoiceLibraryModule.callNotificationMap = new HashMap<>();

        /*
         * Needed for setting/abandoning audio focus during a call
         */
        audioManager = (AudioManager) reactContext.getSystemService(Context.AUDIO_SERVICE);

        /*
         * Ensure the microphone permission is enabled
         */
        if (shouldAskForMicPermission && !checkPermissionForMicrophone()) {
            requestPermissionForMicrophone();
        }
    }

    @Override
    public void onHostResume() {
        /*
         * Enable changing the volume using the up/down keys during a conversation
         */
        getCurrentActivity().setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        registerReceiver();
    }

    @Override
    public void onHostPause() {
        // the library needs to listen for events even when the app is paused
//        unregisterReceiver();
    }

    @Override
    public void onHostDestroy() {
        disconnect();
        callNotificationManager.removeHangupNotification(getReactApplicationContext());
        unsetAudioFocus();
    }

    @Override
    public String getName() {
        return TAG;
    }

    public void onNewIntent(Intent intent) {
        // This is called only when the App is in the foreground
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onNewIntent " + intent.toString());
        }
        if (android.os.Build.VERSION.SDK_INT < 29) {
            Log.w("RNTwilioVoiceAmal", "Ignored Duplicate Call to HandleIncoming");
            return;
        }
        handleIncomingCallIntent(intent);
    }

    private RegistrationListener registrationListener() {
        return new RegistrationListener() {
            @Override
            public void onRegistered(String accessToken, String fcmToken) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Successfully registered FCM");
                }
                eventManager.sendEvent(EVENT_DEVICE_READY, null);
            }

            @Override
            public void onError(RegistrationException error, String accessToken, String fcmToken) {
                Log.e(TAG, String.format("Registration Error: %d, %s", error.getErrorCode(), error.getMessage()));
                WritableMap params = Arguments.createMap();
                params.putString("err", error.getMessage());
                eventManager.sendEvent(EVENT_DEVICE_NOT_READY, params);
            }
        };
    }

    private Call.Listener callListener() {
        return new Call.Listener() {
            @Override
            public void onConnected(Call call) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "CALL CONNECTED callListener().onConnected call state = "+call.getState());
                }
                setAudioFocus();
                proximityManager.startProximitySensor();
                headsetManager.startWiredHeadsetEvent(getReactApplicationContext());

                WritableMap params = Arguments.createMap();
                if (call != null) {
                    params.putString("call_sid",   call.getSid());
                    params.putString("call_state", call.getState().name());
                    params.putString("call_from", call.getFrom());
                    params.putString("call_to", call.getTo());
                    String caller = "Show call details in the app";
                    if (!toName.equals("")) {
                        caller = toName;
                    } else if (!toNumber.equals("")) {
                        caller = toNumber;
                    }
                    activeCall = call;
                    callNotificationManager.createHangupLocalNotification(getReactApplicationContext(),
                            call.getSid(), caller);
                }
                eventManager.sendEvent(EVENT_CONNECTION_DID_CONNECT, params);
            }

            @Override
            public void onDisconnected(Call call, CallException error) {
                unsetAudioFocus();
                proximityManager.stopProximitySensor();
                headsetManager.stopWiredHeadsetEvent(getReactApplicationContext());
                callAccepted = false;

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "call disconnected");
                }

                WritableMap params = Arguments.createMap();
                String callSid = "";
                if (call != null) {
                    callSid = call.getSid();
                    params.putString("call_sid", callSid);
                    params.putString("call_state", call.getState().name());
                    params.putString("call_from", call.getFrom());
                    params.putString("call_to", call.getTo());
                }
                if (error != null) {
                    Log.e(TAG, String.format("CallListener onDisconnected error: %d, %s",
                            error.getErrorCode(), error.getMessage()));
                    params.putString("err", error.getMessage());
                }
                if (callSid != null && activeCall != null && activeCall.getSid() != null && activeCall.getSid().equals(callSid)) {
                    activeCall = null;
                    activeCallInvite = null;
                }
                eventManager.sendEvent(EVENT_CONNECTION_DID_DISCONNECT, params);
                callNotificationManager.removeHangupNotification(getReactApplicationContext());
                toNumber = "";
                toName = "";
            }

            @Override
            public void onConnectFailure(Call call, CallException error) {
                unsetAudioFocus();
                proximityManager.stopProximitySensor();
                callAccepted = false;
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "connect failure");
                }

                Log.e(TAG, String.format("CallListener onDisconnected error: %d, %s",
                        error.getErrorCode(), error.getMessage()));

                WritableMap params = Arguments.createMap();
                params.putString("err", error.getMessage());
                String callSid = "";
                if (call != null) {
                    callSid = call.getSid();
                    params.putString("call_sid", callSid);
                    params.putString("call_state", call.getState().name());
                    params.putString("call_from", call.getFrom());
                    params.putString("call_to", call.getTo());
                }
                if (callSid != null && activeCall != null && activeCall.getSid() != null && activeCall.getSid().equals(callSid)) {
                    activeCall = null;
                    activeCallInvite = null;
                }
                eventManager.sendEvent(EVENT_CONNECTION_DID_DISCONNECT, params);
                callNotificationManager.removeHangupNotification(getReactApplicationContext());
                toNumber = "";
                toName = "";
            }

            @Override
            public void onRinging(@NonNull Call call) {
                eventManager.sendEvent(EVENT_CALL_RINGING, paramsFromCall(call));
            }

            @Override
            public void onReconnecting(@NonNull Call call, @NonNull CallException callException) {
                eventManager.sendEvent(EVENT_CONNECTION_RECONNECTING, paramsFromCall(call));
            }

            @Override
            public void onReconnected(@NonNull Call call) {
                setAudioFocus();
                proximityManager.startProximitySensor();
                headsetManager.startWiredHeadsetEvent(getReactApplicationContext());
                activeCall = call;
                eventManager.sendEvent(EVENT_CONNECTION_DID_RECONNECT, paramsFromCall(call));
            }
        };
    }

    private WritableMap paramsFromCall(Call call) {
        WritableMap params = Arguments.createMap();
        String callSid = "";
        if (call != null) {
            callSid = call.getSid();
            params.putString("call_sid", callSid);
            params.putString("call_state", call.getState().name());
            params.putString("call_from", call.getFrom());
            params.putString("call_to", call.getTo());
        }
        return params;
    }

    /**
     * Register the Voice broadcast receiver
     */
    private void registerReceiver() {
        if (!isReceiverRegistered) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACTION_INCOMING_CALL);
            intentFilter.addAction(ACTION_MISSED_CALL);
            intentFilter.addAction(ACTION_CANCELLED_CALL);
            LocalBroadcastManager.getInstance(getReactApplicationContext()).registerReceiver(
                    voiceBroadcastReceiver, intentFilter);
            registerActionReceiver();
            isReceiverRegistered = true;
        }
    }

//    private void unregisterReceiver() {
//        if (isReceiverRegistered) {
//            LocalBroadcastManager.getInstance(getReactApplicationContext()).unregisterReceiver(voiceBroadcastReceiver);
//            isReceiverRegistered = false;
//        }
//    }

    private void registerActionReceiver() {

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_ANSWER_CALL);
        intentFilter.addAction(ACTION_REJECT_CALL);
        intentFilter.addAction(ACTION_HANGUP_CALL);
        intentFilter.addAction(ACTION_CLEAR_MISSED_CALLS_COUNT);

        getReactApplicationContext().registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                switch (action) {
                    case ACTION_ANSWER_CALL:
                        accept();
                        break;
                    case ACTION_REJECT_CALL:
                        reject();
                        break;
                    case ACTION_HANGUP_CALL:
                        disconnect();
                        break;
                    case ACTION_CLEAR_MISSED_CALLS_COUNT:
                        SharedPreferences sharedPref = context.getSharedPreferences(PREFERENCE_KEY, Context.MODE_PRIVATE);
                        SharedPreferences.Editor sharedPrefEditor = sharedPref.edit();
                        sharedPrefEditor.putInt(MISSED_CALLS_GROUP, 0);
                        sharedPrefEditor.commit();
                        break;
                }
                // Dismiss the notification when the user tap on the relative notification action
                // eventually the notification will be cleared anyway
                // but in this way there is no UI lag
                notificationManager.cancel(intent.getIntExtra(INCOMING_CALL_NOTIFICATION_ID, 0));
            }
        }, intentFilter);
    }

    // removed @Override temporarily just to get it working on different versions of RN
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        onActivityResult(requestCode, resultCode, data);
    }

    // removed @Override temporarily just to get it working on different versions of RN
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Ignored, required to implement ActivityEventListener for RN 0.33
    }

    private void handleMissedCallIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            Log.e(TAG, "handleMissedCallIntent intent is null");
            return;
        }
        Log.d(TAG, "in handleMissedCallIntent");
        int appImportance = callNotificationManager.getApplicationImportance(getReactApplicationContext());
        if (appImportance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                appImportance == RunningAppProcessInfo.IMPORTANCE_SERVICE) {

            Log.d(TAG, "handleMissedCallIntent");
            CancelledCallInvite cancelled = intent.getParcelableExtra(CANCELLED_CALL_INVITE);
            WritableMap params = Arguments.createMap();
            params.putString("call_sid", cancelled.getCallSid());
            params.putString("call_from", cancelled.getFrom());
            params.putString("call_to", cancelled.getTo());
            Log.d(TAG, "sending handleMissedCallIntent:EVENT_DEVICE_DID_DISCONNECT event");
            eventManager.sendEvent(EVENT_CONNECTION_DID_DISCONNECT, params);
        }
        activeCallInvite = null;
    }

    private void handleIncomingCallIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            Log.e(TAG, "handleIncomingCallIntent intent is null");
            return;
        }


        ReactApplicationContext context = getReactApplicationContext();

        SharedPreferences sharedPref = context.getSharedPreferences("NativeStorage", Context.MODE_PRIVATE);
        String session = sharedPref.getString("SESSION", "none");
        String url = sharedPref.getString("URL", "none");
        String bot = sharedPref.getString("CONTACTS_BOT", "none");

        if(session.equalsIgnoreCase("none")){
            return;
        }

        if (intent.getAction().equals(ACTION_INCOMING_CALL)) {
            CallInvite incoming = intent.getParcelableExtra(INCOMING_CALL_INVITE);

            if (incoming!= null) {
                if (activeCallInvite != null || activeCall != null) {
                    // Reject the incoming call
                    Log.d(TAG, "Rejecting call because another call Invite present");
                    incoming.reject(getReactApplicationContext());
                    return;
                }
                callAccepted = false;
                activeCallInvite = incoming;
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "handleIncomingCallIntent state = PENDING");
                }
                SoundPoolManager.getInstance(getReactApplicationContext()).playRinging();

                if (getReactApplicationContext().getCurrentActivity() != null) {
                    Window window = getReactApplicationContext().getCurrentActivity().getWindow();
                    window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    );
                }
                // send a JS event ONLY if the app's importance is FOREGROUND or SERVICE
                // at startup the app would try to fetch the activeIncoming calls
                int appImportance = callNotificationManager.getApplicationImportance(getReactApplicationContext());
                if (appImportance <= 1001) {

                    final WritableMap params = Arguments.createMap();
                    params.putString("call_sid", activeCallInvite.getCallSid());
                    params.putString("call_from", activeCallInvite.getFrom());
                    params.putString("call_to", activeCallInvite.getTo());
                    Log.d(TAG, "sending EVENT_DEVICE_DID_RECEIVE_INCOMING event");
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            eventManager.sendEvent(EVENT_DEVICE_DID_RECEIVE_INCOMING, params);
                        }
                    }, 2000);
                }

            }


        } else if (intent.getAction().equals(ACTION_FCM_TOKEN)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "handleIncomingCallIntent ACTION_FCM_TOKEN");
            }
            registerForCallInvites();
        }
    }

    private class VoiceBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ACTION_INCOMING_CALL)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "VoiceBroadcastReceiver.onReceive ACTION_INCOMING_CALL. Intent "+ intent.getExtras());
                }
                handleIncomingCallIntent(intent);
            } else if (action.equals(ACTION_MISSED_CALL)) {
                SharedPreferences sharedPref = getReactApplicationContext().getSharedPreferences(PREFERENCE_KEY, Context.MODE_PRIVATE);
                SharedPreferences.Editor sharedPrefEditor = sharedPref.edit();
                sharedPrefEditor.remove(MISSED_CALLS_GROUP);
                sharedPrefEditor.commit();
            } else if (action.equals(ACTION_CANCELLED_CALL)) {
                Log.d(TAG, "In ACTION_CANCELLED_CALL");
                handleMissedCallIntent(intent);
            } else {
                Log.e(TAG, "received broadcast unhandled action " + action);
            }
        }
    }

    @ReactMethod
    public void initWithToken(final String accessToken, Promise promise) {
        this.initialize(accessToken, promise);
    }

    @ReactMethod
    public void initWithAccessToken(final String accessToken, Promise promise) {
        this.initialize(accessToken, promise);
    }

    public void initialize(final String accessToken, Promise promise) {
        if (accessToken.equals("")) {
            promise.reject(new JSApplicationIllegalArgumentException("Invalid access token"));
            return;
        }

        if(!checkPermissionForMicrophone()) {
            promise.reject(new AssertionException("Can't init without microphone permission"));
        }

        RNTwilioVoiceLibraryModule.this.accessToken = accessToken;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "initialize ACTION_FCM_TOKEN");
        }
        registerForCallInvites();
        WritableMap params = Arguments.createMap();
        params.putBoolean("initialized", true);
        promise.resolve(params);
    }


    private void clearIncomingNotification(CallInvite callInvite) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "clearIncomingNotification() incoming callInvite from: "+ callInvite.getFrom());
        }
        if (callInvite != null && callInvite.getCallSid() != null) {
            // remove incoming call notification
            String notificationKey = INCOMING_NOTIFICATION_PREFIX + callInvite.getCallSid();
            int notificationId = 0;
            if (RNTwilioVoiceLibraryModule.callNotificationMap.containsKey(notificationKey)) {
                notificationId = RNTwilioVoiceLibraryModule.callNotificationMap.get(notificationKey);
            }
            callNotificationManager.removeIncomingCallNotification(getReactApplicationContext(), callInvite, notificationId);
            RNTwilioVoiceLibraryModule.callNotificationMap.remove(notificationKey);
        }
//        activeCallInvite = null;
    }

    /*
     * Register your FCM token with Twilio to receive incoming call invites
     *
     * If a valid google-services.json has not been provided or the FirebaseInstanceId has not been
     * initialized the fcmToken will be null.
     *
     * In the case where the FirebaseInstanceId has not yet been initialized the
     * VoiceFirebaseInstanceIDService.onTokenRefresh should result in a LocalBroadcast to this
     * activity which will attempt registerForCallInvites again.
     *
     */
    private void registerForCallInvites() {
        FirebaseApp.initializeApp(getReactApplicationContext());
        final String fcmToken = FirebaseInstanceId.getInstance().getToken();
        if (fcmToken != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Registering with FCM");
            }
            Voice.register(accessToken, Voice.RegistrationChannel.FCM, fcmToken, registrationListener);
        }
    }

    @ReactMethod
    public void accept() {
        callAccepted = true;
        SoundPoolManager.getInstance(getReactApplicationContext()).stopRinging();
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "In accept()");
        }
        if (activeCallInvite != null){
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "accept() activeCallInvite.getState() PENDING");
            }
            activeCallInvite.accept(getReactApplicationContext(), callListener);
            clearIncomingNotification(activeCallInvite);
            WritableMap params = Arguments.createMap();
            params.putString("call_sid",   activeCallInvite.getCallSid());
            params.putString("call_from",  activeCallInvite.getFrom());
            params.putString("call_to",    activeCallInvite.getTo());
            params.putString("call_state", "CONNECTED");
            callNotificationManager.createHangupLocalNotification(getReactApplicationContext(),
                    activeCallInvite.getCallSid(),
                    activeCallInvite.getFrom());

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Sending EVENT_CONNECTION_DID_CONNECT");
            }
            eventManager.sendEvent(EVENT_CONNECTION_DID_CONNECT, params);

            /*
                // when the user answers a call from a notification before the react-native App
                // is completely initialised, and the first event has been skipped
                // re-send connectionDidConnect message to JS
                WritableMap params = Arguments.createMap();
                params.putString("call_sid",   activeCallInvite.getCallSid());
                params.putString("call_from",  activeCallInvite.getFrom());
                params.putString("call_to",    activeCallInvite.getTo());
                params.putString("call_state", activeCallInvite.getState().name());
                callNotificationManager.createHangupLocalNotification(getReactApplicationContext(),
                        activeCallInvite.getCallSid(),
                        activeCallInvite.getFrom());
                eventManager.sendEvent(EVENT_CONNECTION_DID_CONNECT, params);
            */
        } else {
            eventManager.sendEvent(EVENT_CONNECTION_DID_DISCONNECT, null);
        }
    }

    @ReactMethod
    public void reject() {
        callAccepted = false;
        SoundPoolManager.getInstance(getReactApplicationContext()).stopRinging();
        WritableMap params = Arguments.createMap();
        if (activeCallInvite != null){
            params.putString("call_sid",   activeCallInvite.getCallSid());
            params.putString("call_from",  activeCallInvite.getFrom());
            params.putString("call_to",    activeCallInvite.getTo());
            activeCallInvite.reject(getReactApplicationContext());
            clearIncomingNotification(activeCallInvite);
        }
        activeCallInvite = null;
        eventManager.sendEvent(EVENT_CONNECTION_DID_DISCONNECT, params);
    }

    @ReactMethod
    public void ignore() {
        callAccepted = false;
        SoundPoolManager.getInstance(getReactApplicationContext()).stopRinging();
        WritableMap params = Arguments.createMap();
        if (activeCallInvite != null){
            params.putString("call_sid",   activeCallInvite.getCallSid());
            params.putString("call_from",  activeCallInvite.getFrom());
            params.putString("call_to",    activeCallInvite.getTo());
            clearIncomingNotification(activeCallInvite);
        }
        eventManager.sendEvent(EVENT_CONNECTION_DID_DISCONNECT, params);
    }

    @ReactMethod
    public void connect(ReadableMap params) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "connect params: "+params);
        }
        WritableMap errParams = Arguments.createMap();
        if (accessToken == null) {
            errParams.putString("err", "Invalid access token");
            eventManager.sendEvent(EVENT_DEVICE_NOT_READY, errParams);
            return;
        }
        if (params == null) {
            errParams.putString("err", "Invalid parameters");
            eventManager.sendEvent(EVENT_CONNECTION_DID_DISCONNECT, errParams);
            return;
        } else if (!params.hasKey("To")) {
            errParams.putString("err", "Invalid To parameter");
            eventManager.sendEvent(EVENT_CONNECTION_DID_DISCONNECT, errParams);
            return;
        }
        toNumber = params.getString("To");
        if (params.hasKey("ToName")) {
            toName = params.getString("ToName");
        }

        twiMLParams.clear();

        ReadableMapKeySetIterator iterator = params.keySetIterator();
        while (iterator.hasNextKey()) {
            String key = iterator.nextKey();
            ReadableType readableType = params.getType(key);
            switch (readableType) {
                case Null:
                    twiMLParams.put(key, "");
                    break;
                case Boolean:
                    twiMLParams.put(key, String.valueOf(params.getBoolean(key)));
                    break;
                case Number:
                    // Can be int or double.
                    twiMLParams.put(key, String.valueOf(params.getDouble(key)));
                    break;
                case String:
                    twiMLParams.put(key, params.getString(key));
                    break;
                default:
                    Log.d(TAG, "Could not convert with key: " + key + ".");
                    break;
            }
        }
        ConnectOptions.Builder connectOptionsBuilder = new ConnectOptions.Builder(accessToken)
                .params(twiMLParams);
        if (params.hasKey("maxBitRate")) {
            connectOptionsBuilder.preferAudioCodecs(new ArrayList<AudioCodec>(Arrays.asList(new OpusCodec(params.getInt("maxBitRate")))));
        }
        activeCall = Voice.connect(getReactApplicationContext(), connectOptionsBuilder.build(), callListener);
    }

    @ReactMethod
    public void disconnect() {
        if (activeCall != null) {
            activeCall.disconnect();
            activeCall = null;
        }
        activeCallInvite = null;
    }

    @ReactMethod
    public void setMuted(Boolean muteValue) {
        if (activeCall != null) {
            activeCall.mute(muteValue);
        }
    }

    @ReactMethod
    public void sendDigits(String digits) {
        if (activeCall != null) {
            activeCall.sendDigits(digits);
        }
    }

    @ReactMethod
    public void getActiveCall(Promise promise) {
        if (activeCall != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Active call found state = "+activeCall.getState());
            }
            WritableMap params = Arguments.createMap();
            params.putString("call_sid",   activeCall.getSid());
            params.putString("call_from",  activeCall.getFrom());
            params.putString("call_to",    activeCall.getTo());
            params.putString("call_state", activeCall.getState().name());
            promise.resolve(params);
            return;
        }
        if (activeCallInvite != null) {
            WritableMap params = Arguments.createMap();
            params.putString("call_sid",   activeCallInvite.getCallSid());
            params.putString("call_from",  activeCallInvite.getFrom());
            params.putString("call_to",    activeCallInvite.getTo());
            promise.resolve(params);
            return;
        }
        promise.resolve(null);
    }

    @ReactMethod
    public void setSpeakerPhone(Boolean value) {
        // TODO check whether it is necessary to call setAudioFocus again
//        setAudioFocus();
        audioManager.setSpeakerphoneOn(value);
    }

    private void setAudioFocus() {
        if (audioManager == null) {
            return;
        }
        originalAudioMode = audioManager.getMode();
        // Request audio focus before making any device switch
        if (Build.VERSION.SDK_INT >= 26) {
            AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int i) { }
                    })
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            );
        }
        /*
         * Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
         * required to be in this mode when playout and/or recording starts for
         * best possible VoIP performance. Some devices have difficulties with speaker mode
         * if this is not set.
         */
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
    }

    private void unsetAudioFocus() {
        if (audioManager == null) {
            return;
        }
        audioManager.setMode(originalAudioMode);
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(null);
        }
    }

    private boolean checkPermissionForMicrophone() {
        int resultMic = ContextCompat.checkSelfPermission(getReactApplicationContext(), Manifest.permission.RECORD_AUDIO);
        return resultMic == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionForMicrophone() {
        if (getCurrentActivity() != null) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(getCurrentActivity(), Manifest.permission.RECORD_AUDIO)) {
                //            Snackbar.make(coordinatorLayout,
                //                    "Microphone permissions needed. Please allow in your application settings.",
                //                    SNACKBAR_DURATION).show();
            } else {
                ActivityCompat.requestPermissions(getCurrentActivity(), new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_REQUEST_CODE);
            }
        }
    }
}

