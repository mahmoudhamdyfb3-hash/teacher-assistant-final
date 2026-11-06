# Teacher Assistant OTP Worker

This Worker replaces Firebase Cloud Functions. It sends ZADX OTP messages and returns a Firebase custom token after verification.

## Cloudflare setup

1. In **Workers & Pages > teacher-assistant-otp > Settings > Bindings**, add a **KV Namespace** binding named `OTP_STORE`. Create a new namespace named `teacher-assistant-otp-codes`.
2. In **Settings > Variables and Secrets**, add the following as **Secrets** (not plain text variables):
   - `ZADX_API_KEY`
   - `ZADX_API_SECRET`
   - `OTP_PEPPER` — a new long random value, at least 32 characters.
   - `FIREBASE_SERVICE_ACCOUNT_JSON` — the complete contents of a newly created Firebase service-account JSON key.
3. Add these as plain text variables:
   - `ZADX_BASE_URL` = `https://smsapi.zadx.net/api/v1`
   - `ZADX_SENDER_ID` = `ZADX`
4. Use **Edit code**, replace the Hello World source with `src/index.js`, then Deploy.

Never put any of these secrets in the Android application, Git, or chat.

## Firebase service account

In Firebase console: Project settings > Service accounts > Generate new private key. Upload/download it only on your own computer, open it in a text editor, copy the complete JSON once into the Cloudflare secret field, then securely delete the downloaded key from the computer. If it is ever exposed, delete that key in Google Cloud and create a replacement.
