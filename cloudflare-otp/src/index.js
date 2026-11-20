/*
 * Teacher Assistant phone-auth worker.
 * Egypt (+20) uses ZADX. All other countries use the Akedly Headless OTP
 * integration. Credentials are Worker secrets; this browser-facing API never
 * returns provider keys or OTP values.
 */

const encoder = new TextEncoder();
const decoder = new TextDecoder();
const jsonHeaders = { "content-type": "application/json; charset=utf-8" };

function json(value, status = 200) { return new Response(JSON.stringify(value), { status, headers: jsonHeaders }); }
function base64url(bytes) { const array = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes); let binary = ""; for (const byte of array) binary += String.fromCharCode(byte); return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, ""); }
function bytesFromBase64url(value) { const input = String(value); const padded = input.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((input.length + 3) % 4); return Uint8Array.from(atob(padded), character => character.charCodeAt(0)); }
async function sha256(value) { return base64url(await crypto.subtle.digest("SHA-256", encoder.encode(String(value)))); }
async function hmac(secret, value) { const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]); return base64url(await crypto.subtle.sign("HMAC", key, encoder.encode(value))); }
function randomId() { const bytes = new Uint8Array(24); crypto.getRandomValues(bytes); return base64url(bytes); }

function normalizePhone(value) {
  let phone = String(value || "").replace(/[\s()\-]/g, "");
  if (/^01\d{9}$/.test(phone)) phone = "+20" + phone.slice(1);
  else if (/^20\d{10}$/.test(phone)) phone = "+" + phone;
  else if (!phone.startsWith("+") && /^\d{7,15}$/.test(phone)) phone = "+" + phone;
  if (!/^\+[1-9]\d{7,14}$/.test(phone) || /(\d)\1{7,}/.test(phone)) return "";
  return phone;
}
function isEgypt(phone) { return phone.startsWith("+20") && /^\+201\d{9}$/.test(phone); }
function cors(response) { const headers = new Headers(response.headers); headers.set("access-control-allow-origin", "*"); headers.set("access-control-allow-methods", "POST, OPTIONS"); headers.set("access-control-allow-headers", "content-type"); return new Response(response.body, { status: response.status, headers }); }
async function save(env, key, value, ttl) { await env.OTP_STORE.put(key, JSON.stringify(value), { expirationTtl: ttl }); }
async function read(env, key) { return (await env.OTP_STORE.get(key, "json")) || null; }

