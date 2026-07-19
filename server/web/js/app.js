// Dynamic Router & SPA Handler
import { checkBrowserCapabilities, getPlaybackRoute } from './capability-checker.js';

const API_BASE = '/api/v1';

function escAttr(str) {
  if (!str) return '';
  return str.replace(/'/g, "\\'").replace(/"/g, '&quot;');
}

function proxyImage(url) {
  if (!url) return 'https://via.placeholder.com/300x450';
  if (url.startsWith('http://localhost') || url.startsWith('http://127.0.0.1') || url.startsWith('/') || url.startsWith('data:')) {
    return url;
  }
  return `/api/v1/proxy?url=${encodeURIComponent(url)}`;
}

const state = {
  activeProvider: localStorage.getItem('cs_active_provider') || '',
  providers: [],
  history: JSON.parse(localStorage.getItem('cs_history') || '[]'),
  bookmarks: []
};

// Toast message helper
function showToast(message) {
  const container = document.getElementById('toast-container');
  const toast = document.createElement('div');
  toast.className = 'toast';
  toast.innerText = message;
  container.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    setTimeout(() => toast.remove(), 300);
  }, 3000);
}

// Full-screen loading overlay (blocks UI while fetching sources)
function showLoadingOverlay(message = 'Fetching sources\u2026') {
  document.getElementById('loading-overlay')?.remove();
  const el = document.createElement('div');
  el.id = 'loading-overlay';
  el.innerHTML = `
    <div class="loading-overlay-inner">
      <div class="loading-spinner"></div>
      <p class="loading-overlay-msg">${message}</p>
    </div>
  `;
  document.body.appendChild(el);
}

function hideLoadingOverlay() {
  document.getElementById('loading-overlay')?.remove();
}

/**
 * Fetch full detail metadata for a URL from the backend provider scraper.
 * Results are cached in sessionStorage to avoid redundant requests within the same session.
 * Returns a partial object with: { name, posterUrl, plot, type, year, score } or {} on failure.
 */
async function fetchAndCacheDetailSnapshot(detailUrl, provider) {
  if (!detailUrl || !provider || detailUrl.startsWith('[')) return {};
  const cacheKey = `cs_detail_snap:${detailUrl}`;
  try {
    const cached = sessionStorage.getItem(cacheKey);
    if (cached) return JSON.parse(cached);
  } catch (_) {}
  try {
    const res = await fetch(`${API_BASE}/load?url=${encodeURIComponent(detailUrl)}&provider=${encodeURIComponent(provider)}`);
    if (!res.ok) return {};
    const d = await res.json();
    const snap = {
      name:      d.name      || null,
      posterUrl: d.posterUrl || null,
      plot:      d.plot      || null,
      type:      d.type      || null,
      year:      d.year      || null,
      score:     d.score     || null
    };
    try { sessionStorage.setItem(cacheKey, JSON.stringify(snap)); } catch (_) {}
    return snap;
  } catch (_) {
    return {};
  }
}

// Router map
const routes = {
  '/': renderHome,
  '/search': renderSearch,
  '/bookmarks': renderBookmarks,
  '/history': renderHistory,
  '/plugins': renderPlugins,
  '/detail': renderDetail,
  '/player': renderPlayer,
  '/downloads': renderDownloads
  ,'/challenge': renderChallenge
};

async function init() {
  window.addEventListener('hashchange', handleRoute);
  
  // Load initial providers
  try {
    const res = await fetch(`${API_BASE}/providers`);
    state.providers = await res.json();
    populateProvidersSelect();
  } catch (e) {
    showToast('Failed to connect to backend server.');
  }

  // Load initial bookmarks
  await syncBookmarks();
  
  // Run router
  handleRoute();
  watchChallengeSessions();
}

function showChallengeModal(sessionId) {
  if (document.getElementById('challenge-modal')) return;
  const modal = document.createElement('div');
  modal.id = 'challenge-modal';
  modal.className = 'source-picker-backdrop';
  modal.innerHTML = `
    <section class="source-picker" role="dialog" aria-modal="true" style="max-width: 600px; padding: 24px; background: var(--color-backgroundLevel2); border-radius: 12px; border: 1px solid rgba(255,255,255,0.08);">
      <div class="source-picker-header" style="display:flex; justify-content:space-between; align-items:flex-start;">
        <div>
          <p class="eyebrow" style="color: var(--color-colorPrimary); font-weight: 600; text-transform: uppercase; font-size: 11px; letter-spacing: 0.1em; margin: 0 0 4px 0;">Cloudflare verification required</p>
          <h2 style="margin: 0; font-size: 20px; font-weight: 700; color: #fff;">Complete Browser Challenge</h2>
          <p style="font-size: 13px; color: var(--color-grayTextColor); margin-top: 4px; max-width: 450px;">
            The server encountered a browser challenge. Please click on the Turnstile checkbox below to verify.
          </p>
        </div>
        <button id="challenge-modal-close" style="background: none; border: none; color: #fff; font-size: 28px; cursor: pointer; padding: 0; line-height: 1;">×</button>
      </div>
      <div style="display: flex; flex-direction: column; align-items: center; gap: 16px; margin: 20px 0;">
        <div id="challenge-modal-status" style="font-size: 14px; font-weight: 500; width: 100%; text-align: center; color: var(--color-colorPrimary);">Initializing...</div>
        <div style="position: relative; width: 100%; max-width: 500px; aspect-ratio: 16/9; background: #000; border-radius: 8px; overflow: hidden; border: 1px solid rgba(255,255,255,0.1);">
          <img id="challenge-modal-screenshot" style="display: none; width: 100%; height: 100%; object-fit: contain; cursor: crosshair;" alt="Challenge screen" />
          <div id="challenge-modal-empty" style="display: flex; position: absolute; inset: 0; align-items: center; justify-content: center; color: var(--color-grayTextColor);">
            Loading screenshot...
          </div>
        </div>
      </div>
      <div style="display: flex; gap: 12px; width: 100%;">
        <input id="challenge-modal-text" type="text" class="search-input" placeholder="Type text here..." style="flex: 1;" />
        <button id="challenge-modal-type-btn" class="btn" style="padding: 10px 20px;">Type</button>
        <button id="challenge-modal-complete-btn" class="btn btn-primary" style="padding: 10px 20px;">Check complete</button>
      </div>
    </section>
  `;
  document.body.appendChild(modal);
  
  const status = document.getElementById('challenge-modal-status');
  const screenshot = document.getElementById('challenge-modal-screenshot');
  const empty = document.getElementById('challenge-modal-empty');
  
  let timer = null;
  
  const update = async () => {
    try {
      const response = await fetch(`${API_BASE}/challenges/${sessionId}`);
      if (!response.ok) {
        clearInterval(timer);
        modal.remove();
        return;
      }
      const data = await response.json();
      status.textContent = `${data.status.toUpperCase()}: ${data.title || data.url}`;
      screenshot.src = `${API_BASE}/challenges/${sessionId}/screenshot?t=${Date.now()}`;
      screenshot.style.display = 'block';
      empty.style.display = 'none';
      
      if (data.status === 'ready') {
        clearInterval(timer);
        showToast('Challenge solved successfully!');
        setTimeout(() => modal.remove(), 1500);
      }
    } catch (_) {
      clearInterval(timer);
      modal.remove();
    }
  };
  
  screenshot.addEventListener('click', async (event) => {
    console.log('[Challenge Modal] Click event detected', { clientX: event.clientX, clientY: event.clientY });
    if (!screenshot.naturalWidth) {
      console.warn('[Challenge Modal] Click ignored: screenshot not fully loaded (naturalWidth is 0)');
      return;
    }
    const rect = screenshot.getBoundingClientRect();
    const x = (event.clientX - rect.left) * screenshot.naturalWidth / rect.width;
    const y = (event.clientY - rect.top) * screenshot.naturalHeight / rect.height;
    
    console.log('[Challenge Modal] Sending click to backend', { x, y, naturalWidth: screenshot.naturalWidth, naturalHeight: screenshot.naturalHeight });
    try {
      const res = await fetch(`${API_BASE}/challenges/${sessionId}/click`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ x, y })
      });
      const data = await res.json();
      console.log('[Challenge Modal] Click response received', data);
    } catch (err) {
      console.error('[Challenge Modal] Click request failed', err);
    }
    await update();
  });
  
  document.getElementById('challenge-modal-type-btn').addEventListener('click', async () => {
    const text = document.getElementById('challenge-modal-text').value;
    await fetch(`${API_BASE}/challenges/${sessionId}/type`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text })
    });
    await update();
  });

  document.getElementById('challenge-modal-complete-btn').addEventListener('click', async () => {
    await fetch(`${API_BASE}/challenges/${sessionId}/complete`, { method: 'POST' });
    await update();
  });
  
  document.getElementById('challenge-modal-close').addEventListener('click', () => {
    clearInterval(timer);
    modal.remove();
    sessionStorage.setItem('closed_challenge_' + sessionId, 'true');
  });
  
  update();
  timer = setInterval(update, 2000);
}

function watchChallengeSessions() {
  window.challengePoller = setInterval(async () => {
    try {
      const sessions = await fetch(`${API_BASE}/challenges`).then(response => response.json());
      const pending = sessions.find(session => session.status === 'pending' && !sessionStorage.getItem('closed_challenge_' + session.id));
      if (pending) {
        showChallengeModal(pending.id);
      }
    } catch (_) {
      // Challenge service may be intentionally unavailable.
    }
  }, 2000);
}

function populateProvidersSelect() {
  const select = document.getElementById('active-provider-select');
  select.innerHTML = '<option value="">All Providers</option>';
  state.providers.forEach(p => {
    const opt = document.createElement('option');
    opt.value = p.name;
    opt.innerText = p.name;
    if (p.name === state.activeProvider) {
      opt.selected = true;
    }
    select.appendChild(opt);
  });

  select.addEventListener('change', (e) => {
    state.activeProvider = e.target.value;
    localStorage.setItem('cs_active_provider', state.activeProvider);
    // Reload active page
    handleRoute();
  });
}

async function syncBookmarks() {
  try {
    const res = await fetch(`${API_BASE}/bookmarks`);
    state.bookmarks = await res.json();
  } catch (e) {
    console.error('Failed to sync bookmarks:', e);
  }
}

