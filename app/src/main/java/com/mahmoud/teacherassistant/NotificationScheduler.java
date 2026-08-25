package com.mahmoud.teacherassistant;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

public final class NotificationScheduler {
    public static final String CHANNEL_ID = "lesson_reminders";
    public static final String PREFS = "lesson_notifications";
    public static final String KEY_LESSONS = "lessons_json";
    public static final String KEY_MINUTES = "notify_minutes";

    private NotificationScheduler() {}

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "تنبيهات الحصص",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("تذكير قبل موعد الحصة");
            channel.enableVibration(true);
            nm.createNotificationChannel(channel);
        }
    }

    public static void saveAndSchedule(Context context, String lessonsJson, int minutes) {
        // Cancel alarms belonging to the previous lesson set before replacing it.
        cancelAll(context);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LESSONS, lessonsJson == null ? "[]" : lessonsJson)
                .putInt(KEY_MINUTES, Math.max(1, minutes))
                .apply();
        scheduleStoredLessons(context);
    }

    public static void scheduleAll(Context context) {
        ensureChannel(context);
        cancelAll(context);
        scheduleStoredLessons(context);
    }

    private static void scheduleStoredLessons(Context context) {
        ensureChannel(context);
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LESSONS, "[]");
        int minutes = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MINUTES, 15);
        try {
            JSONArray lessons = new JSONArray(raw);
            for (int i = 0; i < lessons.length(); i++) {
                JSONObject lesson = lessons.optJSONObject(i);
                if (lesson == null) continue;
                scheduleLesson(context, lesson, minutes);
            }
        } catch (Exception ignored) {
            // Keep the app usable even if old/corrupt local lesson data exists.
        }
    }

    public static void cancelAll(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        // Cancel a generous stable-id range derived from stored lessons.
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LESSONS, "[]");
        Set<Integer> ids = new HashSet<>();
        try {
            JSONArray lessons = new JSONArray(raw);
            for (int i = 0; i < lessons.length(); i++) {
                JSONObject lesson = lessons.optJSONObject(i);
                if (lesson == null) continue;
                String id = lesson.optString("id", String.valueOf(i));
                ids.add(stableId(id));
            }
        } catch (Exception ignored) {}
        for (Integer id : ids) {
            PendingIntent pi = PendingIntent.getBroadcast(
                    context,
                    id,
                    new Intent(context, LessonAlarmReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | mutableFlag()
            );
            am.cancel(pi);
        }
    }

    private static void scheduleLesson(Context context, JSONObject lesson, int minutes) {
        JSONArray days = lesson.optJSONArray("days");
        String time = lesson.optString("time", "17:00");
        if (days == null || days.length() == 0 || time.length() < 4) return;

        int[] hm = parseTime(time);
        if (hm == null) return;
        Calendar trigger = nextTrigger(days, hm[0], hm[1], minutes);
        if (trigger == null) return;

        String lessonId = lesson.optString("id", String.valueOf(lesson.hashCode()));
        int requestCode = stableId(lessonId);
        Intent intent = new Intent(context, LessonAlarmReceiver.class);
        intent.putExtra(LessonAlarmReceiver.EXTRA_LESSON_ID, lessonId);
        intent.putExtra(LessonAlarmReceiver.EXTRA_GROUP, lesson.optString("group", ""));
        intent.putExtra(LessonAlarmReceiver.EXTRA_PLACE, lesson.optString("place", ""));
        intent.putExtra(LessonAlarmReceiver.EXTRA_TIME, time);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | mutableFlag()
        );
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long when = trigger.getTimeInMillis();
        if (when <= System.currentTimeMillis()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    private static Calendar nextTrigger(JSONArray days, int hour, int minute, int notifyMinutes) {
        Calendar now = Calendar.getInstance();
        for (int offset = 0; offset <= 8; offset++) {
            Calendar day = (Calendar) now.clone();
            day.add(Calendar.DAY_OF_YEAR, offset);
            day.set(Calendar.HOUR_OF_DAY, hour);
            day.set(Calendar.MINUTE, minute);
            day.set(Calendar.SECOND, 0);
            day.set(Calendar.MILLISECOND, 0);
            if (day.getTimeInMillis() <= now.getTimeInMillis()) continue;
            String ar = arabicDay(day.get(Calendar.DAY_OF_WEEK));
            if (containsDay(days, ar)) {
                day.add(Calendar.MINUTE, -Math.max(1, notifyMinutes));
                if (day.getTimeInMillis() > now.getTimeInMillis()) return day;
            }
        }
        return null;
    }

    private static boolean containsDay(JSONArray days, String day) {
        for (int i = 0; i < days.length(); i++) {
            if (day.equals(days.optString(i))) return true;
        }
        return false;
    }

    private static String arabicDay(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.SUNDAY: return "الأحد";
            case Calendar.MONDAY: return "الاثنين";
            case Calendar.TUESDAY: return "الثلاثاء";
            case Calendar.WEDNESDAY: return "الأربعاء";
            case Calendar.THURSDAY: return "الخميس";
            case Calendar.FRIDAY: return "الجمعة";
            default: return "السبت";
        }
    }

    private static int[] parseTime(String value) {
        try {
            String[] parts = value.trim().split(":");
            if (parts.length != 2) return null;
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return new int[]{h, m};
        } catch (Exception e) {
            return null;
        }
    }

    static int stableId(String value) {
        int hash = value == null ? 0 : value.hashCode();
        hash = hash & 0x7fffffff;
        return hash == 0 ? 1 : hash;
    }

    static int mutableFlag() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
    }

    public static boolean canSchedule(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return am == null || am.canScheduleExactAlarms();
    }
}
