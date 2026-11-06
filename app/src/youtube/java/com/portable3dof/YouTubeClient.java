package com.portable3dof;

import android.util.Log;

import org.json.JSONObject;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.feed.FeedExtractor;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Thin wrapper over NewPipeExtractor: search, trending, channels, feeds and picking playable streams. All methods block
 * (network) and must be called off the main thread.
 */
public final class YouTubeClient {

    private static final String TAG = "YouTubeClient";

    /** One entry in a list: a video, or a channel (channel == true; views then holds the subscriber count). */
    public static final class Item {
        public final String title, uploader, url, thumb, uploaderUrl, when;
        public final long durationSec, views, uploadedMs;
        public final boolean live, channel;

        Item(String title, String uploader, String url, String thumb, long durationSec, long views, boolean live,
             String uploaderUrl, long uploadedMs, String when, boolean channel) {
            this.title = title == null ? "" : title;
            this.uploader = uploader == null ? "" : uploader;
            this.url = url;
            this.thumb = thumb;
            this.durationSec = Math.max(0, durationSec);
            this.views = Math.max(0, views);
            this.live = live;
            this.uploaderUrl = uploaderUrl;
            this.uploadedMs = uploadedMs;
            this.when = when == null ? "" : when;
            this.channel = channel;
        }

        static Item channel(String name, String url, String thumb, long subs) {
            return new Item(name, "", url, thumb, 0, subs, false, null, 0, "", true);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("t", title).put("u", uploader).put("url", url).put("th", thumb)
                    .put("d", durationSec).put("v", views).put("l", live).put("uu", uploaderUrl)
                    .put("ms", uploadedMs).put("w", when).put("c", channel);
        }

        static Item fromJson(JSONObject o) throws Exception {
            return new Item(o.optString("t"), o.optString("u"), o.getString("url"), o.optString("th", null),
                    o.optLong("d"), o.optLong("v"), o.optBoolean("l"), o.optString("uu", null), o.optLong("ms"),
                    o.optString("w"), o.optBoolean("c"));
        }
    }

    /** A page of results plus what is needed to fetch the next one. */
    public static final class Results {
        public final List<Item> items;
        final ListLinkHandler handler;
        final Page next;
        final boolean search;
        final String fallbackUploader, fallbackUploaderUrl;

        Results(List<Item> items, ListLinkHandler handler, Page next, boolean search, String fbName, String fbUrl) {
            this.items = items;
            this.handler = handler;
            this.next = next;
            this.search = search;
            this.fallbackUploader = fbName;
            this.fallbackUploaderUrl = fbUrl;
        }

        public boolean hasMore() { return next != null && handler != null; }
    }

    /** A channel page: identity plus its first page of videos. */
    public static final class Channel {
        public String name = "", url = "", thumb;
        public long subs;
        public Results videos;
    }

    public static final class Sub {
        public final String url, mime, lang, label;

        Sub(String url, String mime, String lang, String label) {
            this.url = url;
            this.mime = mime;
            this.lang = lang;
            this.label = label;
        }
    }

    /** Everything the player needs to start a video. */
    public static final class Playable {
        public String id = "", title = "", uploader = "", uploaderUrl = "";
        public String videoUrl, audioUrl;      // adaptive pair (preferred)
        public String muxedUrl;                // single-file fallback
        public String hlsUrl;                  // live streams
        public boolean live;
        public int height;
        public String audioDesc = "";
        public final List<Sub> subs = new ArrayList<>();

        public boolean playable() {
            return hlsUrl != null && live || (videoUrl != null && audioUrl != null) || muxedUrl != null;
        }

        public String describe() {
            if (live) return "live";
            if (videoUrl != null) return height + "p";
            return muxedUrl != null ? "muxed" : "?";
        }
    }

    private static boolean inited = false;

    private static synchronized StreamingService yt() {
        if (!inited) {
            NewPipe.init(new YtDownloader(), new Localization("en", "GB"), new ContentCountry("GB"));
            inited = true;
        }
        return ServiceList.YouTube;
    }

    public static OkHttpClientHolder http() {
        yt();
        return new OkHttpClientHolder();
    }

    /** Lets the thumbnail loader reuse the downloader's HTTP client. */
    public static final class OkHttpClientHolder {
        public okhttp3.OkHttpClient client() {
            return ((YtDownloader) NewPipe.getDownloader()).client();
        }
    }

    // ---- search / trending ----------------------------------------------------------------------

