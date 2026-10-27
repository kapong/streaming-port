package com.nailem.streamport.airplay;

import android.graphics.SurfaceTexture;

import java.net.InetAddress;

/**
 * One shared screen on the TV, however it arrives: an AirPlay sender ({@link Session}) or a browser sharing over
 * WebRTC ({@code webrtc.BrowserScreen}). The hub, the tiles, the layout, the limits and the admin page work
 * through this, so both kinds behave the same.
 */
public interface Screen {

    enum Kind { AIRPLAY, BROWSER }

    enum State { PENDING, ALLOWED }

    /** Decoded picture size (crop applied), delivered on the main thread. */
    interface SizeListener {
        void onVideoSize(int width, int height);
    }

    /** A screen's video drawing into one tile; released when the tile goes away. */
    interface VideoOutput {
        void release();
    }

    int id();

    Kind kind();

    /** Sender-supplied, cleaned to printable text. */
    String name();

    /** Device model or browser, e.g. "Mac13,2" or "Chrome on Windows". */
    String model();

    InetAddress peer();

    default String address() {
        return peer().getHostAddress();
    }

    /** What on-screen approval remembers (only with ask=true). */
    String approvalKey();

    State state();

    void setState(State state);

    boolean isClosed();

    /** Disconnects the sender; the hub forgets it. Safe to call more than once, from any thread. */
    void close();

    /** Picture size as far as known (0 until known). The tile's decoder reports the exact size. */
    int sourceWidth();

    int sourceHeight();

    NetStats.Snapshot stats();

    /** True once real video arrived; the hub closes screens that send none within the grace period. */
    boolean hasVideo();

    /** Only the primary screen is heard. */
    void setAudible(boolean audible);

    /** Starts drawing into a tile's texture. Called on the main thread. */
    VideoOutput attachVideo(SurfaceTexture texture, SizeListener listener);
}
