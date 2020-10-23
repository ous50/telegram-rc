package com.qwe7002.telegram_rc;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.gson.Gson;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.Region;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;

import io.paperdb.Paper;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class beacon_receiver_service extends Service {
    private final static String TAG = "beacon_receviver";
    private Context context;
    private WifiManager wifi_manager;
    private int not_found_count = 0;
    private int detect_singal_count = 0;
    private OkHttpClient okhttp_client;
    private String chat_id;
    private String request_url;
    private BeaconManager beacon_manager;
    private long startup_time = 0;
    private long last_receive_time = 0;
    private beacon_config config;
    private beacon_service_consumer beacon_consumer;

    @NotNull
    private battery_info get_battery_info() {
        battery_info info = new battery_info();
        BatteryManager batteryManager = (BatteryManager) context.getSystemService(BATTERY_SERVICE);
        assert batteryManager != null;
        info.battery_level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        IntentFilter intentfilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, intentfilter);
        assert batteryStatus != null;
        int charge_status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

        switch (charge_status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
            case BatteryManager.BATTERY_STATUS_FULL:
                info.is_charging = true;
                break;
        }
        return info;
    }

    static class battery_info {
        int battery_level = 0;
        boolean is_charging = false;
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final BroadcastReceiver reload_config_receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            config = Paper.book().read("beacon_config", new beacon_config());
            beacon_manager.setBackgroundScanPeriod(config.delay);
            beacon_manager.setForegroundScanPeriod(config.delay);
            beacon_manager.setForegroundBetweenScanPeriod(config.delay);
            beacon_manager.setForegroundBetweenScanPeriod(config.delay);
            try {
                beacon_manager.updateScanPeriods();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            startup_time = System.currentTimeMillis();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        Paper.init(context);
        config = Paper.book().read("beacon_config", new beacon_config());
        LocalBroadcastManager.getInstance(this).registerReceiver(reload_config_receiver,
                new IntentFilter("reload_beacon_config"));
        SharedPreferences sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);
        request_url = public_func.get_url(sharedPreferences.getString("bot_token", ""), "SendMessage");
        chat_id = sharedPreferences.getString("chat_id", "");
        wifi_manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        okhttp_client = public_func.get_okhttp_obj(sharedPreferences.getBoolean("doh_switch", true), Paper.book("system_config").read("proxy_config", new proxy_config()));
        beacon_consumer = new beacon_service_consumer();
        beacon_manager = BeaconManager.getInstanceForApplication(this);
        BeaconManager.setAndroidLScanningDisabled(false);
        // Detect iBeacon:
        beacon_manager.getBeaconParsers().add(new BeaconParser().
                setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"));

        // Detect the main identifier (UID) frame:
        beacon_manager.getBeaconParsers().add(new BeaconParser().
                setBeaconLayout(BeaconParser.EDDYSTONE_UID_LAYOUT));

        // Detect the telemetry (TLM) frame:
        beacon_manager.getBeaconParsers().add(new BeaconParser().
                setBeaconLayout(BeaconParser.EDDYSTONE_TLM_LAYOUT));

        // Detect the URL frame:
        beacon_manager.getBeaconParsers().add(new BeaconParser().
                setBeaconLayout(BeaconParser.EDDYSTONE_URL_LAYOUT));
        Notification notification = public_func.get_notification_obj(context, getString(R.string.beacon_receiver));
        beacon_manager.enableForegroundServiceScanning(notification, public_func.BEACON_SERVICE_NOTIFY_ID);
        startForeground(public_func.BEACON_SERVICE_NOTIFY_ID, notification);
        beacon_manager.setForegroundScanPeriod(config.delay);
        beacon_manager.setForegroundBetweenScanPeriod(config.delay);
        startup_time = System.currentTimeMillis();
        beacon_manager.setEnableScheduledScanJobs(false);
        beacon_manager.bind(beacon_consumer);
    }

    @Override
    public void onDestroy() {
        beacon_manager.unbind(beacon_consumer);

        stopForeground(true);
        super.onDestroy();
    }

    class beacon_service_consumer implements BeaconConsumer {
        @Override
        public void onBeaconServiceConnect() {
            beacon_manager.addRangeNotifier((beacons, region) -> {
                SharedPreferences sharedPreferences = context.getSharedPreferences("data", MODE_PRIVATE);
                boolean wifi_is_enable_status;
                if (!config.use_vpn_hotspot) {
                    if (!Settings.System.canWrite(context)) {
                        Log.d(TAG, "onBeaconServiceConnect: No permission");
                        return;
                    }
                    wifi_is_enable_status = remote_control_public.is_tether_active(context);
                } else {
                    if (!sharedPreferences.getBoolean("root", false)) {
                        Log.d(TAG, "onBeaconServiceConnect: No permission");
                        return;
                    }
                    wifi_is_enable_status = Paper.book().read("wifi_open", false);
                }
                beacon_list.beacons = beacons;
                LocalBroadcastManager.getInstance(context).sendBroadcast(new Intent("flush_view"));
                if ((System.currentTimeMillis() - startup_time) < 10000L) {
                    Log.d(TAG, "onBeaconServiceConnect: Startup time is too short");
                    last_receive_time = System.currentTimeMillis();
                    return;
                }
                long current_time = System.currentTimeMillis();
                if ((current_time - last_receive_time) < config.delay) {
                    Log.d(TAG, "onBeaconServiceConnect: Last time is too short");
                    return;
                }
                last_receive_time = current_time;
                if (Paper.book().read("disable_beacon", false)) {
                    not_found_count = 0;
                    detect_singal_count = 0;
                    return;
                }
                battery_info info = get_battery_info();
                if (!info.is_charging && !wifi_is_enable_status && !config.enable) {
                    not_found_count = 0;
                    detect_singal_count = 0;
                    Log.d(TAG, "onBeaconServiceConnect: Turn off beacon automatic activation");
                    return;
                }
                ArrayList<String> listen_beacon_list = Paper.book().read("beacon_address", new ArrayList<>());
                if (listen_beacon_list.size() == 0) {
                    not_found_count = 0;
                    detect_singal_count = 0;
                    Log.d(TAG, "onBeaconServiceConnect: Watchlist is empty");
                    return;
                }
                boolean found_beacon = false;
                Beacon detect_beacon = null;
                for (Beacon beacon : beacons) {
                    Log.d(TAG, "Mac address: " + beacon.getBluetoothAddress() + " Rssi: " + beacon.getRssi() + " Power: " + beacon.getTxPower() + " Distance: " + beacon.getDistance());
                    for (String beacon_address : listen_beacon_list) {
                        if (beacon.getBluetoothAddress().equals(beacon_address)) {
                            found_beacon = true;
                            break;
                        }
                    }
                    if (found_beacon) {
                        detect_beacon = beacon;
                        break;
                    }
                }

                String beacon_status;
                if (found_beacon) {
                    beacon_status = "\nBeacon Rssi: " + detect_beacon.getRssi() + "dBm";
                    not_found_count = 0;
                    ++detect_singal_count;
                } else {
                    beacon_status = "\nBeacon Not Found.";
                    detect_singal_count = 0;
                    ++not_found_count;
                }
                final int STATUS_STANDBY = -1;
                final int STATUS_ENABLE_AP = 0;
                final int STATUS_DISABLE_AP = 1;
                int switch_status = STATUS_STANDBY;
                Log.d(TAG, "detect_singal_count: " + detect_singal_count);
                Log.d(TAG, "not_found_count: " + not_found_count);
                if (wifi_is_enable_status && found_beacon) {
                    if (!config.enable) {
                        if (detect_singal_count >= config.disable_count) {
                            detect_singal_count = 0;
                            not_found_count = 0;
                            if (config.use_vpn_hotspot) {
                                remote_control_public.disable_vpn_ap(wifi_manager);
                            } else {
                                remote_control_public.disable_tether(context);
                            }
                            switch_status = STATUS_DISABLE_AP;
                        }
                    } else {
                        if (detect_singal_count >= config.enable_count) {
                            detect_singal_count = 0;
                            not_found_count = 0;
                            if (config.use_vpn_hotspot) {
                                remote_control_public.enable_vpn_ap(wifi_manager);
                            } else {
                                remote_control_public.enable_tether(context);
                            }
                            switch_status = STATUS_ENABLE_AP;
                        }
                    }
                }
                if (!wifi_is_enable_status && !found_beacon) {
                    if (!config.enable) {
                        if (not_found_count >= config.enable_count) {
                            detect_singal_count = 0;
                            not_found_count = 0;
                            if (config.use_vpn_hotspot) {
                                remote_control_public.enable_vpn_ap(wifi_manager);
                            } else {
                                remote_control_public.enable_tether(context);
                            }
                            switch_status = STATUS_ENABLE_AP;
                        }
                    } else {
                        if (not_found_count >= config.disable_count) {
                            detect_singal_count = 0;
                            not_found_count = 0;
                            if (config.use_vpn_hotspot) {
                                remote_control_public.disable_vpn_ap(wifi_manager);
                            } else {
                                remote_control_public.disable_tether(context);
                            }
                            switch_status = STATUS_DISABLE_AP;
                        }
                    }
                }
                String message = null;
                switch (switch_status) {
                    case STATUS_ENABLE_AP:
                        message = getString(R.string.system_message_head) + "\n" + getString(R.string.enable_wifi) + getString(R.string.action_success) + beacon_status;
                        break;
                    case STATUS_DISABLE_AP:
                        message = getString(R.string.system_message_head) + "\n" + getString(R.string.disable_wifi) + getString(R.string.action_success) + beacon_status;
                        break;
                }
                if (message != null) {
                    network_progress_handle(message, chat_id, okhttp_client);
                }
            });


            try {
                beacon_manager.startRangingBeaconsInRegion(new Region("com.qwe7002.telegram_rc", null, null, null));
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }


        @Override
        public Context getApplicationContext() {
            return beacon_receiver_service.this.getApplicationContext();
        }

        @Override
        public void unbindService(ServiceConnection serviceConnection) {
            beacon_receiver_service.this.unbindService(serviceConnection);
        }

        @Override
        public boolean bindService(Intent intent, ServiceConnection serviceConnection, int i) {
            return beacon_receiver_service.this.bindService(intent, serviceConnection, i);
        }

    }
    private void network_progress_handle(String message, String chat_id, @NotNull OkHttpClient okhttp_client) {
        message_json request_body = new message_json();
        request_body.chat_id = chat_id;
        request_body.text = message + "\n" + context.getString(R.string.current_battery_level) + chat_command_service.get_battery_info(context) + "\n" + getString(R.string.current_network_connection_status) + chat_command_service.get_network_type(context, true);
        String request_body_json = new Gson().toJson(request_body);
        RequestBody body = RequestBody.create(request_body_json, public_func.JSON);
        Request request_obj = new Request.Builder().url(request_url).method("POST", body).build();
        Call call = okhttp_client.newCall(request_obj);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                Log.d(TAG, "onResponse: " + Objects.requireNonNull(response.body()).string());
            }
        });
    }
}