function handleRoute() {
  const hash = window.location.hash || '#/';
  
  // Clean up any existing Hls instances or timers
  if (window.currentHls) {
    window.currentHls.destroy();
    window.currentHls = null;
  }
  if (window.savePlayerProgress) {
    try { window.savePlayerProgress(); } catch (_) {}
    window.savePlayerProgress = null;
  }
  if (window.playerKeyHandler) {
    document.removeEventListener('keydown', window.playerKeyHandler);
    window.playerKeyHandler = null;
  }

  // Highlight active nav item
  document.querySelectorAll('.nav-item').forEach(item => {
    item.classList.remove('active');
  });

  let routePath = hash.substring(1);
  // Strip query params for routing
  if (routePath.includes('?')) {
    routePath = routePath.split('?')[0];
  }

  const navMap = {
    '/': 'nav-home',
    '/search': 'nav-search',
    '/bookmarks': 'nav-bookmarks',
    '/history': 'nav-history',
    '/plugins': 'nav-plugins',
    '/downloads': 'nav-downloads'
  };

  const activeNavId = navMap[routePath];
  if (activeNavId) {
    document.getElementById(activeNavId)?.classList.add('active');
  }

  const renderFn = routes[routePath] || renderHome;
  
  const titleMap = {
    '/': 'Home',
    '/search': 'Search',
    '/bookmarks': 'Bookmarks',
    '/history': 'History',
    '/plugins': 'Plugins',
    '/detail': 'Details',
    '/player': 'Player',
  '/downloads': 'Downloads'
    ,'/challenge': 'Browser Challenge'
  };
  document.getElementById('page-title').innerText = titleMap[routePath] || 'Ka0SStream';

  renderFn();
}

// Get query parameters from hash
function getQueryParams() {
  const hash = window.location.hash;
  if (!hash.includes('?')) return {};
  const queryStr = hash.split('?')[1];
  const params = {};
  queryStr.split('&').forEach(pair => {
    const [key, val] = pair.split('=');
    params[key] = decodeURIComponent(val);
  });
  return params;
}

// --- View Renders ---

async function renderHome() {
  const outlet = document.getElementById('view-outlet');
  outlet.innerHTML = `
    <div id="resume-container"></div>
    <div id="sections-container"></div>
    <div id="home-spinner" class="loading">Loading home sections...</div>
  `;

  const resumeContainer = document.getElementById('resume-container');
  const sectionsContainer = document.getElementById('sections-container');
  const spinner = document.getElementById('home-spinner');

  let hasResume = false;
  let hasSections = false;

  // 1. Fetch and render watch history (Resume section) immediately
  try {
    const historyRes = await fetch(`${API_BASE}/history`);
    if (historyRes.ok) {
      const historyList = await historyRes.json();
      const resumeItems = historyList.filter(item => {
        if (!item.durationMs || !item.positionMs) return false;
        const pct = item.positionMs / item.durationMs;
        return pct >= 0.01 && pct <= 0.95;
      });

      if (resumeItems.length > 0) {
        hasResume = true;
        resumeContainer.innerHTML = `
          <div class="carousel-section">
            <h3 class="carousel-title">Resume Watching</h3>
            <div class="grid-container" style="display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 16px;">
              ${resumeItems.map(item => {
                const pct = Math.min(100, Math.round((item.positionMs / item.durationMs) * 100));
                const displayTitle = item.title || 'Untitled';
                const label = (item.seasonNum != null && item.episodeNum != null)
                  ? `S${item.seasonNum}E${item.episodeNum}`
                  : 'Movie';
                const detailsUrl = (item.parentId && !item.parentId.startsWith('[')) ? item.parentId : (!item.id.startsWith('[') ? item.id : '');
                const prov = item.provider || '';
                return `
                  <div class="media-card resume-card" style="position: relative;">
                    <img class="card-poster" src="${proxyImage(item.posterUrl)}" alt="${displayTitle}">
                    
                    <!-- Premium watch control overlays -->
                    <div class="resume-card-overlay">
                      <button class="resume-action-btn play-btn" onclick="event.stopPropagation(); playMedia('${escAttr(item.id)}', '${escAttr(prov)}', '${escAttr(displayTitle)}', '${escAttr(item.parentId || '')}', '${escAttr(item.posterUrl || '')}', ${item.seasonNum !== null ? item.seasonNum : 'null'}, ${item.episodeNum !== null ? item.episodeNum : 'null'})" title="Resume Watching">
                        <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
                      </button>
                      <button class="resume-action-btn info-btn" onclick="event.stopPropagation(); if ('${detailsUrl}') window.location.hash = '#/detail?url=${encodeURIComponent(detailsUrl)}&provider=${encodeURIComponent(prov)}'; else showToast('Details URL not available');" title="Show Info">
                        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
                      </button>
                      <button class="resume-action-btn remove-btn" data-remove-id="${btoa(unescape(encodeURIComponent(item.id)))}" title="Remove Watch Progress">
                        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                      </button>
                    </div>
                    
                    <div style="position: absolute; bottom: 0; left: 0; right: 0; height: 6px; background: rgba(255,255,255,0.15);">
                      <div style="width: ${pct}%; height: 100%; background: var(--color-colorPrimary);"></div>
                    </div>
                    <div class="card-info">
                      <div class="card-title">${displayTitle}</div>
                      <div class="card-metadata">
                        <span>${label}</span>
                        <span>${pct}%</span>
                      </div>
                    </div>
                  </div>
                `;
              }).join('')}
            </div>
          </div>
        `;

        // Wire remove buttons via event delegation (avoids HTML-escaping issues with inline onclick)
        resumeContainer.querySelectorAll('.remove-btn[data-remove-id]').forEach(btn => {
          btn.addEventListener('click', e => {
            e.stopPropagation();
            try {
              const id = decodeURIComponent(escape(atob(btn.dataset.removeId)));
              removeHistoryItem(id);
            } catch (_) {
              showToast('Error identifying item to remove');
            }
          });
        });
      }
    }
  } catch (err) {
    console.error('Error fetching watch history:', err);
  }

  // 2. Stream homepage sections from Ktor backend
  try {
    const providerQuery = state.activeProvider
      ? `?provider=${encodeURIComponent(state.activeProvider)}`
      : '';
    const homeRes = await fetch(`${API_BASE}/home${providerQuery}`);
    if (!homeRes.body) throw new Error('ReadableStream not supported');

    const reader = homeRes.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop(); // Keep incomplete line

      for (const line of lines) {
        if (!line.trim()) continue;
        try {
          const section = JSON.parse(line);
          if (section.items && section.items.length > 0) {
            hasSections = true;
            const sectionDiv = document.createElement('div');
            sectionDiv.className = 'carousel-section';
            sectionDiv.innerHTML = `
              <h3 class="carousel-title">${section.name} <span class="section-provider">${section.provider}</span></h3>
              <div class="grid-container">
                ${section.items.map(item => `
                  <div class="media-card" onclick="window.location.hash = '#/detail?url=${encodeURIComponent(item.url)}&provider=${encodeURIComponent(item.apiName)}'">
                    <img class="card-poster" src="${proxyImage(item.posterUrl)}" alt="${item.name}">
                    <div class="card-info">
                      <div class="card-title">${item.name}</div>
                      <div class="card-metadata">
                        <span>${item.type || 'Media'}</span>
                        ${item.score ? `<span class="rating-badge">★ ${item.score.toFixed(1)}</span>` : ''}
                      </div>
                    </div>
                  </div>
                `).join('')}
              </div>
            `;
            sectionsContainer.appendChild(sectionDiv);
          }
        } catch (err) {
          console.error('Failed to parse home section JSON line:', err);
        }
      }
    }
  } catch (err) {
    console.error('Failed to stream homepage sections:', err);
  } finally {
    spinner.remove();
    if (!hasResume && !hasSections) {
      sectionsContainer.innerHTML = '<div class="loading">No homepage content is available. Try searching.</div>';
    }
  }
}

async function removeHistoryItem(id) {
  if (!confirm('Are you sure you want to remove this item from your Continue Watching list?')) return;
  try {
    const res = await fetch(`${API_BASE}/history/${encodeURIComponent(id)}`, {
      method: 'DELETE'
    });
    if (res.ok) {
      showToast('Removed from history');
      renderHome(); // Re-render Home view to update the feed
    } else {
      showToast('Failed to remove item');
    }
  } catch (e) {
    showToast('Error removing history item');
  }
}

async function renderSearch() {
  const outlet = document.getElementById('view-outlet');
  outlet.innerHTML = `
    <div class="search-container">
      <input type="text" class="search-input" id="search-box" placeholder="Search movies, shows, or anime..." />
      <button class="btn btn-primary" id="search-btn">Search</button>
    </div>
    <div id="search-results"></div>
  `;

  const searchBox = document.getElementById('search-box');
  const searchBtn = document.getElementById('search-btn');

  const executeSearch = async () => {
    const q = searchBox.value.trim();
    if (!q) return;
    const resultsArea = document.getElementById('search-results');
    resultsArea.innerHTML = '<div class="loading">Searching...</div>';

    try {
      const url = `${API_BASE}/search?q=${encodeURIComponent(q)}${state.activeProvider ? `&provider=${encodeURIComponent(state.activeProvider)}` : ''}&diagnostics=true`;
      const res = await fetch(url);
      const payload = await res.json();
      const results = Array.isArray(payload) ? payload : (payload.results || []);
      const failures = Array.isArray(payload) ? [] : (payload.failures || []);
      const failureNotice = failures.length ? `<div class="provider-failure-notice"><strong>Some providers were unavailable</strong>${failures.slice(0, 8).map(f => {
        const provider = state.providers.find(item => item.name === f.provider);
        const action = f.code === 'CHALLENGE_REQUIRED' && provider?.url
          ? ` <a href="#/challenge?url=${encodeURIComponent(provider.url)}">Open browser challenge</a>`
          : '';
        return `<span>${f.provider}: ${f.message}${action}</span>`;
      }).join('')}</div>` : '';

      if (results.length === 0) {
        resultsArea.innerHTML = `${failureNotice}<div class="loading">No results found.</div>`;
        return;
      }

      resultsArea.innerHTML = `${failureNotice}
        <div class="grid-container">
          ${results.map(item => `
            <div class="media-card" onclick="window.location.hash = '#/detail?url=${encodeURIComponent(item.url)}&provider=${encodeURIComponent(item.apiName)}'">
              <img class="card-poster" src="${proxyImage(item.posterUrl)}" alt="${item.name}">
              <div class="card-info">
                <div class="card-title">${item.name}</div>
                <div class="card-metadata">
                  <span>${item.type || 'Media'}</span>
                  ${item.score ? `<span class="rating-badge">★ ${item.score.toFixed(1)}</span>` : ''}
                </div>
              </div>
            </div>
          `).join('')}
        </div>
      `;
    } catch (e) {
      resultsArea.innerHTML = '<div class="loading">Error performing search.</div>';
    }
  };

  searchBtn.addEventListener('click', executeSearch);
  searchBox.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') executeSearch();
  });
}

