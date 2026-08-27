package com.mahmoud.teacherassistant;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

public final class NotificationScheduler {
    private static final String TAG = "TeacherAssistantNotif";

    public static final String CHANNEL_ID = "lesson_reminders";
    private static final String PREFS = "lesson_notifications";
    private static final String KEY_LESSONS = "lessons_json";
    private static final String KEY_MINUTES = "notify_minutes";

    private NotificationScheduler() {}

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationManager nm =
                context.getSystemService(
                        NotificationManager.class
                );

        if (nm == null) return;

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        "تنبيهات الحصص",
                        NotificationManager.IMPORTANCE_HIGH
                );

        channel.setDescription(
                "تذكير قبل موعد الحصة"
        );
        channel.enableVibration(true);

        nm.createNotificationChannel(channel);
    }

    public static void saveAndSchedule(
            Context context,
            String lessonsJson,
            int minutes
    ) {
        // Cancel the alarms that were previously stored.
        cancelAll(context);

        String safeJson =
                lessonsJson == null ||
                        lessonsJson.trim().isEmpty()
                        ? "[]"
                        : lessonsJson;

        int safeMinutes =
                Math.max(1, minutes);

        context.getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                )
                .edit()
                .putString(
                        KEY_LESSONS,
                        safeJson
                )
                .putInt(
                        KEY_MINUTES,
                        safeMinutes
                )
                .apply();

        scheduleStoredLessons(context);
    }

    public static void scheduleAll(Context context) {
        ensureChannel(context);
        cancelAll(context);
        scheduleStoredLessons(context);
    }

    private static void scheduleStoredLessons(Context context) {
        String raw =
                context.getSharedPreferences(
                                PREFS,
                                Context.MODE_PRIVATE
                        )
                        .getString(
                                KEY_LESSONS,
                                "[]"
                        );

        int minutes =
                context.getSharedPreferences(
                                PREFS,
                                Context.MODE_PRIVATE
                        )
                        .getInt(
                                KEY_MINUTES,
                                15
                        );

        try {
            JSONArray lessons =
                    new JSONArray(raw);

            Log.d(
                    TAG,
                    "scheduleStoredLessons count=" +
                            lessons.length() +
                            ", minutes=" +
                            minutes
            );

            for (int i = 0;
                 i < lessons.length();
                 i++) {

                JSONObject lesson =
                        lessons.optJSONObject(i);

                if (lesson != null) {
                    scheduleLesson(
                            context,
                            lesson,
                            minutes
                    );
                }
            }
        } catch (Exception e) {
            Log.e(
                    TAG,
                    "Invalid lessons JSON",
                    e
            );
        }
    }

    public static void cancelAll(Context context) {
        AlarmManager am =
                (AlarmManager)
                        context.getSystemService(
                                Context.ALARM_SERVICE
                        );

        if (am == null) return;

        String raw =
                context.getSharedPreferences(
                                PREFS,
                                Context.MODE_PRIVATE
                        )
                        .getString(
                                KEY_LESSONS,
                                "[]"
                        );

        Set<Integer> ids =
                new HashSet<>();

        try {
            JSONArray lessons =
                    new JSONArray(raw);

            for (int i = 0;
                 i < lessons.length();
                 i++) {

                JSONObject lesson =
                        lessons.optJSONObject(i);

                if (lesson == null) continue;

                String id =
                        lesson.optString(
                                "id",
                                String.valueOf(i)
                        );

                ids.add(stableId(id));
            }
        } catch (Exception ignored) {
        }

        for (Integer id : ids) {
            PendingIntent pi =
                    PendingIntent.getBroadcast(
                            context,
                            id,
                            new Intent(
                                    context,
                                    LessonAlarmReceiver.class
                            ),
                            PendingIntent.FLAG_UPDATE_CURRENT |
                                    mutableFlag()
                    );

            am.cancel(pi);
        }
    }

    private static void scheduleLesson(
            Context context,
            JSONObject lesson,
            int notifyMinutes
    ) {
        JSONArray days =
                lesson.optJSONArray("days");

        String time =
                lesson.optString(
                        "time",
                        ""
                );

        if (days == null ||
                days.length() == 0 ||
                time.trim().isEmpty()) {
            Log.w(
                    TAG,
                    "Skipping lesson: missing days/time"
            );
            return;
        }

        int[] hm = parseTime(time);

        if (hm == null) {
            Log.w(
                    TAG,
                    "Skipping lesson: invalid time " +
                            time
            );
            return;
        }

        Calendar trigger =
                nextTrigger(
                        days,
                        hm[0],
                        hm[1],
                        notifyMinutes
                );

        if (trigger == null) {
            Log.w(
                    TAG,
                    "No next trigger found for lesson"
            );
            return;
        }

        String lessonId =
                lesson.optString(
                        "id",
                        String.valueOf(
                                lesson.hashCode()
                        )
                );

        int requestCode =
                stableId(lessonId);

        Intent intent =
                new Intent(
                        context,
                        LessonAlarmReceiver.class
                );

        intent.putExtra(
                LessonAlarmReceiver.EXTRA_LESSON_ID,
                lessonId
        );

        intent.putExtra(
                LessonAlarmReceiver.EXTRA_GROUP,
                lesson.optString(
                        "group",
                        ""
                )
        );

        intent.putExtra(
                LessonAlarmReceiver.EXTRA_PLACE,
                lesson.optString(
                        "place",
                        ""
                )
        );

        intent.putExtra(
                LessonAlarmReceiver.EXTRA_TIME,
                time
        );

        PendingIntent pi =
                PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT |
                                mutableFlag()
                );

        AlarmManager am =
                (AlarmManager)
                        context.getSystemService(
                                Context.ALARM_SERVICE
                        );

        if (am == null) return;

        long when =
                trigger.getTimeInMillis();

        if (when <=
                System.currentTimeMillis()) {
            return;
        }

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S) {

            if (!am.canScheduleExactAlarms()) {
                Log.w(
                        TAG,
                        "Exact alarm permission missing"
                );
                return;
            }

            am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    when,
                    pi
            );
        } else if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M) {

            am.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    when,
                    pi
            );
        } else {
            am.set(
                    AlarmManager.RTC_WAKEUP,
                    when,
                    pi
            );
        }

        Log.d(
                TAG,
                "Alarm scheduled id=" +
                        lessonId +
                        " trigger=" +
                        trigger.getTimeInMillis()
        );
    }

    private static Calendar nextTrigger(
            JSONArray days,
            int hour,
            int minute,
            int notifyMinutes
    ) {
        Calendar now =
                Calendar.getInstance();

        for (int offset = 0;
             offset <= 8;
             offset++) {

            Calendar lessonTime =
                    (Calendar) now.clone();

            lessonTime.add(
                    Calendar.DAY_OF_YEAR,
                    offset
            );

            lessonTime.set(
                    Calendar.HOUR_OF_DAY,
                    hour
            );
            lessonTime.set(
                    Calendar.MINUTE,
                    minute
            );
            lessonTime.set(
                    Calendar.SECOND,
                    0
            );
            lessonTime.set(
                    Calendar.MILLISECOND,
                    0
            );

            String day =
                    arabicDay(
                            lessonTime.get(
                                    Calendar.DAY_OF_WEEK
                            )
                    );

            if (!containsDay(days, day)) {
                continue;
            }

            Calendar trigger =
                    (Calendar)
                            lessonTime.clone();

            trigger.add(
                    Calendar.MINUTE,
                    -Math.max(
                            1,
                            notifyMinutes
                    )
            );

            // If the lesson is still in the future but its
            // normal reminder time has already passed, notify
            // shortly from now. This handles "lesson in 5 minutes"
            // with a 15-minute reminder.
            if (trigger.getTimeInMillis() <=
                    now.getTimeInMillis() &&
                    lessonTime.getTimeInMillis() >
                            now.getTimeInMillis()) {

                Calendar immediate =
                        (Calendar) now.clone();

                immediate.add(
                        Calendar.SECOND,
                        3
                );

                return immediate;
            }

            if (trigger.getTimeInMillis() >
                    now.getTimeInMillis()) {

                return trigger;
            }
        }

        return null;
    }

    private static boolean containsDay(
            JSONArray days,
            String day
    ) {
        for (int i = 0;
             i < days.length();
             i++) {

            if (day.equals(
                    days.optString(i)
            )) {
                return true;
            }
        }

        return false;
    }

    private static String arabicDay(
            int dayOfWeek
    ) {
        switch (dayOfWeek) {
            case Calendar.SUNDAY:
                return "الأحد";
            case Calendar.MONDAY:
                return "الاثنين";
            case Calendar.TUESDAY:
                return "الثلاثاء";
            case Calendar.WEDNESDAY:
                return "الأربعاء";
            case Calendar.THURSDAY:
                return "الخميس";
            case Calendar.FRIDAY:
                return "الجمعة";
            default:
                return "السبت";
        }
    }

    private static int[] parseTime(
            String value
    ) {
        try {
            String[] parts =
                    value.trim().split(":");

            if (parts.length != 2) {
                return null;
            }

            int hour =
                    Integer.parseInt(parts[0]);

            int minute =
                    Integer.parseInt(parts[1]);

            if (hour < 0 ||
                    hour > 23 ||
                    minute < 0 ||
                    minute > 59) {
                return null;
            }

            return new int[]{
                    hour,
                    minute
            };
        } catch (Exception e) {
            return null;
        }
    }

    static int stableId(
            String value
    ) {
        int hash =
                value == null
                        ? 0
                        : value.hashCode();

        hash &= 0x7fffffff;

        return hash == 0
                ? 1
                : hash;
    }

    static int mutableFlag() {
        return Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE
                : 0;
    }

    public static boolean canSchedule(
            Context context
    ) {
        if (Build.VERSION.SDK_INT <
                Build.VERSION_CODES.S) {
            return true;
        }

        AlarmManager am =
                (AlarmManager)
                        context.getSystemService(
                                Context.ALARM_SERVICE
                        );

        return am != null &&
                am.canScheduleExactAlarms();
    }
}
