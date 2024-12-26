package com.phlox.shttps_android_exemplum;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.widget.Toast;

import com.phlox.server.SimpleHttpServer;
import com.phlox.simpleserver.SHTTPSApp;

public class ServerService extends Service {
    private static final int ONGOING_NOTIFICATION_ID = 1;

    public static volatile ServerService instance = null;
    public static volatile Runnable onStartStopListener = null;

    private final SHTTPSApp app = SHTTPSApp.getInstance();
    private SimpleHttpServer server;

    public ServerService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;

        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags);

        Notification.Builder builder;
        builder = new Notification.Builder(this, AndroidApp.HTTP_SERVICE_CHANNEL_ID);
        builder.setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        Notification notification = builder.setContentTitle(getText(R.string.app_name))
                .setContentText(getString(R.string.http_service_running))
                .setSmallIcon(R.drawable.ic_baseline_http_24)
                .setContentIntent(pendingIntent)
                .setTicker(getText(R.string.app_name))
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(Notification.PRIORITY_DEFAULT)
                .build();

        startForeground(ONGOING_NOTIFICATION_ID, notification);

        try {
            server = app.startServer();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this.getApplicationContext(), getString(R.string.errorf, e), Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        Runnable ssl = onStartStopListener;
        if (ssl != null) {
            ssl.run();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int notificationID = intent.getIntExtra("notificationId", -1);
            if (notificationID != -1) {
                NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (notificationManager != null) {
                    notificationManager.cancel(notificationID);
                }
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        app.stopServer();
        Runnable ssl = onStartStopListener;
        if (ssl != null) {
            ssl.run();
        }
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancelAll();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Binding is not supported");
    }
}