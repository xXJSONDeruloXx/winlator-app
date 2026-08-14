package com.winlator;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.widget.XServerView;
import com.winlator.xserver.XServerCore;

/** SteamDroid product entry point; the legacy Winlator/Wine UI is not exposed. */
public class SteamDroidActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private LinearLayout controls;
    private FrameLayout content;
    private XServerView xServerView;
    private SteamSessionService sessionService;
    private boolean serviceBound;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            sessionService = ((SteamSessionService.LocalBinder)binder).getService();
            serviceBound = true;
            updateStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            sessionService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("SteamDroid");

        content = new FrameLayout(this);

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(32, 32, 32, 32);

        status = new TextView(this);
        status.setText("SteamDroid substrate is not started.");
        controls.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button start = new Button(this);
        start.setText("Start native session");
        start.setOnClickListener(view -> {
            startService(new Intent(this, SteamSessionService.class).setAction(SteamSessionService.ACTION_START));
            status.setText("Starting native session…");
        });
        controls.addView(start, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button installHolo = new Button(this);
        installHolo.setText("Install Holo ARM64 runtime");
        installHolo.setOnClickListener(view -> {
            startService(new Intent(this, SteamSessionService.class).setAction(SteamSessionService.ACTION_INSTALL_HOLO));
            status.setText("Provisioning Holo ARM64 runtime…");
        });
        controls.addView(installHolo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button installPackages = new Button(this);
        installPackages.setText("Install Holo X11/GPU packages");
        installPackages.setOnClickListener(view -> {
            startService(new Intent(this, SteamSessionService.class).setAction(SteamSessionService.ACTION_INSTALL_HOLO_PACKAGES));
            status.setText("Installing Holo X11/GPU packages…");
        });
        controls.addView(installPackages, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button installSteam = new Button(this);
        installSteam.setText("Install native ARM64 Steam client");
        installSteam.setOnClickListener(view -> {
            startService(new Intent(this, SteamSessionService.class).setAction(SteamSessionService.ACTION_INSTALL_STEAM));
            status.setText("Installing native ARM64 Steam client…");
        });
        controls.addView(installSteam, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button launchSteam = new Button(this);
        launchSteam.setText("Launch Steam Big Picture");
        launchSteam.setOnClickListener(view -> {
            // Attach before asking the service to exec Steam so the renderer
            // receives the first MapWindow events.
            attachDisplay();
            controls.setVisibility(ViewGroup.GONE);
            startService(new Intent(this, SteamSessionService.class).setAction(SteamSessionService.ACTION_LAUNCH_STEAM));
            status.setText("Launching Steam Big Picture…");
        });
        controls.addView(launchSteam, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button stop = new Button(this);
        stop.setText("Stop native session");
        stop.setOnClickListener(view -> {
            startService(new Intent(this, SteamSessionService.class).setAction(SteamSessionService.ACTION_STOP));
            status.setText("Stopping native session…");
        });
        controls.addView(stop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams controlsLayout = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsLayout.gravity = Gravity.TOP;
        content.addView(controls, controlsLayout);
        setContentView(content);
        startService(new Intent(this, SteamSessionService.class).setAction(SteamSessionService.ACTION_START));
        bindService(new Intent(this, SteamSessionService.class), serviceConnection, Context.BIND_AUTO_CREATE);
        handler.post(statusUpdater);
    }

    private final Runnable statusUpdater = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 500L);
        }
    };

    private void attachDisplay() {
        if (sessionService == null || xServerView != null) return;
        XServerCore xServer = sessionService.getXServerCore();
        xServerView = new XServerView(this, xServer);
        // Keep this SurfaceView behind the Android control layer until the
        // native Steam launch is accepted. The controls are hidden from the
        // status updater at that point, allowing the X11 surface to occupy
        // the full Activity without losing the launch interaction when the
        // SurfaceView is first created.
        xServer.setRenderer(xServerView.getRenderer());
        content.addView(xServerView, 0, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void updateStatus() {
        if (status == null || sessionService == null) return;
        String serviceStatus = sessionService.getLastStatus();
        status.setText(serviceStatus);
        if (serviceStatus.startsWith("native Steam process started:")) {
            if (xServerView == null) attachDisplay();
            controls.setVisibility(ViewGroup.GONE);
        }
        else if (serviceStatus.startsWith("session destroyed") ||
            serviceStatus.startsWith("error:") || serviceStatus.startsWith("native Steam launch error:")) {
            detachDisplay();
            controls.setVisibility(ViewGroup.VISIBLE);
        }
    }

    private void detachDisplay() {
        if (xServerView == null || sessionService == null) return;
        sessionService.getXServerCore().detachRenderer(xServerView.getRenderer());
        content.removeView(xServerView);
        xServerView = null;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(statusUpdater);
        detachDisplay();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }
}
