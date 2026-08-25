# مساعد المعلم الذكي — Android

نسخة Android نظيفة وجاهزة للرفع إلى مستودع GitHub جديد.

تتضمن:
- WebView لواجهة التطبيق داخل `app/src/main/assets/www`.
- اللوجو الجديد كأيقونة Android باستخدام `ic_teacher_assistant`.
- إشعارات الحصص عبر Android native.
- AdMob App ID + Banner Ad Unit ID في إعدادات التطبيق.
- اختبار Banner على نسخة Debug، والإعلان الحقيقي على Release.
- Workflow لبناء AAB موقّع عبر GitHub Actions.

## متطلبات GitHub Actions
أنشئ هذه الأسرار في المستودع الجديد:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
