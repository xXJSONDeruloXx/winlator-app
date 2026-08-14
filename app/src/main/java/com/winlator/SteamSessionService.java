package com.winlator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.winlator.runtime.SteamControlClient;
import com.winlator.runtime.SteamControlProtocol;
import com.winlator.runtime.SteamHoloProvisioner;
import com.winlator.runtime.SteamHoloPackageProvisioner;
import com.winlator.runtime.SteamArm64Channel;
import com.winlator.runtime.SteamClientProvisioner;
import com.winlator.runtime.SteamKgslProviderProvisioner;
import com.winlator.runtime.SteamIdentity;
import com.winlator.runtime.SteamNativeExecRequest;
import com.winlator.runtime.SteamNativeSubstrate;
import com.winlator.runtime.SteamSupervisor;
import com.winlator.xserver.XServerCore;
import com.winlator.xserver.ScreenInfo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** Owns the long-lived SteamDroid session independently of an Activity. */
public class SteamSessionService extends Service {
    private static final String TAG = "SteamSessionService";
    public static final String ACTION_START = "com.xjsonderulo.steamdroid.action.START";
    public static final String ACTION_STOP = "com.xjsonderulo.steamdroid.action.STOP";
    public static final String ACTION_INSTALL_HOLO = "com.xjsonderulo.steamdroid.action.INSTALL_HOLO";
    public static final String ACTION_INSTALL_HOLO_PACKAGES = "com.xjsonderulo.steamdroid.action.INSTALL_HOLO_PACKAGES";
    public static final String ACTION_INSTALL_STEAM = "com.xjsonderulo.steamdroid.action.INSTALL_STEAM";
    public static final String ACTION_LAUNCH_STEAM = "com.xjsonderulo.steamdroid.action.LAUNCH_STEAM";
    public static final String ACTION_RUNTIME_BWRAP_SELF_TEST =
        "com.xjsonderulo.steamdroid.action.RUNTIME_BWRAP_SELF_TEST";
    private static final String NOTIFICATION_CHANNEL = "steamdroid-session";
    private static final int NOTIFICATION_ID = 1101;

