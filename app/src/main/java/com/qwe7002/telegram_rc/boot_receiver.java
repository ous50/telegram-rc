package com.qwe7002.telegram_rc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.qwe7002.telegram_rc.static_class.log_func;
import com.qwe7002.telegram_rc.static_class.remote_control_func;
import com.qwe7002.telegram_rc.static_class.resend_func;
import com.qwe7002.telegram_rc.static_class.service_func;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

import io.paperdb.Paper;

public class boot_receiver extends BroadcastReceiver {

    @Override
    public void onReceive(@NotNull final Context context, @NotNull Intent intent) {
        final String TAG = "boot_receiver";
        Log.d(TAG, "Receive action: " + intent.getAction());
        Paper.init(context);
        final SharedPreferences sharedPreferences = context.getSharedPreferences("data", Context.MODE_PRIVATE);
        if (sharedPreferences.getBoolean("initialized", false)) {
            if (remote_control_func.is_termux_exist(context)) {
                com.qwe7002.telegram_rc.root_kit.startup.start_termux_script("init.rc");
            }
            log_func.write_log(context, "Received [" + intent.getAction() + "] broadcast, starting background service.");
            service_func.start_service(context, sharedPreferences.getBoolean("battery_monitoring_switch", false), sharedPreferences.getBoolean("chat_command", false), sharedPreferences.getBoolean("wifi_monitor_switch", false));
            if (Paper.book().read("resend_list", new ArrayList<>()).size() != 0) {
                Log.d(TAG, "An unsent message was detected, and the automatic resend process was initiated.");
                resend_func.start_resend_service(context);
            }
            if (sharedPreferences.getBoolean("root", false)) {
                if (Paper.book("system_config").contains("dummy_ip_addr")) {
                    String dummy_ip_addr = Paper.book("system_config").read("dummy_ip_addr");
                    com.qwe7002.telegram_rc.root_kit.network.add_dummy_device(dummy_ip_addr);
                }
                if (Paper.book("system_config").contains("adb_port")) {
                    int adb_port = Paper.book("system_config").read("adb_port");
                    com.qwe7002.telegram_rc.root_kit.nadb.set_nadb(adb_port);
                }
            }
        }
    }
}

