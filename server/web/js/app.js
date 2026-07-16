// Dynamic Router & SPA Handler
const API_BASE = '/api/v1';

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
  if (window.currentProgressInterval) {
    clearInterval(window.currentProgressInterval);
    window.currentProgressInterval = null;
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
  };
  document.getElementById('page-title').innerText = titleMap[routePath] || 'CloudStream';

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
  outlet.innerHTML = '<div class="loading">Loading Home...</div>';

  if (!state.activeProvider) {
    outlet.innerHTML = `
      <div style="text-align: center; margin-top: 80px;">
        <h2 style="margin-bottom: 16px;">Welcome to CloudStream Web!</h2>
        <p style="color: var(--color-grayTextColor); margin-bottom: 24px;">Please select an active provider from the top-right corner to browse recommendations.</p>
      </div>
    `;
    return;
  }

  try {
    // To implement the lightest solution, we will search for empty string or general keyword to act as homepage content,
    // or trigger provider.getMainPage() if available.
    // Let's run a default search query "popular" or "trending" on the provider to get content quickly
    const res = await fetch(`${API_BASE}/search?q=popular&provider=${state.activeProvider}`);
    const results = await res.json();
    
    if (results.length === 0) {
      outlet.innerHTML = '<div class="loading">No content found. Try searching.</div>';
      return;
    }

    outlet.innerHTML = `
      <div class="carousel-section">
        <h3 class="carousel-title">Trending Content</h3>
        <div class="grid-container">
          ${results.map(item => `
            <div class="media-card" onclick="window.location.hash = '#/detail?url=${encodeURIComponent(item.url)}&provider=${encodeURIComponent(item.apiName)}'">
              <img class="card-poster" src="${item.posterUrl || 'https://via.placeholder.com/300x450'}" alt="${item.name}">
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
      </div>
    `;
  } catch (e) {
    outlet.innerHTML = '<div class="loading">Failed to load content.</div>';
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
      const url = `${API_BASE}/search?q=${encodeURIComponent(q)}${state.activeProvider ? `&provider=${state.activeProvider}` : ''}`;
      const res = await fetch(url);
      const results = await res.json();

      if (results.length === 0) {
        resultsArea.innerHTML = '<div class="loading">No results found.</div>';
        return;
      }

      resultsArea.innerHTML = `
        <div class="grid-container">
          ${results.map(item => `
            <div class="media-card" onclick="window.location.hash = '#/detail?url=${encodeURIComponent(item.url)}&provider=${encodeURIComponent(item.apiName)}'">
              <img class="card-poster" src="${item.posterUrl || 'https://via.placeholder.com/300x450'}" alt="${item.name}">
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

    outlet.innerHTML = `
      <div class="detail-container">
        <img class="detail-poster" src="${details.posterUrl || 'https://via.placeholder.com/300x450'}" alt="${details.name}">
        <div class="detail-content">
          <h2 class="detail-title">${details.name}</h2>
          <div class="detail-meta-row">
            <span>${details.type}</span>
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

          <h3>Episodes</h3>
          <div style="margin-top: 16px; max-height: 400px; overflow-y: auto;">
            ${details.episodes.map(ep => `
              <div class="episode-row" style="display:flex; justify-content:space-between; align-items:center; padding: 12px; border-bottom: 1px solid rgba(255,255,255,0.03); cursor:pointer;" onclick="playMedia('${ep.url}', '${details.apiName}', '${details.name} - ${ep.name || `Episode ${ep.episode}`}')">
                <span>${ep.name || `Episode ${ep.episode || 1}`}</span>
                <span style="color: var(--color-grayTextColor); font-size:14px;">Play</span>
              </div>
            `).join('')}
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
        playMedia(details.episodes[0].url, details.apiName, `${details.name}`);
      } else {
        showToast('No links available.');
      }
    });

  } catch (e) {
    outlet.innerHTML = '<div class="loading">Failed to load details.</div>';
  }
}