    public static Results search(String query) throws Exception {
        StreamingService s = yt();
        SearchQueryHandler h = s.getSearchQHFactory().fromQuery(query, Collections.singletonList("all"), "");
        SearchInfo info = SearchInfo.getInfo(s, h);
        return new Results(convert(info.getRelatedItems(), null, null), h, info.getNextPage(), true, null, null);
    }

    public static Results more(Results r) throws Exception {
        if (!r.hasMore()) return new Results(new ArrayList<Item>(), null, null, r.search, null, null);
        StreamingService s = yt();
        ListExtractor.InfoItemsPage<InfoItem> page = r.search
                ? SearchInfo.getMoreItems(s, (SearchQueryHandler) r.handler, r.next)
                : ChannelTabInfo.getMoreItems(s, r.handler, r.next);
        return new Results(convert(page.getItems(), r.fallbackUploader, r.fallbackUploaderUrl), r.handler,
                page.getNextPage(), r.search, r.fallbackUploader, r.fallbackUploaderUrl);
    }

    // ---- Shorts ---------------------------------------------------------------------------------

    static boolean isShortsUrl(String url) { return url != null && url.contains("/shorts/"); }

    /** The video id of a watch / shorts / youtu.be URL, or null. */
    public static String videoId(String url) {
        if (url == null) return null;
        int i = url.indexOf("v=");
        if (i >= 0) {
            String id = url.substring(i + 2);
            int e = id.indexOf('&');
            return e >= 0 ? id.substring(0, e) : id;
        }
        for (String k : new String[]{"/shorts/", "youtu.be/"}) {
            i = url.indexOf(k);
            if (i >= 0) {
                String id = url.substring(i + k.length());
                int e = id.indexOf('?');
                return e >= 0 ? id.substring(0, e) : id;
            }
        }
        return null;
    }

    private static okhttp3.OkHttpClient noRedirects;

