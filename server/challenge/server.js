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

function isChallengeText(text, title) {
  const combined = (text || '') + ' ' + (title || '');
  return /(verify you are human|checking your browser|just a moment|cf-chl-|challenge-platform|attention required)/i.test(combined);
}

async function inspect(session) {
  const title = await session.page.title().catch(() => '') || '';
  const text = await session.page.locator('body').innerText().catch(() => '') || '';
  
  // If the page is still in transition / completely empty, keep it pending
  if (!text && !title) {
    session.challenge = true;
    session.status = 'pending';
    session.url = session.page.url();
    session.title = 'Loading...';
    return;
  }
  
  session.challenge = isChallengeText(text, title);
  session.status = session.challenge ? 'pending' : 'ready';
  session.url = session.page.url();
  session.title = title;

  // Auto self-cleanup on completion
  if (session.status === 'ready' && !session.cleanupStarted) {
    session.cleanupStarted = true;
    setTimeout(async () => {
      try {
        console.log(`[Session] Cleaning up completed session: ${session.id}`);
        sessions.delete(session.id);
        await session.context.close().catch(() => {});
        const fs = require('fs');
        if (session.profileDir) {
          fs.rmSync(session.profileDir, { recursive: true, force: true });
        }
      } catch (err) {
        console.error(`[Session] Error during cleanup of ${session.id}:`, err);
      }
    }, 60000); // Clean up after 1 minute
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
  const profileDir = `/tmp/playwright-profile-${crypto.randomBytes(8).toString('hex')}`;
  
  console.log(`[Browser] Launching persistent headful context: ${profileDir}`);
  const context = await chromium.launchPersistentContext(profileDir, {
    headless: false,
    ignoreDefaultArgs: ['--enable-automation'],
    args: [
      '--no-sandbox',
      '--disable-dev-shm-usage',
      '--disable-blink-features=AutomationControlled'
    ],
    userAgent: data.userAgent || defaultUA,
    viewport: { width: 1280, height: 1024 },
    deviceScaleFactor: 1,
    hasTouch: false,
    isMobile: false
  });
  
  // Hide webdriver and mimic standard browser attributes
  await context.addInitScript(() => {
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
    Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
    Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
    
    // Override permissions query
    const originalQuery = window.navigator.permissions.query;
    window.navigator.permissions.query = (parameters) => (
      parameters.name === 'notifications' ?
        Promise.resolve({ state: Notification.permission }) :
        originalQuery(parameters)
    );
  });

  const page = context.pages()[0] || await context.newPage();
  const session = {
    id: crypto.randomBytes(18).toString('base64url'),
    context,
    page,
    profileDir,
    status: 'pending',
    challenge: true,
    url: target.href,
    title: '',
    userAgent: data.userAgent || await page.evaluate(() => navigator.userAgent)
  };
  
  sessions.set(session.id, session);
  
  // Fail-safe: Unconditionally clean up the session after 5 minutes to prevent leaks
  setTimeout(async () => {
    try {
      if (sessions.has(session.id)) {
        console.log(`[Session] Expiring session due to 5-minute timeout: ${session.id}`);
        sessions.delete(session.id);
        await session.context.close().catch(() => {});
        const fs = require('fs');
        if (session.profileDir) {
          fs.rmSync(session.profileDir, { recursive: true, force: true });
        }
      }
    } catch (err) {
      console.error(`[Session] Error during timeout cleanup of ${session.id}:`, err);
    }
  }, 300000);

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

// Start HTTP server immediately and launch browser on-demand to avoid Xvfb startup race conditions.
http.createServer(main).listen(port, '0.0.0.0', () => console.log(`challenge browser listening on ${port}`));
