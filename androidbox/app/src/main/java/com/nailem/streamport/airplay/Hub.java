package com.nailem.streamport.airplay;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The screens on the TV (AirPlay senders and browser shares, at most {@link Limits#maxStreams} together), which
 * ones are approved, which one is primary, and which one is heard. Listeners run on the main thread.
 */
public final class Hub {

    /**
     * Dynamic: the primary screen is biggest and the rest use as much of the TV as fits. Grid: equal cells. Focus:
     * only the primary screen, as big as it fits (the others keep running off screen, for a quick switch).
     */
    public enum Layout { DYNAMIC, GRID, FOCUS;

        /** What OK on the remote switches to: dynamic, grid, focus, and round again. */
        public Layout next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** One device shares once; two allows a reconnect overlapping the old screen. */
    public static final int MAX_PER_HOST = 2;
    /** A screen must deliver real video this soon after it starts, or it is closed. */
    public static final long VIDEO_START_MS = 20_000;

    public interface Listener {
        /** A screen arrived, left, was approved, or changed video size. */
        void onSessionsChanged();
    }

    private static final Hub INSTANCE = new Hub();

    public static Hub get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean notifyPending = new AtomicBoolean();
    private final List<Screen> screens = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Approvals since the receiver started (only with ask=true); see {@link Screen#approvalKey}. */
    private final Set<String> approved = new HashSet<>();
    private volatile boolean askBeforeShowing;
    private int nextId = 1;
    private Screen audible;
    /** The screen the remote's arrows (or the admin page) picked; it is biggest in DYNAMIC and the one heard. */
    private Screen primary;
    private volatile Layout layout = Layout.DYNAMIC;
    private Session.AudioSink audioSink;

    private Hub() {
    }

    public void setAskBeforeShowing(boolean ask) {
        askBeforeShowing = ask;
    }

    public void setAudioSink(Session.AudioSink sink) {
        Screen heard;
        synchronized (this) {
            audioSink = sink;
            heard = audible;
        }
        if (heard != null) heard.setAudible(true);
    }

    synchronized Session.AudioSink audioSink() {
        return audioSink;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public synchronized List<Screen> screens() {
        return new ArrayList<>(screens);
    }

    public synchronized int count() {
        return screens.size();
    }

    public synchronized int nextId() {
        return nextId++;
    }

    /**
     * What the box's hardware decoders hold at once, in decoded pixels. Measured on this box (2026-10-27): one 4K
     * plus one 1080p (10.4 MP) works, one more 1080p or a second 4K is refused by the decoder ("insufficient
     * resources"); five 1080p-class streams fit. Past the limit Android's codec layer can even crash the app, so a
     * screen that won't fit is turned away as soon as its size is known.
     */
    public static final long DECODE_BUDGET_PIXELS = 3840L * 2160 + 1920L * 1088;
    /** While the rebroadcast's encoder runs it takes a share of the same hardware: one 4K, or four 1080p. */
    public static final long DECODE_BUDGET_ENCODING = 3840L * 2160;

    private volatile long decodeBudget = DECODE_BUDGET_PIXELS;

    /** The rebroadcast's encoder started or stopped. */
    public void setEncoding(boolean on) {
        decodeBudget = on ? DECODE_BUDGET_ENCODING : DECODE_BUDGET_PIXELS;
    }

    public long decodeBudget() {
        return decodeBudget;
    }

    /** True if a picture of this size still fits next to every other screen's (sizes known so far). */
    public synchronized boolean fitsDecoder(Screen self, int width, int height) {
        long used = 0;
        for (Screen s : screens) {
            if (s == self || s.isClosed()) continue;
            used += (long) s.sourceWidth() * s.sourceHeight();
        }
        return used + (long) width * height <= decodeBudget;
    }

    /** Decoded pixels in use now (for a guard that doesn't know which screen a frame belongs to). */
    public synchronized long decodedPixels() {
        long used = 0;
        for (Screen s : screens) {
            if (!s.isClosed()) used += (long) s.sourceWidth() * s.sourceHeight();
        }
        return used;
    }

    /** Screens on the TV from one address. */
    public synchronized int countFrom(InetAddress peer) {
        int n = 0;
        for (Screen s : screens) {
            if (s.peer().equals(peer) && !s.isClosed()) n++;
        }
        return n;
    }

    /** True when there is room for one more screen from this address (a cheap check before costly setup). */
    public synchronized boolean hasRoomFor(InetAddress peer) {
        return screens.size() < Limits.maxStreams() && countFrom(peer) < MAX_PER_HOST;
    }

    /** Registers an AirPlay sender, or returns null when there is no room. */
    synchronized Session add(String name, String model, String deviceId, InetAddress peer,
                             byte[] masterKey, byte[] eiv) {
        if (!hasRoomFor(peer)) return null;
        Session s = new Session(this, nextId(), name, model, deviceId, peer, masterKey, eiv);
        return add(s) ? s : null;
    }

    /** Puts a screen on the TV. Returns false when all places are taken or its address already has its share. */
    public boolean add(Screen s) {
        synchronized (this) {
            if (!hasRoomFor(s.peer())) return false;
            String key = s.approvalKey();
            if (!askBeforeShowing || (key != null && approved.contains(key))) s.setState(Screen.State.ALLOWED);
            screens.add(s);
            if (primary == null) setPrimary(s);
        }
        // A screen that never delivers video would hold a place (and a black tile) forever.
        main.postDelayed(() -> {
            if (!s.hasVideo() && !s.isClosed()) {
                Log.i("StreamPort", "screen " + s.id() + ": no video after " + VIDEO_START_MS / 1000 + " s; closing");
                s.close();
            }
        }, VIDEO_START_MS);
        notifyChanged();
        return true;
    }

    /** Called by a screen as it closes. */
    public void remove(Screen s) {
        synchronized (this) {
            if (!screens.remove(s)) return;
            if (audible == s) audible = null;
            if (primary == s) {
                primary = null;
                if (!screens.isEmpty()) setPrimary(screens.get(0));
            }
        }
        notifyChanged();
    }

    public void approve(Screen s) {
        synchronized (this) {
            if (s.isClosed()) return;
            s.setState(Screen.State.ALLOWED);
            String key = s.approvalKey();
            if (key != null) approved.add(key);
        }
        notifyChanged();
    }

    public void refuse(Screen s) {
        s.close();
    }

    public void disconnectAll() {
        for (Screen s : screens()) s.close();
    }

    public Layout layout() {
        return layout;
    }

    public void setLayout(Layout l) {
        if (l == null || l == layout) return;
        layout = l;
        notifyChanged();
    }

    public synchronized Screen primary() {
        return primary;
    }

    /** Makes a screen the primary one (and the one heard). */
    public synchronized void setPrimary(Screen s) {
        if (s == null || s.isClosed() || !screens.contains(s)) return;
        primary = s;
        if (audible != s) {
            if (audible != null) audible.setAudible(false);
            audible = s;
            s.setAudible(true);
        }
        notifyChanged();
    }

    public synchronized Screen find(int id) {
        for (Screen s : screens) {
            if (s.id() == id) return s;
        }
        return null;
    }

    /** Something outside the screens changed (a setting, the rebroadcast): listeners look again. */
    public void refresh() {
        notifyChanged();
    }

    /** A screen's size changed: relayout. */
    public void onVideoChanged(Screen s) {
        notifyChanged();
    }

    /** Coalesced: however many changes arrive, listeners run once per main-thread turn. */
    private void notifyChanged() {
        if (!notifyPending.compareAndSet(false, true)) return;
        main.post(() -> {
            notifyPending.set(false);
            for (Listener l : listeners) l.onSessionsChanged();
        });
    }
}