async function renderChallenge() {
  const outlet = document.getElementById('view-outlet');
  const params = getQueryParams();
  outlet.innerHTML = `
    <div class="challenge-view">
      <div class="challenge-header"><p class="eyebrow">Interactive provider access</p><h2>Complete browser verification</h2><p>Use this isolated browser session to complete the provider challenge. Cookies remain on the server and are never exposed to this page.</p></div>
      <div class="challenge-start-row"><input class="search-input" id="challenge-url" value="${params.url || ''}" placeholder="https://provider.example/challenge"><button class="btn btn-primary" id="challenge-start-btn">Open challenge</button></div>
      <div id="challenge-status" class="challenge-status">No challenge session started.</div>
      <div class="challenge-stage"><img id="challenge-screenshot" alt="Provider challenge screenshot" style="display: none;"><div id="challenge-empty" style="display: block;">The challenge screenshot will appear here.</div></div>
      <div class="challenge-actions"><input class="search-input" id="challenge-text" placeholder="Optional text input"><button class="btn" id="challenge-type-btn">Type</button><button class="btn btn-primary" id="challenge-complete-btn">Check completion</button></div>
    </div>
  `;
  let sessionId = params.id || null;
  let pollTimer = null;
  const status = document.getElementById('challenge-status');
  const screenshot = document.getElementById('challenge-screenshot');
  const empty = document.getElementById('challenge-empty');
  const update = async () => {
    if (!sessionId) return;
    const response = await fetch(`${API_BASE}/challenges/${sessionId}`);
    const data = await response.json();
    status.textContent = `${data.status}: ${data.title || data.url || ''}`;
    screenshot.src = `${API_BASE}/challenges/${sessionId}/screenshot?t=${Date.now()}`;
    screenshot.style.display = 'block';
    empty.style.display = 'none';
    if (data.status === 'ready') {
      clearInterval(pollTimer);
      showToast('Challenge completed. Retry the provider operation.');
    }
  };
  document.getElementById('challenge-start-btn').addEventListener('click', async () => {
    const url = document.getElementById('challenge-url').value.trim();
    if (!/^https?:\/\//i.test(url)) return showToast('Enter a valid provider URL.');
    const response = await fetch(`${API_BASE}/challenges`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ url }) });
    const data = await response.json();
    if (!response.ok) return showToast(data.error || 'Unable to start challenge.');
    sessionId = data.id;
    status.textContent = `${data.status}: ${data.title || data.url}`;
    await update();
    clearInterval(pollTimer);
    pollTimer = setInterval(update, 2000);
  });
  screenshot.addEventListener('click', async (event) => {
    if (!sessionId || !screenshot.naturalWidth) return;
    const rect = screenshot.getBoundingClientRect();
    await fetch(`${API_BASE}/challenges/${sessionId}/click`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ x: (event.clientX - rect.left) * screenshot.naturalWidth / rect.width, y: (event.clientY - rect.top) * screenshot.naturalHeight / rect.height }) });
    await update();
  });
  document.getElementById('challenge-type-btn').addEventListener('click', async () => {
    if (!sessionId) return;
    const text = document.getElementById('challenge-text').value;
    await fetch(`${API_BASE}/challenges/${sessionId}/type`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text }) });
    await update();
  });
  document.getElementById('challenge-complete-btn').addEventListener('click', async () => {
    if (sessionId) await fetch(`${API_BASE}/challenges/${sessionId}/complete`, { method: 'POST' });
    await update();
  });
  if (params.id) {
    document.getElementById('challenge-start-btn').disabled = true;
    update().then(() => { clearInterval(pollTimer); pollTimer = setInterval(update, 2000); });
  } else if (params.url) {
    document.getElementById('challenge-start-btn').click();
  }
}

async function renderDetail() {
  const outlet = document.getElementById('view-outlet');
  const params = getQueryParams();
  if (!params.url || !params.provider) {
    window.location.hash = '#/';
    return;
  }

  outlet.innerHTML = '<div class="loading">Loading details...</div>';

  try {
    const res = await fetch(`${API_BASE}/load?url=${encodeURIComponent(params.url)}&provider=${encodeURIComponent(params.provider)}`);
    const details = await res.json();

    const isBookmarked = state.bookmarks.some(b => b.url === details.url);

    // Fallback title/poster from local watch history when provider metadata is sparse
    const histEntry = state.history.find(h => h.url === params.url || h.url === details.url);
    const displayName = (details.name && details.name !== details.url) ? details.name : (histEntry?.title || details.name || params.url);
    const displayPoster = details.posterUrl || histEntry?.posterUrl || null;

    outlet.innerHTML = `
      <div class="detail-container">
        <img class="detail-poster" src="${proxyImage(displayPoster)}" alt="${displayName}">
        <div class="detail-content">
          <h2 class="detail-title">${displayName}</h2>
          <div class="detail-meta-row">
            <span>${details.type || 'Movie'}</span>
            ${details.year ? `<span>${details.year}</span>` : ''}
            ${details.duration ? `<span>${details.duration} min</span>` : ''}
            ${details.score ? `<span class="rating-badge">★ ${details.score.toFixed(1)}</span>` : ''}
          </div>
          <p class="detail-plot">${details.plot || 'No overview available.'}</p>

          <div style="display: flex; gap: 16px; margin-bottom: 40px;">
            <button class="btn btn-primary" id="play-btn">
              <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
              Play
            </button>
            <button class="btn" style="background: rgba(255,255,255,0.05); color:#fff;" id="download-btn">
              Download
            </button>
            <button class="btn" style="background: rgba(255,255,255,0.05); color:#fff;" id="bookmark-btn">
              ${isBookmarked ? 'Remove Bookmark' : 'Add Bookmark'}
            </button>
          </div>

          <div style="margin-top: 16px; max-height: 400px; overflow-y: auto;">
            ${details.episodes.map(ep => {
              const epTitle = `${details.name} - ${ep.name || `Episode ${ep.episode || 1}`}`;
              return `
                <div class="episode-row" style="display:flex; justify-content:space-between; align-items:center; padding: 12px; border-bottom: 1px solid rgba(255,255,255,0.03); cursor:pointer;" onclick="playMedia('${escAttr(ep.url)}', '${escAttr(details.apiName)}', '${escAttr(epTitle)}', '${escAttr(details.url)}', '${escAttr(details.posterUrl || '')}', ${ep.season !== undefined && ep.season !== null ? ep.season : 'null'}, ${ep.episode !== undefined && ep.episode !== null ? ep.episode : 'null'})">
                  <span>${ep.name || `Episode ${ep.episode || 1}`}</span>
                  <span style="color: var(--color-grayTextColor); font-size:14px;">Play</span>
                </div>
              `;
            }).join('')}
          </div>
        </div>
      </div>
    `;

    document.getElementById('download-btn').addEventListener('click', async () => {
      if (details.episodes.length > 0) {
        const ep = details.episodes[0];
        showToast('Resolving stream link for download...');
        try {
          const res = await fetch(`${API_BASE}/links`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ data: ep.url, provider: details.apiName })
          });
          const data = await res.json();
          if (data.links.length > 0) {
            const link = data.links[0];
            await fetch(`${API_BASE}/downloads`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({
                id: ep.url,
                title: `${details.name} - ${ep.name || `Episode ${ep.episode || 1}`}`,
                url: link.url
              })
            });
            showToast('Download started on server!');
          } else {
            showToast('No download links found.');
          }
        } catch (e) {
          showToast('Failed to start download.');
        }
      } else {
        showToast('No episodes available to download.');
      }
    });

    document.getElementById('bookmark-btn').addEventListener('click', async () => {
      const bookmarked = state.bookmarks.some(b => b.url === details.url);
      if (bookmarked) {
        await fetch(`${API_BASE}/bookmarks/${encodeURIComponent(details.url)}`, { method: 'DELETE' });
        showToast('Removed from bookmarks');
      } else {
        await fetch(`${API_BASE}/bookmarks`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            id: details.url,
            name: details.name,
            url: details.url,
            apiName: details.apiName,
            posterUrl: details.posterUrl,
            type: details.type
          })
        });
        showToast('Added to bookmarks');
      }
      await syncBookmarks();
      renderDetail();
    });

    document.getElementById('play-btn').addEventListener('click', () => {
      if (details.episodes.length > 0) {
        const firstEp = details.episodes[0];
        playMedia(firstEp.url, details.apiName, `${details.name}`, details.url, details.posterUrl || '', firstEp.season !== undefined && firstEp.season !== null ? firstEp.season : null, firstEp.episode !== undefined && firstEp.episode !== null ? firstEp.episode : null);
      } else {
        showToast('No links available.');
      }
    });

  } catch (e) {
    outlet.innerHTML = '<div class="loading">Failed to load details.</div>';
  }
}

async function playMedia(url, provider, title, parentId = '', posterUrl = '', seasonNum = null, episodeNum = null) {
  showLoadingOverlay('Fetching sources\u2026');
  try {
    const res = await fetch(`${API_BASE}/links`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ data: url, provider })
    });
    const data = await res.json();
    hideLoadingOverlay();

    if (data.links.length === 0) {
      showToast('No playable video sources found.');
      return;
    }

    showSourcePicker(data, title, url, provider, parentId, posterUrl, seasonNum, episodeNum);
  } catch (e) {
    hideLoadingOverlay();
    showToast(e.message || 'Error loading video sources.');
  }
}

