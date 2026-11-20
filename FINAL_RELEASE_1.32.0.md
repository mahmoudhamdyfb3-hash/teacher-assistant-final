# Teacher Assistant 1.32.0

## Included

- International phone entry with a country picker, flag, and calling code.
- Countries and commonly used territories are available in the selector.
- Egyptian numbers can still be entered locally, for example `01012345678`.
- Invalid/too-short/repeated-digit numbers are rejected before an OTP request is sent.
- Egypt uses ZADX; every other selected country uses the configured Akedly OTP route.
- Phone-code verification accepts the provider's 4 to 8 digit OTP length.
- Fixed a verification request issue in the Cloudflare Worker.

## Publish both parts

1. Replace the Cloudflare Worker code with `cloudflare-otp/src/index.js` and deploy it.
2. Build and publish the Android app from this release. Its version is `1.32.0` (`versionCode 118`).

Do not put the Akedly API key, Firebase service-account JSON, or any other secret into the Android project.
