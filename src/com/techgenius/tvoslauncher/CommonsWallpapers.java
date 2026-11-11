package com.techgenius.tvoslauncher;

import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Landscape images from the featured collections on Wikimedia Commons. */
final class CommonsWallpapers {
    static final String USER_AGENT = "tvOSLauncher/2.1.6 (https://github.com/lemilleur/tvOS-Launcher)";

    enum Theme {
        NATURE("Nature", "landscapes"),
        SPACE("Space", "galaxies", "the Milky Way"),
        CITIES("Cities", "cityscapes"),
        ANIMALS("Animals", "Passeriformes", "Panthera leo", "Cetacea"),
        SEA("Sea", "coasts");

        final String label;
        final List<String> categories;

        Theme(String label, String... categories) {
            this.label = label;
            this.categories = Arrays.asList(categories);
        }
    }

    static final class Photo {
        final String url, credits;

        Photo(String url, String credits) {
            this.url = url;
            this.credits = credits;
        }
    }

    private CommonsWallpapers() {}

    static List<Photo> fetch(Theme theme) throws Exception {
        List<String> categories = new ArrayList<>(theme.categories);
        Collections.shuffle(categories);
        for (String category : categories) {
            List<Photo> photos = fetchCategory("Category:Featured pictures of " + category);
            if (!photos.isEmpty()) return photos;
        }
        return Collections.emptyList();
    }

    private static List<Photo> fetchCategory(String category) throws Exception {
        String url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2"
                + "&generator=categorymembers&gcmnamespace=6&gcmtype=file&gcmlimit=24&gcmsort=timestamp&gcmdir=desc"
                + "&gcmtitle=" + URLEncoder.encode(category, "UTF-8")
                + "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=1920"
                + "&iiextmetadatafilter=Artist%7CLicenseShortName%7CLicenseUrl";
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try (InputStream stream = connection.getInputStream()) {
            JSONObject result = new JSONObject(new String(Suggestions.readAll(stream), StandardCharsets.UTF_8));
            return parse(result.getJSONObject("query").getJSONArray("pages"));
        } finally {
            connection.disconnect();
        }
    }

    static List<Photo> parse(JSONArray pages) throws Exception {
        List<Photo> photos = new ArrayList<>();
        for (int i = 0; i < pages.length(); i++) {
            JSONArray images = pages.getJSONObject(i).optJSONArray("imageinfo");
            if (images == null || images.length() == 0) continue;
            JSONObject image = images.getJSONObject(0);
            String mime = image.optString("mime");
            String url = image.optString("thumburl");
            int width = image.optInt("width"), height = image.optInt("height");
            if (!(mime.equals("image/jpeg") || mime.equals("image/png"))
                    || width < 1920 || height < 1080 || width < height * 1.2 || width > height * 2.8
                    || !(url.startsWith("https://thumb.wikimedia.org/")
                    || url.startsWith("https://upload.wikimedia.org/"))) continue;
            JSONObject meta = image.optJSONObject("extmetadata");
            String license = metadata(meta, "LicenseShortName");
            String licenseUrl = metadata(meta, "LicenseUrl");
            String artist = metadata(meta, "Artist");
            String page = image.optString("descriptionurl");
            boolean publicDomain = license.equals("Public domain") || license.equals("CC0") || license.equals("CC0 1.0");
            boolean creativeCommons = license.startsWith("CC BY ") || license.startsWith("CC BY-SA ");
            if (!page.startsWith("https://commons.wikimedia.org/")
                    || (!publicDomain && !(creativeCommons && !artist.isEmpty() && !licenseUrl.isEmpty()))) continue;
            String title = pages.getJSONObject(i).optString("title").replaceFirst("^File:", "");
            String credits = title + "\n" + (artist.isEmpty() ? "" : artist + "\n") + license
                    + (licenseUrl.isEmpty() ? "" : "\n" + licenseUrl) + "\n" + page;
            photos.add(new Photo(url, credits));
        }
        return photos;
    }

    private static String metadata(JSONObject metadata, String key) {
        JSONObject field = metadata == null ? null : metadata.optJSONObject(key);
        if (field == null) return "";
        return Html.fromHtml(field.optString("value"), Html.FROM_HTML_MODE_COMPACT).toString().trim();
    }
}
