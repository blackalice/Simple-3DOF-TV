package com.portable3dof;

/**
 * Stand-in for the "core" edition, which has no YouTube support (the YouTube edition adds a GPL-3.0 library; see
 * README "Which version do you want?"). Only the members the shared code refers to exist, and nothing here is ever reached at runtime.
 */
public final class YouTubeClient {
    private YouTubeClient() { }

    /** A video in a YouTube list. */
    public static final class Item {
        public final String url = null;
    }

    /** Everything the player needs to start a video. */
    public static final class Playable {
        public String id = "", title = "", uploader = "", uploaderUrl = "";
        public boolean live;
        public int height;

        public boolean playable() { return false; }
    }

    public static Playable resolve(String url, int maxHeight) throws Exception {
        throw new UnsupportedOperationException("YouTube is not part of this edition");
    }
}
