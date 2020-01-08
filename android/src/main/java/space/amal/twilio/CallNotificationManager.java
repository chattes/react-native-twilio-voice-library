package space.amal.twilio;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.WindowManager;

import space.amal.twilio.BuildConfig;
import space.amal.twilio.R;
import com.facebook.react.bridge.ReactApplicationContext;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;


import org.json.JSONException;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;


import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;


import java.io.IOException;

import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static android.content.Context.ACTIVITY_SERVICE;

import static space.amal.twilio.RNTwilioVoiceLibraryModule.CANCELLED_CALL_INVITE;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.TAG;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.ACTION_ANSWER_CALL;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.ACTION_REJECT_CALL;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.ACTION_HANGUP_CALL;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.ACTION_INCOMING_CALL;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.ACTION_MISSED_CALL;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.INCOMING_CALL_INVITE;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.INCOMING_CALL_NOTIFICATION_ID;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.CANCELLED_CALL_SSID;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.NOTIFICATION_TYPE;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.CALL_SID_KEY;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.INCOMING_NOTIFICATION_PREFIX;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.MISSED_CALLS_GROUP;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.MISSED_CALLS_NOTIFICATION_ID;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.HANGUP_NOTIFICATION_ID;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.PREFERENCE_KEY;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.ACTION_CLEAR_MISSED_CALLS_COUNT;
import static space.amal.twilio.RNTwilioVoiceLibraryModule.CLEAR_MISSED_CALLS_NOTIFICATION_ID;


public class CallNotificationManager {

    private static final String VOICE_CHANNEL = "default";
    public static String Caller_name_global = "";

    private NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();

    public CallNotificationManager() {}

    public int getApplicationImportance(ReactApplicationContext context) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
        if (activityManager == null) {
            return 0;
        }
        List<ActivityManager.RunningAppProcessInfo> processInfos = activityManager.getRunningAppProcesses();
        if (processInfos == null) {
            return 0;
        }

