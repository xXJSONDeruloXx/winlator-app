package com.winlator.runtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import com.winlator.core.TarCompressorUtils;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.RootFS;
import com.winlator.xenvironment.XEnvironment;
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.xenvironment.components.PulseAudioComponent;
import com.winlator.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.xenvironment.components.XServerComponent;
import com.winlator.xserver.XServerCore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Starts only the native Linux display substrate used by SteamDroid.
 *
 * This deliberately does not add Winlator's Wine/Box64 guest launcher. The
 * native Steam client and its Steam-managed compatibility tools are attached
 * to this substrate in later milestones.
 */
public final class SteamNativeSubstrate {
    private final Context context;
    private XEnvironment environment;
    private File rootDir;
    private SteamIdentity identity;
    private boolean running;

    public SteamNativeSubstrate(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start(XServerCore xServer) {
        if (running) return;

        ensurePulseAudioModules();
        rootDir = new File(context.getFilesDir(), "steamdroid/guest");
        createDirectory("tmp");
        createDirectory("etc");
        createDirectory("run");
        createDirectory("home/steam");
        try {
            identity = SteamIdentity.capture();
            identity.writeGuestIdentityViews(rootDir);
            writeGuestResolverConfig();
        }
        catch (IOException e) {
            throw new IllegalStateException("unable to provision dynamic guest identity", e);
        }

        RootFS runtimeRoot = RootFS.forDirectory(rootDir);
        environment = new XEnvironment(context, runtimeRoot);
        environment.addComponent(new SysVSharedMemoryComponent(
            xServer, UnixSocketConfig.create(rootDir.getPath(), UnixSocketConfig.SYSVSHM_SERVER_PATH)));
        environment.addComponent(new XServerComponent(
            xServer, UnixSocketConfig.create(rootDir.getPath(), UnixSocketConfig.XSERVER_PATH)));
        environment.addComponent(new NetworkInfoUpdateComponent());

        PulseAudioComponent pulse = new PulseAudioComponent(
            UnixSocketConfig.create(rootDir.getPath(), UnixSocketConfig.PULSE_SERVER_PATH));
        environment.addComponent(pulse);

        // There is intentionally no GuestProgramLauncherComponent here. The
        // root helper owns the Holo/chroot process tree; this environment only
        // provides X11, SysV shared memory, network metadata, and PulseAudio.
        environment.startEnvironmentComponents();
        running = true;
    }

    public synchronized void stop() {
        if (!running) return;
        if (environment != null) environment.stopEnvironmentComponents();
        environment = null;
        running = false;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized File getRootDir() {
        return rootDir;
    }

    public synchronized XEnvironment getEnvironment() {
        return environment;
    }

    public synchronized SteamIdentity getIdentity() {
        return identity;
    }

    private void createDirectory(String relativePath) {
        File directory = new File(rootDir, relativePath);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("unable to create Steam substrate directory: " + directory);
        }
    }

    private void writeGuestResolverConfig() throws IOException {
        File resolver = new File(rootDir, "etc/resolv.conf");
        File partial = new File(rootDir, "etc/resolv.conf.partial");
        ConnectivityManager connectivity = (ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network activeNetwork = connectivity != null ? connectivity.getActiveNetwork() : null;
        LinkProperties linkProperties = activeNetwork != null && connectivity != null
            ? connectivity.getLinkProperties(activeNetwork) : null;

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(partial),
                StandardCharsets.US_ASCII)) {
            if (linkProperties != null) {
                for (InetAddress address : linkProperties.getDnsServers()) {
                    writer.write("nameserver ");
                    writer.write(address.getHostAddress());
                    writer.write('\n');
                }
            }
            // A missing Android LinkProperties record should not make the
            // guest permanently offline. These are only fallbacks; active
            // network DNS servers above always take precedence.
            if (linkProperties == null || linkProperties.getDnsServers().isEmpty()) {
                writer.write("nameserver 1.1.1.1\n");
                writer.write("nameserver 8.8.8.8\n");
            }
        }
        if (resolver.isFile() && !resolver.delete()) {
            throw new IOException("unable to replace guest resolver configuration");
        }
        if (!partial.renameTo(resolver)) {
            throw new IOException("unable to commit guest resolver configuration");
        }
        if (!resolver.setReadable(true, false)) {
            throw new IOException("unable to make guest resolver configuration readable");
        }
    }

    private void ensurePulseAudioModules() {
        File pulseDir = new File(context.getFilesDir(), "pulseaudio");
        File modulesDir = new File(pulseDir, "modules");
        File nativeProtocol = new File(modulesDir, "module-native-protocol-unix.so");
        File aaudioSink = new File(modulesDir, "module-aaudio-sink.so");
        if (nativeProtocol.isFile() && aaudioSink.isFile()) return;
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context,
            "pulseaudio.tzst", pulseDir)) {
            throw new IllegalStateException("unable to provision PulseAudio modules");
        }
        if (!nativeProtocol.isFile() || !aaudioSink.isFile()) {
            throw new IllegalStateException("PulseAudio module archive is incomplete");
        }
    }
}
