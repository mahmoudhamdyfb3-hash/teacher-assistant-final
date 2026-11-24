# Teacher Assistant 1.34.4

## Included

- International phone entry with a country picker, flag, and calling code.
- Countries and commonly used territories are available in the selector.
- Egyptian numbers can still be entered locally, for example `01012345678`.
- Invalid/too-short/repeated-digit numbers are rejected before an OTP request is sent.
- The verification-code field now appears immediately after the code is sent, and the login phone field keeps its Arabic label above a wider phone-number box.
- A restored local backup is protected from a delayed empty cloud response and is uploaded immediately after restoration.
- A complete cloud snapshot is saved alongside individual records and is used first on a new installation.
- Cloud restore now waits for slower mobile-data connections and never saves an empty state while it is waiting.
- Existing teachers open directly to their current home screen after updating and can activate their phone from a non-blocking in-app card.
- The center is removed from teacher-profile editing; verified phone changes use the same country picker and OTP flow.
- OTP confirmation remains valid when the user changes between Wi-Fi and mobile data after requesting the code.
- Logout now waits for Firebase to confirm that the complete cloud backup was saved before it clears device data.
- Empty or old blank cloud snapshots are ignored. They cannot overwrite teacher data or trigger a repeated home-screen refresh.
- Cloud restore refreshes the interface at most once per app opening, preventing a refresh loop on slower networks.
- Existing students in the older Firebase `students` collection are recovered automatically and migrated into the protected personal cloud snapshot.
- Recovered group records are normalized before opening Groups, Attendance, or the timetable, avoiding a blank/crashed Groups screen.
- When an older student record contains its fee, the recovered group now uses that saved fee instead of a blank amount.
- Account access is now split into a phone-only sign-in screen and a separate account-creation screen for name, subject, and phone verification.
- Fixed the sign-in tab so it remains labeled “تسجيل الدخول” and only the main action button starts authentication.
- Settings now removes the center field entirely and replaces the phone field with a verified country-picker-and-code phone-change flow.
- Existing local teachers must confirm a phone number before they can continue; their local data remains intact and is saved only after verification.
- Teacher name and subject are included in the cloud backup even before students are added.
- The verified-phone change card appears only in Settings, never on the account-creation screen.
- Lessons and attendance history are never fabricated when the older cloud data contains students only.
- Egypt uses ZADX; every other selected country uses the configured Akedly OTP route.
- Phone-code verification accepts the provider's 4 to 8 digit OTP length.
- Fixed a verification request issue in the Cloudflare Worker.

## Publish both parts

1. Replace the Cloudflare Worker code with `cloudflare-otp/src/index.js` and deploy it.
2. Build and publish the Android app from this release. Its version is `1.34.4` (`versionCode 136`).

Do not put the Akedly API key, Firebase service-account JSON, or any other secret into the Android project.
