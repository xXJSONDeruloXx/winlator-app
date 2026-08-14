package com.winlator.xserver;

import com.winlator.core.CursorLocker;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.Texture;
import com.winlator.xserver.extensions.BigReqExtension;
import com.winlator.xserver.extensions.DRI3Extension;
import com.winlator.xserver.extensions.Extension;
import com.winlator.xserver.extensions.GLXExtension;
import com.winlator.xserver.extensions.MITSHMExtension;
import com.winlator.xserver.extensions.PresentExtension;
import com.winlator.xserver.extensions.RandRExtension;
import com.winlator.xserver.extensions.SyncExtension;
import com.winlator.xserver.extensions.XComposite;
import com.winlator.xserver.extensions.XFixesExtension;
import com.winlator.xserver.extensions.XInputExtension;

import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    private final XServerHost host;
    private final Extension[] extensions;
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    public final CursorLocker cursorLocker;
    private SHMSegmentManager shmSegmentManager;
    private GLRenderer renderer;
    private final List<Texture> deferredTextureDestroys = new ArrayList<>();
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private boolean relativeMouseMovement = false;
    private XClient serverGrabOwner;

    public XServer(ScreenInfo screenInfo) {
        this(XServerHost.NO_OP, screenInfo);
    }

    public XServer(XServerHost host, ScreenInfo screenInfo) {
        this.host = host != null ? host : XServerHost.NO_OP;
        this.screenInfo = screenInfo;
        cursorLocker = new CursorLocker(this);
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);

        DesktopHelper.attachTo(this);
        extensions = setupExtensions();
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        cursorLocker.setEnabled(!relativeMouseMovement);
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public void setRenderer(GLRenderer renderer) {
        this.renderer = renderer;
        if (renderer != null) {
            final List<Texture> pending;
            synchronized (deferredTextureDestroys) {
                pending = new ArrayList<>(deferredTextureDestroys);
                deferredTextureDestroys.clear();
            }
            if (!pending.isEmpty()) {
                renderer.xServerView.queueEvent(() -> {
                    for (Texture texture : pending) texture.destroy();
                });
            }
        }
    }

    /**
     * Destroys a renderer-owned texture on the GL thread when a surface is
     * attached. Guest X clients are allowed to run while the Activity is not
     * attached, so retain allocated textures until a renderer returns instead
     * of dereferencing an Activity-owned view from the X thread.
     */
    public void destroyTexture(Texture texture) {
        if (texture == null) return;
        GLRenderer currentRenderer = renderer;
        if (currentRenderer != null && currentRenderer.xServerView != null) {
            currentRenderer.xServerView.queueEvent(texture::destroy);
        }
        else if (texture.isAllocated()) {
            synchronized (deferredTextureDestroys) {
                if (!deferredTextureDestroys.contains(texture)) deferredTextureDestroys.add(texture);
            }
        }
        else {
            texture.destroy();
        }
    }

    public void detachRenderer(GLRenderer renderer) {
        if (this.renderer == renderer) {
            if (renderer != null) renderer.release();
            this.renderer = null;
        }
    }

    public XServerHost getHost() {
        return host;
    }

    public boolean shouldAutoMapTopLevelWindow(Window window) {
        return host.autoMapTopLevelWindows() &&
            window.getParent() == windowManager.rootWindow &&
            window.isInputOutput() && !window.attributes.isOverrideRedirect() &&
            window.getWidth() > 1 && window.getHeight() > 1;
    }

    public String getNativeLibraryDir() {
        return host.nativeLibraryDir();
    }

    public void sendRelativeMouseEvent(int flags, int dx, int dy, int wheelDelta) {
        host.relativeMouseEvent(flags, dx, dy, wheelDelta);
    }

    public void sendGamepadState(ControlsProfile profile) {
        host.sendGamepadState(profile);
    }

    public void sendMidiShortMessage(byte status, byte data1, byte data2, byte data3) {
        host.midiShortMessage(status, data1, data2, data3);
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    /**
     * Record the protocol-level XGrabServer owner. The X connector currently
     * dispatches clients on one epoll thread, so this state is checked by the
     * request handler rather than represented by a Java lock held across
     * requests.
     */
    public synchronized void grabServer(XClient client) {
        if (serverGrabOwner == null) serverGrabOwner = client;
    }

    public synchronized void ungrabServer(XClient client) {
        if (serverGrabOwner == client) serverGrabOwner = null;
    }

    public synchronized boolean isServerGrabbedByOther(XClient client) {
        return serverGrabOwner != null && serverGrabOwner != client;
    }

    public synchronized void releaseServerGrab(XClient client) {
        ungrabServer(client);
    }

    public Extension getExtensionByName(String name) {
        for (Extension extension : extensions) if (extension.getName().equals(name)) return extension;
        return null;
    }

    public Extension[] getExtensions() {
        return extensions.clone();
    }

    public void injectPointerMove(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(x, y);
        }
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(pointer.getX() + dx, pointer.getY() + dy);
        }
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);
        }
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);
        }
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.id, keysym);
        }
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.id);
        }
    }

    private Extension[] setupExtensions() {
        byte opcode = Extension.START_MAJOR_OPCODE;
        return new Extension[]{
            new BigReqExtension(this, opcode--),
            new MITSHMExtension(this, opcode--),
            new DRI3Extension(this, opcode--),
            new PresentExtension(this, opcode--),
            new SyncExtension(this, opcode--),
            new XComposite(this, opcode--),
            new GLXExtension(this, opcode--),
            new XFixesExtension(this, opcode--),
            new RandRExtension(this, opcode--),
            new XInputExtension(this, opcode--)
        };
    }

    public <T extends Extension> T getExtension(byte opcode) {
        int index = Extension.START_MAJOR_OPCODE - opcode;
        return (T)extensions[index];
    }

    public void debugPrint(String line) {
        host.debugPrint("xserver:"+line);
    }
}
