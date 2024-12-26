package com.phlox.shttps_android_exemplum;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.simpleserver.SHTTPSApp;
import com.phlox.simpleserver.SHTTPSConfigAndroid;
import com.phlox.simpleserver.database.SHTTPSDatabaseFabric;
import com.phlox.simpleserver.database.DatabaseFabricAndroid;
import com.phlox.simpleserver.utils.AndroidLogger;
import com.phlox.simpleserver.utils.PlatformUtilsAndroid;
import com.phlox.simpleserver.utils.SHTTPSPlatformUtils;

public class AndroidApp extends Application {
    public static final String HTTP_SERVICE_CHANNEL_ID = "http_service";
    public static AndroidApp instance;

    private SHTTPSApp app;

    private SHTTPSConfigAndroid config;

    static {
        SHTTPSLoggerProxy.setFactory(tag -> new AndroidLogger(tag, SHTTPSLoggerProxy.Logger.ALL));
    }

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;

        SHTTPSPlatformUtils platformUtils = new PlatformUtilsAndroid(this);

        config = new SHTTPSConfigAndroid(this, "main", platformUtils);
        if (getConfig().getRootDir() == null) {
            getConfig().setRootDir(getFilesDir().getAbsolutePath());
        }

        NotificationChannel generalChannel = new NotificationChannel(
                HTTP_SERVICE_CHANNEL_ID,
                getString(R.string.notifications_channel_general_description),
                NotificationManager.IMPORTANCE_LOW);

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(generalChannel);

        SHTTPSDatabaseFabric databaseFabric = new DatabaseFabricAndroid(this, platformUtils);

        app = SHTTPSApp.init(getConfig(), platformUtils, databaseFabric);
    }

    public SHTTPSConfigAndroid getConfig() {
        return config;
    }

    public SHTTPSApp getSHTTPSApp() {
        return app;
    }
}