async function playMedia(url, provider, title) {
  showToast('Fetching video links...');
  try {
    const res = await fetch(`${API_BASE}/links`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ data: url, provider })
    });
    const data = await res.json();
    
    if (data.links.length === 0) {
      showToast('No playable video sources found.');
      return;
    }
    
    // Play the first link
    const firstLink = data.links[0];
    let streamUrl = firstLink.url;
    let referer = firstLink.referer || '';

    // If source requires specialized headers (like referer), route it through Ktor stream proxy
    if (firstLink.headers && Object.keys(firstLink.headers).length > 0 || referer) {
      streamUrl = `${API_BASE}/proxy?url=${encodeURIComponent(streamUrl)}&referer=${encodeURIComponent(referer)}`;
    }
    
    // Save to local history
    const historyItem = { title, url, provider, timestamp: Date.now() };
    state.history = [historyItem, ...state.history.filter(h => h.url !== url)].slice(0, 50);
    localStorage.setItem('cs_history', JSON.stringify(state.history));
    
    // Redirect SPA router to embedded video player view
    window.location.hash = `#/player?url=${encodeURIComponent(streamUrl)}&title=${encodeURIComponent(title)}&provider=${encodeURIComponent(provider)}&id=${encodeURIComponent(url)}`;
  } catch (e) {
    showToast('Error loading video sources.');
  }
}

function renderPlayer() {
  const outlet = document.getElementById('view-outlet');
  const params = getQueryParams();
  
  if (!params.url || !params.title || !params.id) {
    window.location.hash = '#/';
    return;
  }

  outlet.innerHTML = `
    <div class="player-container">
      <a href="#/history" class="player-back-btn" id="player-close-btn">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
        Back
      </a>
      <div class="player-header">
        <span class="player-title" id="player-title-text">${params.title}</span>
      </div>
      <video id="video-element" class="player-video" controls autoplay></video>
    </div>
  `;

  const video = document.getElementById('video-element');
  const streamUrl = params.url;

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

  // Initialize HLS player if needed
  if (Hls.isSupported() && (streamUrl.includes('.m3u8') || streamUrl.includes('/proxy?url='))) {
    const hls = new Hls({
      maxMaxBufferLength: 30
    });
    hls.loadSource(streamUrl);
    hls.attachMedia(video);
    window.currentHls = hls;
  } else {
    video.src = streamUrl;
  }

  // Periodic watch progress logging to server backend DB
  window.currentProgressInterval = setInterval(() => {
    if (!video.duration || video.paused) return;
    
    fetch(`${API_BASE}/history`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        id: params.id,
        parentId: params.url, // Episode URL serves as context reference
        positionMs: Math.round(video.currentTime * 1000),
        durationMs: Math.round(video.duration * 1000)
      })
    }).catch(console.error);
  }, 8000);
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
          <img class="card-poster" src="${item.posterUrl || 'https://via.placeholder.com/300x450'}" alt="${item.name}">
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

function renderHistory() {
  const outlet = document.getElementById('view-outlet');
  if (state.history.length === 0) {
    outlet.innerHTML = '<div class="loading">No watch history found.</div>';
    return;
  }

  outlet.innerHTML = `
    <div style="max-height: 600px; overflow-y: auto;">
      ${state.history.map(item => `
        <div style="display:flex; justify-content:space-between; align-items:center; padding:16px; border-bottom:1px solid rgba(255,255,255,0.03);">
          <div>
            <div style="font-weight:600;">${item.title}</div>
            <div style="font-size:12px; color:var(--color-grayTextColor); margin-top:4px;">Played via ${item.provider} on ${new Date(item.timestamp).toLocaleString()}</div>
          </div>
          <button class="btn" style="padding: 8px 16px; font-size:14px; background:var(--color-colorPrimary);" onclick="playMedia('${item.url}', '${item.provider}', '${item.title}')">Replay</button>
        </div>
      `).join('')}
    </div>
  `;
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
          <div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.05); padding:16px; border-radius:var(--border-radius-md); display:flex; justify-content:space-between; align-items:center;">
            <div>
              <div style="font-weight:600; font-size:16px;">${p.name}</div>
              <div style="font-size:12px; color:var(--color-grayTextColor); margin-top:4px;">Class: ${p.pluginClassName}</div>
            </div>
            <span style="color:#48E484; font-weight:600; font-size:14px;">Active</span>
          </div>
        `).join('')}
      </div>
    `;

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

// Window load init
window.addEventListener('DOMContentLoaded', init);
window.playMedia = playMedia;
window.deleteDownload = deleteDownload;