function showSourcePicker(data, title, id, provider, parentId = '', posterUrl = '', seasonNum = null, episodeNum = null) {
  document.getElementById('source-picker')?.remove();
  sessionStorage.setItem(`cs_sources:${id}`, JSON.stringify(data));
  const overlay = document.createElement('div');
  overlay.id = 'source-picker';
  overlay.className = 'source-picker-backdrop';
  overlay.innerHTML = `
    <section class="source-picker" role="dialog" aria-modal="true" aria-labelledby="source-picker-title">
      <div class="source-picker-header">
        <div><p class="eyebrow">Playback setup</p><h2 id="source-picker-title">Choose how to play</h2><p class="source-picker-subtitle"></p></div>
        <button class="source-picker-close" type="button" aria-label="Close">×</button>
      </div>
      <div class="source-picker-section"><h3>Sources</h3><div class="source-list" id="source-list"></div><div class="native-player-actions"><button class="player-toolbar-btn" type="button" id="open-vlc-btn">Open in VLC</button><button class="player-toolbar-btn" type="button" id="open-infuse-btn">Open in Infuse</button><button class="player-toolbar-btn" type="button" id="download-vlc-btn">Download VLC playlist</button></div></div>
      <div class="source-picker-section"><h3>Subtitles</h3><div class="subtitle-list" id="subtitle-list"></div></div>
      <div class="source-picker-actions"><button class="btn" type="button" id="source-cancel-btn">Cancel</button><button class="btn btn-primary" type="button" id="source-play-btn">Play selected source</button></div>
    </section>
  `;
  document.body.appendChild(overlay);
  overlay.querySelector('.source-picker-subtitle').textContent = title;

  const mediaId = parentId || id;
  const lastSourceUrl = localStorage.getItem(`cs_last_source_url:${mediaId}`);
  const lastSourceName = localStorage.getItem(`cs_last_source_name:${mediaId}`);
  const lastSubtitles = JSON.parse(localStorage.getItem(`cs_last_subtitles:${mediaId}`) || '[]');
  
  const prefQuality = localStorage.getItem('cs_pref_source_quality');
  const prefName = localStorage.getItem('cs_pref_source_name');
  const prefSubtitle = localStorage.getItem('cs_pref_subtitle_lang');

  const sourceList = document.getElementById('source-list');
  let selectedIndex = 0;
  
  // Try to find matching source index based on last used source or global preference
  const matchIndex = data.links.findIndex(link => 
    (lastSourceUrl && link.url === lastSourceUrl) ||
    (lastSourceName && link.name === lastSourceName) ||
    (prefQuality && link.quality === prefQuality && prefName && link.name === prefName)
  );
  if (matchIndex !== -1) {
    selectedIndex = matchIndex;
  }

  data.links.forEach((link, index) => {
    const label = document.createElement('label');
    label.className = 'source-choice';
    label.innerHTML = `<input type="radio" name="playback-source" value="${index}" ${index === selectedIndex ? 'checked' : ''}><span class="source-choice-copy"><strong></strong><small></small></span>`;
    label.querySelector('strong').textContent = link.name || link.quality || `Source ${index + 1}`;
    label.querySelector('small').textContent = [link.quality, link.referer ? 'Protected stream' : 'Direct stream'].filter(Boolean).join(' · ');
    sourceList.appendChild(label);
  });

  const subtitleList = document.getElementById('subtitle-list');
  if (!data.subtitles?.length) {
    subtitleList.innerHTML = '<p class="source-empty">No subtitles were provided by this source.</p>';
  } else {
    data.subtitles.forEach((subtitle, index) => {
      const isChecked = lastSubtitles.includes(subtitle.lang) || (prefSubtitle && subtitle.lang === prefSubtitle);
      const label = document.createElement('label');
      label.className = 'subtitle-choice';
      label.innerHTML = `<input type="checkbox" value="${index}" ${isChecked ? 'checked' : ''}><span></span>`;
      label.querySelector('span').textContent = subtitle.lang || `Subtitle ${index + 1}`;
      subtitleList.appendChild(label);
    });
  }

  const close = () => overlay.remove();
  overlay.querySelector('.source-picker-close').addEventListener('click', close);
  document.getElementById('source-cancel-btn').addEventListener('click', close);
  document.getElementById('source-play-btn').addEventListener('click', () => {
    const sourceIndex = Number(document.querySelector('input[name="playback-source"]:checked')?.value || 0);
    const selectedSubtitles = [...document.querySelectorAll('#subtitle-list input:checked')].map(input => data.subtitles[Number(input.value)]);
    close();
    startPlayback(data.links[sourceIndex], selectedSubtitles, title, id, provider, sourceIndex, parentId, posterUrl, seasonNum, episodeNum);
  });
  const getNativeUrl = () => {
    const sourceIndex = Number(document.querySelector('input[name="playback-source"]:checked')?.value || 0);
    const source = data.links[sourceIndex];
    const referer = source.referer || '';
    return new URL(`${API_BASE}/proxy?url=${encodeURIComponent(source.url)}&referer=${encodeURIComponent(referer)}`, window.location.href).href;
  };
  const openNative = async (scheme) => {
    const nativeUrl = getNativeUrl();
    const userAgent = navigator.userAgent || '';
    const sourceIndex = Number(document.querySelector('input[name="playback-source"]:checked')?.value || 0);
    const source = data.links[sourceIndex];

    // Save source preference selections
    localStorage.setItem(`cs_last_source_url:${mediaId}`, source.url);
    localStorage.setItem(`cs_last_source_name:${mediaId}`, source.name || '');
    if (source.quality) localStorage.setItem('cs_pref_source_quality', source.quality);
    if (source.name) localStorage.setItem('cs_pref_source_name', source.name);

    // Fetch full detail snapshot so the history record is as rich as a native play
    // Use parentId (series/movie page) when available, otherwise fall back to the episode/link id
    const snapUrl = (parentId && !parentId.startsWith('[')) ? parentId : (!id.startsWith('[') ? id : null);
    const snap = await fetchAndCacheDetailSnapshot(snapUrl, provider);

    const richTitle    = snap.name      || title;
    const richPoster   = snap.posterUrl || posterUrl || null;

    // Save to watch history on backend
    fetch(`${API_BASE}/history`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        id:         id,
        parentId:   parentId || null,
        positionMs: 10000,
        durationMs: 500000,
        title:      richTitle,
        posterUrl:  richPoster,
        plot:       snap.plot  || null,
        type:       snap.type  || null,
        year:       snap.year  || null,
        score:      snap.score || null,
        provider:   provider,
        seasonNum:  seasonNum,
        episodeNum: episodeNum
      }),
      keepalive: true
    }).catch(console.error);

    // Save to local state
    const historyItem = { title: richTitle, url: id, provider, timestamp: Date.now(), parentId, posterUrl: richPoster, seasonNum, episodeNum };
    state.history = [historyItem, ...state.history.filter(h => h.url !== id)].slice(0, 50);
    localStorage.setItem('cs_history', JSON.stringify(state.history));

    if (scheme === 'vlc' && /Android/i.test(userAgent)) {
      window.location.href = `intent://${nativeUrl.replace(/^https?:\/\//, '')}#Intent;scheme=https;package=org.videolan.vlc;end`;
    } else if (scheme === 'vlc' && /iPad|iPhone|iPod/i.test(userAgent)) {
      window.location.href = `vlc-x-callback://x-callback-url/stream?url=${encodeURIComponent(nativeUrl)}`;
    } else if (scheme === 'vlc') {
      window.location.href = `vlc://${nativeUrl}`;
    } else {
      window.location.href = `infuse://x-callback-url/play?url=${encodeURIComponent(nativeUrl)}`;
    }
  };
  document.getElementById('open-vlc-btn').addEventListener('click', () => openNative('vlc'));
  document.getElementById('open-infuse-btn').addEventListener('click', () => openNative('infuse'));
  document.getElementById('download-vlc-btn').addEventListener('click', () => {
    const content = `#EXTM3U\n#EXTINF:-1,${title}\n${getNativeUrl()}\n`;
    const blob = new Blob([content], { type: 'audio/x-mpegurl' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = `${title.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '') || 'video'}.m3u`;
    link.click();
    URL.revokeObjectURL(link.href);
  });
}

async function startPlayback(firstLink, subtitles, title, id, provider, sourceIndex = 0, parentId = '', posterUrl = '', seasonNum = null, episodeNum = null) {
    let streamUrl = firstLink.url;
    let referer = firstLink.referer || '';

    // If source requires specialized headers (like referer), route through Ktor stream proxy
    if ((firstLink.headers && Object.keys(firstLink.headers).length > 0) || referer) {
      streamUrl = `${API_BASE}/proxy?url=${encodeURIComponent(streamUrl)}&referer=${encodeURIComponent(referer)}`;
    }

    let sourceData = null;
    try { sourceData = JSON.parse(sessionStorage.getItem(`cs_sources:${id}`) || 'null'); } catch (_) { sourceData = null; }
    sessionStorage.setItem(`cs_playback:${id}`, JSON.stringify({
      streamUrl,
      subtitles: subtitles || [],
      sourceIndex,
      links: sourceData?.links || [{ url: firstLink.url, quality: firstLink.quality, name: firstLink.name, referer: firstLink.referer }]
    }));

    // Save source/subtitle preferences for auto-resume
    const mediaId = parentId || id;
    localStorage.setItem(`cs_last_source_url:${mediaId}`, firstLink.url);
    localStorage.setItem(`cs_last_source_name:${mediaId}`, firstLink.name || '');

    const selectedSubLangs = (subtitles || []).map(s => s.lang);
    localStorage.setItem(`cs_last_subtitles:${mediaId}`, JSON.stringify(selectedSubLangs));

    if (firstLink.quality) localStorage.setItem('cs_pref_source_quality', firstLink.quality);
    if (firstLink.name) localStorage.setItem('cs_pref_source_name', firstLink.name);
    if (selectedSubLangs.length > 0) localStorage.setItem('cs_pref_subtitle_lang', selectedSubLangs[0]);

    // Fetch full detail snapshot for a rich history record (cached, so no extra round-trip if detail page was already loaded)
    const snapUrl = (parentId && !parentId.startsWith('[')) ? parentId : (!id.startsWith('[') ? id : null);
    const snap = await fetchAndCacheDetailSnapshot(snapUrl, provider);

    const richTitle  = snap.name      || title;
    const richPoster = snap.posterUrl || posterUrl || null;

    // Save to local history
    const historyItem = { title: richTitle, url: id, provider, timestamp: Date.now(), parentId, posterUrl: richPoster, seasonNum, episodeNum };
    state.history = [historyItem, ...state.history.filter(h => h.url !== id)].slice(0, 50);
    localStorage.setItem('cs_history', JSON.stringify(state.history));

    // Redirect SPA router to embedded video player view
    window.location.hash = `#/player?url=${encodeURIComponent(streamUrl)}&title=${encodeURIComponent(richTitle)}&provider=${encodeURIComponent(provider)}&id=${encodeURIComponent(id)}&parentId=${encodeURIComponent(parentId)}&posterUrl=${encodeURIComponent(richPoster || posterUrl)}&seasonNum=${seasonNum !== null ? seasonNum : ''}&episodeNum=${episodeNum !== null ? episodeNum : ''}`;
}

function renderPlayer() {
  const outlet = document.getElementById('view-outlet');
  const params = getQueryParams();
  
  if (!params.url || !params.title || !params.id) {
    window.location.hash = '#/';
    return;
  }

  let playback = null;
  try {
    playback = JSON.parse(sessionStorage.getItem(`cs_playback:${params.id}`) || 'null');
  } catch (_) {
    playback = null;
  }

  outlet.innerHTML = `
    <div class="player-container" id="player-shell">
      <a href="#/history" class="player-back-btn" id="player-close-btn">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
        Back
      </a>
      <div class="player-header">
        <span class="player-title" id="player-title-text">${params.title}</span>
      </div>
      <div class="player-stage" id="player-stage">
        <video id="video-element" class="player-video" controls autoplay playsinline preload="metadata"></video>
        <canvas id="subtitle-canvas-overlay" class="subtitle-overlay-canvas"></canvas>
        <div class="player-center-controls">
          <button class="player-control" id="skip-back-btn" aria-label="Skip back 10 seconds">↶ 10</button>
          <button class="player-control player-play-control" id="play-toggle-btn" aria-label="Play or pause">▶</button>
          <button class="player-control" id="skip-forward-btn" aria-label="Skip forward 10 seconds">10 ↷</button>
        </div>
      </div>
      <div class="player-toolbar" aria-label="Player options">
        <label class="player-option">Speed
          <select id="playback-speed" aria-label="Playback speed">
            <option value="0.75">0.75×</option><option value="1" selected>1×</option><option value="1.25">1.25×</option><option value="1.5">1.5×</option><option value="2">2×</option>
          </select>
        </label>
        <label class="player-option" id="quality-option" hidden>Quality
          <select id="playback-quality" aria-label="Video quality"><option value="-1">Auto</option></select>
        </label>
        <label class="player-option" id="audio-option" hidden>Audio
          <select id="playback-audio" aria-label="Audio track"><option value="-1">Auto</option></select>
        </label>
        <label class="player-option" id="subtitle-option" hidden>Subtitles
          <select id="subtitle-select" aria-label="Subtitles"><option value="-1">Off</option></select>
        </label>
        <button class="player-toolbar-btn" id="pip-btn" type="button">PiP</button>
        <button class="player-toolbar-btn" id="change-source-btn" type="button">Change source</button>
        <button class="player-toolbar-btn" id="fullscreen-btn" type="button">Fullscreen</button>
      </div>
    </div>
  `;

  const video = document.getElementById('video-element');
  const streamUrl = playback?.streamUrl || params.url;
  const subtitles = playback?.subtitles || [];
  let sourceData = null;
  try {
    sourceData = JSON.parse(sessionStorage.getItem(`cs_sources:${params.id}`) || 'null');
  } catch (_) {
    sourceData = null;
  }

  // Retrieve any existing watch position from history to support resume watching
  fetch(`${API_BASE}/history`)
    .then(res => res.json())
    .then(history => {
      const record = history.find(h => h.id === params.id);
      if (record && record.positionMs > 0 && record.durationMs > 0) {
        const resumeTime = record.positionMs / 1000;
        // Resume playback if duration checks out
        video.addEventListener('loadedmetadata', () => {
          if (resumeTime < video.duration - 10) {
            video.currentTime = resumeTime;
            showToast(`Resumed from ${Math.floor(resumeTime / 60)}m ${Math.floor(resumeTime % 60)}s`);
          }
        }, { once: true });
      }
    }).catch(console.error);

  // Solve routing pathway dynamically. A source may be a signed URL without
  // a file extension, so the error fallback below remains authoritative.
  const selectedSource = playback?.links?.[playback.sourceIndex || 0] || sourceData?.links?.[playback?.sourceIndex || 0];
  const route = getPlaybackRoute(streamUrl, {
    container: selectedSource?.type?.toLowerCase?.().includes('m3u8') ? 'mp4' : undefined
  });
  route.referer = selectedSource?.referer || '';
  serverLog("INFO", "PlayerRoute", `Selected method: ${route.method}. Reason: ${route.reasons.join(' | ')}. URL: ${streamUrl}`);

  if (route.method === 'DIRECT') {
    const canPlayNatively = video.canPlayType('application/vnd.apple.mpegurl') !== '' || 
                            video.canPlayType('application/x-mpegurl') !== '';
    if (canPlayNatively) {
      video.src = streamUrl;
    } else if (Hls.isSupported() && (streamUrl.includes('.m3u8') || streamUrl.includes('/proxy?url='))) {
      const hls = new Hls({
        maxMaxBufferLength: 30
      });
      hls.on(Hls.Events.ERROR, (event, data) => {
        console.error('[HLS Error]', data.type, data.details, data.fatal, data.error?.message || data.response?.statusText);
      });
      hls.on(Hls.Events.MANIFEST_LOADED, (event, data) => {
        const supportsAAC = window.MediaSource?.isTypeSupported('audio/mp4; codecs="mp4a.40.2"') || 
                            window.MediaSource?.isTypeSupported('audio/mp4; codecs="mp4a.40.1"');
        if (!supportsAAC) {
          console.warn('[HLS Engine] AAC audio is unsupported by this browser. Stripping audio tracks to play video-only.');
          data.audioTracks = [];
          if (data.playlists) {
            data.playlists.forEach(p => {
              if (p.attrs) {
                delete p.attrs.AUDIO;
                if (p.attrs.CODECS) {
                  p.attrs.CODECS = p.attrs.CODECS.split(',')
                    .filter(c => !c.trim().startsWith('mp4a'))
                    .join(',');
                }
              }
            });
          }
        }
      });
      hls.loadSource(streamUrl);
      hls.attachMedia(video);
      window.currentHls = hls;
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        const qualitySelect = document.getElementById('playback-quality');
        const qualityOption = document.getElementById('quality-option');
        const levels = hls.levels || [];
        levels.forEach((level, index) => {
          const option = document.createElement('option');
          option.value = index;
          option.textContent = level.height ? `${level.height}p` : `${Math.round((level.bitrate || 0) / 1000)} kbps`;
          qualitySelect.appendChild(option);
        });
        qualityOption.hidden = levels.length < 2;
        qualitySelect.addEventListener('change', () => {
          hls.currentLevel = Number(qualitySelect.value);
        });
        const audioSelect = document.getElementById('playback-audio');
        const audioOption = document.getElementById('audio-option');
        const addAudioTracks = () => {
          audioSelect.innerHTML = '<option value="-1">Auto</option>';
          (hls.audioTracks || []).forEach((track, index) => {
            const option = document.createElement('option');
            option.value = index;
            option.textContent = track.name || track.lang || `Audio ${index + 1}`;
            audioSelect.appendChild(option);
          });
          audioOption.hidden = (hls.audioTracks || []).length < 2;
        };
        if (hls.audioTracks?.length) addAudioTracks();
        hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, addAudioTracks);
        audioSelect.addEventListener('change', () => { hls.audioTrack = Number(audioSelect.value); });
      });
      hls.on(Hls.Events.ERROR, (_, error) => {
        if (error.fatal) showToast('The stream could not be loaded.');
      });
    } else {
      video.src = streamUrl;
    }
  } else {
    // Initiate WASM playback for unsupported container/codecs
    initiateWasmPlayback(video, streamUrl, route, subtitles, selectedSource);
  }

  // Playback watchdog timer to handle silent loads, CORS blocks, or connection hangs
  let watchdog = null;
  const startWatchdog = () => {
    if (watchdog) return;
    watchdog = setTimeout(() => {
      if (video.readyState < 3) {
        serverLog("WARN", "PlayerWatchdog", `Playback did not start 4.5s after play trigger (readyState: ${video.readyState}). Triggering backend transcode fallback.`);
        fallbackToBackendTranscode(video, streamUrl, route, selectedSource);
      }
    }, 4500);
  };

  const clearWatchdog = () => {
    if (watchdog) {
      serverLog("INFO", "PlayerWatchdog", `Playback successfully started (readyState: ${video.readyState}). Watchdog cleared.`);
      clearTimeout(watchdog);
      watchdog = null;
    }
  };

  video.addEventListener('play', startWatchdog);
  video.addEventListener('playing', clearWatchdog);
  video.addEventListener('canplay', clearWatchdog);
  video.addEventListener('timeupdate', () => {
    if (video.currentTime > 0) {
      clearWatchdog();
    }
  });

  if (!video.paused) {
    startWatchdog();
  }

  video.addEventListener('error', () => {
    clearTimeout(watchdog);
    watchdog = null;
    if (fallbackToBackendTranscode(video, streamUrl, route, selectedSource)) return;
    const message = 'This source is not browser-compatible. iPad supports MP4/HLS, but this source may be MKV or use an unsupported codec.';
    showToast(message);
    const errorNotice = document.createElement('div');
    errorNotice.className = 'player-error-notice';
    errorNotice.innerHTML = '<strong>Unable to play this source</strong><span>Choose an MP4 or HLS source, or try another provider.</span>';
    document.getElementById('player-stage')?.appendChild(errorNotice);
  }, { once: true });

  const subtitleSelect = document.getElementById('subtitle-select');
  const subtitleOption = document.getElementById('subtitle-option');
  subtitles.forEach((subtitle, index) => {
    const track = document.createElement('track');
    const referer = subtitle.headers?.Referer || subtitle.headers?.referer || '';
    track.kind = 'subtitles';
    track.label = subtitle.lang || `Subtitle ${index + 1}`;
    track.srclang = subtitle.langTag || 'en';
    track.src = referer
      ? `${API_BASE}/proxy?url=${encodeURIComponent(subtitle.url)}&referer=${encodeURIComponent(referer)}`
      : subtitle.url;
    video.appendChild(track);
    const option = document.createElement('option');
    option.value = index;
    option.textContent = subtitle.lang || `Subtitle ${index + 1}`;
    subtitleSelect.appendChild(option);
  });
  subtitleOption.hidden = subtitles.length === 0;
  subtitleSelect.addEventListener('change', () => {
    [...video.textTracks].forEach((track, index) => {
      track.mode = index === Number(subtitleSelect.value) ? 'showing' : 'disabled';
    });
  });

  const playToggle = document.getElementById('play-toggle-btn');
  const syncPlayButton = () => { playToggle.textContent = video.paused ? '▶' : 'Ⅱ'; };
  playToggle.addEventListener('click', () => video.paused ? video.play() : video.pause());
  video.addEventListener('play', syncPlayButton);
  video.addEventListener('pause', syncPlayButton);
  document.getElementById('skip-back-btn').addEventListener('click', () => { video.currentTime = Math.max(0, video.currentTime - 10); });
  document.getElementById('skip-forward-btn').addEventListener('click', () => { video.currentTime = Math.min(video.duration || Infinity, video.currentTime + 10); });
  document.getElementById('playback-speed').addEventListener('change', (event) => { video.playbackRate = Number(event.target.value); });
  document.getElementById('pip-btn').addEventListener('click', async () => {
    if (document.pictureInPictureEnabled) {
      if (document.pictureInPictureElement) await document.exitPictureInPicture();
      else await video.requestPictureInPicture();
    }
  });
  document.getElementById('fullscreen-btn').addEventListener('click', () => {
    const shell = document.getElementById('player-shell');
    if (document.fullscreenElement) document.exitFullscreen();
    else shell.requestFullscreen?.();
  });
  document.getElementById('change-source-btn').addEventListener('click', () => {
    if (sourceData) showSourcePicker(sourceData, params.title, params.id, params.provider);
    else window.location.hash = `#/detail?url=${encodeURIComponent(params.id)}&provider=${encodeURIComponent(params.provider)}`;
  });
  document.getElementById('player-stage').addEventListener('dblclick', (event) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    video.currentTime += event.clientX < bounds.left + bounds.width / 2 ? -10 : 10;
  });
  document.addEventListener('keydown', window.playerKeyHandler = (event) => {
    if (event.target.matches('input, select, textarea')) return;
    if (event.key === ' ') { event.preventDefault(); video.paused ? video.play() : video.pause(); }
    if (event.key === 'ArrowLeft') video.currentTime = Math.max(0, video.currentTime - 10);
    if (event.key === 'ArrowRight') video.currentTime = Math.min(video.duration || Infinity, video.currentTime + 10);
    if (event.key.toLowerCase() === 'f') document.getElementById('fullscreen-btn').click();
  });

  // Auto-hide controls and cursor on inactivity
  const playerShell = document.getElementById('player-shell');
  let controlsTimeout;
  const showControls = () => {
    playerShell.classList.remove('controls-hidden');
    clearTimeout(controlsTimeout);
    if (!video.paused) {
      controlsTimeout = setTimeout(() => {
        playerShell.classList.add('controls-hidden');
      }, 3000);
    }
  };
  playerShell.addEventListener('mousemove', showControls);
  playerShell.addEventListener('click', showControls);
  playerShell.addEventListener('touchstart', showControls);
  video.addEventListener('play', showControls);
  video.addEventListener('pause', showControls);
  showControls();

  const seasonNum = params.seasonNum ? Number(params.seasonNum) : null;
  const episodeNum = params.episodeNum ? Number(params.episodeNum) : null;

  const saveProgress = () => {
    if (!video.duration) return;
    fetch(`${API_BASE}/history`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        id: params.id,
        parentId: params.parentId || null,
        seasonNum: seasonNum,
        episodeNum: episodeNum,
        positionMs: Math.round(video.currentTime * 1000),
        durationMs: Math.round(video.duration * 1000),
        title: params.title,
        posterUrl: params.posterUrl || null,
        provider: params.provider
      }),
      keepalive: true
    }).catch(console.error);
  };

  // 1. Throttle updates during playback using timeupdate
  let lastSaveTime = 0;
  video.addEventListener('timeupdate', () => {
    const now = Date.now();
    if (now - lastSaveTime < 8000) return;
    lastSaveTime = now;
    if (!video.paused) {
      saveProgress();
    }
  });

  // 2. Immediate save on important state transitions
  video.addEventListener('pause', () => {
    saveProgress();
  });
  video.addEventListener('seeked', () => {
    saveProgress();
  });

  // 3. Save when unloading the page
  window.addEventListener('pagehide', () => {
    saveProgress();
  });
  window.savePlayerProgress = saveProgress;
}

