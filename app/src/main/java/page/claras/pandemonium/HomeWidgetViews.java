package page.claras.pandemonium;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

final class HomeWidgetViews {
    static final String MAIL = "mail", CALENDAR = "calendar";
    static boolean valid(String kind) { return MAIL.equals(kind) || CALENDAR.equals(kind); }

    static RemoteViews create(Context context, String kind, int id, JSONObject cache) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_home_list);
        views.setTextViewText(R.id.home_widget_title, context.getString(MAIL.equals(kind) ? R.string.mail_widget_title : R.string.work_widget_title));
        Intent adapter = new Intent(context, HomeWidgetListService.class).putExtra("kind", kind)
            .setData(Uri.parse("pandemonium://widget/" + kind + "/" + id));
        views.setRemoteAdapter(R.id.home_widget_list, adapter);
        views.setEmptyView(R.id.home_widget_list, R.id.home_widget_empty);
        JSONObject section = cache.optJSONObject(kind);
        if (CALENDAR.equals(kind) && WorkWeekSource.isCurrent(section, Instant.now())) {
            LocalDate start = LocalDate.parse(section.optString("weekStart"));
            LocalDate end = start.plusDays(6);
            String first = DateTimeFormatter.ofPattern(start.getMonth() == end.getMonth() ? "d" : "d MMM", Locale.getDefault()).format(start);
            String last = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(end);
            views.setTextViewText(R.id.home_widget_title, context.getString(R.string.work_widget_range, first, last));
        }
        boolean signedOut = cache.optBoolean("signedOut", section == null);
        int empty = signedOut ? R.string.widget_sign_in : section == null ? R.string.widget_load : R.string.widget_no_events;
        if (CALENDAR.equals(kind) && !signedOut && !WorkWeekSource.isCurrent(section, Instant.now()))
            empty = R.string.widget_work_unavailable;
        if (MAIL.equals(kind)) empty = signedOut ? R.string.widget_sign_in
            : section != null && section.has("refreshError") ? mailError(section.optString("refreshError"))
            : section == null || !section.optBoolean("paginationComplete") ? R.string.widget_load : R.string.mail_widget_no_mail;
        views.setTextViewText(R.id.home_widget_empty, context.getString(empty));
        long updated = section == null ? 0 : section.optLong("updatedAt");
        boolean stale = updated > 0 && System.currentTimeMillis() - updated > 60 * 60_000L;
        boolean failed = CALENDAR.equals(kind) && section != null && section.optBoolean("refreshFailed");
        views.setViewVisibility(R.id.home_widget_updated, stale || failed ? View.VISIBLE : View.GONE);
        if (stale || failed) views.setTextViewText(R.id.home_widget_updated, updated > 0
            ? context.getString(failed ? R.string.widget_work_saved : R.string.widget_updated,
                DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
                    .withZone(CALENDAR.equals(kind) ? WorkWeekSource.ZONE : ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(updated)))
            : context.getString(R.string.widget_work_unavailable));
        if (MAIL.equals(kind)) {
            views.setViewVisibility(R.id.home_widget_updated, signedOut ? View.GONE : View.VISIBLE);
            views.setTextViewText(R.id.home_widget_updated, mailStatus(context, section));
        }
        Intent open = (MAIL.equals(kind) ? new Intent(context, MailClientActivity.class) : MainActivity.workIntent(context))
            .setData(Uri.parse("pandemonium://widget/" + kind));
        views.setOnClickPendingIntent(R.id.home_widget_title, PendingIntent.getActivity(context, kind.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        views.setOnClickPendingIntent(R.id.home_widget_empty, PendingIntent.getActivity(context, 900,
            MAIL.equals(kind) && signedOut ? new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP) : open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        // Collection fill-in intents carry only an opaque item id; the destination activity is explicit.
        views.setPendingIntentTemplate(R.id.home_widget_list, PendingIntent.getActivity(context, kind.hashCode() + 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
        Intent refresh = new Intent(context, MAIL.equals(kind) ? MailWidgetProvider.class : CalendarWidgetProvider.class)
            .setAction(HomeWidgetProvider.REFRESH).setData(Uri.parse("pandemonium://widget/refresh/" + kind));
        views.setOnClickPendingIntent(R.id.home_widget_refresh, PendingIntent.getBroadcast(context, kind.hashCode(), refresh,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return views;
    }

    static RemoteViews row(Context context, String kind, JSONObject item) {
        RemoteViews views = new RemoteViews(context.getPackageName(), MAIL.equals(kind) ? R.layout.widget_mail_row : R.layout.widget_home_row);
        String title, heading, date, preview, id;
        if (MAIL.equals(kind)) {
            title = item.optString("subject", "");
            if (title.isEmpty()) title = context.getString(R.string.widget_no_subject);
            heading = sender(item);
            date = mailDate(item.optString("date"));
            preview = heading + (item.optString("snippet").isEmpty() ? "" : " — " + item.optString("snippet"));
            id = item.optString("messageRef", item.optString("messageId"));
        } else {
            title = item.optString("title");
            heading = eventDay(item);
            date = item.optBoolean("allDay") ? context.getString(R.string.widget_all_day) : eventTime(item);
            preview = item.optString("location");
            id = item.optString("eventId");
        }
        if (!MAIL.equals(kind)) views.setTextViewText(R.id.home_widget_row_heading, heading);
        views.setTextViewText(R.id.home_widget_row_date, date);
        CharSequence displayedTitle = title;
        if (MAIL.equals(kind) && item.optBoolean("unread")) {
            android.text.SpannableString unread = new android.text.SpannableString(title);
            unread.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            displayedTitle = unread;
        }
        views.setTextViewText(R.id.home_widget_row_title, displayedTitle);
        views.setTextViewText(R.id.home_widget_row_preview, preview);
        views.setViewVisibility(R.id.home_widget_row_preview, preview.isEmpty() ? View.GONE : View.VISIBLE);
        views.setOnClickFillInIntent(R.id.home_widget_row, new Intent().putExtra("itemId", id));
        String readState = MAIL.equals(kind) ? context.getString(item.has("unread")
            ? item.optBoolean("unread") ? R.string.mail_widget_unread : R.string.mail_widget_read : R.string.mail_widget_read_unknown) + ". " : "";
        views.setContentDescription(R.id.home_widget_row, readState + heading + ". " + date + ". " + title);
        return views;
    }

    static JSONArray items(JSONObject cache, String kind) {
        return items(cache, kind, Instant.now());
    }

    static JSONArray items(JSONObject cache, String kind, Instant now) {
        if (MAIL.equals(kind)) return new JSONArray();
        JSONObject section = cache.optJSONObject(kind);
        JSONArray items = section == null ? null : section.optJSONArray("items");
        if (items == null) return new JSONArray();
        return WorkWeekSource.items(section, now);
    }

    private static int mailError(String code) {
        if (code.equals("expired")) return R.string.mail_widget_expired;
        if (code.equals("limit")) return R.string.mail_widget_limit;
        if (code.equals("incomplete")) return R.string.mail_widget_incomplete;
        if (code.equals("cache_error")) return R.string.mail_widget_cache_error;
        return R.string.mail_widget_unavailable;
    }

    static String mailStatus(Context context, JSONObject section) {
        if (section == null) return context.getString(R.string.widget_load);
        JSONObject coverage = section.optJSONObject("coverage");
        if (coverage == null) return context.getString(section.has("refreshError") ? mailError(section.optString("refreshError")) : R.string.widget_load);
        JSONArray connections = coverage.optJSONArray("connections");
        int total = connections == null ? 0 : connections.length(), available = 0;
        for (int i = 0; i < total; i++) {
            JSONObject connection = connections.optJSONObject(i);
            if (connection != null && "available".equals(connection.optString("state"))) available++;
        }
        String result = context.getString(R.string.mail_widget_sources, available, total);
        try {
            String snapshot = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())
                .format(Instant.parse(coverage.getString("to")));
            result += " · " + context.getString(R.string.mail_widget_snapshot, snapshot);
        } catch (Exception ignored) { }
        Instant oldest = MailWindowSource.oldestSync(section);
        result += "\n" + (oldest == null ? context.getString(R.string.mail_widget_freshness_unknown)
            : context.getString(R.string.mail_widget_oldest_sync, DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
                .withZone(ZoneId.systemDefault()).format(oldest)));
        if (section.has("refreshError")) result = context.getString(mailError(section.optString("refreshError"))) + "\n" + result;
        else if (!coverage.optBoolean("complete")) result = context.getString(R.string.mail_widget_partial) + "\n" + result;
        return result;
    }

    static String sender(JSONObject item) {
        JSONArray from = item.optJSONArray("from");
        JSONObject first = from == null ? null : from.optJSONObject(0);
        if (first == null) return "";
        String name = first.optString("name");
        return name.isEmpty() ? first.optString("address") : name;
    }

    static String eventDay(JSONObject item) {
        try { return DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()).format(eventDate(item)); }
        catch (Exception ignored) { return ""; }
    }

    static LocalDate eventDate(JSONObject item) {
        if (item.optBoolean("allDay")) return LocalDate.parse(item.optString("startTime"));
        return Instant.parse(item.optString("startTime")).atZone(zone(item)).toLocalDate();
    }

    static String eventTime(JSONObject item) {
        try { return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
            .format(Instant.parse(item.optString("startTime")).atZone(zone(item))); }
        catch (Exception ignored) { return ""; }
    }

    private static ZoneId zone(JSONObject item) {
        try { return ZoneId.of(item.optString("timezone")); }
        catch (Exception ignored) { return ZoneId.systemDefault(); }
    }

    private static String mailDate(String value) {
        try {
            Instant parsed;
            try { parsed = Instant.parse(value); }
            catch (Exception ignored) { parsed = java.time.ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); }
            java.time.ZonedDateTime date = parsed.atZone(ZoneId.systemDefault());
            return date.toLocalDate().equals(LocalDate.now()) ? DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(date)
                : DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(date);
        } catch (Exception ignored) { return ""; }
    }
}
