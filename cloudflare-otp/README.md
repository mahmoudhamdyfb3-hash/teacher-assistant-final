# Teacher Assistant OTP Worker

This Worker replaces Firebase Cloud Functions. Egyptian numbers receive ZADX OTP messages; all other countries use Akedly (WhatsApp first, then the SMS fallback configured in Akedly). It returns a Firebase custom token after verification.

The application stores the verified phone identity in the Worker. When a teacher changes their phone number from Settings, the new number is verified by OTP and stays attached to the same Firebase account, so their cloud data remains available.

## Cloudflare setup

1. In **Workers & Pages > teacher-assistant-otp > Settings > Bindings**, add a **KV Namespace** binding named `OTP_STORE`. Create a new namespace named `teacher-assistant-otp-codes`.
2. In **Settings > Variables and Secrets**, add the following as **Secrets** (not plain text variables):
   - `ZADX_API_KEY`
   - `ZADX_API_SECRET`
   - `OTP_PEPPER` — a new long random value, at least 32 characters.
   - `FIREBASE_SERVICE_ACCOUNT_JSON` — the complete contents of a newly created Firebase service-account JSON key.
   - `AKEDLY_API_KEY` — the restricted API key created for the Teacher Assistant OTP integration.
3. Add the following as a plain text variable:
   - `AKEDLY_PIPELINE_ID` — the Integration ID of Teacher Assistant OTP.
4. Keep these as plain text variables:
   - `ZADX_BASE_URL` = `https://smsapi.zadx.net/api/v1`
   - `ZADX_SENDER_ID` = `ZADX`
5. In Akedly's integration **Security** tab, keep Proof-of-Work at **Fixed level 2** and leave Cloudflare Turnstile off. The Worker solves the level-2 challenge at the edge and also applies per-number and per-day limits before sending a code.
6. Use **Edit code**, replace the Worker source with `src/index.js`, then Deploy.

Never put any of these secrets in the Android application, Git, or chat.

## Firebase service account

In Firebase console: Project settings > Service accounts > Generate new private key. Upload/download it only on your own computer, open it in a text editor, copy the complete JSON once into the Cloudflare secret field, then securely delete the downloaded key from the computer. If it is ever exposed, delete that key in Google Cloud and create a replacement.
