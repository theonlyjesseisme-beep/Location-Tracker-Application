package tracklocation.devdeeds.com.tracklocationproject;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import tracklocation.devdeeds.com.tracklocationproject.services.LocationMonitoringService;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    // ── Permissions ───────────────────────────────────────────────────────────
    private static final int REQUEST_PERMISSIONS_REQUEST_CODE = 34;

    // ── Notifications ─────────────────────────────────────────────────────────
    private static final String NOTIFICATION_CHANNEL_ID = "firebase_watch_channel";
    private static final String NOTIFICATION_CHANNEL_NAME = "Firebase Updates";
    private static final int NOTIFICATION_ID = 1001;

    // ── Firebase ──────────────────────────────────────────────────────────────
    /**
     * Change this to the Firebase path you want to watch.
     * e.g. "locations/device_001" or "alerts"
     */
    private static final String FIREBASE_WATCH_PATH = "locations";

    private DatabaseReference mFirebaseRef;
    private ValueEventListener mFirebaseListener;

    // ── UI / state ────────────────────────────────────────────────────────────
    private boolean mAlreadyStartedService = false;
    private TextView mMsgView;


    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mMsgView = (TextView) findViewById(R.id.msgView);

        // Create the notification channel once (required on Android 8+)
        createNotificationChannel();

        // Listen for location broadcasts from our background service
        LocalBroadcastManager.getInstance(this).registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String latitude  = intent.getStringExtra(LocationMonitoringService.EXTRA_LATITUDE);
                        String longitude = intent.getStringExtra(LocationMonitoringService.EXTRA_LONGITUDE);

                        if (latitude != null && longitude != null) {
                            mMsgView.setText(
                                getString(R.string.msg_location_service_started)
                                + "\n Latitude : " + latitude
                                + "\n Longitude: " + longitude
                            );
                        }
                    }
                },
                new IntentFilter(LocationMonitoringService.ACTION_LOCATION_BROADCAST)
        );

        // Start watching Firebase
        startFirebaseWatch();
    }

    @Override
    public void onResume() {
        super.onResume();
        startStep1();
    }

    @Override
    public void onDestroy() {
        // Stop the location service
        stopService(new Intent(this, LocationMonitoringService.class));
        mAlreadyStartedService = false;

        // Remove the Firebase listener to avoid memory leaks
        stopFirebaseWatch();

        super.onDestroy();
    }


    // ═════════════════════════════════════════════════════════════════════════
    // Firebase Watch
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Attaches a ValueEventListener to FIREBASE_WATCH_PATH.
     * onDataChange fires immediately with the current value, then again
     * every time the data at that path changes.
     */
    private void startFirebaseWatch() {
        mFirebaseRef = FirebaseDatabase.getInstance().getReference(FIREBASE_WATCH_PATH);

        mFirebaseListener = new ValueEventListener() {

            // Tracks whether this is the very first snapshot (skip notifying on initial load)
            private boolean isFirstLoad = true;

            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (isFirstLoad) {
                    // Silently consume the initial snapshot — we only want
                    // to notify on *subsequent* changes.
                    isFirstLoad = false;
                    Log.d(TAG, "Firebase initial snapshot loaded at: " + FIREBASE_WATCH_PATH);
                    return;
                }

                // Data has changed — build a human-readable summary
                String summary = buildChangeSummary(snapshot);

                Log.d(TAG, "Firebase data changed: " + summary);

                // Update the UI if the activity is still alive
                if (mMsgView != null) {
                    mMsgView.setText("🔔 Firebase updated:\n" + summary);
                }

                // Fire the local notification
                sendFirebaseNotification("Firebase data changed", summary);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Firebase watch cancelled: " + error.getMessage());
                sendFirebaseNotification(
                    "Firebase watch error",
                    error.getMessage()
                );
            }
        };

        mFirebaseRef.addValueEventListener(mFirebaseListener);
        Log.d(TAG, "Firebase watch started on path: " + FIREBASE_WATCH_PATH);
    }

    /**
     * Removes the listener from Firebase. Call this in onDestroy to prevent
     * callbacks arriving after the Activity is gone.
     */
    private void stopFirebaseWatch() {
        if (mFirebaseRef != null && mFirebaseListener != null) {
            mFirebaseRef.removeEventListener(mFirebaseListener);
            Log.d(TAG, "Firebase watch stopped");
        }
    }

    /**
     * Converts a DataSnapshot into a short, readable string for the notification body.
     * Customize this to match your actual data structure.
     */
    private String buildChangeSummary(DataSnapshot snapshot) {
        if (!snapshot.exists()) {
            return "Data was removed at: " + snapshot.getRef().getKey();
        }

        // If the snapshot has children (it's a map/list), list the top-level keys
        if (snapshot.hasChildren()) {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (DataSnapshot child : snapshot.getChildren()) {
                if (count >= 3) { // Cap preview to 3 entries to keep the notification concise
                    sb.append("… and ").append((int) snapshot.getChildrenCount() - 3).append(" more");
                    break;
                }
                sb.append(child.getKey()).append(": ").append(child.getValue()).append("\n");
                count++;
            }
            return sb.toString().trim();
        }

        // Simple scalar value
        return snapshot.getRef().getKey() + " = " + snapshot.getValue();
    }


    // ═════════════════════════════════════════════════════════════════════════
    // Notifications
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Creates the NotificationChannel required by Android 8.0+.
     * Safe to call multiple times — the system ignores duplicate channel creation.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Alerts when Firebase data changes");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Builds and displays a local notification.
     *
     * @param title The notification title.
     * @param body  The notification body / detail text.
     */
    private void sendFirebaseNotification(String title, String body) {
        // Tapping the notification re-opens this Activity
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with your own icon
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body)) // Expand for long text
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true); // Dismiss notification when tapped

        NotificationManager manager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (manager != null) {
            manager.notify(NOTIFICATION_ID, builder.build());
        }
    }


    // ═════════════════════════════════════════════════════════════════════════
    // Location service startup steps (unchanged from original)
    // ═════════════════════════════════════════════════════════════════════════

    /** Step 1: Check Google Play services */
    private void startStep1() {
        if (isGooglePlayServicesAvailable()) {
            startStep2(null);
        } else {
            Toast.makeText(getApplicationContext(),
                R.string.no_google_playservice_available, Toast.LENGTH_LONG).show();
        }
    }

    /** Step 2: Check & prompt for internet connection */
    private Boolean startStep2(DialogInterface dialog) {
        ConnectivityManager connectivityManager =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();

        if (activeNetworkInfo == null || !activeNetworkInfo.isConnected()) {
            promptInternetConnect();
            return false;
        }

        if (dialog != null) {
            dialog.dismiss();
        }

        if (checkPermissions()) {
            startStep3();
        } else {
            requestPermissions();
        }
        return true;
    }

    /** Show a dialog prompting the user to reconnect to the internet */
    private void promptInternetConnect() {
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle(R.string.title_alert_no_intenet);
        builder.setMessage(R.string.msg_alert_no_internet);

        builder.setPositiveButton(getString(R.string.btn_label_refresh),
            new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (startStep2(dialog)) {
                        if (checkPermissions()) {
                            startStep3();
                        } else if (!checkPermissions()) {
                            requestPermissions();
                        }
                    }
                }
            });

        builder.create().show();
    }

    /** Step 3: Start the background location service */
    private void startStep3() {
        if (!mAlreadyStartedService && mMsgView != null) {
            mMsgView.setText(R.string.msg_location_service_started);
            Intent intent = new Intent(this, LocationMonitoringService.class);
            startService(intent);
            mAlreadyStartedService = true;
        }
    }


    // ═════════════════════════════════════════════════════════════════════════
    // Permissions (unchanged from original)
    // ═════════════════════════════════════════════════════════════════════════

    public boolean isGooglePlayServicesAvailable() {
        GoogleApiAvailability googleApiAvailability = GoogleApiAvailability.getInstance();
        int status = googleApiAvailability.isGooglePlayServicesAvailable(this);
        if (status != ConnectionResult.SUCCESS) {
            if (googleApiAvailability.isUserResolvableError(status)) {
                googleApiAvailability.getErrorDialog(this, status, 2404).show();
            }
            return false;
        }
        return true;
    }

    private boolean checkPermissions() {
        int permissionState1 = ActivityCompat.checkSelfPermission(this,
            android.Manifest.permission.ACCESS_FINE_LOCATION);
        int permissionState2 = ActivityCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_COARSE_LOCATION);
        return permissionState1 == PackageManager.PERMISSION_GRANTED
            && permissionState2 == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        boolean shouldProvideRationale =
            ActivityCompat.shouldShowRequestPermissionRationale(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION);
        boolean shouldProvideRationale2 =
            ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.ACCESS_COARSE_LOCATION);

        if (shouldProvideRationale || shouldProvideRationale2) {
            Log.i(TAG, "Displaying permission rationale to provide additional context.");
            showSnackbar(R.string.permission_rationale,
                android.R.string.ok, new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            },
                            REQUEST_PERMISSIONS_REQUEST_CODE);
                    }
                });
        } else {
            Log.i(TAG, "Requesting permission");
            ActivityCompat.requestPermissions(MainActivity.this,
                new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                },
                REQUEST_PERMISSIONS_REQUEST_CODE);
        }
    }

    private void showSnackbar(final int mainTextStringId, final int actionStringId,
                              View.OnClickListener listener) {
        Snackbar.make(
            findViewById(android.R.id.content),
            getString(mainTextStringId),
            Snackbar.LENGTH_INDEFINITE)
            .setAction(getString(actionStringId), listener)
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        Log.i(TAG, "onRequestPermissionResult");
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE) {
            if (grantResults.length <= 0) {
                Log.i(TAG, "User interaction was cancelled.");
            } else if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Permission granted, starting location updates");
                startStep3();
            } else {
                showSnackbar(R.string.permission_denied_explanation,
                    R.string.settings, new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            Intent intent = new Intent();
                            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            Uri uri = Uri.fromParts("package", BuildConfig.APPLICATION_ID, null);
                            intent.setData(uri);
                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        }
                    });
            }
        }
    }
}
