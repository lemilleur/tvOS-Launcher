package com.techgenius.tvoslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

/**
 * Current weather and up to five days of forecasts from MET Norway, Open-Meteo and wttr.in.
 * The chosen source is tried first and the others are fallbacks.
 * The location comes from the internet address unless a city is
 * chosen in Settings. Every source is stored in one format, cached, and refreshed on a schedule
 * (every 15 minutes by default).
 */
final class Weather {
    static final String PREFS = "weather";
    static final String K_ENABLED = "enabled", K_UNITS = "units", K_PLACE = "place", K_LAT = "lat", K_LON = "lon",
            K_MANUAL = "manual", K_SOURCE = "source", K_REFRESH = "refresh_min",
            K_CACHE = "cache2", K_CACHE_AT = "cache_at", K_CACHE_KEY = "cache_key";
    private static final String K_ZONE = "timezone", K_AUTO_LOCATION = "auto_location";
    static final String SRC_OPEN_METEO = "openmeteo", SRC_MET = "met", SRC_WTTR = "wttr";
    static final String[] SOURCES = {SRC_MET, SRC_OPEN_METEO, SRC_WTTR};
    static final int[] REFRESH_CHOICES = {10, 15, 30, 60};
    static final String UA = "tvOSLauncher/2.1.6 (https://github.com/lemilleur/tvOS-Launcher)";