function renderBookmarks() {
  const outlet = document.getElementById('view-outlet');
  if (state.bookmarks.length === 0) {
    outlet.innerHTML = '<div class="loading">No bookmarks saved yet.</div>';
    return;
  }

  outlet.innerHTML = `
    <div class="grid-container">
      ${state.bookmarks.map(item => `
        <div class="media-card" onclick="window.location.hash = '#/detail?url=${encodeURIComponent(item.url)}&provider=${encodeURIComponent(item.apiName)}'">
          <img class="card-poster" src="${proxyImage(item.posterUrl)}" alt="${item.name}">
          <div class="card-info">
            <div class="card-title">${item.name}</div>
            <div class="card-metadata">
              <span>${item.apiName}</span>
            </div>
          </div>
        </div>
      `).join('')}
    </div>
  `;
}

async function renderDownloads() {
  const outlet = document.getElementById('view-outlet');
  outlet.innerHTML = '<div class="loading">Loading downloads...</div>';

  try {
    const res = await fetch(`${API_BASE}/downloads`);
    const downloads = await res.json();

    if (downloads.length === 0) {
      outlet.innerHTML = '<div class="loading">No downloads found on server.</div>';
      return;
    }

    outlet.innerHTML = `
      <div style="display:flex; flex-direction:column; gap:16px;">
        ${downloads.map(d => {
          const progress = d.bytesTotal > 0 ? Math.round((d.bytesLoaded / d.bytesTotal) * 100) : 0;
          const loadedMb = (d.bytesLoaded / (1024 * 1024)).toFixed(1);
          const totalMb = (d.bytesTotal / (1024 * 1024)).toFixed(1);
          
          return `
            <div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.05); padding:20px; border-radius:var(--border-radius-md);">
              <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">
                <div>
                  <div style="font-weight:600; font-size:16px;">${d.title}</div>
                  <div style="font-size:12px; color:var(--color-grayTextColor); margin-top:4px;">${d.status} • ${loadedMb} MB / ${d.bytesTotal > 0 ? `${totalMb} MB` : 'Unknown'}</div>
                </div>
                <button class="btn" style="padding: 8px 16px; font-size:14px; background:#FF6F63;" onclick="deleteDownload('${d.id}')">Delete</button>
              </div>
              <div style="width:100%; height:6px; background:rgba(255,255,255,0.05); border-radius:3px; overflow:hidden;">
                <div style="width:${progress}%; height:100%; background:var(--color-colorPrimary); transition:width 0.3s ease;"></div>
              </div>
            </div>
          `;
        }).join('')}
      </div>
    `;
  } catch (e) {
    outlet.innerHTML = '<div class="loading">Failed to load downloads list.</div>';
  }
}