async function makeSession(env, uid) { const payload = base64url(encoder.encode(JSON.stringify({ uid, exp: Date.now() + 30 * 24 * 60 * 60 * 1000 }))); return `${payload}.${await hmac(env.OTP_PEPPER, `account-session:${payload}`)}`; }
async function readSession(env, value) {
  const parts = String(value || "").split("."); if (parts.length !== 2 || !parts[0] || !parts[1] || (await hmac(env.OTP_PEPPER, `account-session:${parts[0]}`)) !== parts[1]) return null;
  try { const payload = JSON.parse(decoder.decode(bytesFromBase64url(parts[0]))); return payload && /^phone_[A-Za-z0-9]{10,80}$/.test(payload.uid) && Number(payload.exp) > Date.now() ? payload : null; } catch (_) { return null; }
}
function pemBytes(pem) { const binary = atob(String(pem).replace(/-----(BEGIN|END) PRIVATE KEY-----/g, "").replace(/\s/g, "")); return Uint8Array.from(binary, character => character.charCodeAt(0)); }
async function createFirebaseCustomToken(phone, uid, serviceAccountJson) {
  const account = JSON.parse(serviceAccountJson); if (!account.client_email || !account.private_key) throw new Error("Firebase غير مُعد في خادم التحقق.");
  const now = Math.floor(Date.now() / 1000);
  const header = base64url(encoder.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const payload = base64url(encoder.encode(JSON.stringify({ iss: account.client_email, sub: account.client_email, aud: "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit", iat: now, exp: now + 3600, uid })));
  const privateKey = await crypto.subtle.importKey("pkcs8", pemBytes(account.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const input = `${header}.${payload}`; const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", privateKey, encoder.encode(input)); return `${input}.${base64url(signature)}`;
}
async function rateLimit(env, phone) {
  const phoneHash = await sha256(phone), day = new Date().toISOString().slice(0, 10), key = `rate:${phoneHash}:${day}`, current = (await read(env, key)) || { count: 0, lastSentAt: 0 }, now = Date.now();
  if (now - Number(current.lastSentAt || 0) < 60 * 1000) throw new Error("انتظر دقيقة قبل طلب كود جديد."); if (Number(current.count || 0) >= 5) throw new Error("تم الوصول للحد اليومي لهذا الرقم. حاول غدًا.");
  await save(env, key, { count: Number(current.count || 0) + 1, lastSentAt: now }, 48 * 60 * 60);
}
async function sendZadx(env, phone, code, id) {
  const base = String(env.ZADX_BASE_URL || "").replace(/\/$/, ""); if (!base || !env.ZADX_API_KEY || !env.ZADX_API_SECRET) throw new Error("خدمة الرسائل المصرية غير مُعدة بعد.");
  const payload = { to: "0" + phone.slice(3), otp: code, locale: "ar" }; if (env.ZADX_SENDER_ID) payload.sender_id = env.ZADX_SENDER_ID;
  const response = await fetch(`${base}/otp/send`, { method: "POST", headers: { "content-type": "application/json", "x-api-key": env.ZADX_API_KEY, "x-api-secret": env.ZADX_API_SECRET, "idempotency-key": id }, body: JSON.stringify(payload) });
  if (!response.ok) throw new Error("تعذر إرسال الكود. حاول مرة أخرى.");
}
async function akedlyRequest(path, init = {}) { const response = await fetch(`https://api.akedly.io/api/v1.2/transactions${path}`, init), body = await response.json().catch(() => ({})); if (!response.ok || body.status === "error") throw new Error(body.message || body.error || "تعذر إرسال كود التحقق الدولي."); return body.data || body; }
async function solveAkedlyPow(challenge, difficulty) {
  const zeros = Math.max(1, Math.min(5, Number(difficulty) || 2));
  for (let nonce = 0; nonce < 2000000; nonce += 1) {
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(`${challenge}:${nonce}`)));
    let leading = 0;
    for (const byte of digest) {
      if (byte === 0) { leading += 2; continue; }
      if (byte < 16) leading += 1;
      break;
    }
    if (leading >= zeros) return nonce;
  }
  throw new Error("تعذر تجهيز حماية التحقق. حاول مرة أخرى.");
}
async function sendAkedly(env, request, phone, id) {
  if (!env.AKEDLY_API_KEY || !env.AKEDLY_PIPELINE_ID) throw new Error("خدمة التحقق الدولي غير مُعدة بعد.");
  const query = new URLSearchParams({ APIKey: env.AKEDLY_API_KEY, pipelineID: env.AKEDLY_PIPELINE_ID }), challenge = await akedlyRequest(`/challenge?${query.toString()}`);
  if (challenge.turnstile && challenge.turnstile.required) throw new Error("أوقف Cloudflare Turnstile من إعدادات Akedly قبل التشغيل.");
  const powSolution = challenge.challengeRequired ? { challengeToken: challenge.challengeToken, nonce: await solveAkedlyPow(challenge.challenge, challenge.difficulty) } : undefined;
  const ip = request.headers.get("CF-Connecting-IP") || request.headers.get("x-forwarded-for") || "0.0.0.0";
  const transaction = await akedlyRequest("/send", { method: "POST", headers: { "content-type": "application/json", "x-end-user-ip": ip }, body: JSON.stringify({ APIKey: env.AKEDLY_API_KEY, pipelineID: env.AKEDLY_PIPELINE_ID, verificationAddress: { phoneNumber: phone }, ...(powSolution ? { powSolution } : {}) }) });
  const transactionReqID = transaction.transactionReqID || transaction.transactionId || transaction.id; if (!transactionReqID) throw new Error("لم تُرجع خدمة التحقق رقم العملية."); return { id, provider: "akedly", transactionReqID, phone, createdAt: Date.now() };
}
async function begin(env, request, body) {
  const phone = normalizePhone(body.phone); if (!phone) throw new Error("اكتب رقم هاتف صحيحًا مع كود الدولة."); let uid = null;
  if (body.session) { const session = await readSession(env, body.session); if (!session) throw new Error("انتهت جلسة الحساب. سجّل الدخول مرة أخرى."); uid = session.uid; }
  await rateLimit(env, phone); const id = randomId();
  const phoneId = await sha256(phone), ip = await sha256(request.headers.get("CF-Connecting-IP") || "");
  if (isEgypt(phone)) { const code = String(Math.floor(100000 + Math.random() * 900000)); await sendZadx(env, phone, code, id); await save(env, `challenge:${id}`, { id, provider: "zadx", phone, phoneId, accountUid: uid, hash: await hmac(env.OTP_PEPPER, `${id}:${code}`), attempts: 0, ip }, 300); }
  else { const record = await sendAkedly(env, request, phone, id); record.phoneId = phoneId; record.accountUid = uid; record.attempts = 0; record.ip = ip; await save(env, `challenge:${id}`, record, 300); }
  return { challengeId: id, expiresIn: 300 };
}
async function complete(env, request, body) {
  const id = String(body.challengeId || ""), code = String(body.code || "").trim(); if (!/^[A-Za-z0-9_-]{20,}$/.test(id) || !/^\d{4,8}$/.test(code)) throw new Error("بيانات التحقق غير صحيحة.");
  const record = await read(env, `challenge:${id}`); if (!record) throw new Error("انتهت صلاحية الكود. اطلب كودًا جديدًا."); if (record.ip && record.ip !== await sha256(request.headers.get("CF-Connecting-IP") || "")) throw new Error("لا يمكن تأكيد الكود من جهاز اتصال مختلف."); if (Number(record.attempts || 0) >= 5) { await env.OTP_STORE.delete(`challenge:${id}`); throw new Error("تم تجاوز عدد المحاولات. اطلب كودًا جديدًا."); }
  if (record.provider === "zadx") { if ((await hmac(env.OTP_PEPPER, `${id}:${code}`)) !== record.hash) { record.attempts = Number(record.attempts || 0) + 1; await save(env, `challenge:${id}`, record, 300); throw new Error("الكود غير صحيح."); } }
  else if (record.provider === "akedly") { const result = await akedlyRequest("/verify", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ transactionReqID: record.transactionReqID, otp: code }) }); if (result.approved === false || result.verified === false) throw new Error("الكود غير صحيح."); }
  else throw new Error("تعذر معرفة خدمة التحقق.");
  await env.OTP_STORE.delete(`challenge:${id}`); const storedUid = await env.OTP_STORE.get(`phone:${record.phoneId}`), uid = record.accountUid || storedUid || `phone_${(await sha256(record.phone)).replace(/[^a-zA-Z0-9]/g, "").slice(0, 40)}`, previous = await read(env, `account:${uid}`);
  if (record.accountUid && previous && previous.phoneId && previous.phoneId !== record.phoneId) await env.OTP_STORE.delete(`phone:${previous.phoneId}`);
  await Promise.all([env.OTP_STORE.put(`phone:${record.phoneId}`, uid), save(env, `account:${uid}`, { phoneId: record.phoneId }, 60 * 60 * 24 * 365 * 5)]);
  return { phone: record.phone, uid, session: await makeSession(env, uid), customToken: await createFirebaseCustomToken(record.phone, uid, env.FIREBASE_SERVICE_ACCOUNT_JSON) };
}
export default { async fetch(request, env) {
  if (request.method === "OPTIONS") return cors(new Response(null, { status: 204 })); if (request.method !== "POST") return cors(json({ error: "POST only" }, 405));
  try { const body = await request.json(), action = String(body.action || ""); if (action === "start") return cors(json(await begin(env, request, body))); if (action === "verify") return cors(json(await complete(env, request, body))); return cors(json({ error: "طلب غير معروف." }, 400)); }
  catch (error) { return cors(json({ error: error && error.message ? error.message : "تعذر إتمام الطلب." }, 400)); }
} };