    /**
     * Is this video a Short? Feeds (RSS) do not say, so ask youtube.com/shorts/ID: a Short answers 200, anything else
     * redirects to /watch. The consent cookie avoids the EU/UK consent redirect. Returns null when it cannot tell.
     */
    public static Boolean isShort(String videoUrl) {
        String id = videoId(videoUrl);
        if (id == null || id.isEmpty()) return null;
        synchronized (YouTubeClient.class) {
            if (noRedirects == null) {
                noRedirects = http().client().newBuilder().followRedirects(false).followSslRedirects(false).build();
            }
        }
        okhttp3.Request rq = new okhttp3.Request.Builder().url("https://www.youtube.com/shorts/" + id).head()
                .header("User-Agent", YtDownloader.USER_AGENT).header("Cookie", "SOCS=CAI").build();
        try (okhttp3.Response r = noRedirects.newCall(rq).execute()) {
            if (r.code() == 200) return true;
            String loc = r.header("Location");
            if (r.isRedirect() && loc != null && loc.contains("/watch")) return false;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Search suggestions for what has been typed so far (YouTube's autocomplete). */
    public static List<String> suggestions(String query) throws Exception {
        List<String> l = yt().getSuggestionExtractor().suggestionList(query);
        return l == null ? new ArrayList<String>() : l;
    }

    /** A channel's avatar URL (one page fetch, no videos), or null. */
    public static String channelAvatar(String url) throws Exception {
        ChannelInfo ci = ChannelInfo.getInfo(yt(), url);
        return bestThumb(ci.getAvatars());
    }

    public static List<Item> trending() throws Exception {
        StreamingService s = yt();
        KioskExtractor<? extends InfoItem> k = s.getKioskList().getDefaultKioskExtractor();
        k.fetchPage();
        return convert(k.getInitialPage().getItems(), null, null);
    }

    // ---- channels / subscriptions feed ------------------------------------------------------------

    public static Channel channel(String url) throws Exception {
        StreamingService s = yt();
        ChannelInfo ci = ChannelInfo.getInfo(s, url);
        Channel c = new Channel();
        c.name = ci.getName();
        c.url = ci.getUrl() != null ? ci.getUrl() : url;
        c.thumb = bestThumb(ci.getAvatars());
        c.subs = ci.getSubscriberCount();
        for (ListLinkHandler t : ci.getTabs()) {
            if (t.getContentFilters() != null && t.getContentFilters().contains("videos")) {
                ChannelTabInfo ti = ChannelTabInfo.getInfo(s, t);
                c.videos = new Results(convert(ti.getRelatedItems(), c.name, c.url), t, ti.getNextPage(), false,
                        c.name, c.url);
                break;
            }
        }
        if (c.videos == null) c.videos = new Results(new ArrayList<Item>(), null, null, false, c.name, c.url);
        return c;
    }

    /** Latest uploads of one channel via its (cheap) RSS feed, falling back to the channel's Videos tab. */
    public static List<Item> feed(String channelUrl) throws Exception {
        StreamingService s = yt();
        try {
            FeedExtractor fe = s.getFeedExtractor(channelUrl);
            if (fe != null) {
                fe.fetchPage();
                List<Item> l = convert(fe.getInitialPage().getItems(), null, channelUrl);
                if (!l.isEmpty()) return l;
            }
        } catch (Exception e) {
            Log.w(TAG, "feed extractor failed for " + channelUrl + ": " + e);
        }
        return channel(channelUrl).videos.items;
    }

    private static List<Item> convert(List<? extends InfoItem> in, String fbName, String fbUrl) {
        List<Item> out = new ArrayList<>();
        for (InfoItem i : in) {
            if (i instanceof StreamInfoItem) {
                StreamInfoItem v = (StreamInfoItem) i;
                if (Settings.hideShorts && (v.isShortFormContent() || isShortsUrl(v.getUrl()))) continue;
                long ms = 0;
                DateWrapper d = v.getUploadDate();
                if (d != null) {
                    try { ms = d.offsetDateTime().toInstant().toEpochMilli(); } catch (Exception ignored) { }
                }
                String name = v.getUploaderName();
                if (name == null || name.isEmpty()) name = fbName;
                String uurl = v.getUploaderUrl();
                if (uurl == null || uurl.isEmpty()) uurl = fbUrl;
                out.add(new Item(v.getName(), name, v.getUrl(), bestThumb(v.getThumbnails()), v.getDuration(),
                        v.getViewCount(), v.getStreamType() == StreamType.LIVE_STREAM, uurl, ms,
                        v.getTextualUploadDate(), false));
            } else if (i instanceof ChannelInfoItem) {
                ChannelInfoItem c = (ChannelInfoItem) i;
                out.add(Item.channel(c.getName(), c.getUrl(), bestThumb(c.getThumbnails()), c.getSubscriberCount()));
            }
        }
        return out;
    }

    /** The thumbnail closest to (but not below) ~180 px tall, else the largest available. */
    private static String bestThumb(List<Image> imgs) {
        if (imgs == null || imgs.isEmpty()) return null;
        Image best = null;
        for (Image im : imgs) {
            int h = im.getHeight();
            if (best == null) { best = im; continue; }
            int bh = best.getHeight();
            boolean better = bh < 180 ? h > bh : (h >= 180 && h < bh);
            if (better) best = im;
        }
        return best.getUrl();
    }

    // ---- resolving a video ------------------------------------------------------------------------

    /** Picks streams for the best quality at or below maxHeight (H.264 on a tie, otherwise whatever is highest: VP9 decodes in hardware). */

    public static Playable resolve(String url, int maxHeight) throws Exception {
        long t0 = android.os.SystemClock.elapsedRealtime();
        StreamInfo info = StreamInfo.getInfo(yt(), url);
        if (MediaEngine.exp.contains("perf")) Log.i("P3D_PERF", "resolve took " + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
        Playable p = new Playable();
        p.id = info.getId();
        p.title = info.getName();
        p.uploader = info.getUploaderName();
        p.uploaderUrl = info.getUploaderUrl() == null ? "" : info.getUploaderUrl();
        p.live = info.getStreamType() == StreamType.LIVE_STREAM;
        if (p.live && info.getHlsUrl() != null && !info.getHlsUrl().isEmpty()) {
            p.hlsUrl = info.getHlsUrl();
            return p;
        }

        if (MediaEngine.exp.contains("perf")) for (VideoStream v : info.getVideoOnlyStreams())
            Log.i("P3D_PERF", "avail " + v.getFormat() + " " + v.getHeight() + "p" + v.getFps() + " " + v.getCodec() + " " + v.getBitrate() / 1000 + "k");
        VideoStream bestAvc = null, bestAny = null;
        for (VideoStream v : info.getVideoOnlyStreams()) {
            if (!v.isUrl() || v.getHeight() > maxHeight) continue;
            String codec = v.getCodec();
            if (codec != null && codec.startsWith("av01")) continue;       // AV1: not worth the risk, VP9/H.264 are always there
            bestAny = betterVideo(bestAny, v);
            if (v.getFormat() == MediaFormat.MPEG_4) bestAvc = betterVideo(bestAvc, v);
        }
        // Highest resolution wins (the hardware decoder handles VP9 up to 4K60 here); H.264 only on a tie in height and fps.
        VideoStream chosen = bestAvc != null && (bestAny == null || (bestAvc.getHeight() == bestAny.getHeight()
                && bestAvc.getFps() >= bestAny.getFps())) ? bestAvc : bestAny;

        AudioStream bestAudio = null;
        int bestScore = Integer.MIN_VALUE;
        for (AudioStream a : info.getAudioStreams()) {
            if (!a.isUrl()) continue;
            int sc = audioScore(a);
            if (bestAudio == null || sc > bestScore) { bestAudio = a; bestScore = sc; }
        }
        if (bestAudio != null) {
            p.audioDesc = (bestAudio.getAudioLocale() != null ? bestAudio.getAudioLocale().toLanguageTag() : "?")
                    + " " + bestAudio.getAudioTrackType() + " " + bestAudio.getAudioTrackName();
            Log.i(TAG, "audio track: " + p.audioDesc + " (" + bestAudio.getFormat() + " "
                    + bestAudio.getAverageBitrate() + "k)");
        }

        if (chosen != null && bestAudio != null) {
            p.videoUrl = chosen.getContent();
            p.audioUrl = bestAudio.getContent();
            p.height = chosen.getHeight();
            if (MediaEngine.exp.contains("perf")) Log.i("P3D_PERF", "chose " + chosen.getFormat() + " " + chosen.getHeight() + "p" + chosen.getFps() + " " + chosen.getBitrate() / 1000 + "kbps codec=" + chosen.getCodec());
        } else {
            VideoStream muxed = null;
            for (VideoStream v : info.getVideoStreams()) {
                if (!v.isUrl()) continue;
                if (muxed == null || (v.getHeight() <= maxHeight && v.getHeight() > muxed.getHeight())) muxed = v;
            }
            if (muxed != null) { p.muxedUrl = muxed.getContent(); p.height = muxed.getHeight(); }
        }

        // subtitles: manual before auto-generated, English first, at most four
        List<SubtitlesStream> subs = new ArrayList<>(info.getSubtitles());
        Collections.sort(subs, new java.util.Comparator<SubtitlesStream>() {
            @Override public int compare(SubtitlesStream a, SubtitlesStream b) {
                int sa = (a.isAutoGenerated() ? 10 : 0) + ("en".equals(a.getLanguageTag()) ? 0 : 1);
                int sb = (b.isAutoGenerated() ? 10 : 0) + ("en".equals(b.getLanguageTag()) ? 0 : 1);
                return sa - sb;
            }
        });
        for (SubtitlesStream s : subs) {
            if (p.subs.size() >= 4) break;
            String mime = s.getFormat() == MediaFormat.VTT ? "text/vtt"
                    : s.getFormat() == MediaFormat.TTML ? "application/ttml+xml"
                    : s.getFormat() == MediaFormat.SRT ? "application/x-subrip" : null;
            if (mime == null || !s.isUrl()) continue;
            String label = s.getDisplayLanguageName() + (s.isAutoGenerated() ? " (auto)" : "");
            p.subs.add(new Sub(s.getContent(), mime, s.getLanguageTag(), label));
        }
        return p;
    }

    /**
     * Videos can carry several audio tracks (original + dubs + audio description). Prefer English, then the original
     * track, never audio description; within one track prefer AAC (m4a) and the higher bitrate.
     */
    private static int audioScore(AudioStream a) {
        int s = 0;
        Locale l = a.getAudioLocale();
        if (l != null && "en".equals(l.getLanguage())) s += 1000;
        AudioTrackType t = a.getAudioTrackType();
        if (t == AudioTrackType.DESCRIPTIVE) s -= 5000;
        else if (t == null || t == AudioTrackType.ORIGINAL) s += 300;
        else if (t == AudioTrackType.DUBBED) s += 100;
        if (a.getFormat() == MediaFormat.M4A) s += 50;
        s += Math.min(40, Math.max(0, a.getAverageBitrate()) / 4);
        return s;
    }

    private static VideoStream betterVideo(VideoStream cur, VideoStream v) {
        if (cur == null) return v;
        if (v.getHeight() != cur.getHeight()) return v.getHeight() > cur.getHeight() ? v : cur;
        return v.getFps() > cur.getFps() ? v : cur;
    }
}
