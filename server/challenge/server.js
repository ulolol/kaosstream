const http = require('http');
const crypto = require('crypto');
const { chromium } = require('playwright');

const port = Number(process.env.CHALLENGE_PORT || 3210);
const sessions = new Map();
let browser;

function json(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) });
  res.end(payload);
}

function body(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', chunk => { data += chunk; if (data.length > 1024 * 1024) reject(new Error('payload too large')); });
    req.on('end', () => { try { resolve(data ? JSON.parse(data) : {}); } catch (error) { reject(error); } });
    req.on('error', reject);
  });
}

function isChallengeText(text) {
  return /(verify you are human|checking your browser|just a moment|cf-chl-|challenge-platform)/i.test(text || '');
}

async function inspect(session) {
  const text = await session.page.locator('body').innerText().catch(() => '');
  session.challenge = isChallengeText(text);
  session.status = session.challenge ? 'pending' : 'ready';
  session.url = session.page.url();
  session.title = await session.page.title().catch(() => '');
}

async function trySolveTurnstile(page) {
  try {
    const frames = page.frames();
    for (const frame of frames) {
      if (frame.url().includes('challenges.cloudflare.com')) {
        // Try clicking the frame element itself
        const frameElement = await frame.frameElement().catch(() => null);
        if (frameElement && await frameElement.isVisible()) {
          console.log('[Turnstile] Clicking Turnstile iframe center...');
          await frameElement.click({ force: true, timeout: 2000 }).catch(() => {});
        }
        
        // Also try clicking inside the frame if checkbox is resolved
        const checkbox = await frame.$('input[type="checkbox"], #challenge-stage, .cb-i').catch(() => null);
        if (checkbox && await checkbox.isVisible()) {
          console.log('[Turnstile] Clicking checkbox inside Turnstile iframe...');
          await checkbox.click({ force: true, timeout: 2000 }).catch(() => {});
        }
      }
    }
  } catch (error) {
    // Ignore error
  }
}

async function start(data) {
  console.log(`Starting session for URL: ${data.url}`);
  const target = new URL(data.url);
  if (!['http:', 'https:'].includes(target.protocol)) throw new Error('Only HTTP(S) challenge URLs are supported');
  if (target.username || target.password || target.hostname === 'localhost' || target.hostname.endsWith('.local') || target.hostname === '0.0.0.0') {
    throw new Error('Private or credential-bearing challenge URLs are not allowed');
  }
  
  const defaultUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
  const context = await browser.newContext({
    userAgent: data.userAgent || defaultUA,
    viewport: { width: 1280, height: 1024 },
    deviceScaleFactor: 1,
    hasTouch: false,
    isMobile: false
  });
  
  // Hide webdriver
  await context.addInitScript(() => {
    Object.defineProperty(navigator, 'webdriver', {
      get: () => undefined
    });
  });

  const page = await context.newPage();
  const session = {
    id: crypto.randomBytes(18).toString('base64url'),
    context,
    page,
    status: 'pending',
    challenge: true,
    url: target.href,
    title: '',
    userAgent: data.userAgent || await page.evaluate(() => navigator.userAgent)
  };
  
  sessions.set(session.id, session);
  
  // Periodic background solver for Cloudflare Turnstile
  const solveInterval = setInterval(async () => {
    const activeSession = sessions.get(session.id);
    if (!activeSession || activeSession.status === 'ready' || activeSession.status === 'error') {
      clearInterval(solveInterval);
      return;
    }
    await trySolveTurnstile(page);
  }, 2500);

  // Navigate to page
  await page.goto(target.href, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(err => {
    console.error(`Page navigation failed: ${err.message}`);
  });
  
  await inspect(session);
  return publicSession(session);
}

function publicSession(session) {
  return { id: session.id, status: session.status, challenge: session.challenge, url: session.url, title: session.title, userAgent: session.userAgent };
}

async function getSession(id) {
  const session = sessions.get(id);
  if (!session) return null;
  await inspect(session);
  return session;
}

async function main(req, res) {
  const parsed = new URL(req.url, `http://${req.headers.host}`);
  const parts = parsed.pathname.split('/').filter(Boolean);
  console.log(`[${new Date().toISOString()}] ${req.method} ${parsed.pathname}`);
  try {
    if (req.method === 'GET' && parts[0] === 'sessions' && parts.length === 1) {
      return json(res, 200, [...sessions.values()].map(publicSession));
    }
    if (req.method === 'POST' && parts[0] === 'sessions' && parts.length === 1) return json(res, 201, await start(await body(req)));
    const session = parts[0] === 'sessions' ? await getSession(parts[1]) : null;
    if (!session) return json(res, 404, { error: 'session not found' });
    if (req.method === 'GET' && parts.length === 2) return json(res, 200, publicSession(session));
    if (req.method === 'GET' && parts[2] === 'screenshot') {
      const image = await session.page.screenshot({ type: 'png' });
      res.writeHead(200, { 'content-type': 'image/png', 'cache-control': 'no-store' });
      return res.end(image);
    }
    if (req.method === 'POST' && parts[2] === 'click') {
      const action = await body(req);
      await session.page.mouse.click(Number(action.x), Number(action.y));
      await session.page.waitForTimeout(500);
      return json(res, 200, publicSession(session));
    }
    if (req.method === 'POST' && parts[2] === 'type') {
      const action = await body(req);
      await session.page.keyboard.type(String(action.text || ''));
      return json(res, 200, publicSession(session));
    }
    if (req.method === 'POST' && parts[2] === 'complete') {
      await session.page.waitForTimeout(1500);
      await inspect(session);
      return json(res, 200, publicSession(session));
    }
    if (req.method === 'GET' && parts[2] === 'cookies') {
      return json(res, 200, { cookies: await session.context.cookies() });
    }
    return json(res, 404, { error: 'route not found' });
  } catch (error) {
    console.error(`[${new Date().toISOString()}] Error handling ${req.method} ${parsed.pathname}:`, error);
    return json(res, 400, { error: error.message || 'challenge operation failed' });
  }
}

(async () => {
  browser = await chromium.launch({
    headless: false,
    args: [
      '--no-sandbox',
      '--disable-dev-shm-usage',
      '--disable-blink-features=AutomationControlled'
    ]
  });
  http.createServer(main).listen(port, '0.0.0.0', () => console.log(`challenge browser listening on ${port}`));
})();