    private Weather() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean(K_ENABLED, true);
    }

    /** "c" or "f"; automatic picks Fahrenheit for the US, Liberia and Myanmar. */
    static String units(Context c) {
        String u = prefs(c).getString(K_UNITS, "auto");
        if (!u.equals("auto")) return u;
        String cc = I18n.locale(c).getCountry();
        return cc.equals("US") || cc.equals("LR") || cc.equals("MM") ? "f" : "c";
    }

    static boolean manual(Context c) {
        return prefs(c).getBoolean(K_MANUAL, false);
    }

    static String place(Context c) {
        return prefs(c).getString(K_PLACE, "");
    }

    static String source(Context c) {
        String source = prefs(c).getString(K_SOURCE, SRC_MET);
        for (String supported : SOURCES) if (supported.equals(source)) return source;
        return SRC_MET;
    }

    static String sourceName(String s) {
        return s.equals(SRC_MET) ? "MET Norway" : s.equals(SRC_WTTR) ? "wttr.in" : "Open-Meteo";
    }

    /** Minutes between updates (15 by default). */
    static int refreshMinutes(Context c) {
        return prefs(c).getInt(K_REFRESH, 15);
    }

    /** Settings changed: the next refresh fetches again. */
    static void changed(Context c) {
        prefs(c).edit().remove(K_CACHE_AT).apply();
        refresh(c, true, null);
    }

    // ---------------------------------------------------------------- data

    static final class Hour {
        long time;
        int temp, code;
        boolean day;
        String label;
    }

    static final class Day {
        String date, label;
        int code, max, min, rain = -1;
    }

    static final class Data {
        String place = "", source = SRC_OPEN_METEO, windUnit = "m/s", timezone = "";
        long fetchedAt;
        int temp, feels, code, humidity, wind, max, min;
        boolean day = true;
        final List<Hour> hours = new ArrayList<>();
        final List<Day> days = new ArrayList<>();
        Day tomorrow;

        JSONObject toJson() throws Exception {
            JSONObject j = new JSONObject();
            j.put("place", place).put("source", source).put("windUnit", windUnit).put("fetchedAt", fetchedAt)
                    .put("timezone", timezone)
                    .put("temp", temp).put("feels", feels).put("code", code).put("humidity", humidity)
                    .put("wind", wind).put("day", day);
            JSONArray h = new JSONArray();
            for (Hour x : hours) {
                h.put(new JSONObject().put("t", x.time).put("temp", x.temp).put("code", x.code).put("day", x.day));
            }
            JSONArray d = new JSONArray();
            for (Day x : days) {
                d.put(new JSONObject().put("date", x.date).put("code", x.code).put("max", x.max).put("min", x.min)
                        .put("rain", x.rain));
            }
            return j.put("hours", h).put("days", d);
        }

        static Data fromJson(JSONObject j) throws Exception {
            Data d = new Data();
            d.place = j.optString("place");
            d.source = j.optString("source", SRC_OPEN_METEO);
            d.windUnit = j.optString("windUnit", "km/h");
            d.timezone = j.optString("timezone");
            d.fetchedAt = j.optLong("fetchedAt");
            d.temp = j.getInt("temp");
            d.feels = j.optInt("feels", d.temp);
            d.code = j.getInt("code");
            d.humidity = j.optInt("humidity");
            d.wind = j.optInt("wind");
            d.day = j.optBoolean("day", true);
            JSONArray h = j.getJSONArray("hours");
            for (int i = 0; i < h.length(); i++) {
                JSONObject o = h.getJSONObject(i);
                Hour x = new Hour();
                x.time = o.getLong("t");
                x.temp = o.getInt("temp");
                x.code = o.getInt("code");
                x.day = o.optBoolean("day", true);
                d.hours.add(x);
            }
            JSONArray a = j.getJSONArray("days");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Day x = new Day();
                x.date = o.getString("date");
                x.code = o.getInt("code");
                x.max = o.getInt("max");
                x.min = o.getInt("min");
                x.rain = o.optInt("rain", -1);
                d.days.add(x);
            }
            return d;
        }
    }

    /**
     * The cached forecast ready to show: hours from the current hour ("Now" first), days from
     * today ("Today" first), and today's high/low. Null when nothing has loaded yet.
     */
    static Data cached(Context c) {
        SharedPreferences p = prefs(c);
        if (!enabled(c) || !cacheMatchesLocation(c, p)) return null;
        String raw = p.getString(K_CACHE, null);
        if (raw == null) return null;
        try {
            return present(c, Data.fromJson(new JSONObject(raw)));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean cacheMatchesLocation(Context c, SharedPreferences p) {
        String stored = p.getString(K_CACHE_KEY, "");
        int sourceStart = stored.indexOf('|');
        int locationStart = stored.indexOf('|', sourceStart + 1);
        return sourceStart >= 0 && locationStart > sourceStart
                && stored.substring(0, sourceStart).equals(units(c))
                && stored.substring(locationStart + 1).equals(locationKey(c));
    }

    private static Data present(Context c, Data d) throws Exception {
        TimeZone zone = timeZone(d.timezone);
        Date now = new Date();
        SimpleDateFormat hourFmt = new SimpleDateFormat(
                android.text.format.DateFormat.is24HourFormat(c) ? "HH:mm" : "h a", I18n.locale(c));
        hourFmt.setTimeZone(zone);
        d.hours.removeIf(h -> h.time <= now.getTime());
        for (Hour h : d.hours) {
            h.label = hourFmt.format(new Date(h.time));
        }
        Hour current = new Hour();
        current.time = now.getTime();
        current.label = I18n.t(c, "Now");
        current.temp = d.temp;
        current.code = d.code;
        current.day = d.day;
        d.hours.add(0, current);
        if (d.hours.size() > 6) d.hours.subList(6, d.hours.size()).clear();

        SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        ymd.setTimeZone(zone);
        SimpleDateFormat dayFmt = new SimpleDateFormat("EEE", I18n.locale(c));
        dayFmt.setTimeZone(zone);
        String today = ymd.format(now);
        Calendar next = Calendar.getInstance(zone);
        next.setTime(now);
        next.add(Calendar.DATE, 1);
        String tomorrow = ymd.format(next.getTime());
        d.days.removeIf(day -> day.date.compareTo(today) < 0);
        if (d.days.size() > 5) d.days.subList(5, d.days.size()).clear();
        d.max = d.min = d.temp;
        for (Day x : d.days) {
            if (x.date.equals(today)) {
                x.label = I18n.t(c, "Today");
                d.max = Math.max(x.max, d.temp);
                d.min = Math.min(x.min, d.temp);
            } else {
                x.label = dayFmt.format(ymd.parse(x.date));
            }
            if (x.date.equals(tomorrow)) d.tomorrow = x;
        }
        return d;
    }

    static boolean stale(Context c) {
        SharedPreferences p = prefs(c);
        return System.currentTimeMillis() - p.getLong(K_CACHE_AT, 0) > refreshMinutes(c) * 60000L
                || !key(c).equals(p.getString(K_CACHE_KEY, ""));
    }

    private static String key(Context c) {
        return units(c) + "|" + source(c) + "|" + locationKey(c);
    }

    private static String locationKey(Context c) {
        SharedPreferences p = prefs(c);
        return manual(c) ? p.getFloat(K_LAT, 0) + "," + p.getFloat(K_LON, 0) : "auto";
    }

    /** Rebinds weather shown in a view whenever the shared forecast or its display settings change. */
    static void bind(View view, Runnable update) {
        SharedPreferences p = prefs(view.getContext());
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
                if (K_CACHE.equals(key) || K_ENABLED.equals(key) || K_UNITS.equals(key)
                        || K_MANUAL.equals(key) || K_LAT.equals(key) || K_LON.equals(key)) update.run();
            };

            @Override public void onViewAttachedToWindow(View v) {
                p.registerOnSharedPreferenceChangeListener(listener);
                update.run();
                refresh(v.getContext(), false, update);
            }

            @Override public void onViewDetachedFromWindow(View v) {
                p.unregisterOnSharedPreferenceChangeListener(listener);
            }
        });
        update.run();
    }

    private static boolean busy;
    private static String attemptedKey;
    private static long attemptedAt;
    private static final List<Runnable> refreshCallbacks = new ArrayList<>();

    static String status(Context c) {
        return I18n.t(c, enabled(c) && (busy || !key(c).equals(attemptedKey))
                ? "Getting the weather\u2026" : "Weather unavailable");
    }

    /** Fetches in the background when the cache is due; {@code done} runs on the main thread. */
    static void refresh(Context ctx, boolean force, Runnable done) {
        Context c = ctx.getApplicationContext();
        if (!enabled(c) || (!force && !stale(c))) return;
        String requestKey = key(c);
        if (busy) {
            if (done != null) refreshCallbacks.add(done);
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && requestKey.equals(attemptedKey) && now - attemptedAt < 60_000L) return;
        attemptedKey = requestKey;
        attemptedAt = now;
        if (done != null) refreshCallbacks.add(done);
        busy = true;
        Handler main = new Handler(Looper.getMainLooper());
        SharedPreferences p = prefs(c);
        boolean isManual = manual(c);
        Place selected = isManual ? new Place(p.getString(K_PLACE, ""), p.getFloat(K_LAT, 0),
                p.getFloat(K_LON, 0), p.getString(K_ZONE, "")) : null;
        String previousAutoLocation = p.getString(K_AUTO_LOCATION, "");
        boolean f = units(c).equals("f");
        String preferredSource = source(c);
        new Thread(() -> {
            Place location = selected;
            Data forecast = null;
            try {
                if (!isManual) location = locate(previousAutoLocation);
                List<String> order = new ArrayList<>();
                order.add(preferredSource);
                for (String s : SOURCES) if (!order.contains(s)) order.add(s);
                for (String s : order) {
                    try {
                        if (!s.equals(SRC_OPEN_METEO) && isManual && location.timezone.isEmpty()) {
                            location = withGeocodedTimezone(c, location);
                        }
                        Data d = s.equals(SRC_MET) ? fetchMet(location, f)
                                : s.equals(SRC_WTTR) ? fetchWttr(location, f) : fetchOpenMeteo(location, f);
                        if (d.hours.isEmpty() || d.days.isEmpty()) throw new Exception("Empty forecast");
                        d.source = s;
                        d.place = location.name.isEmpty() ? d.place : location.name;
                        d.fetchedAt = System.currentTimeMillis();
                        forecast = d;
                        break;
                    } catch (Exception ignored) {
                        // try the next source
                    }
                }
            } catch (Exception ignored) {
                // Keep showing the last forecast.
            }
            Data result = forecast;
            Place usedLocation = location;
            main.post(() -> {
                busy = false;
                if (enabled(c) && !requestKey.equals(key(c))) {
                    refresh(c, true, null);
                    return;
                }
                if (result != null && enabled(c)) {
                    try {
                        SharedPreferences.Editor edit = p.edit().putString(K_CACHE, result.toJson().toString())
                                .putLong(K_CACHE_AT, result.fetchedAt).putString(K_CACHE_KEY, requestKey);
                        if (isManual) edit.putString(K_ZONE, result.timezone);
                        else edit.putString(K_PLACE, result.place).putString(K_AUTO_LOCATION,
                                new JSONObject().put("city", result.place).put("latitude", usedLocation.lat)
                                        .put("longitude", usedLocation.lon).put("timezone", result.timezone).toString());
                        edit.apply();
                    } catch (Exception ignored) {
                        // Leave the existing cache in place.
                    }
                }
                List<Runnable> callbacks = new ArrayList<>(refreshCallbacks);
                refreshCallbacks.clear();
                for (Runnable callback : callbacks) callback.run();
            });
        }).start();
    }

    // ---------------------------------------------------------------- sources

    /** Open-Meteo: hourly and daily forecast in the place's own time zone. */
    private static Data fetchOpenMeteo(Place location, boolean f) throws Exception {
        JSONObject j = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + location.lat + "&longitude=" + location.lon
                + "&current=temperature_2m,apparent_temperature,weather_code,is_day,relative_humidity_2m,wind_speed_10m"
                + "&hourly=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=6&forecast_hours=24"
                + (f ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "&wind_speed_unit=ms")));
        Data d = new Data();
        d.windUnit = f ? "mph" : "m/s";
        d.timezone = j.getString("timezone");
        JSONObject cur = j.getJSONObject("current");
        d.temp = round(cur.getDouble("temperature_2m"));
        d.feels = round(cur.getDouble("apparent_temperature"));
        d.code = cur.getInt("weather_code");
        d.day = cur.getInt("is_day") == 1;
        d.humidity = cur.getInt("relative_humidity_2m");
        d.wind = round(cur.getDouble("wind_speed_10m"));
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        iso.setTimeZone(new SimpleTimeZone(j.getInt("utc_offset_seconds") * 1000, d.timezone));
        JSONObject h = j.getJSONObject("hourly");
        JSONArray ht = h.getJSONArray("time");
        for (int i = 0; i < ht.length(); i++) {
            Hour x = new Hour();
            x.time = iso.parse(ht.getString(i)).getTime();
            x.temp = round(h.getJSONArray("temperature_2m").getDouble(i));
            x.code = h.getJSONArray("weather_code").getInt(i);
            x.day = h.getJSONArray("is_day").getInt(i) == 1;
            d.hours.add(x);
        }
        JSONObject dl = j.getJSONObject("daily");
        JSONArray dt = dl.getJSONArray("time"), rain = dl.optJSONArray("precipitation_probability_max");
        for (int i = 0; i < dt.length(); i++) {
            Day x = new Day();
            x.date = dt.getString(i);
            x.code = dl.getJSONArray("weather_code").getInt(i);
            x.max = round(dl.getJSONArray("temperature_2m_max").getDouble(i));
            x.min = round(dl.getJSONArray("temperature_2m_min").getDouble(i));
            x.rain = rain == null || rain.isNull(i) ? -1 : rain.getInt(i);
            d.days.add(x);
        }
        return d;
    }

    /** MET Norway (Yr) Locationforecast: UTC time series in \u00b0C and m/s; days grouped locally. */
    private static Data fetchMet(Place location, boolean f) throws Exception {
        JSONObject j = new JSONObject(getMetForecast(String.format(Locale.US,
                "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=%.4f&lon=%.4f", location.lat, location.lon)));
        JSONArray ts = j.getJSONObject("properties").getJSONArray("timeseries");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Data d = new Data();
        d.windUnit = f ? "mph" : "m/s";
        TimeZone zone = timeZone(location.timezone);
        d.timezone = location.timezone;
        ymd.setTimeZone(zone);
        Calendar cal = Calendar.getInstance(zone);
        long now = System.currentTimeMillis();
        Map<String, Day> days = new LinkedHashMap<>();
        Map<String, Long> noonGap = new LinkedHashMap<>();
        long current = Long.MIN_VALUE;
        for (int i = 0; i < ts.length(); i++) {
            JSONObject e = ts.getJSONObject(i);
            long t = iso.parse(e.getString("time")).getTime();
            JSONObject data = e.getJSONObject("data");
            JSONObject inst = data.getJSONObject("instant").getJSONObject("details");
            double c = inst.getDouble("air_temperature");
            String sym = symbol(data, "next_1_hours");
            if (sym == null) sym = symbol(data, "next_6_hours");
            if ((t <= now && t > current) || (current == Long.MIN_VALUE && t <= now + 3600000L)) {
                double ws = inst.getDouble("wind_speed"), rh = inst.getDouble("relative_humidity");
                d.temp = temp(c, f);
                d.feels = temp(apparent(c, rh, ws), f);
                d.humidity = (int) Math.round(rh);
                d.wind = round(f ? ws * 2.237 : ws);
                d.code = metCode(sym);
                d.day = metDay(sym);
                current = t;
            }
            if (t >= now - 3600000L && t <= now + 24 * 3600000L && data.has("next_1_hours")) {
                Hour x = new Hour();
                x.time = t;
                x.temp = temp(c, f);
                x.code = metCode(sym);
                x.day = metDay(sym);
                d.hours.add(x);
            }
            String date = ymd.format(new Date(t));
            Day day = days.get(date);
            if (day == null) {
                day = new Day();
                day.date = date;
                day.max = Integer.MIN_VALUE;
                day.min = Integer.MAX_VALUE;
                day.code = 3;
                days.put(date, day);
            }
            day.max = Math.max(day.max, temp(c, f));
            day.min = Math.min(day.min, temp(c, f));
            // The day's weather: the symbol nearest midday.
            cal.setTimeInMillis(t);
            long gap = Math.abs(cal.get(Calendar.HOUR_OF_DAY) - 12);
            Long best = noonGap.get(date);
            String daySym = symbol(data, "next_6_hours");
            if (daySym == null) daySym = sym;
            if (daySym != null && (best == null || gap < best)) {
                noonGap.put(date, gap);
                day.code = metCode(daySym);
            }
        }
        if (current < now - 6 * 3600000L) throw new Exception("No current data");
        d.days.addAll(days.values());
        return d;
    }

    /** wttr.in JSON: current conditions and 3 days in 3-hour steps, local time. */
    private static Data fetchWttr(Place location, boolean f) throws Exception {
        JSONObject j = new JSONObject(get(String.format(Locale.US, "https://wttr.in/%.4f,%.4f?format=j1",
                location.lat, location.lon)));
        JSONObject cur = j.getJSONArray("current_condition").getJSONObject(0);
        Data d = new Data();
        d.windUnit = f ? "mph" : "m/s";
        TimeZone zone = timeZone(location.timezone);
        d.timezone = location.timezone;
        d.temp = cur.getInt(f ? "temp_F" : "temp_C");
        d.feels = cur.optInt(f ? "FeelsLikeF" : "FeelsLikeC", d.temp);
        d.humidity = cur.getInt("humidity");
        d.wind = f ? cur.getInt("windspeedMiles") : round(cur.getDouble("windspeedKmph") / 3.6);
        d.code = wwoCode(cur.getInt("weatherCode"));
        try {
            d.place = j.getJSONArray("nearest_area").getJSONObject(0).getJSONArray("areaName").getJSONObject(0).getString("value");
        } catch (Exception ignored) {
            // no area name
        }
        SimpleDateFormat local = new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US);
        local.setTimeZone(zone);
        SimpleDateFormat ymd = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        ymd.setTimeZone(zone);
        Date now = new Date();
        String today = ymd.format(now);
        JSONArray w = j.getJSONArray("weather");
        for (int i = 0; i < w.length(); i++) {
            JSONObject o = w.getJSONObject(i);
            Day x = new Day();
            x.date = o.getString("date");
            JSONArray astronomy = o.optJSONArray("astronomy");
            JSONObject sun = astronomy == null ? null : astronomy.optJSONObject(0);
            long sunrise = sunTime(local, x.date, sun, "sunrise", "0600");
            long sunset = sunTime(local, x.date, sun, "sunset", "1800");
            if (x.date.equals(today)) d.day = now.getTime() >= sunrise && now.getTime() < sunset;
            x.max = o.getInt(f ? "maxtempF" : "maxtempC");
            x.min = o.getInt(f ? "mintempF" : "mintempC");
            JSONArray hr = o.getJSONArray("hourly");
            x.code = 3;
            x.rain = 0;
            for (int k = 0; k < hr.length(); k++) {
                JSONObject h = hr.getJSONObject(k);
                int hhmm = h.getInt("time");
                int code = wwoCode(h.optInt("weatherCode", 116));
                if (hhmm == 1200) x.code = code;
                x.rain = Math.max(x.rain, h.optInt("chanceofrain", 0));
                Hour hour = new Hour();
                hour.time = local.parse(x.date + " " + String.format(Locale.US, "%04d", hhmm)).getTime();
                hour.temp = h.getInt(f ? "tempF" : "tempC");
                hour.code = code;
                hour.day = hour.time >= sunrise && hour.time < sunset;
                d.hours.add(hour);
            }
            d.days.add(x);
        }
        return d;
    }

    private static long sunTime(SimpleDateFormat local, String date, JSONObject sun, String key,
                                String fallback) throws Exception {
        if (sun != null) {
            try {
                SimpleDateFormat clock = new SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.US);
                clock.setTimeZone(local.getTimeZone());
                return clock.parse(date + " " + sun.getString(key)).getTime();
            } catch (Exception ignored) {
                // Some locations have no sunrise or sunset on this date.
            }
        }
        return local.parse(date + " " + fallback).getTime();
    }

    private static boolean metDay(String symbol) {
        return symbol == null || (!symbol.endsWith("_night") && !symbol.endsWith("_polartwilight"));
    }

    private static String symbol(JSONObject data, String period) {
        JSONObject p = data.optJSONObject(period);
        if (p == null || p.optJSONObject("summary") == null) return null;
        return p.optJSONObject("summary").optString("symbol_code", null);
    }

    /** MET Norway symbol \u2192 WMO weather code. */
    static int metCode(String sym) {
        if (sym == null) return 3;
        String s = sym.replace("_day", "").replace("_night", "").replace("_polartwilight", "");
        if (s.contains("thunder")) return 95;
        if (s.contains("snow")) return s.startsWith("heavy") ? 75 : s.startsWith("light") ? 71 : 73;
        if (s.contains("sleet")) return 68;
        if (s.contains("rain")) {
            if (s.contains("showers")) return s.startsWith("heavy") ? 82 : 80;
            return s.startsWith("heavy") ? 65 : s.startsWith("light") ? 61 : 63;
        }
        switch (s) {
            case "clearsky": return 0;
            case "fair": return 1;
            case "partlycloudy": return 2;
            case "fog": return 45;
            default: return 3;
        }
    }

    /** World Weather Online code (used by wttr.in) \u2192 WMO weather code. */
    static int wwoCode(int c) {
        switch (c) {
            case 113: return 0;
            case 116: return 2;
            case 143: case 248: case 260: return 45;
            case 176: case 263: case 266: case 293: case 296: case 353: return 61;
            case 299: case 302: case 356: return 63;
            case 305: case 308: case 359: return 65;
            case 179: case 227: case 323: case 326: case 368: return 71;
            case 329: case 332: case 335: case 371: return 73;
            case 230: case 338: case 392: case 395: return 75;
            case 185: case 281: case 284: return 56;
            case 311: case 314: return 66;
            case 182: case 317: case 320: case 350: case 362: case 365: case 374: case 377: return 68;
            case 200: case 386: case 389: return 95;
            default: return 3;
        }
    }

    /** Apparent temperature (Australian BoM formula), \u00b0C from \u00b0C, % humidity and m/s wind. */
    static double apparent(double t, double rh, double ws) {
        double e = rh / 100 * 6.105 * Math.exp(17.27 * t / (237.7 + t));
        return t + 0.33 * e - 0.70 * ws - 4.00;
    }

    static TimeZone timeZone(String id) {
        if (id == null || id.isEmpty()) return TimeZone.getDefault();
        TimeZone zone = TimeZone.getTimeZone(id);
        return "GMT".equals(zone.getID()) && !"GMT".equals(id) ? TimeZone.getDefault() : zone;
    }

    private static int temp(double celsius, boolean f) {
        return round(f ? celsius * 9 / 5 + 32 : celsius);
    }

    private static int round(double v) {
        return (int) Math.round(v);
    }

    // ---------------------------------------------------------------- location

    /** Approximate location from the internet address, with the last successful lookup as fallback. */
    private static Place locate(String previous) throws Exception {
        String[] services = {"https://ipwho.is/", "https://get.geojs.io/v1/ip/geo.json", "https://ipapi.co/json/"};
        Exception last = null;
        Place withoutZone = null;
        for (String url : services) {
            try {
                Place place = parseLocation(new JSONObject(get(url)));
                if (!place.timezone.isEmpty()) return place;
                if (withoutZone == null) withoutZone = place;
            } catch (Exception e) {
                last = e;
            }
        }
        if (withoutZone != null) return withoutZone;
        if (!previous.isEmpty()) return parseLocation(new JSONObject(previous));
        throw last != null ? last : new Exception("No location");
    }

    private static Place parseLocation(JSONObject j) throws Exception {
        if (j.optBoolean("error") || !j.optBoolean("success", true)) throw new Exception("No location");
        double lat = j.getDouble("latitude"), lon = j.getDouble("longitude");
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw new Exception("Invalid location");
        }
        JSONObject nested = j.optJSONObject("timezone");
        String zone = nested == null ? j.optString("timezone") : nested.optString("id");
        return new Place(j.optString("city"), lat, lon, zone);
    }

    static final class Place {
        final String name, timezone;
        final double lat, lon;
        String detail = "";

        Place(String name, double lat, double lon, String timezone) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
            this.timezone = timezone;
        }
    }

    /** City search for Settings (Open-Meteo geocoding). Call off the main thread. */
    static List<Place> search(Context c, String q) throws Exception {
        String language = I18n.locale(c).getLanguage();
        String name = q.trim();
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=" + language
                + "&name=" + URLEncoder.encode(name, "UTF-8");
        if ("ru".equals(language) && !name.contains(",")) {
            List<Place> russian = searchPlaces(url + "&countryCode=RU");
            if (!russian.isEmpty()) return russian;
        }
        return searchPlaces(url);
    }

    private static Place withGeocodedTimezone(Context c, Place saved) {
        try {
            for (Place match : search(c, saved.name)) {
                if (Math.abs(match.lat - saved.lat) < 0.1 && Math.abs(match.lon - saved.lon) < 0.1) {
                    return new Place(saved.name, saved.lat, saved.lon, match.timezone);
                }
            }
        } catch (Exception ignored) {
            // Continue with the device time zone if geocoding is temporarily unavailable.
        }
        return saved;
    }

    private static List<Place> searchPlaces(String url) throws Exception {
        JSONObject j = new JSONObject(get(url));
        List<Place> out = new ArrayList<>();
        JSONArray a = j.optJSONArray("results");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.getJSONObject(i);
            Place pl = new Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"),
                    r.optString("timezone"));
            StringBuilder d = new StringBuilder();
            for (String k : new String[]{"admin1", "country"}) {
                String v = r.optString(k, "");
                if (!v.isEmpty()) d.append(d.length() > 0 ? ", " : "").append(v);
            }
            pl.detail = d.toString();
            out.add(pl);
        }
        return out;
    }

    static void setPlace(Context c, Place pl) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (pl == null) e.putBoolean(K_MANUAL, false).remove(K_PLACE).remove(K_ZONE);
        else e.putBoolean(K_MANUAL, true).putString(K_PLACE, pl.name)
                .putFloat(K_LAT, (float) pl.lat).putFloat(K_LON, (float) pl.lon).putString(K_ZONE, pl.timezone);
        e.remove(K_CACHE_AT).apply();
    }

    /** WMO weather code -> short description, as Apple Weather words it. */
    static String describe(int code, boolean day) {
        switch (code) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly Sunny" : "Mostly Clear";
            case 2: return "Partly Cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Foggy";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing Drizzle";
            case 61: case 63: case 80: case 81: return "Rain";
            case 65: case 82: return "Heavy Rain";
            case 66: case 67: return "Freezing Rain";
            case 68: return "Sleet";
            case 71: case 73: case 75: case 77: case 85: case 86: return "Snow";
            case 95: case 96: case 99: return "Thunderstorms";
            default: return "Weather unavailable";
        }
    }

    static String describe(Context c, int code, boolean day) {
        return I18n.t(c, describe(code, day));
    }

    private static String metUrl, metResponse, metLastModified;
    private static long metExpires;

    private static String getMetForecast(String url) throws Exception {
        boolean sameLocation = url.equals(metUrl);
        long now = System.currentTimeMillis();
        if (sameLocation && now < metExpires) return metResponse;
        HttpURLConnection con = connect(url);
        if (sameLocation && metLastModified != null) con.setRequestProperty("If-Modified-Since", metLastModified);
        try {
            if (con.getResponseCode() != HttpURLConnection.HTTP_NOT_MODIFIED) {
                metResponse = read(con);
                metUrl = url;
                metLastModified = con.getHeaderField("Last-Modified");
            }
            long expires = con.getHeaderFieldDate("Expires", 0);
            metExpires = expires > now ? expires : now + 3 * 3600000L;
            return metResponse;
        } finally {
            con.disconnect();
        }
    }

    private static HttpURLConnection connect(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(8000);
        con.setReadTimeout(10000);
        con.setRequestProperty("User-Agent", UA);
        return con;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = connect(url);
        try {
            return read(con);
        } finally {
            con.disconnect();
        }
    }

    private static String read(HttpURLConnection con) throws Exception {
        try (InputStream in = con.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    static String formatTemp(Context c, int temp) {
        return temp + "°";
    }

    static String codeText(Context c, int code) {
        return describe(c, code, true);
    }

    static final class WeatherIconView extends View {
        private int code = -1;
        private boolean day = true;

        WeatherIconView(Context c) {
            super(c);
        }

        void setConditions(int code, boolean day) {
            this.code = code;
            this.day = day;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            int size = Math.min(getWidth(), getHeight());
            if (code < 0 || size <= 0) return;
            canvas.drawBitmap(WeatherIcons.get(code, day, size),
                    (getWidth() - size) / 2f, (getHeight() - size) / 2f, null);
        }
    }
}