async function deleteDownload(id) {
  if (!confirm('Are you sure you want to delete this download?')) return;
  showToast('Deleting download...');
  try {
    const res = await fetch(`${API_BASE}/downloads?id=${encodeURIComponent(id)}`, {
      method: 'DELETE'
    });
    if (res.ok) {
      showToast('Download deleted.');
      renderDownloads();
    } else {
      showToast('Failed to delete download.');
    }
  } catch (e) {
    showToast('Error deleting download.');
  }
}

async function renderHistory() {
  const outlet = document.getElementById('view-outlet');
  outlet.innerHTML = '<div class="loading">Loading History...</div>';
  try {
    const res = await fetch(`${API_BASE}/history`);
    const historyList = await res.json();
    if (historyList.length === 0) {
      outlet.innerHTML = '<div class="loading">No watch history found.</div>';
      return;
    }

    outlet.innerHTML = `
      <div style="max-height: 600px; overflow-y: auto;">
        ${historyList.map(item => {
          const displayTitle = item.title || 'Untitled';
          const prov = item.provider || '';
          const timeStr = item.updatedAt ? new Date(item.updatedAt).toLocaleString() : 'Recently';
          const pct = item.durationMs ? Math.round((item.positionMs / item.durationMs) * 100) : 0;
          return `
            <div style="display:flex; justify-content:space-between; align-items:center; padding:16px; border-bottom:1px solid rgba(255,255,255,0.03);">
              <div>
                <div style="font-weight:600;">${displayTitle}</div>
                <div style="font-size:12px; color:var(--color-grayTextColor); margin-top:4px;">
                  Played via ${prov} on ${timeStr} · ${pct}% watched
                </div>
              </div>
              <button class="btn" style="padding: 8px 16px; font-size:14px; background:var(--color-colorPrimary);" 
                      onclick="playMedia('${escAttr(item.id)}', '${escAttr(prov)}', '${escAttr(displayTitle)}', '${escAttr(item.parentId || '')}', '${escAttr(item.posterUrl || '')}', ${item.seasonNum !== null ? item.seasonNum : 'null'}, ${item.episodeNum !== null ? item.episodeNum : 'null'})">
                Replay
              </button>
            </div>
          `;
        }).join('')}
      </div>
    `;
  } catch (e) {
    outlet.innerHTML = '<div class="loading">Failed to load watch history.</div>';
  }
}