    private final IBinder binder = new LocalBinder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Process supervisorProcess;
    private SteamControlClient controlClient;
    private File controlSocket;
    private File guestProxySocket;
    private XServerCore xServerCore;
    private SteamNativeSubstrate nativeSubstrate;
    private volatile String lastStatus = "idle";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification("SteamDroid session service"));
        File sessionDirectory = new File(getFilesDir(), "session");
        sessionDirectory.mkdirs();
        controlSocket = new File(sessionDirectory, "control.sock");
        // The guest endpoint is created in the app-private session directory
        // before namespace creation and bind-mounted into Holo as
        // /tmp/steamdroid-runtime-bwrap.sock. The Android control socket
        // remains outside that mount, so the two capabilities cannot be
        // addressed interchangeably.
        guestProxySocket = new File(sessionDirectory, "guest-proxy.sock");
        nativeSubstrate = new SteamNativeSubstrate(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_STOP.equals(action)) {
            executor.execute(this::destroySession);
        }
        else if (ACTION_INSTALL_HOLO.equals(action)) {
            executor.execute(this::installHolo);
        }
        else if (ACTION_INSTALL_HOLO_PACKAGES.equals(action)) {
            executor.execute(this::installHoloPackages);
        }
        else if (ACTION_INSTALL_STEAM.equals(action)) {
            executor.execute(this::installSteam);
        }
        else if (ACTION_LAUNCH_STEAM.equals(action)) {
            executor.execute(this::launchSteam);
        }
        else if (ACTION_RUNTIME_BWRAP_SELF_TEST.equals(action)) {
            executor.execute(this::runtimeBwrapSelfTest);
        }
        else {
            executor.execute(this::prepareSession);
        }
        // A user-requested STOP must remain stopped. The Activity starts the
        // service explicitly when a session is desired; sticky restart would
        // race teardown and recreate a root helper behind the user's back.
        return START_NOT_STICKY;
    }

    private void installHolo() {
        if (supervisorProcess != null && supervisorProcess.isAlive()) {
            lastStatus = "stop the native session before installing Holo";
            return;
        }
        try {
            lastStatus = "provisioning Holo ARM64 rootfs…";
            SteamHoloProvisioner provisioner = new SteamHoloProvisioner(this);
            provisioner.downloadAndInstall(SteamIdentity.capture());
            lastStatus = "Holo ARM64 rootfs ready: " + provisioner.getRootDir();
            Log.i(TAG, lastStatus);
            installHoloPackages();
        }
        catch (Exception e) {
            lastStatus = "Holo provisioning error: " + e.getMessage();
            Log.e(TAG, lastStatus, e);
        }
    }

    private void installHoloPackages() {
        try {
            lastStatus = "downloading Holo X11/GPU ARM64 packages…";
            SteamHoloPackageProvisioner provisioner = new SteamHoloPackageProvisioner(this);
            provisioner.downloadAll();
            new SteamKgslProviderProvisioner(this).ensureInstalled();
            ensureSessionPrepared();
            SteamControlClient.Response response = controlClient.request(
                SteamControlProtocol.INSTALL_HOLO_PACKAGES);
            if (!response.isSuccess()) throw new IOException("Holo package install status=" + response.status);
            lastStatus = "Holo X11/GPU packages ready: " + response.payloadAsString().trim();
            Log.i(TAG, lastStatus);
        }
        catch (Exception e) {
            lastStatus = "Holo package provisioning error: " + e.getMessage();
            Log.e(TAG, lastStatus, e);
        }
    }

    private void installSteam() {
        if (supervisorProcess != null && supervisorProcess.isAlive()) {
            lastStatus = "stop the native session before installing Steam";
            return;
        }
        try {
            lastStatus = "downloading native ARM64 Steam client…";
            SteamClientProvisioner provisioner = new SteamClientProvisioner(this);
            provisioner.downloadAndInstall(SteamIdentity.capture());
            lastStatus = "native ARM64 Steam client ready: " + provisioner.getExecutable();
            Log.i(TAG, lastStatus);
        }
        catch (Exception e) {
            lastStatus = "Steam client provisioning error: " + e.getMessage();
            Log.e(TAG, lastStatus, e);
        }
    }

    private void launchSteam() {
        try {
            SteamClientProvisioner provisioner = new SteamClientProvisioner(this);
            if (!provisioner.isInstalled()) throw new IOException("native ARM64 Steam client is not installed");
            provisioner.ensureCompatibilityAssets();
            new SteamKgslProviderProvisioner(this).ensureInstalled();
            ensureSessionPrepared();
            SteamArm64Channel channel = SteamArm64Channel.load(this);
            String steamExecutable = "/home/steam/.local/share/Steam/" + channel.steamClientExecutable;
            List<String> steamArguments = new ArrayList<>(Arrays.asList(
                "/usr/bin/dbus-run-session", "--", steamExecutable));
            // Use the native ARM client's GamepadUI/SteamOS contract. These
            // flags select Steam's own Big Picture frontend; Winlator does
            // not provide a replacement UI or translated Windows process.
            steamArguments.add("-gamepadui");
            steamArguments.add("-steamos3");
            steamArguments.add("-steampal");
            steamArguments.add("-steamdeck");
            steamArguments.add("-no-cef-sandbox");
            steamArguments.add("-cef-disable-gpu");
            // Match the validated ARM64 Steam launch profile used by the
            // sibling Termux:X11 harness. These avoid the slow client-side
            // preallocation path and keep the already-provisioned client from
            // re-entering file verification during a UI handoff.
            steamArguments.add("-chromeosnopreallocate");
            steamArguments.add("-noverifyfiles");
            byte[] payload = SteamNativeExecRequest.encode(steamArguments);
            SteamControlClient.Response response = controlClient.request(
                SteamControlProtocol.EXEC_NATIVE_STEAM, payload);
            if (!response.isSuccess()) throw new IOException("native Steam launch status=" + response.status);
            lastStatus = "native Steam process started: " + response.payloadAsString().trim();
            Log.i(TAG, lastStatus);
        }
        catch (Exception e) {
            lastStatus = "native Steam launch error: " + e.getMessage();
            Log.e(TAG, lastStatus, e);
        }
    }

    /** Runs only the local FD/--args proxy fixture; it never launches Steam. */
    private void runtimeBwrapSelfTest() {
        try {
            ensureSessionPrepared();
            File proxy = new File(getFilesDir(),
                "steamdroid/holo-rootfs/usr/bin/steamdroid-bwrap-proxy");
            if (!proxy.isFile() || !proxy.canExecute()) throw new IOException("guest proxy is not staged");
            // Use a pipe as the fixture descriptor. Pressure Vessel may pass
            // a pipe or memfd through --args, not only a regular file.
            String command = "exec " + shellQuote(proxy.getPath()) + " --args 0";
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true);
            builder.environment().put("STEAMDROID_BWRAP_SOCKET", guestProxySocket.getPath());
            builder.environment().put("STEAMDROID_REAL_BWRAP", "/usr/bin/steamdroid-bwrap-fixture");
            Process process = builder.start();
            try (OutputStream input = process.getOutputStream()) {
                input.write(new byte[]{'-', '-', 0, '/', 'b', 'i', 'n', '/', 't', 'r', 'u', 'e', 0});
            }
            StringBuilder output = new StringBuilder();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("guest proxy self-test timed out");
            }
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                while (input.available() > 0 && output.length() < 8192) {
                    int length = input.read(buffer);
                    if (length <= 0) break;
                    output.append(new String(buffer, 0, length));
                }
            }
            if (process.exitValue() != 0) {
                throw new IOException("guest proxy self-test status=" + process.exitValue() +
                    " output=" + output);
            }
            lastStatus = "Runtime-4 proxy FD/--args self-test passed";
            Log.i(TAG, lastStatus);
        }
        catch (Exception e) {
            lastStatus = "Runtime-4 proxy self-test error: " + e.getMessage();
            Log.e(TAG, lastStatus, e);
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void ensureSessionPrepared() throws IOException {
        if (supervisorProcess == null || !supervisorProcess.isAlive() || controlClient == null) {
            prepareSession();
        }
        if (controlClient == null || supervisorProcess == null || !supervisorProcess.isAlive()) {
            throw new IOException("native session could not be prepared: " + lastStatus);
        }
    }

    public synchronized XServerCore getXServerCore() {
        if (xServerCore == null) xServerCore = new XServerCore(new ScreenInfo("1280x720"));
        return xServerCore;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    /** Starts the native ARM64 Steam executable inside the prepared Holo root. */
    public void launchNativeSteam(String executable, String... arguments) {
        String[] argv = new String[arguments.length + 1];
        argv[0] = executable;
        System.arraycopy(arguments, 0, argv, 1, arguments.length);
        executor.execute(() -> {
            try {
                if (controlClient == null) throw new IOException("native session is not prepared");
                byte[] payload = SteamNativeExecRequest.encode(Arrays.asList(argv));
                SteamControlClient.Response response = controlClient.request(
                    SteamControlProtocol.EXEC_NATIVE_STEAM, payload);
                if (!response.isSuccess()) throw new IOException("native Steam launch status=" + response.status);
                lastStatus = response.payloadAsString();
                Log.i(TAG, "native Steam launched: " + lastStatus.trim());
            }
            catch (Exception e) {
                lastStatus = "native Steam launch error: " + e.getMessage();
                Log.e(TAG, lastStatus, e);
            }
        });
    }

    private void prepareSession() {
        try {
            if (supervisorProcess == null || !supervisorProcess.isAlive()) {
                if (controlSocket.exists() && !controlSocket.delete()) throw new IOException("stale control socket");
                if (guestProxySocket.exists() && !guestProxySocket.delete()) throw new IOException("stale guest proxy socket");
                SteamSupervisor.SelfTestResult selfTest = SteamSupervisor.selfTest(this);
                if (selfTest.status != 0 || !selfTest.hashMatches) {
                    throw new IOException("supervisor self-test/hash failed: installed=" + selfTest.sha256 +
                        " apk=" + selfTest.packagedSha256 + " output=" + selfTest.output);
                }
                Log.i(TAG, "supervisor self-test passed sha256=" + selfTest.sha256 +
                    " apk_sha256=" + selfTest.packagedSha256 + " output=" + selfTest.output.trim());
                supervisorProcess = SteamSupervisor.start(this, controlSocket.getPath(), guestProxySocket.getPath());
                controlClient = new SteamControlClient(controlSocket.getPath(),
                    android.net.LocalSocketAddress.Namespace.FILESYSTEM);
                waitForControlEndpoint();
                Log.i(TAG, "supervisor control endpoint ready: " + controlSocket.getPath());
            }

            // Start the Android-owned X/Pulse/SysV substrate before PREPARE so
            // the root supervisor can bind these exact socket directories into
            // the Holo guest when one has been provisioned.
            nativeSubstrate.start(getXServerCore());
            SteamControlClient.Response response = controlClient.request(SteamControlProtocol.PREPARE_SESSION);
            if (!response.isSuccess()) throw new IOException("PREPARE_SESSION status=" + response.status);
            lastStatus = response.payloadAsString();
            waitForSocket(guestProxySocket, "guest proxy");
            Log.i(TAG, "native substrate started root=" + nativeSubstrate.getRootDir());
            Log.i(TAG, "session prepared: " + lastStatus.trim());
        }
        catch (Exception e) {
            lastStatus = "error: " + e.getMessage();
            Log.e(TAG, lastStatus, e);
            if (supervisorProcess != null) destroySession();
        }
    }

    private void destroySession() {
        try {
            if (controlClient != null) {
                SteamControlClient.Response response = controlClient.request(SteamControlProtocol.DESTROY_SESSION);
                lastStatus = response.payloadAsString();
                Log.i(TAG, "session destroyed: " + lastStatus.trim());
            }
        }
        catch (Exception e) {
            lastStatus = "teardown error: " + e.getMessage();
            Log.e(TAG, lastStatus, e);
        }
        finally {
            // The private namespace owns descendants that still use the
            // Android-provided X/Pulse/SysV substrate. Ask the supervisor to
            // stop and unmount those descendants first; only then tear down
            // the substrate they depend on.
            try {
                if (nativeSubstrate != null) nativeSubstrate.stop();
            }
            catch (Exception e) {
                lastStatus = "substrate teardown error: " + e.getMessage();
                Log.e(TAG, lastStatus, e);
            }
            if (controlClient != null) controlClient.close();
            controlClient = null;
            Process process = supervisorProcess;
            supervisorProcess = null;
            if (process != null) {
                try {
                    if (process.isAlive()) process.destroy();
                    if (process.isAlive() && !process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                    if (process.isAlive()) Log.w(TAG, "su wrapper did not exit during teardown");
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
            if (guestProxySocket != null) guestProxySocket.delete();
            if (controlSocket != null) controlSocket.delete();
        }
    }

    private void waitForControlEndpoint() throws IOException, InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 5000L;
        IOException lastError = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (supervisorProcess != null && !supervisorProcess.isAlive()) {
                throw new IOException("supervisor exited");
            }
            try {
                SteamControlClient.Response response = controlClient.request(
                    SteamControlProtocol.GET_CONTROL_STATUS);
                if (response.isSuccess()) return;
                lastError = new IOException("GET_CONTROL_STATUS status=" + response.status);
            }
            catch (IOException e) {
                lastError = e;
            }
            Thread.sleep(25L);
        }
        throw new IOException("supervisor control endpoint timeout", lastError);
    }

    private void waitForSocket(File socket, String name) throws IOException, InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + 5000L;
        while (!socket.exists()) {
            if (supervisorProcess != null && !supervisorProcess.isAlive()) throw new IOException("supervisor exited");
            if (SystemClock.elapsedRealtime() >= deadline) throw new IOException(name + " socket timeout");
            Thread.sleep(25L);
        }
    }

    private Notification notification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setContentTitle("SteamDroid")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
        }
        return new Notification.Builder(this)
            .setContentTitle("SteamDroid")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(
                NOTIFICATION_CHANNEL, "SteamDroid session", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public void onDestroy() {
        Future<?> cleanup = executor.submit(this::destroySession);
        executor.shutdown();
        try {
            cleanup.get(3, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            Log.w(TAG, "bounded service teardown wait ended: " + e.getMessage());
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public final class LocalBinder extends Binder {
        public SteamSessionService getService() {
            return SteamSessionService.this;
        }
    }
}
