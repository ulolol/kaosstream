(function(){const t=document.createElement("link").relList;if(t&&t.supports&&t.supports("modulepreload"))return;for(const i of document.querySelectorAll('link[rel="modulepreload"]'))e(i);new MutationObserver(i=>{for(const n of i)if(n.type==="childList")for(const s of n.addedNodes)s.tagName==="LINK"&&s.rel==="modulepreload"&&e(s)}).observe(document,{childList:!0,subtree:!0});function a(i){const n={};return i.integrity&&(n.integrity=i.integrity),i.referrerPolicy&&(n.referrerPolicy=i.referrerPolicy),i.crossOrigin==="use-credentials"?n.credentials="include":i.crossOrigin==="anonymous"?n.credentials="omit":n.credentials="same-origin",n}function e(i){if(i.ep)return;i.ep=!0;const n=a(i);fetch(i.href,n)}})();const l="/api/v1",d={activeProvider:localStorage.getItem("cs_active_provider")||"",providers:[],history:JSON.parse(localStorage.getItem("cs_history")||"[]"),bookmarks:[]};function r(o){const t=document.getElementById("toast-container"),a=document.createElement("div");a.className="toast",a.innerText=o,t.appendChild(a),setTimeout(()=>{a.style.opacity="0",setTimeout(()=>a.remove(),300)},3e3)}const $={"/":h,"/search":T,"/bookmarks":L,"/history":M,"/plugins":b,"/detail":g,"/player":I,"/downloads":w};async function k(){window.addEventListener("hashchange",v);try{const o=await fetch(`${l}/providers`);d.providers=await o.json(),x()}catch{r("Failed to connect to backend server.")}await m(),v()}function x(){const o=document.getElementById("active-provider-select");o.innerHTML='<option value="">All Providers</option>',d.providers.forEach(t=>{const a=document.createElement("option");a.value=t.name,a.innerText=t.name,t.name===d.activeProvider&&(a.selected=!0),o.appendChild(a)}),o.addEventListener("change",t=>{d.activeProvider=t.target.value,localStorage.setItem("cs_active_provider",d.activeProvider),v()})}async function m(){try{const o=await fetch(`${l}/bookmarks`);d.bookmarks=await o.json()}catch(o){console.error("Failed to sync bookmarks:",o)}}function v(){var s;const o=window.location.hash||"#/";window.currentHls&&(window.currentHls.destroy(),window.currentHls=null),window.currentProgressInterval&&(clearInterval(window.currentProgressInterval),window.currentProgressInterval=null),document.querySelectorAll(".nav-item").forEach(p=>{p.classList.remove("active")});let t=o.substring(1);t.includes("?")&&(t=t.split("?")[0]);const e={"/":"nav-home","/search":"nav-search","/bookmarks":"nav-bookmarks","/history":"nav-history","/plugins":"nav-plugins","/downloads":"nav-downloads"}[t];e&&((s=document.getElementById(e))==null||s.classList.add("active"));const i=$[t]||h,n={"/":"Home","/search":"Search","/bookmarks":"Bookmarks","/history":"History","/plugins":"Plugins","/detail":"Details","/player":"Player","/downloads":"Downloads"};document.getElementById("page-title").innerText=n[t]||"CloudStream",i()}function y(){const o=window.location.hash;if(!o.includes("?"))return{};const t=o.split("?")[1],a={};return t.split("&").forEach(e=>{const[i,n]=e.split("=");a[i]=decodeURIComponent(n)}),a}async function h(){const o=document.getElementById("view-outlet");if(o.innerHTML='<div class="loading">Loading Home...</div>',!d.activeProvider){o.innerHTML=`
      <div style="text-align: center; margin-top: 80px;">
        <h2 style="margin-bottom: 16px;">Welcome to CloudStream Web!</h2>
        <p style="color: var(--color-grayTextColor); margin-bottom: 24px;">Please select an active provider from the top-right corner to browse recommendations.</p>
      </div>
    `;return}try{const a=await(await fetch(`${l}/search?q=popular&provider=${d.activeProvider}`)).json();if(a.length===0){o.innerHTML='<div class="loading">No content found. Try searching.</div>';return}o.innerHTML=`
      <div class="carousel-section">
        <h3 class="carousel-title">Trending Content</h3>
        <div class="grid-container">
          ${a.map(e=>`
            <div class="media-card" onclick="window.location.hash = '#/detail?url=${encodeURIComponent(e.url)}&provider=${encodeURIComponent(e.apiName)}'">
              <img class="card-poster" src="${e.posterUrl||"https://via.placeholder.com/300x450"}" alt="${e.name}">
              <div class="card-info">
                <div class="card-title">${e.name}</div>
                <div class="card-metadata">
                  <span>${e.type||"Media"}</span>
                  ${e.score?`<span class="rating-badge">★ ${e.score.toFixed(1)}</span>`:""}
                </div>
              </div>
            </div>
          `).join("")}
        </div>
      </div>
    `}catch{o.innerHTML='<div class="loading">Failed to load content.</div>'}}async function T(){const o=document.getElementById("view-outlet");o.innerHTML=`
    <div class="search-container">
      <input type="text" class="search-input" id="search-box" placeholder="Search movies, shows, or anime..." />
      <button class="btn btn-primary" id="search-btn">Search</button>
    </div>
    <div id="search-results"></div>
  `;const t=document.getElementById("search-box"),a=document.getElementById("search-btn"),e=async()=>{const i=t.value.trim();if(!i)return;const n=document.getElementById("search-results");n.innerHTML='<div class="loading">Searching...</div>';try{const s=`${l}/search?q=${encodeURIComponent(i)}${d.activeProvider?`&provider=${d.activeProvider}`:""}`,u=await(await fetch(s)).json();if(u.length===0){n.innerHTML='<div class="loading">No results found.</div>';return}n.innerHTML=`
        <div class="grid-container">
          ${u.map(c=>`
            <div class="media-card" onclick="window.location.hash = '#/detail?url=${encodeURIComponent(c.url)}&provider=${encodeURIComponent(c.apiName)}'">
              <img class="card-poster" src="${c.posterUrl||"https://via.placeholder.com/300x450"}" alt="${c.name}">
              <div class="card-info">
                <div class="card-title">${c.name}</div>
                <div class="card-metadata">
                  <span>${c.type||"Media"}</span>
                  ${c.score?`<span class="rating-badge">★ ${c.score.toFixed(1)}</span>`:""}
                </div>
              </div>
            </div>
          `).join("")}
        </div>
      `}catch{n.innerHTML='<div class="loading">Error performing search.</div>'}};a.addEventListener("click",e),t.addEventListener("keypress",i=>{i.key==="Enter"&&e()})}async function g(){const o=document.getElementById("view-outlet"),t=y();if(!t.url||!t.provider){window.location.hash="#/";return}o.innerHTML='<div class="loading">Loading details...</div>';try{const e=await(await fetch(`${l}/load?url=${encodeURIComponent(t.url)}&provider=${encodeURIComponent(t.provider)}`)).json(),i=d.bookmarks.some(n=>n.url===e.url);o.innerHTML=`
      <div class="detail-container">
        <img class="detail-poster" src="${e.posterUrl||"https://via.placeholder.com/300x450"}" alt="${e.name}">
        <div class="detail-content">
          <h2 class="detail-title">${e.name}</h2>
          <div class="detail-meta-row">
            <span>${e.type}</span>
            ${e.year?`<span>${e.year}</span>`:""}
            ${e.duration?`<span>${e.duration} min</span>`:""}
            ${e.score?`<span class="rating-badge">★ ${e.score.toFixed(1)}</span>`:""}
          </div>
          <p class="detail-plot">${e.plot||"No overview available."}</p>
          
          <div style="display: flex; gap: 16px; margin-bottom: 40px;">
            <button class="btn btn-primary" id="play-btn">
              <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
              Play
            </button>
            <button class="btn" style="background: rgba(255,255,255,0.05); color:#fff;" id="download-btn">
              Download
            </button>
            <button class="btn" style="background: rgba(255,255,255,0.05); color:#fff;" id="bookmark-btn">
              ${i?"Remove Bookmark":"Add Bookmark"}
            </button>
          </div>

          <h3>Episodes</h3>
          <div style="margin-top: 16px; max-height: 400px; overflow-y: auto;">
            ${e.episodes.map(n=>`
              <div class="episode-row" style="display:flex; justify-content:space-between; align-items:center; padding: 12px; border-bottom: 1px solid rgba(255,255,255,0.03); cursor:pointer;" onclick="playMedia('${n.url}', '${e.apiName}', '${e.name} - ${n.name||`Episode ${n.episode}`}')">
                <span>${n.name||`Episode ${n.episode||1}`}</span>
                <span style="color: var(--color-grayTextColor); font-size:14px;">Play</span>
              </div>
            `).join("")}
          </div>
        </div>
      </div>
    `,document.getElementById("download-btn").addEventListener("click",async()=>{if(e.episodes.length>0){const n=e.episodes[0];r("Resolving stream link for download...");try{const p=await(await fetch(`${l}/links`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({data:n.url,provider:e.apiName})})).json();if(p.links.length>0){const u=p.links[0];await fetch(`${l}/downloads`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({id:n.url,title:`${e.name} - ${n.name||`Episode ${n.episode||1}`}`,url:u.url})}),r("Download started on server!")}else r("No download links found.")}catch{r("Failed to start download.")}}else r("No episodes available to download.")}),document.getElementById("bookmark-btn").addEventListener("click",async()=>{d.bookmarks.some(s=>s.url===e.url)?(await fetch(`${l}/bookmarks/${encodeURIComponent(e.url)}`,{method:"DELETE"}),r("Removed from bookmarks")):(await fetch(`${l}/bookmarks`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({id:e.url,name:e.name,url:e.url,apiName:e.apiName,posterUrl:e.posterUrl,type:e.type})}),r("Added to bookmarks")),await m(),g()}),document.getElementById("play-btn").addEventListener("click",()=>{e.episodes.length>0?f(e.episodes[0].url,e.apiName,`${e.name}`):r("No links available.")})}catch{o.innerHTML='<div class="loading">Failed to load details.</div>'}}async function f(o,t,a){r("Fetching video links...");try{const i=await(await fetch(`${l}/links`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({data:o,provider:t})})).json();if(i.links.length===0){r("No playable video sources found.");return}const n=i.links[0];let s=n.url,p=n.referer||"";(n.headers&&Object.keys(n.headers).length>0||p)&&(s=`${l}/proxy?url=${encodeURIComponent(s)}&referer=${encodeURIComponent(p)}`);const u={title:a,url:o,provider:t,timestamp:Date.now()};d.history=[u,...d.history.filter(c=>c.url!==o)].slice(0,50),localStorage.setItem("cs_history",JSON.stringify(d.history)),window.location.hash=`#/player?url=${encodeURIComponent(s)}&title=${encodeURIComponent(a)}&provider=${encodeURIComponent(t)}&id=${encodeURIComponent(o)}`}catch{r("Error loading video sources.")}}function I(){const o=document.getElementById("view-outlet"),t=y();if(!t.url||!t.title||!t.id){window.location.hash="#/";return}o.innerHTML=`
    <div class="player-container">
      <a href="#/history" class="player-back-btn" id="player-close-btn">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
        Back
      </a>
      <div class="player-header">
        <span class="player-title" id="player-title-text">${t.title}</span>
      </div>
      <video id="video-element" class="player-video" controls autoplay></video>
    </div>
  `;const a=document.getElementById("video-element"),e=t.url;if(fetch(`${l}/history`).then(i=>i.json()).then(i=>{const n=i.find(s=>s.id===t.id);if(n&&n.positionMs>0&&n.durationMs>0){const s=n.positionMs/1e3;a.addEventListener("loadedmetadata",()=>{s<a.duration-10&&(a.currentTime=s,r(`Resumed from ${Math.floor(s/60)}m ${Math.floor(s%60)}s`))},{once:!0})}}).catch(console.error),Hls.isSupported()&&(e.includes(".m3u8")||e.includes("/proxy?url="))){const i=new Hls({maxMaxBufferLength:30});i.loadSource(e),i.attachMedia(a),window.currentHls=i}else a.src=e;window.currentProgressInterval=setInterval(()=>{!a.duration||a.paused||fetch(`${l}/history`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({id:t.id,parentId:t.url,positionMs:Math.round(a.currentTime*1e3),durationMs:Math.round(a.duration*1e3)})}).catch(console.error)},8e3)}function L(){const o=document.getElementById("view-outlet");if(d.bookmarks.length===0){o.innerHTML='<div class="loading">No bookmarks saved yet.</div>';return}o.innerHTML=`
    <div class="grid-container">
      ${d.bookmarks.map(t=>`
        <div class="media-card" onclick="window.location.hash = '#/detail?url=${encodeURIComponent(t.url)}&provider=${encodeURIComponent(t.apiName)}'">
          <img class="card-poster" src="${t.posterUrl||"https://via.placeholder.com/300x450"}" alt="${t.name}">
          <div class="card-info">
            <div class="card-title">${t.name}</div>
            <div class="card-metadata">
              <span>${t.apiName}</span>
            </div>
          </div>
        </div>
      `).join("")}
    </div>
  `}async function w(){const o=document.getElementById("view-outlet");o.innerHTML='<div class="loading">Loading downloads...</div>';try{const a=await(await fetch(`${l}/downloads`)).json();if(a.length===0){o.innerHTML='<div class="loading">No downloads found on server.</div>';return}o.innerHTML=`
      <div style="display:flex; flex-direction:column; gap:16px;">
        ${a.map(e=>{const i=e.bytesTotal>0?Math.round(e.bytesLoaded/e.bytesTotal*100):0,n=(e.bytesLoaded/(1024*1024)).toFixed(1),s=(e.bytesTotal/(1024*1024)).toFixed(1);return`
            <div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.05); padding:20px; border-radius:var(--border-radius-md);">
              <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">
                <div>
                  <div style="font-weight:600; font-size:16px;">${e.title}</div>
                  <div style="font-size:12px; color:var(--color-grayTextColor); margin-top:4px;">${e.status} • ${n} MB / ${e.bytesTotal>0?`${s} MB`:"Unknown"}</div>
                </div>
                <button class="btn" style="padding: 8px 16px; font-size:14px; background:#FF6F63;" onclick="deleteDownload('${e.id}')">Delete</button>
              </div>
              <div style="width:100%; height:6px; background:rgba(255,255,255,0.05); border-radius:3px; overflow:hidden;">
                <div style="width:${i}%; height:100%; background:var(--color-colorPrimary); transition:width 0.3s ease;"></div>
              </div>
            </div>
          `}).join("")}
      </div>
    `}catch{o.innerHTML='<div class="loading">Failed to load downloads list.</div>'}}async function E(o){if(confirm("Are you sure you want to delete this download?")){r("Deleting download...");try{(await fetch(`${l}/downloads?id=${encodeURIComponent(o)}`,{method:"DELETE"})).ok?(r("Download deleted."),w()):r("Failed to delete download.")}catch{r("Error deleting download.")}}}function M(){const o=document.getElementById("view-outlet");if(d.history.length===0){o.innerHTML='<div class="loading">No watch history found.</div>';return}o.innerHTML=`
    <div style="max-height: 600px; overflow-y: auto;">
      ${d.history.map(t=>`
        <div style="display:flex; justify-content:space-between; align-items:center; padding:16px; border-bottom:1px solid rgba(255,255,255,0.03);">
          <div>
            <div style="font-weight:600;">${t.title}</div>
            <div style="font-size:12px; color:var(--color-grayTextColor); margin-top:4px;">Played via ${t.provider} on ${new Date(t.timestamp).toLocaleString()}</div>
          </div>
          <button class="btn" style="padding: 8px 16px; font-size:14px; background:var(--color-colorPrimary);" onclick="playMedia('${t.url}', '${t.provider}', '${t.title}')">Replay</button>
        </div>
      `).join("")}
    </div>
  `}async function b(){const o=document.getElementById("view-outlet");o.innerHTML='<div class="loading">Loading plugins...</div>';try{const a=await(await fetch(`${l}/plugins`)).json();o.innerHTML=`
      <div style="margin-bottom: 24px; display:flex; justify-content:space-between; align-items:center;">
        <h3>Installed Plugins</h3>
        <label class="btn btn-primary" style="cursor:pointer; display:inline-flex; align-items:center;">
          Install JAR Plugin
          <input type="file" id="plugin-file-input" accept=".jar" style="display:none;" />
        </label>
      </div>
      <div style="display:flex; flex-direction:column; gap:12px;">
        ${a.length===0?'<div class="loading">No plugins installed.</div>':a.map(e=>`
          <div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.05); padding:16px; border-radius:var(--border-radius-md); display:flex; justify-content:space-between; align-items:center;">
            <div>
              <div style="font-weight:600; font-size:16px;">${e.name}</div>
              <div style="font-size:12px; color:var(--color-grayTextColor); margin-top:4px;">Class: ${e.pluginClassName}</div>
            </div>
            <span style="color:#48E484; font-weight:600; font-size:14px;">Active</span>
          </div>
        `).join("")}
      </div>
    `,document.getElementById("plugin-file-input").addEventListener("change",async e=>{const i=e.target.files[0];if(i){r("Uploading plugin...");try{const n=await i.arrayBuffer();(await fetch(`${l}/plugins/install`,{method:"POST",body:n})).ok?(r("Plugin installed successfully!"),b()):r("Failed to install plugin.")}catch{r("Error uploading plugin JAR.")}}})}catch{o.innerHTML='<div class="loading">Failed to load plugins.</div>'}}window.addEventListener("DOMContentLoaded",k);window.playMedia=f;window.deleteDownload=E;
