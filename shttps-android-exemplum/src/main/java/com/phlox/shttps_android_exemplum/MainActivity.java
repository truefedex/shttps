package com.phlox.shttps_android_exemplum;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.phlox.server.utils.docfile.DocumentFile;
import com.phlox.simpleserver.SHTTPSConfig;

public class MainActivity extends Activity {
    public static final int PERMISSIONS_REQUEST_POST_NOTIFICATIONS = 1;
    public static final int FILE_MANAGER_PERMISSION_ACTIVITY_REQUEST_CODE = 2;

    private Button btnAccessExternalStorage;
    private Button btnStartStop;
    private Button btnOpenInBrowser;
    private TextView tvStatusInfo;
    private TextView tvRootFolderInfo;

    private final SHTTPSConfig config = AndroidApp.instance.getConfig();
    private final Runnable serverStartStopListener = this::updateServerStatusUI;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatusInfo = findViewById(R.id.tvStatusInfo);
        tvRootFolderInfo = findViewById(R.id.tvRootFolderInfo);

        btnAccessExternalStorage = findViewById(R.id.btnAccessExternalStorage);
        btnAccessExternalStorage.setOnClickListener(this::accessExternalStorageClicked);

        btnStartStop = findViewById(R.id.btnStartStop);
        btnStartStop.setOnClickListener(this::toggleServerClicked);

        btnOpenInBrowser = findViewById(R.id.btnOpenInBrowser);
        btnOpenInBrowser.setOnClickListener(this::openInBrowserClicked);

        // There is how to configure the simple http server
        config.setPort(8080);
        config.setAllowEditing(true);
        if (Environment.isExternalStorageManager()) {
            config.setRootDir(Environment.getExternalStorageDirectory().getAbsolutePath());
        }
        AndroidApp.instance.getSHTTPSApp().notifyConfigChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ServerService.onStartStopListener = serverStartStopListener;
        updateServerStatusUI();
    }

    @Override
    protected void onPause() {
        ServerService.onStartStopListener = null;
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_POST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleServer(btnStartStop);
            } else {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_SHORT).show();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_MANAGER_PERMISSION_ACTIVITY_REQUEST_CODE) {
            if (Environment.isExternalStorageManager()) {
                config.setRootDir(Environment.getExternalStorageDirectory().getAbsolutePath());
            }
            updateServerStatusUI();
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void accessExternalStorageClicked(View button) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.permission_required)
                .setMessage(R.string.external_storage_permission_message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    showFileManagerPermissionsSystemSettings();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggleServerClicked(View button) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                toggleServer(button);
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.permission_required)
                        .setMessage(R.string.notifications_permission_message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSIONS_REQUEST_POST_NOTIFICATIONS);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } else {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSIONS_REQUEST_POST_NOTIFICATIONS);
            }
        } else {
            toggleServer(button);
        }
    }

    private void openInBrowserClicked(View button) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("http://localhost:" + config.getPort()));
        startActivity(intent);
    }

    private void toggleServer(View button) {
        button.setEnabled(false);
        ServerService srv = ServerService.instance;
        if (srv != null) {
            srv.stopSelf();
        } else {
            Intent intent = new Intent(MainActivity.this, ServerService.class);
            startForegroundService(intent);
        }
    }

    private void updateServerStatusUI() {
        ServerService srv = ServerService.instance;
        tvStatusInfo.setText(srv != null ? R.string.running : R.string.not_running);
        tvStatusInfo.setTextColor(getResources().getColor(srv != null ?
                R.color.color_success : R.color.color_default_text_inactive,
                getTheme()));

        DocumentFile rootDir = config.getRootDir();
        tvRootFolderInfo.setText(rootDir == null || rootDir.getUri().equals("file://" + getFilesDir().getAbsolutePath()) ?
                R.string.app_internal_files : R.string.external_storage);

        btnStartStop.setText(srv != null ? R.string.stop : R.string.start);
        btnStartStop.setEnabled(true);
        btnStartStop.requestFocus();

        btnAccessExternalStorage.setVisibility(Environment.isExternalStorageManager() ?
                View.GONE : View.VISIBLE);
        btnOpenInBrowser.setVisibility(srv != null ? View.VISIBLE : View.GONE);
    }

    private void showFileManagerPermissionsSystemSettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, FILE_MANAGER_PERMISSION_ACTIVITY_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.err_not_supported_on_this_system, Toast.LENGTH_SHORT).show();
        }
    }
}