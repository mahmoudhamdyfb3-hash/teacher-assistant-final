# Teacher Assistant 1.32.8

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
- Egypt uses ZADX; every other selected country uses the configured Akedly OTP route.
- Phone-code verification accepts the provider's 4 to 8 digit OTP length.
- Fixed a verification request issue in the Cloudflare Worker.

## Publish both parts

1. Replace the Cloudflare Worker code with `cloudflare-otp/src/index.js` and deploy it.
2. Build and publish the Android app from this release. Its version is `1.32.8` (`versionCode 126`).

Do not put the Akedly API key, Firebase service-account JSON, or any other secret into the Android project.