        for (ActivityManager.RunningAppProcessInfo processInfo : processInfos) {
            if (processInfo.processName.equals(context.getApplicationInfo().packageName)) {
                return processInfo.importance;
            }
        }
        return 0;
    }

    public Class getMainActivityClass(ReactApplicationContext context) {
        String packageName = context.getPackageName();
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        String className = launchIntent.getComponent().getClassName();
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    public Intent getLaunchIntent(ReactApplicationContext context,
                                  int notificationId,
                                  CallInvite callInvite,
                                  Boolean shouldStartNewTask,
                                  int appImportance
    ) {
        Intent launchIntent = new Intent(context, getMainActivityClass(context));

        int launchFlag = Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP;
        if (shouldStartNewTask || appImportance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            launchFlag = Intent.FLAG_ACTIVITY_NEW_TASK;
        }

        launchIntent.setAction(ACTION_INCOMING_CALL)
                .putExtra(INCOMING_CALL_NOTIFICATION_ID, notificationId)
                .addFlags(
                        launchFlag +
                                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED +
                                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD +
                                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON +
                                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                );

        if (callInvite != null) {
            launchIntent.putExtra(INCOMING_CALL_INVITE, callInvite);
        }
        return launchIntent;
    }


    class RequestTask extends AsyncTask<Object, String, String>{
        @Override
        protected String doInBackground(Object... objects) {
            String id = (String) objects[0];
            String sessionId = (String) objects[1];
            String urlContacts = (String) objects[2];
            String bot = (String) objects[3];


            OkHttpClient client = new OkHttpClient();

            HttpUrl.Builder urlBuilder = HttpUrl.parse(urlContacts).newBuilder();
            urlBuilder.addQueryParameter("userId", id);
            urlBuilder.addQueryParameter("botId", bot);

            String url = urlBuilder.build().toString();

            Request request = new Request.Builder().url(url).header("sessionId", sessionId).build();

            String callerName = "Unknown";
            try{
                Response response = client.newCall(request).execute();
                String jsonData = response.body().string();
                JSONObject jObject = new JSONObject(jsonData);
                callerName = jObject.getString("userName");

                Caller_name_global = callerName;

            }catch(IOException e){

            }catch(JSONException e){

            }

            return callerName;

        }
    }

    public void createIncomingCallNotification(ReactApplicationContext context,
                                               CallInvite callInvite,
                                               int notificationId,
                                               Intent launchIntent)
    {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "createIncomingCallNotification intent "+launchIntent.getFlags());
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        /*
         * Pass the notification id and call sid to use as an identifier to cancel the
         * notification later
         */
        Bundle extras = new Bundle();
        extras.putInt(INCOMING_CALL_NOTIFICATION_ID, notificationId);
        extras.putString(CALL_SID_KEY, callInvite.getCallSid());
        extras.putString(NOTIFICATION_TYPE, ACTION_INCOMING_CALL);
        /*
         * Create the notification shown in the notification drawer
         */
        initCallNotificationsChannel(notificationManager);


//      FrontM Changes *****************

        String caller_name = callInvite.getFrom();

        if(caller_name.toLowerCase().contains("client")){
            String caller_id = callInvite.getFrom().split(":")[1];
            caller_name = getCallerName(context, caller_id);
            if(caller_name == "" || caller_name == "Unknown"){
                return;
            }
        }

        Caller_name_global = caller_name;



//      *********************************

        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(context, VOICE_CHANNEL)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setCategory(NotificationCompat.CATEGORY_CALL)
                        .setSmallIcon(R.drawable.ic_call_white_24dp)
                        .setContentTitle("Incoming call")
                        .setContentText(Caller_name_global + " is calling")
                        .setOngoing(true)
                        .setAutoCancel(true)
                        .setExtras(extras)
                        .setFullScreenIntent(pendingIntent, true);

        // build notification large icon
        Resources res = context.getResources();
        int largeIconResId = res.getIdentifier("ic_launcher", "mipmap", context.getPackageName());
        Bitmap largeIconBitmap = BitmapFactory.decodeResource(res, largeIconResId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (largeIconResId != 0) {
                notificationBuilder.setLargeIcon(largeIconBitmap);
            }
        }

        // Reject action
        Intent rejectIntent = new Intent(ACTION_REJECT_CALL)
                .putExtra(INCOMING_CALL_NOTIFICATION_ID, notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingRejectIntent = PendingIntent.getBroadcast(context, 1, rejectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        notificationBuilder.addAction(0, "DISMISS", pendingRejectIntent);

        // Answer action
        Intent answerIntent = new Intent(ACTION_ANSWER_CALL);
        answerIntent
                .putExtra(INCOMING_CALL_NOTIFICATION_ID, notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingAnswerIntent = PendingIntent.getBroadcast(context, 0, answerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        notificationBuilder.addAction(R.drawable.ic_call_white_24dp, "ANSWER", pendingAnswerIntent);

        notificationManager.notify(notificationId, notificationBuilder.build());
        RNTwilioVoiceLibraryModule.callNotificationMap.put(INCOMING_NOTIFICATION_PREFIX+callInvite.getCallSid(), notificationId);
    }

    private String getCallerName(ReactApplicationContext context, String caller_id){
        SharedPreferences sharedPref = context.getSharedPreferences("NativeStorage", Context.MODE_PRIVATE);
        String session = sharedPref.getString("SESSION", "none");
        String url = sharedPref.getString("URL", "none");
        String bot = sharedPref.getString("CONTACTS_BOT", "none");

        if(session.equalsIgnoreCase("none")){
            return "";
        }

        try{
            return new RequestTask().execute(caller_id, session, url, bot).get(10000, TimeUnit.MILLISECONDS);
        }catch(InterruptedException e){
            return "";
        }catch(ExecutionException e){
            return "";
        }catch(TimeoutException e){
            return "";
        }
    }

    public void initCallNotificationsChannel(NotificationManager notificationManager) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(VOICE_CHANNEL,
                "Primary Voice Channel", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLightColor(Color.GREEN);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notificationManager.createNotificationChannel(channel);
    }

    public Intent createMissedCallIntent(ReactApplicationContext context, CancelledCallInvite callInvite) {
        SharedPreferences sharedPref = context.getSharedPreferences(PREFERENCE_KEY, Context.MODE_PRIVATE);
        SharedPreferences.Editor sharedPrefEditor = sharedPref.edit();

        /*
         * Create a PendingIntent to specify the action when the notification is
         * selected in the notification drawer
         */

        Intent intent = new Intent(context, getMainActivityClass(context));
        intent.setAction(ACTION_MISSED_CALL)
                .putExtra(CANCELLED_CALL_SSID, callInvite.getCallSid())
                .putExtra(CANCELLED_CALL_INVITE, callInvite)
                .putExtra(INCOMING_CALL_NOTIFICATION_ID, MISSED_CALLS_NOTIFICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent clearMissedCallsCountIntent = new Intent(ACTION_CLEAR_MISSED_CALLS_COUNT)
                .putExtra(INCOMING_CALL_NOTIFICATION_ID, CLEAR_MISSED_CALLS_NOTIFICATION_ID);
        PendingIntent clearMissedCallsCountPendingIntent = PendingIntent.getBroadcast(context, 0, clearMissedCallsCountIntent, 0);
        /*
         * Pass the notification id and call sid to use as an identifier to open the notification
         */
        Bundle extras = new Bundle();
        extras.putInt(INCOMING_CALL_NOTIFICATION_ID, MISSED_CALLS_NOTIFICATION_ID);
        extras.putString(CALL_SID_KEY, callInvite.getCallSid());
        extras.putString(NOTIFICATION_TYPE, ACTION_MISSED_CALL);

        /*
         * Create the notification shown in the notification drawer
         */
        NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, VOICE_CHANNEL)
                        .setGroup(MISSED_CALLS_GROUP)
                        .setGroupSummary(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setSmallIcon(R.drawable.ic_call_missed_white_24dp)
                        .setContentTitle("Missed call")
                        .setContentText(Caller_name_global + " called")
                        .setAutoCancel(true)
                        .setShowWhen(true)
                        .setExtras(extras)
                        .setDeleteIntent(clearMissedCallsCountPendingIntent)
                        .setContentIntent(pendingIntent);

        int missedCalls = sharedPref.getInt(MISSED_CALLS_GROUP, 0);
        missedCalls++;
        if (missedCalls == 1) {
            inboxStyle = new NotificationCompat.InboxStyle();
            inboxStyle.setBigContentTitle("Missed call");
        } else {
            inboxStyle.setBigContentTitle(String.valueOf(missedCalls) + " missed calls");
        }
        inboxStyle.addLine("from: " +callInvite.getFrom());
        sharedPrefEditor.putInt(MISSED_CALLS_GROUP, missedCalls);
        sharedPrefEditor.commit();

        notification.setStyle(inboxStyle);

        // build notification large icon
        Resources res = context.getResources();
        int largeIconResId = res.getIdentifier("ic_launcher", "mipmap", context.getPackageName());
        Bitmap largeIconBitmap = BitmapFactory.decodeResource(res, largeIconResId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && largeIconResId != 0) {
            notification.setLargeIcon(largeIconBitmap);
        }

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(MISSED_CALLS_NOTIFICATION_ID, notification.build());
        return intent;
    }

    public void createMissedCallNotification(ReactApplicationContext context, CancelledCallInvite callInvite) {
        SharedPreferences sharedPref = context.getSharedPreferences(PREFERENCE_KEY, Context.MODE_PRIVATE);
        SharedPreferences.Editor sharedPrefEditor = sharedPref.edit();

        /*
         * Create a PendingIntent to specify the action when the notification is
         * selected in the notification drawer
         */
        Intent intent = new Intent(context, getMainActivityClass(context));
        intent.setAction(ACTION_MISSED_CALL)
                .putExtra(INCOMING_CALL_NOTIFICATION_ID, MISSED_CALLS_NOTIFICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent clearMissedCallsCountIntent = new Intent(ACTION_CLEAR_MISSED_CALLS_COUNT)
                .putExtra(INCOMING_CALL_NOTIFICATION_ID, CLEAR_MISSED_CALLS_NOTIFICATION_ID);
        PendingIntent clearMissedCallsCountPendingIntent = PendingIntent.getBroadcast(context, 0, clearMissedCallsCountIntent, 0);
        /*
         * Pass the notification id and call sid to use as an identifier to open the notification
         */
        Bundle extras = new Bundle();
        extras.putInt(INCOMING_CALL_NOTIFICATION_ID, MISSED_CALLS_NOTIFICATION_ID);
        extras.putString(CALL_SID_KEY, callInvite.getCallSid());
        extras.putString(NOTIFICATION_TYPE, ACTION_MISSED_CALL);

        /*
         * Create the notification shown in the notification drawer
         */
        NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, VOICE_CHANNEL)
                        .setGroup(MISSED_CALLS_GROUP)
                        .setGroupSummary(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setSmallIcon(R.drawable.ic_call_missed_white_24dp)
                        .setContentTitle("Missed call")
                        .setContentText(Caller_name_global + " called")
                        .setAutoCancel(true)
                        .setShowWhen(true)
                        .setExtras(extras)
                        .setDeleteIntent(clearMissedCallsCountPendingIntent)
                        .setContentIntent(pendingIntent);

        int missedCalls = sharedPref.getInt(MISSED_CALLS_GROUP, 0);
        missedCalls++;
        if (missedCalls == 1) {
            inboxStyle = new NotificationCompat.InboxStyle();
            inboxStyle.setBigContentTitle("Missed call");
        } else {
            inboxStyle.setBigContentTitle(String.valueOf(missedCalls) + " missed calls");
        }
        inboxStyle.addLine("from: " +Caller_name_global);
        sharedPrefEditor.putInt(MISSED_CALLS_GROUP, missedCalls);
        sharedPrefEditor.commit();

        notification.setStyle(inboxStyle);

        // build notification large icon
        Resources res = context.getResources();
        int largeIconResId = res.getIdentifier("ic_launcher", "mipmap", context.getPackageName());
        Bitmap largeIconBitmap = BitmapFactory.decodeResource(res, largeIconResId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && largeIconResId != 0) {
            notification.setLargeIcon(largeIconBitmap);
        }

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(MISSED_CALLS_NOTIFICATION_ID, notification.build());
    }

    public void createMissedCallNotification(ReactApplicationContext context, CallInvite callInvite) {
        SharedPreferences sharedPref = context.getSharedPreferences(PREFERENCE_KEY, Context.MODE_PRIVATE);
        SharedPreferences.Editor sharedPrefEditor = sharedPref.edit();

        /*
         * Create a PendingIntent to specify the action when the notification is
         * selected in the notification drawer
         */
        Intent intent = new Intent(context, getMainActivityClass(context));
        intent.setAction(ACTION_MISSED_CALL)
                .putExtra(INCOMING_CALL_NOTIFICATION_ID, MISSED_CALLS_NOTIFICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent clearMissedCallsCountIntent = new Intent(ACTION_CLEAR_MISSED_CALLS_COUNT)
                .putExtra(INCOMING_CALL_NOTIFICATION_ID, CLEAR_MISSED_CALLS_NOTIFICATION_ID);
        PendingIntent clearMissedCallsCountPendingIntent = PendingIntent.getBroadcast(context, 0, clearMissedCallsCountIntent, 0);
        /*
         * Pass the notification id and call sid to use as an identifier to open the notification
         */
        Bundle extras = new Bundle();
        extras.putInt(INCOMING_CALL_NOTIFICATION_ID, MISSED_CALLS_NOTIFICATION_ID);
        extras.putString(CALL_SID_KEY, callInvite.getCallSid());
        extras.putString(NOTIFICATION_TYPE, ACTION_MISSED_CALL);

        /*
         * Create the notification shown in the notification drawer
         */
        NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, VOICE_CHANNEL)
                        .setGroup(MISSED_CALLS_GROUP)
                        .setGroupSummary(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setSmallIcon(R.drawable.ic_call_missed_white_24dp)
                        .setContentTitle("Missed call")
                        .setContentText(callInvite.getFrom() + " called")
                        .setAutoCancel(true)
                        .setShowWhen(true)
                        .setExtras(extras)
                        .setDeleteIntent(clearMissedCallsCountPendingIntent)
                        .setContentIntent(pendingIntent);

        int missedCalls = sharedPref.getInt(MISSED_CALLS_GROUP, 0);
        missedCalls++;
        if (missedCalls == 1) {
            inboxStyle = new NotificationCompat.InboxStyle();
            inboxStyle.setBigContentTitle("Missed call");
        } else {
            inboxStyle.setBigContentTitle(String.valueOf(missedCalls) + " missed calls");
        }
        inboxStyle.addLine("from: " +callInvite.getFrom());
        sharedPrefEditor.putInt(MISSED_CALLS_GROUP, missedCalls);
        sharedPrefEditor.commit();

        notification.setStyle(inboxStyle);

        // build notification large icon
        Resources res = context.getResources();
        int largeIconResId = res.getIdentifier("ic_launcher", "mipmap", context.getPackageName());
        Bitmap largeIconBitmap = BitmapFactory.decodeResource(res, largeIconResId);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && largeIconResId != 0) {
            notification.setLargeIcon(largeIconBitmap);
        }

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(MISSED_CALLS_NOTIFICATION_ID, notification.build());
    }

    public void createHangupLocalNotification(ReactApplicationContext context, String callSid, String caller) {
        PendingIntent pendingHangupIntent = PendingIntent.getBroadcast(
                context,
                0,
                new Intent(ACTION_HANGUP_CALL).putExtra(INCOMING_CALL_NOTIFICATION_ID, HANGUP_NOTIFICATION_ID),
                PendingIntent.FLAG_UPDATE_CURRENT
        );
        Intent launchIntent = new Intent(context, getMainActivityClass(context));
        launchIntent.setAction(ACTION_INCOMING_CALL)
                .putExtra(INCOMING_CALL_NOTIFICATION_ID, HANGUP_NOTIFICATION_ID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent activityPendingIntent = PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        /*
         * Pass the notification id and call sid to use as an identifier to cancel the
         * notification later
         */
        Bundle extras = new Bundle();
        extras.putInt(INCOMING_CALL_NOTIFICATION_ID, HANGUP_NOTIFICATION_ID);
        extras.putString(CALL_SID_KEY, callSid);
        extras.putString(NOTIFICATION_TYPE, ACTION_HANGUP_CALL);

        if(caller.toLowerCase().contains("client")){
            String caller_id = caller.split(":")[1];
            caller = getCallerName(context, caller_id);
            if(caller == "" || caller == "Unknown"){
                return;
            }
        }
        Caller_name_global = caller;

        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, VOICE_CHANNEL)
                .setContentTitle("Call in progress")
                .setContentText(Caller_name_global)
                .setSmallIcon(R.drawable.ic_call_white_24dp)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setExtras(extras)
                .setContentIntent(activityPendingIntent);

        notification.addAction(0, "HANG UP", pendingHangupIntent);
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        // Create notifications channel (required for API > 25)
        initCallNotificationsChannel(notificationManager);
        notificationManager.notify(HANGUP_NOTIFICATION_ID, notification.build());
    }

    public void removeIncomingCallNotification(ReactApplicationContext context,
                                               CallInvite callInvite,
                                               int notificationId) {
        Log.d(TAG, "removeIncomingCallNotification");
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            /*
             * If the incoming call message was cancelled then remove the notification by matching
             * it with the call sid from the list of notifications in the notification drawer.
             */
            StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();
            for (StatusBarNotification statusBarNotification : activeNotifications) {
                Notification notification = statusBarNotification.getNotification();
                String notificationType = notification.extras.getString(NOTIFICATION_TYPE);
                if (callInvite != null && callInvite.getCallSid().equals(notification.extras.getString(CALL_SID_KEY)) &&
                        notificationType != null && notificationType.equals(ACTION_INCOMING_CALL)) {
                    notificationManager.cancel(notification.extras.getInt(INCOMING_CALL_NOTIFICATION_ID));
                }
            }
            if (notificationId != 0) {
                notificationManager.cancel(notificationId);
            }
        } else {
            if (notificationId != 0) {
                notificationManager.cancel(notificationId);
            } else if (callInvite != null) {
                String notificationKey = INCOMING_NOTIFICATION_PREFIX+callInvite.getCallSid();
                if (RNTwilioVoiceLibraryModule.callNotificationMap.containsKey(notificationKey)) {
                    notificationId = RNTwilioVoiceLibraryModule.callNotificationMap.get(notificationKey);
                    notificationManager.cancel(notificationId);
                    RNTwilioVoiceLibraryModule.callNotificationMap.remove(notificationKey);
                }
            }
        }
    }


    public void removeCancelledCallNotification(ReactApplicationContext context,
                                               CancelledCallInvite callInvite,
                                               int notificationId) {
        Log.d(TAG, "removeIncomingCallNotification");
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            /*
             * If the incoming call message was cancelled then remove the notification by matching
             * it with the call sid from the list of notifications in the notification drawer.
             */
            StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();
            for (StatusBarNotification statusBarNotification : activeNotifications) {
                Notification notification = statusBarNotification.getNotification();
                String notificationType = notification.extras.getString(NOTIFICATION_TYPE);
                if (callInvite != null && callInvite.getCallSid().equals(notification.extras.getString(CALL_SID_KEY)) &&
                        notificationType != null && notificationType.equals(ACTION_INCOMING_CALL)) {
                    notificationManager.cancel(notification.extras.getInt(INCOMING_CALL_NOTIFICATION_ID));
                }
            }
            if (notificationId != 0) {
                notificationManager.cancel(notificationId);
            }
        } else {
            if (notificationId != 0) {
                notificationManager.cancel(notificationId);
            } else if (callInvite != null) {
                String notificationKey = INCOMING_NOTIFICATION_PREFIX+callInvite.getCallSid();
                if (RNTwilioVoiceLibraryModule.callNotificationMap.containsKey(notificationKey)) {
                    notificationId = RNTwilioVoiceLibraryModule.callNotificationMap.get(notificationKey);
                    notificationManager.cancel(notificationId);
                    RNTwilioVoiceLibraryModule.callNotificationMap.remove(notificationKey);
                }
            }
        }
    }

    public void removeHangupNotification(ReactApplicationContext context) {
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(HANGUP_NOTIFICATION_ID);
    }
}

