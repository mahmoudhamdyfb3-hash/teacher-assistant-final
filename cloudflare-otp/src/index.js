const encoder = new TextEncoder();

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...corsHeaders() }
  });
}

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Allow-Methods": "POST, OPTIONS"
  };
}

function normalizeEgyptPhone(value) {
  const phone = String(value || "").replace(/[\s()-]/g, "");
  if (/^01\d{9}$/.test(phone)) return "+20" + phone.slice(1);
  if (/^201\d{9}$/.test(phone)) return "+" + phone;
  if (/^\+201\d{9}$/.test(phone)) return phone;
  return "";
}

function localNumber(phone) {
  return "0" + phone.slice(3);
}

function base64url(value) {
  const bytes = value instanceof Uint8Array ? value : encoder.encode(value);
  let binary = "";
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function randomId() {
  const bytes = new Uint8Array(24);
  crypto.getRandomValues(bytes);
  return base64url(bytes);
}

async function sha256(value) {
  return base64url(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value))));
}

async function hmac(value, pepper) {
  const key = await crypto.subtle.importKey("raw", encoder.encode(pepper), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return base64url(new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(value))));
}

function sameValue(left, right) {
  if (left.length !== right.length) return false;
  let mismatch = 0;
  for (let index = 0; index < left.length; index += 1) mismatch |= left.charCodeAt(index) ^ right.charCodeAt(index);
  return mismatch === 0;
}

function pemToBytes(pem) {
  const raw = String(pem || "").replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const binary = atob(raw);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

async function createFirebaseCustomToken(phone, serviceAccountJson) {
  const account = JSON.parse(serviceAccountJson);
  if (!account.client_email || !account.private_key) throw new Error("بيانات Firebase في Cloudflare غير مكتملة.");
  const now = Math.floor(Date.now() / 1000);
  const uid = "phone_" + (await sha256(phone)).replace(/[^a-zA-Z0-9]/g, "").slice(0, 40);
  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const payload = base64url(JSON.stringify({
    iss: account.client_email,
    sub: account.client_email,
    aud: "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit",
    iat: now,
    exp: now + 3600,
    uid
  }));
  const privateKey = await crypto.subtle.importKey(
    "pkcs8",
    pemToBytes(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signed = encoder.encode(`${header}.${payload}`);
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", privateKey, signed));
  return { customToken: `${header}.${payload}.${base64url(signature)}`, uid };
}

async function startOtp(body, request, env) {
  const phone = normalizeEgyptPhone(body.phone);
  if (!phone) return json({ error: "اكتب رقم موبايل مصري صحيح." }, 400);
  if (!env.OTP_STORE || !env.ZADX_API_KEY || !env.ZADX_API_SECRET || !env.OTP_PEPPER) {
    return json({ error: "خدمة تسجيل الهاتف لم تُضبط بعد." }, 503);
  }
  const now = Date.now();
  const phoneId = await sha256(phone);
  const day = new Date().toISOString().slice(0, 10);
  const rateKey = `rate:${phoneId}:${day}`;
  const rate = await env.OTP_STORE.get(rateKey, "json") || { count: 0, lastSentAt: 0 };
  if (now - Number(rate.lastSentAt || 0) < 60 * 1000) return json({ error: "انتظر دقيقة قبل طلب كود جديد." }, 429);
  if (Number(rate.count || 0) >= 5) return json({ error: "تم الوصول للحد اليومي لهذا الرقم. حاول غدًا." }, 429);

  const challengeId = randomId();
  const code = String(100000 + Math.floor(Math.random() * 900000));
  const hash = await hmac(`${challengeId}:${code}`, env.OTP_PEPPER);
  const sms = await fetch(`${String(env.ZADX_BASE_URL || "https://smsapi.zadx.net/api/v1").replace(/\/$/, "")}/otp/send`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Api-Key": env.ZADX_API_KEY,
      "X-Api-Secret": env.ZADX_API_SECRET,
      "Idempotency-Key": challengeId
    },
    body: JSON.stringify({
      to: localNumber(phone),
      otp: code,
      locale: "ar",
      ...(env.ZADX_SENDER_ID ? { sender_id: env.ZADX_SENDER_ID } : {})
    })
  });
  if (!sms.ok) {
    console.error("ZADX OTP failed", sms.status, await sms.text());
    return json({ error: "تعذر إرسال الكود. حاول مرة أخرى." }, 502);
  }
  await Promise.all([
    env.OTP_STORE.put(rateKey, JSON.stringify({ count: Number(rate.count || 0) + 1, lastSentAt: now }), { expirationTtl: 48 * 60 * 60 }),
    env.OTP_STORE.put(`challenge:${challengeId}`, JSON.stringify({ phone, hash, attempts: 0, ip: await sha256(request.headers.get("CF-Connecting-IP") || "") }), { expirationTtl: 5 * 60 })
  ]);
  return json({ challengeId, expiresIn: 300 });
}

async function verifyOtp(body, request, env) {
  const challengeId = String(body.challengeId || "");
  const code = String(body.code || "");
  if (!/^[A-Za-z0-9_-]{20,}$/.test(challengeId) || !/^\d{6}$/.test(code)) return json({ error: "بيانات التحقق غير صحيحة." }, 400);
  if (!env.OTP_STORE || !env.OTP_PEPPER || !env.FIREBASE_SERVICE_ACCOUNT_JSON) return json({ error: "خدمة تسجيل الهاتف لم تُضبط بعد." }, 503);
  const key = `challenge:${challengeId}`;
  const challenge = await env.OTP_STORE.get(key, "json");
  if (!challenge) return json({ error: "انتهت صلاحية الكود. اطلب كودًا جديدًا." }, 400);
  if (challenge.ip && challenge.ip !== await sha256(request.headers.get("CF-Connecting-IP") || "")) return json({ error: "لا يمكن تأكيد الكود من جهاز اتصال مختلف." }, 400);
  if (Number(challenge.attempts || 0) >= 5) { await env.OTP_STORE.delete(key); return json({ error: "تم تجاوز عدد المحاولات. اطلب كودًا جديدًا." }, 429); }
  const expected = await hmac(`${challengeId}:${code}`, env.OTP_PEPPER);
  if (!sameValue(String(challenge.hash || ""), expected)) {
    challenge.attempts = Number(challenge.attempts || 0) + 1;
    await env.OTP_STORE.put(key, JSON.stringify(challenge), { expirationTtl: 5 * 60 });
    return json({ error: "الكود غير صحيح." }, 400);
  }
  await env.OTP_STORE.delete(key);
  const token = await createFirebaseCustomToken(challenge.phone, env.FIREBASE_SERVICE_ACCOUNT_JSON);
  return json(token);
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders() });
    if (request.method !== "POST") return json({ error: "POST only" }, 405);
    try {
      const body = await request.json();
      if (body.action === "start") return startOtp(body, request, env);
      if (body.action === "verify") return verifyOtp(body, request, env);
      return json({ error: "طلب غير معروف." }, 400);
    } catch (error) {
      console.error("phone auth error", error);
      return json({ error: "تعذر إتمام الطلب." }, 400);
    }
  }
};
