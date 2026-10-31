package dev.filterbridge.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort hint derived from a shared URL; it is not proof of which app sent the share. */
public enum ContentSourcePlatform {
    FACEBOOK, INSTAGRAM, YOUTUBE, OTHER, UNKNOWN;

    private static final Pattern URL_HOST = Pattern.compile("https?://([^\\s/?#]+)", Pattern.CASE_INSENSITIVE);

    public static ContentSourcePlatform detectFromText(String text) {
        if (text == null || text.isEmpty()) return UNKNOWN;
        Matcher matcher = URL_HOST.matcher(text);
        while (matcher.find()) {
            String host = matcher.group(1).toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            if (host.equals("facebook.com") || host.endsWith(".facebook.com") || host.equals("fb.watch")) return FACEBOOK;
            if (host.equals("instagram.com") || host.endsWith(".instagram.com")) return INSTAGRAM;
            if (host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be")) return YOUTUBE;
        }
        return UNKNOWN;
    }
}