async function renderPlugins() {
  const outlet = document.getElementById('view-outlet');
  outlet.innerHTML = '<div class="loading">Loading plugins...</div>';

  try {
    const res = await fetch(`${API_BASE}/plugins`);
    const plugins = await res.json();

    outlet.innerHTML = `
      <div style="margin-bottom: 24px; display:flex; justify-content:space-between; align-items:center;">
        <h3>Installed Plugins</h3>
        <label class="btn btn-primary" style="cursor:pointer; display:inline-flex; align-items:center;">
          Install JAR Plugin
          <input type="file" id="plugin-file-input" accept=".jar" style="display:none;" />
        </label>
      </div>
      <div style="display:flex; flex-direction:column; gap:12px;">
        ${plugins.length === 0 ? '<div class="loading">No plugins installed.</div>' : plugins.map(p => `
          <div class="plugin-row ${p.enabled ? '' : 'plugin-disabled'}">
            <div>
              <div style="font-weight:600; font-size:16px;">${p.name}</div>
              <div style="font-size:12px; color:var(--color-grayTextColor); margin-top:4px;">${p.jarName}${p.embedded ? ' · Embedded' : ' · Uploaded'}${p.pluginClassName ? ` · ${p.pluginClassName}` : ''}</div>
            </div>
            <label class="plugin-toggle">
              <input type="checkbox" data-plugin-jar="${p.jarName}" ${p.enabled ? 'checked' : ''}>
              <span class="plugin-toggle-track"><span></span></span>
              <strong>${p.enabled ? (p.loaded ? 'Enabled' : 'Failed') : 'Disabled'}</strong>
            </label>
          </div>
        `).join('')}
      </div>
    `;

    outlet.querySelectorAll('[data-plugin-jar]').forEach(toggle => {
      toggle.addEventListener('change', async () => {
        const enabled = toggle.checked;
        const scrollPosition = outlet.scrollTop;
        toggle.disabled = true;
        showToast(`${enabled ? 'Enabling' : 'Disabling'} ${toggle.dataset.pluginJar}...`);
        try {
          const response = await fetch(`${API_BASE}/plugins/${encodeURIComponent(toggle.dataset.pluginJar)}/enabled`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled })
          });
          if (!response.ok) throw new Error(await response.text());
          showToast(`${enabled ? 'Enabled' : 'Disabled'} ${toggle.dataset.pluginJar}`);
          await renderPlugins();
          requestAnimationFrame(() => {
            const refreshedOutlet = document.getElementById('view-outlet');
            if (refreshedOutlet) refreshedOutlet.scrollTop = scrollPosition;
          });
        } catch (error) {
          toggle.checked = !enabled;
          toggle.disabled = false;
          showToast(error.message || 'Failed to change plugin state.');
        }
      });
    });

    document.getElementById('plugin-file-input').addEventListener('change', async (e) => {
      const file = e.target.files[0];
      if (!file) return;

      showToast('Uploading plugin...');
      try {
        const fileBytes = await file.arrayBuffer();
        const uploadRes = await fetch(`${API_BASE}/plugins/install`, {
          method: 'POST',
          body: fileBytes
        });
        
        if (uploadRes.ok) {
          showToast('Plugin installed successfully!');
          renderPlugins();
        } else {
          showToast('Failed to install plugin.');
        }
      } catch (err) {
        showToast('Error uploading plugin JAR.');
      }
    });

  } catch (e) {
    outlet.innerHTML = '<div class="loading">Failed to load plugins.</div>';
  }
}

// Helper to send logs to the server
function serverLog(level, tag, message) {
  console.log(`[${tag}] ${message}`);
  fetch(`${API_BASE}/log`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ level, tag, message })
  }).catch(() => {});
}

// Dynamic script injection helper
function loadScript(url) {
  return new Promise((resolve, reject) => {
    if (document.querySelector(`script[src="${url}"]`)) {
      return resolve();
    }
    const script = document.createElement('script');
    script.src = url;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`Failed to load script: ${url}`));
    document.head.appendChild(script);
  });
}

// Global flags for loaded components
let libavInitialized = false;
let subtitlesOctopusInitialized = false;

async function ensureLibav() {
  if (libavInitialized) return;
  showToast("Loading client-side WASM Demuxer...");
  // Using official variant-webcodecs package from jsDelivr for full container and muxer support
  await loadScript("https://cdn.jsdelivr.net/npm/@libav.js/variant-webcodecs@6.9.8/dist/libav-webcodecs.js");
  libavInitialized = true;
  showToast("WASM Demuxer loaded successfully.");
}

async function ensureSubtitlesOctopus() {
  if (subtitlesOctopusInitialized) return;
  showToast("Loading Styled Subtitle Renderer...");
  await loadScript("https://cdn.jsdelivr.net/npm/subtitles-octopus@4.0.0/dist/subtitles-octopus.js");
  subtitlesOctopusInitialized = true;
  showToast("Styled Subtitle Renderer loaded.");
}

function getBackendTranscodeUrl(streamUrl, route, source) {
  const caps = checkBrowserCapabilities();
  const supportedVideos = Object.keys(caps.videoCodecs).filter(k => caps.videoCodecs[k]).join(',');
  // canPlayType() may report AC3/E-AC3 support for native playback, but
  // those codecs are not safe in the fragmented-MP4/MSE fallback on iPad.
  const safeAudioCodecs = new Set(['aac', 'mp3']);
  const supportedAudios = Object.keys(caps.audioCodecs)
    .filter(k => caps.audioCodecs[k] && safeAudioCodecs.has(k))
    .join(',');
  const targetUrl = streamUrl.startsWith('/')
    ? streamUrl
    : new URL(streamUrl, window.location.origin).href;
  return `${API_BASE}/transcode?url=${encodeURIComponent(targetUrl)}` +
    `&referer=${encodeURIComponent(source?.referer || route?.referer || '')}` +
    `&supportedVideoCodecs=${encodeURIComponent(supportedVideos)}` +
    `&supportedAudioCodecs=${encodeURIComponent(supportedAudios)}`;
}

function playTranscodeStreamViaMse(videoElement, transcodeUrl) {
  const mediaSource = new MediaSource();
  videoElement.src = URL.createObjectURL(mediaSource);
  videoElement.load();

  mediaSource.addEventListener('sourceopen', async () => {
    try {
      const mimeType = 'video/mp4; codecs="avc1.640028, mp4a.40.2"';
      const sourceBuffer = mediaSource.addSourceBuffer(mimeType);

      const writeQueue = [];
      function appendToSourceBuffer(buffer) {
        if (sourceBuffer.updating) {
          writeQueue.push(buffer);
        } else {
          sourceBuffer.appendBuffer(buffer);
        }
      }

      sourceBuffer.addEventListener('updateend', () => {
        if (writeQueue.length > 0 && !sourceBuffer.updating) {
          sourceBuffer.appendBuffer(writeQueue.shift());
        }
      });

      const response = await fetch(transcodeUrl);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const reader = response.body.getReader();

      videoElement.addEventListener('emptied', () => {
        try { reader.cancel(); } catch(_) {}
      }, { once: true });

      while (mediaSource.readyState === 'open') {
        const { done, value } = await reader.read();
        if (done) {
          if (mediaSource.readyState === 'open') {
            mediaSource.endOfStream();
          }
          break;
        }
        appendToSourceBuffer(value);
      }
    } catch (err) {
      console.error("[Transcode MSE Error]", err);
      serverLog("ERROR", "TranscodeMse", `Stream reader error: ${err.message || err}`);
    }
  });
}

