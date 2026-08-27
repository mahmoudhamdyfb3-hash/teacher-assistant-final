package com.mahmoud.teacherassistant;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class LessonAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "TeacherAssistantNotif";

    public static final String EXTRA_LESSON_ID = "lesson_id";
    public static final String EXTRA_GROUP = "group";
    public static final String EXTRA_PLACE = "place";
    public static final String EXTRA_TIME = "time";

    @Override
    public void onReceive(
            Context context,
            Intent intent
    ) {
        NotificationScheduler.ensureChannel(context);

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {

            Log.w(
                    TAG,
                    "Notification permission missing"
            );
            return;
        }

        String group =
                intent.getStringExtra(EXTRA_GROUP);

        String place =
                intent.getStringExtra(EXTRA_PLACE);

        String time =
                intent.getStringExtra(EXTRA_TIME);

        String lessonId =
                intent.getStringExtra(EXTRA_LESSON_ID);

        String title =
                "⏰ تذكير بالحصة";

        StringBuilder body =
                new StringBuilder(
                        "الحصة تبدأ قريبًا"
                );

        if (group != null &&
                !group.trim().isEmpty()) {
            body.append(" • مجموعة ")
                    .append(group);
        }

        if (time != null &&
                !time.trim().isEmpty()) {
            body.append(" • الساعة ")
                    .append(time);
        }

        if (place != null &&
                !place.trim().isEmpty()) {
            body.append(" • ")
                    .append(place);
        }

        // Use a notification-safe launcher icon.
        int smallIcon =
                R.mipmap.ic_teacher_assistant;

        Notification notification =
                new NotificationCompat.Builder(
                        context,
                        NotificationScheduler.CHANNEL_ID
                )
                .setSmallIcon(smallIcon)
                .setContentTitle(title)
                .setContentText(body.toString())
                .setStyle(
                        new NotificationCompat.BigTextStyle()
                                .bigText(body.toString())
                )
                .setPriority(
                        NotificationCompat.PRIORITY_HIGH
                )
                .setAutoCancel(true)
                .setCategory(
                        NotificationCompat.CATEGORY_REMINDER
                )
                .setDefaults(
                        Notification.DEFAULT_ALL
                )
                .build();

        NotificationManager nm =
                context.getSystemService(
                        NotificationManager.class
                );

        if (nm != null) {
            int id =
                    NotificationScheduler.stableId(
                            lessonId == null
                                    ? "lesson"
                                    : lessonId
                    );

            nm.notify(id, notification);

            Log.d(
                    TAG,
                    "Notification shown id=" + id
            );
        }

        // Re-schedule the next weekly occurrence.
        try {
            NotificationScheduler.scheduleAll(context);
        } catch (Exception e) {
            Log.e(
                    TAG,
                    "Failed to schedule next occurrence",
                    e
            );
        }
    }
}