function fallbackToBackendTranscode(videoElement, streamUrl, route, source) {
  if (videoElement.dataset.backendTranscodeAttempted === 'true') return false;
  videoElement.dataset.backendTranscodeAttempted = 'true';
  showToast('Browser playback failed. Starting server-side remux/transcode...');
  
  const transcodeUrl = getBackendTranscodeUrl(streamUrl, route, source);
  
  const isSafari = /^((?!chrome|android).)*safari/i.test(navigator.userAgent) || 
                   /iPad|iPhone|iPod/.test(navigator.platform) ||
                   (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

  if (isSafari) {
    playTranscodeStreamViaMse(videoElement, transcodeUrl);
  } else {
    videoElement.src = transcodeUrl;
    videoElement.load();
  }
  return true;
}

async function initiateWasmPlayback(videoElement, streamUrl, route, subtitles = [], source = null) {
  try {
    // 1. Initialize WASM dependencies
    await ensureLibav();

    // Setup MediaSource if remuxing
    // Setup MediaSource if remuxing
    if (route.method === 'REMUX_COPY' || route.method === 'REMUX_TRANSCODE') {
      const isSafari = /^((?!chrome|android).)*safari/i.test(navigator.userAgent) || 
                       /iPad|iPhone|iPod/.test(navigator.platform) ||
                       (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
      
      const libavOpts = {};
      if (isSafari || typeof window.SharedArrayBuffer === 'undefined') {
        libavOpts.noworker = true;
      }

      const factory = window.LibAV || window.Libav;
      const libav = await factory.LibAV(libavOpts);
      showToast(`Initializing remuxer for ${route.container.toUpperCase()} container...`);

      // Helper to get total size of remote media file (via HEAD or bytes=0-0 GET via proxy)
      async function getFileSize(url) {
        const proxyUrl = `${API_BASE}/proxy?url=${encodeURIComponent(url)}&referer=${encodeURIComponent(route?.referer || '')}`;
        try {
          const res = await fetch(proxyUrl, { method: 'HEAD' });
          if (res.ok) {
            const len = res.headers.get('Content-Length');
            if (len) {
              const size = parseInt(len, 10);
              serverLog("INFO", "WasmInit", `File size resolved via Proxy HEAD: ${size} bytes`);
              return size;
            }
          }
        } catch (err) {
          console.warn("Proxy HEAD request failed, trying range GET", err);
        }
        try {
          const res = await fetch(proxyUrl, { headers: { 'Range': 'bytes=0-0' } });
          if (res.status === 206 || res.ok) {
            const contentRange = res.headers.get('Content-Range');
            if (contentRange) {
              const parts = contentRange.split('/');
              if (parts.length > 1) {
                const size = parseInt(parts[1], 10);
                if (!isNaN(size)) {
                  serverLog("INFO", "WasmInit", `File size resolved via Proxy Content-Range: ${size} bytes`);
                  return size;
                }
              }
            }
            const len = res.headers.get('Content-Length');
            if (len) {
              const size = parseInt(len, 10);
              serverLog("INFO", "WasmInit", `File size resolved via Proxy Content-Length: ${size} bytes`);
              return size;
            }
          }
        } catch (err) {
          console.error("Failed to get file size via proxy", err);
        }
        serverLog("WARN", "WasmInit", "Failed to resolve file size via proxy, using 1GB fallback");
        return 1024 * 1024 * 1024; // 1GB fallback
      }

      const fileName = 'input.' + route.container;
      const size = await getFileSize(streamUrl);
      await libav.mkblockreaderdev(fileName, size);

      libav.onblockread = async (name, position, length) => {
        try {
          const res = await fetch(streamUrl, { headers: { 'Range': `bytes=${position}-${position + length - 1}` } });
          const buf = await res.arrayBuffer();
          await libav.ff_block_reader_dev_send(name, position, new Uint8Array(buf));
        } catch (err) {
          console.error("[onblockread error]", err);
          await libav.ff_block_reader_dev_send(name, position, new Uint8Array(0));
        }
      };

      // Load container headers and analyze streams
      const [fmt_ctx, streams] = await libav.ff_init_demuxer_file(fileName);

      // Find audio and video tracks
      const videoStream = streams.find(s => s.codec_type === libav.AVMEDIA_TYPE_VIDEO);
      const audioStream = streams.find(s => s.codec_type === libav.AVMEDIA_TYPE_AUDIO);

       const vCodecName = videoStream ? await libav.avcodec_get_name(videoStream.codec_id) : 'none';
       const aCodecName = audioStream ? await libav.avcodec_get_name(audioStream.codec_id) : 'none';
       serverLog("INFO", "WasmInit", `Parsed streams - Video codec: ${vCodecName}, Audio codec: ${aCodecName}`);

       // The browser MSE output is fragmented MP4 with packet-copy only.
       // AC3/DTS/HEVC and similar streams must use the server FFmpeg path;
       // attempting to append them here produces an InvalidStateError.
       const copyCompatible = (!videoStream || ['h264', 'avc1'].includes(String(vCodecName).toLowerCase())) &&
         (!audioStream || String(aCodecName).toLowerCase() === 'aac');
       if (!copyCompatible) {
         serverLog("INFO", "WasmInit", `Unsupported copy-remux stream ${vCodecName}/${aCodecName}; falling back to server transcode`);
         fallbackToBackendTranscode(videoElement, streamUrl, route, source);
         return;
       }

       // Build media source and set source src
      const mediaSource = new MediaSource();
      videoElement.src = URL.createObjectURL(mediaSource);
      videoElement.load(); // Reset player error state and initiate loading

      mediaSource.addEventListener('sourceopen', async () => {
        try {
          // Build MSE SourceBuffer using dynamic capabilities detection synchronously first
          let codecs = [];
          if (videoStream) {
            if (videoStream.codec_id === libav.AV_CODEC_ID_HEVC) {
              codecs.push('hvc1');
            } else {
              codecs.push('avc1.640028');
            }
          }
          if (audioStream) {
            if (audioStream.codec_id === libav.AV_CODEC_ID_AAC) {
              codecs.push('mp4a.40.2');
            } else if (audioStream.codec_id === libav.AV_CODEC_ID_AC3) {
              codecs.push('ac-3');
            } else if (audioStream.codec_id === libav.AV_CODEC_ID_EAC3) {
              codecs.push('ec-3');
            } else {
              codecs.push('mp4a.40.2');
            }
          }
          const mimeType = `video/mp4; codecs="${codecs.join(', ')}"`;
          const sourceBuffer = mediaSource.addSourceBuffer(mimeType);

          // Initialize writer device for capture (async)
          await libav.mkwriterdev('output.mp4');

          // MSE update end queue management
          const writeQueue = [];
          function appendToSourceBuffer(buffer) {
            if (sourceBuffer.updating) {
              writeQueue.push(buffer);
            } else {
              sourceBuffer.appendBuffer(buffer);
            }
          }

          sourceBuffer.addEventListener('updateend', () => {
            if (writeQueue.length > 0 && !sourceBuffer.updating) {
              const nextBuf = writeQueue.shift();
              sourceBuffer.appendBuffer(nextBuf);
            }
          });

          // Intercept output chunks and push to MSE
          libav.onwrite = (name, position, buffer) => {
            if (name === 'output.mp4') {
              appendToSourceBuffer(new Uint8Array(buffer));
            }
          };

          const videoTb = videoStream ? (videoStream.time_base || [1, 90000]) : [1, 90000];
          const audioTb = audioStream ? (audioStream.time_base || [1, 48000]) : [1, 48000];
          
          const videoTbNum = Array.isArray(videoTb) ? videoTb[0] : (videoTb.num || 1);
          const videoTbDen = Array.isArray(videoTb) ? videoTb[1] : (videoTb.den || 90000);
          const audioTbNum = Array.isArray(audioTb) ? audioTb[0] : (audioTb.num || 1);
          const audioTbDen = Array.isArray(audioTb) ? audioTb[1] : (audioTb.den || 48000);

          let videoStreamIndexInMuxer = -1;
          let audioStreamIndexInMuxer = -1;
          const muxerStreams = [];

          if (videoStream) {
            videoStreamIndexInMuxer = muxerStreams.length;
            muxerStreams.push([videoStream.codecpar, videoTbNum, videoTbDen]);
          }
          if (audioStream) {
            audioStreamIndexInMuxer = muxerStreams.length;
            muxerStreams.push([audioStream.codecpar, audioTbNum, audioTbDen]);
          }

          // Configure output muxer with codec parameters directly (no transcoder contexts)
          const [out_fmt_ctx, , , out_streams] = await libav.ff_init_muxer({
            format_name: 'mp4',
            filename: 'output.mp4',
            open: true,
            codecpars: true
          }, muxerStreams);

          // Configure movflags for fragmented MP4 streaming via options dictionary
          await libav.av_opt_set(out_fmt_ctx, "movflags", "fragmented+empty_moov+default_base_moof+frag_keyframe", 0);

          // Write fragmented MP4 format header
          await libav.avformat_write_header(out_fmt_ctx, 0);

          // Demux loop
          let active = true;
          const pkt = await libav.av_packet_alloc();

          videoElement.addEventListener('emptied', () => { active = false; });

          async function demuxLoop() {
            if (!active) {
              await libav.av_packet_free(pkt);
              return;
            }
            try {
              const ret = await libav.av_read_frame(fmt_ctx, pkt);
              if (ret >= 0) {
                const streamIndex = await libav.AVPacket_stream_index(pkt);
                if (videoStream && streamIndex === videoStream.index) {
                  await libav.AVPacket_stream_index_s(pkt, videoStreamIndexInMuxer);
                  await libav.av_interleaved_write_frame(out_fmt_ctx, pkt);
                } else if (audioStream && streamIndex === audioStream.index) {
                  await libav.AVPacket_stream_index_s(pkt, audioStreamIndexInMuxer);
                  await libav.av_interleaved_write_frame(out_fmt_ctx, pkt);
                }
                await libav.av_packet_unref(pkt);
                // Throttled schedule
                setTimeout(demuxLoop, 5);
              } else {
                // EOF or demux error - flush trailer and cleanup
                await libav.av_write_trailer(out_fmt_ctx);
                serverLog("INFO", "WasmDemux", `Reached EOF or read error (code ${ret}), trailer written`);
                await libav.av_packet_free(pkt);
              }
            } catch (err) {
              console.error("[WASM Demux Error]", err);
              serverLog("ERROR", "WasmDemux", `Demux loop error: ${err.message || err}`);
              await libav.av_packet_free(pkt);
              fallbackToBackendTranscode(videoElement, streamUrl, route, source);
            }
          }

          demuxLoop();
        } catch (err) {
          console.error("Failed to setup MSE demuxer:", err);
          showToast("Failed to initialize WASM demuxer: " + err.message);
          serverLog("ERROR", "WasmInit", `Failed to setup MSE demuxer: ${err.message || err}. Stack: ${err.stack || ''}`);
          fallbackToBackendTranscode(videoElement, streamUrl, route, source);
        }
      });
    } else if (route.method === 'SOFT_DECODE') {
      showToast("Software decoding is currently simulated; rendering directly to canvas overlay.");
      videoElement.src = streamUrl; // Fallback to let system try
    }

    // 2. Setup Subtitles (ASS/SSA styled subtitles canvas renderer)
    const assSubtitle = subtitles.find(s => s.url && (s.url.includes('.ass') || s.url.includes('.ssa')));
    if (assSubtitle) {
      await ensureSubtitlesOctopus();
      const subCanvas = document.getElementById('subtitle-canvas-overlay');
      const octopusOptions = {
        video: videoElement,
        subUrl: assSubtitle.url,
        workerUrl: 'https://cdn.jsdelivr.net/npm/subtitles-octopus@4.0.0/dist/subtitles-octopus-worker.js',
        canvas: subCanvas
      };
      window.currentSubtitleOctopus = new window.SubtitlesOctopus(octopusOptions);
      showToast("ASS Subtitles initialized via WASM overlay.");
    }
  } catch (err) {
    console.error("[WASM Playback Init Error]", err);
    showToast("WASM Playback Failed. Falling back to backend server transcode...");
    serverLog("ERROR", "WasmInit", `WASM Playback Init Error: ${err.message || err}. Stack: ${err.stack || ''}`);
    
    // Obtain browser capability lists dynamically
    fallbackToBackendTranscode(videoElement, streamUrl, route, source);
  }
}

// Window load init
window.addEventListener('DOMContentLoaded', init);
window.playMedia = playMedia;
window.deleteDownload = deleteDownload;
