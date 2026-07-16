(function(){const t=document.createElement("link").relList;if(t&&t.supports&&t.supports("modulepreload"))return;for(const s of document.querySelectorAll('link[rel="modulepreload"]'))e(s);new MutationObserver(s=>{for(const l of s)if(l.type==="childList")for(const c of l.addedNodes)c.tagName==="LINK"&&c.rel==="modulepreload"&&e(c)}).observe(document,{childList:!0,subtree:!0});function o(s){const l={};return s.integrity&&(l.integrity=s.integrity),s.referrerPolicy&&(l.referrerPolicy=s.referrerPolicy),s.crossOrigin==="use-credentials"?l.credentials="include":s.crossOrigin==="anonymous"?l.credentials="omit":l.credentials="same-origin",l}function e(s){if(s.ep)return;s.ep=!0;const l=o(s);fetch(s.href,l)}})();const u="/api/v1";function I(n){return n?n.replace(/'/g,"\\'").replace(/"/g,"&quot;"):""}const f={activeProvider:localStorage.getItem("cs_active_provider")||"",providers:[],history:JSON.parse(localStorage.getItem("cs_history")||"[]"),bookmarks:[]};function g(n){const t=document.getElementById("toast-container"),o=document.createElement("div");o.className="toast",o.innerText=n,t.appendChild(o),setTimeout(()=>{o.style.opacity="0",setTimeout(()=>o.remove(),300)},3e3)}function G(n="Fetching sources…"){var o;(o=document.getElementById("loading-overlay"))==null||o.remove();const t=document.createElement("div");t.id="loading-overlay",t.innerHTML=`
    <div class="loading-overlay-inner">
      <div class="loading-spinner"></div>
      <p class="loading-overlay-msg">${n}</p>
    </div>
  `,document.body.appendChild(t)}function q(){var n;(n=document.getElementById("loading-overlay"))==null||n.remove()}async function D(n,t){if(!n||!t||n.startsWith("["))return{};const o=`cs_detail_snap:${n}`;try{const e=sessionStorage.getItem(o);if(e)return JSON.parse(e)}catch{}try{const e=await fetch(`${u}/load?url=${encodeURIComponent(n)}&provider=${encodeURIComponent(t)}`);if(!e.ok)return{};const s=await e.json(),l={name:s.name||null,posterUrl:s.posterUrl||null,plot:s.plot||null,type:s.type||null,year:s.year||null,score:s.score||null};try{sessionStorage.setItem(o,JSON.stringify(l))}catch{}return l}catch{return{}}}const Z={"/":J,"/search":ae,"/bookmarks":ie,"/history":de,"/plugins":_,"/detail":z,"/player":re,"/downloads":Q,"/challenge":se};async function ee(){window.addEventListener("hashchange",R);try{const n=await fetch(`${u}/providers`);f.providers=await n.json(),oe()}catch{g("Failed to connect to backend server.")}await F(),R(),ne()}function te(n){if(document.getElementById("challenge-modal"))return;const t=document.createElement("div");t.id="challenge-modal",t.className="source-picker-backdrop",t.innerHTML=`
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
          <img id="challenge-modal-screenshot" style="width: 100%; height: 100%; object-fit: contain; cursor: crosshair;" alt="Challenge screen" hidden />
          <div id="challenge-modal-empty" style="position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: var(--color-grayTextColor);">
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
  `,document.body.appendChild(t);const o=document.getElementById("challenge-modal-status"),e=document.getElementById("challenge-modal-screenshot"),s=document.getElementById("challenge-modal-empty");let l=null;const c=async()=>{try{const d=await fetch(`${u}/challenges/${n}`);if(!d.ok){clearInterval(l),t.remove();return}const a=await d.json();o.textContent=`${a.status.toUpperCase()}: ${a.title||a.url}`,e.src=`${u}/challenges/${n}/screenshot?t=${Date.now()}`,e.hidden=!1,s.hidden=!0,a.status==="ready"&&(clearInterval(l),g("Challenge solved successfully!"),setTimeout(()=>t.remove(),1500))}catch{clearInterval(l),t.remove()}};e.addEventListener("click",async d=>{if(!e.naturalWidth)return;const a=e.getBoundingClientRect(),r=(d.clientX-a.left)*e.naturalWidth/a.width,p=(d.clientY-a.top)*e.naturalHeight/a.height;await fetch(`${u}/challenges/${n}/click`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({x:r,y:p})}),await c()}),document.getElementById("challenge-modal-type-btn").addEventListener("click",async()=>{const d=document.getElementById("challenge-modal-text").value;await fetch(`${u}/challenges/${n}/type`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({text:d})}),await c()}),document.getElementById("challenge-modal-complete-btn").addEventListener("click",async()=>{await fetch(`${u}/challenges/${n}/complete`,{method:"POST"}),await c()}),document.getElementById("challenge-modal-close").addEventListener("click",()=>{clearInterval(l),t.remove()}),c(),l=setInterval(c,2e3)}function ne(){window.challengePoller=setInterval(async()=>{try{const t=(await fetch(`${u}/challenges`).then(o=>o.json())).find(o=>o.status==="pending");t&&te(t.id)}catch{}},2e3)}function oe(){const n=document.getElementById("active-provider-select");n.innerHTML='<option value="">All Providers</option>',f.providers.forEach(t=>{const o=document.createElement("option");o.value=t.name,o.innerText=t.name,t.name===f.activeProvider&&(o.selected=!0),n.appendChild(o)}),n.addEventListener("change",t=>{f.activeProvider=t.target.value,localStorage.setItem("cs_active_provider",f.activeProvider),R()})}async function F(){try{const n=await fetch(`${u}/bookmarks`);f.bookmarks=await n.json()}catch(n){console.error("Failed to sync bookmarks:",n)}}function R(){var c;const n=window.location.hash||"#/";if(window.currentHls&&(window.currentHls.destroy(),window.currentHls=null),window.savePlayerProgress){try{window.savePlayerProgress()}catch{}window.savePlayerProgress=null}window.playerKeyHandler&&(document.removeEventListener("keydown",window.playerKeyHandler),window.playerKeyHandler=null),document.querySelectorAll(".nav-item").forEach(d=>{d.classList.remove("active")});let t=n.substring(1);t.includes("?")&&(t=t.split("?")[0]);const e={"/":"nav-home","/search":"nav-search","/bookmarks":"nav-bookmarks","/history":"nav-history","/plugins":"nav-plugins","/downloads":"nav-downloads"}[t];e&&((c=document.getElementById(e))==null||c.classList.add("active"));const s=Z[t]||J,l={"/":"Home","/search":"Search","/bookmarks":"Bookmarks","/history":"History","/plugins":"Plugins","/detail":"Details","/player":"Player","/downloads":"Downloads","/challenge":"Browser Challenge"};document.getElementById("page-title").innerText=l[t]||"Ka0SStream",s()}function O(){const n=window.location.hash;if(!n.includes("?"))return{};const t=n.split("?")[1],o={};return t.split("&").forEach(e=>{const[s,l]=e.split("=");o[s]=decodeURIComponent(l)}),o}async function J(){const n=document.getElementById("view-outlet");n.innerHTML=`
    <div id="resume-container"></div>
    <div id="sections-container"></div>
    <div id="home-spinner" class="loading">Loading home sections...</div>
  `;const t=document.getElementById("resume-container"),o=document.getElementById("sections-container"),e=document.getElementById("home-spinner");let s=!1,l=!1;try{const c=await fetch(`${u}/history`);if(c.ok){const a=(await c.json()).filter(r=>{if(!r.durationMs||!r.positionMs)return!1;const p=r.positionMs/r.durationMs;return p>=.01&&p<=.95});a.length>0&&(s=!0,t.innerHTML=`
          <div class="carousel-section">
            <h3 class="carousel-title">Resume Watching</h3>
            <div class="grid-container" style="display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 16px;">
              ${a.map(r=>{const p=Math.min(100,Math.round(r.positionMs/r.durationMs*100)),$=r.title||"Untitled",v=r.seasonNum!=null&&r.episodeNum!=null?`S${r.seasonNum}E${r.episodeNum}`:"Movie",b=r.parentId&&!r.parentId.startsWith("[")?r.parentId:r.id.startsWith("[")?"":r.id,E=r.provider||"";return`
                  <div class="media-card resume-card" style="position: relative;">
                    <img class="card-poster" src="${r.posterUrl||"https://via.placeholder.com/300x450"}" alt="${$}">
                    
                    <!-- Premium watch control overlays -->
                    <div class="resume-card-overlay">
                      <button class="resume-action-btn play-btn" onclick="event.stopPropagation(); playMedia('${I(r.id)}', '${I(E)}', '${I($)}', '${I(r.parentId||"")}', '${I(r.posterUrl||"")}', ${r.seasonNum!==null?r.seasonNum:"null"}, ${r.episodeNum!==null?r.episodeNum:"null"})" title="Resume Watching">
                        <svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
                      </button>
                      <button class="resume-action-btn info-btn" onclick="event.stopPropagation(); if ('${b}') window.location.hash = '#/detail?url=${encodeURIComponent(b)}&provider=${encodeURIComponent(E)}'; else showToast('Details URL not available');" title="Show Info">
                        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
                      </button>
                      <button class="resume-action-btn remove-btn" onclick="event.stopPropagation(); removeHistoryItem('${I(r.id)}')" title="Remove Watch Progress">
                        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                      </button>
                    </div>
                    
                    <div style="position: absolute; bottom: 0; left: 0; right: 0; height: 6px; background: rgba(255,255,255,0.15);">
                      <div style="width: ${p}%; height: 100%; background: var(--color-colorPrimary);"></div>
                    </div>
                    <div class="card-info">
                      <div class="card-title">${$}</div>
                      <div class="card-metadata">
                        <span>${v}</span>
                        <span>${p}%</span>
                      </div>
                    </div>
                  </div>
                `}).join("")}
            </div>
          </div>
        `)}}catch(c){console.error("Error fetching watch history:",c)}try{const c=f.activeProvider?`?provider=${encodeURIComponent(f.activeProvider)}`:"",d=await fetch(`${u}/home${c}`);if(!d.body)throw new Error("ReadableStream not supported");const a=d.body.getReader(),r=new TextDecoder;let p="";for(;;){const{value:$,done:v}=await a.read();if(v)break;p+=r.decode($,{stream:!0});const b=p.split(`
`);p=b.pop();for(const E of b)if(E.trim())try{const i=JSON.parse(E);if(i.items&&i.items.length>0){l=!0;const m=document.createElement("div");m.className="carousel-section",m.innerHTML=`
              <h3 class="carousel-title">${i.name} <span class="section-provider">${i.provider}</span></h3>
              <div class="grid-container">
                ${i.items.map(h=>`
                  <div class="media-card" onclick="window.location.hash = '#/detail?url=${encodeURIComponent(h.url)}&provider=${encodeURIComponent(h.apiName)}'">
                    <img class="card-poster" src="${h.posterUrl||"https://via.placeholder.com/300x450"}" alt="${h.name}">
                    <div class="card-info">
                      <div class="card-title">${h.name}</div>
                      <div class="card-metadata">
                        <span>${h.type||"Media"}</span>
                        ${h.score?`<span class="rating-badge">★ ${h.score.toFixed(1)}</span>`:""}
                      </div>
                    </div>
                  </div>
                `).join("")}
              </div>
            `,o.appendChild(m)}}catch(i){console.error("Failed to parse home section JSON line:",i)}}}catch(c){console.error("Failed to stream homepage sections:",c)}finally{e.remove(),!s&&!l&&(o.innerHTML='<div class="loading">No homepage content is available. Try searching.</div>')}}async function ae(){const n=document.getElementById("view-outlet");n.innerHTML=`
    <div class="search-container">
      <input type="text" class="search-input" id="search-box" placeholder="Search movies, shows, or anime..." />
      <button class="btn btn-primary" id="search-btn">Search</button>
    </div>
    <div id="search-results"></div>
  `;const t=document.getElementById("search-box"),o=document.getElementById("search-btn"),e=async()=>{const s=t.value.trim();if(!s)return;const l=document.getElementById("search-results");l.innerHTML='<div class="loading">Searching...</div>';try{const c=`${u}/search?q=${encodeURIComponent(s)}${f.activeProvider?`&provider=${encodeURIComponent(f.activeProvider)}`:""}&diagnostics=true`,a=await(await fetch(c)).json(),r=Array.isArray(a)?a:a.results||[],p=Array.isArray(a)?[]:a.failures||[],$=p.length?`<div class="provider-failure-notice"><strong>Some providers were unavailable</strong>${p.slice(0,8).map(v=>{const b=f.providers.find(i=>i.name===v.provider),E=v.code==="CHALLENGE_REQUIRED"&&(b!=null&&b.url)?` <a href="#/challenge?url=${encodeURIComponent(b.url)}">Open browser challenge</a>`:"";return`<span>${v.provider}: ${v.message}${E}</span>`}).join("")}</div>`:"";if(r.length===0){l.innerHTML=`${$}<div class="loading">No results found.</div>`;return}l.innerHTML=`${$}
        <div class="grid-container">
          ${r.map(v=>`
            <div class="media-card" onclick="window.location.hash = '#/detail?url=${encodeURIComponent(v.url)}&provider=${encodeURIComponent(v.apiName)}'">
              <img class="card-poster" src="${v.posterUrl||"https://via.placeholder.com/300x450"}" alt="${v.name}">
              <div class="card-info">
                <div class="card-title">${v.name}</div>
                <div class="card-metadata">
                  <span>${v.type||"Media"}</span>
                  ${v.score?`<span class="rating-badge">★ ${v.score.toFixed(1)}</span>`:""}
                </div>
              </div>
            </div>
          `).join("")}
        </div>
      `}catch{l.innerHTML='<div class="loading">Error performing search.</div>'}};o.addEventListener("click",e),t.addEventListener("keypress",s=>{s.key==="Enter"&&e()})}async function se(){const n=document.getElementById("view-outlet"),t=O();n.innerHTML=`
    <div class="challenge-view">
      <div class="challenge-header"><p class="eyebrow">Interactive provider access</p><h2>Complete browser verification</h2><p>Use this isolated browser session to complete the provider challenge. Cookies remain on the server and are never exposed to this page.</p></div>
      <div class="challenge-start-row"><input class="search-input" id="challenge-url" value="${t.url||""}" placeholder="https://provider.example/challenge"><button class="btn btn-primary" id="challenge-start-btn">Open challenge</button></div>
      <div id="challenge-status" class="challenge-status">No challenge session started.</div>
      <div class="challenge-stage"><img id="challenge-screenshot" alt="Provider challenge screenshot" hidden><div id="challenge-empty">The challenge screenshot will appear here.</div></div>
      <div class="challenge-actions"><input class="search-input" id="challenge-text" placeholder="Optional text input"><button class="btn" id="challenge-type-btn">Type</button><button class="btn btn-primary" id="challenge-complete-btn">Check completion</button></div>
    </div>
  `;let o=t.id||null,e=null;const s=document.getElementById("challenge-status"),l=document.getElementById("challenge-screenshot"),c=document.getElementById("challenge-empty"),d=async()=>{if(!o)return;const r=await(await fetch(`${u}/challenges/${o}`)).json();s.textContent=`${r.status}: ${r.title||r.url||""}`,l.src=`${u}/challenges/${o}/screenshot?t=${Date.now()}`,l.hidden=!1,c.hidden=!0,r.status==="ready"&&(clearInterval(e),g("Challenge completed. Retry the provider operation."))};document.getElementById("challenge-start-btn").addEventListener("click",async()=>{const a=document.getElementById("challenge-url").value.trim();if(!/^https?:\/\//i.test(a))return g("Enter a valid provider URL.");const r=await fetch(`${u}/challenges`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({url:a})}),p=await r.json();if(!r.ok)return g(p.error||"Unable to start challenge.");o=p.id,s.textContent=`${p.status}: ${p.title||p.url}`,await d(),clearInterval(e),e=setInterval(d,2e3)}),l.addEventListener("click",async a=>{if(!o||!l.naturalWidth)return;const r=l.getBoundingClientRect();await fetch(`${u}/challenges/${o}/click`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({x:(a.clientX-r.left)*l.naturalWidth/r.width,y:(a.clientY-r.top)*l.naturalHeight/r.height})}),await d()}),document.getElementById("challenge-type-btn").addEventListener("click",async()=>{if(!o)return;const a=document.getElementById("challenge-text").value;await fetch(`${u}/challenges/${o}/type`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({text:a})}),await d()}),document.getElementById("challenge-complete-btn").addEventListener("click",async()=>{o&&await fetch(`${u}/challenges/${o}/complete`,{method:"POST"}),await d()}),t.id?(document.getElementById("challenge-start-btn").disabled=!0,d().then(()=>{clearInterval(e),e=setInterval(d,2e3)})):t.url&&document.getElementById("challenge-start-btn").click()}async function z(){const n=document.getElementById("view-outlet"),t=O();if(!t.url||!t.provider){window.location.hash="#/";return}n.innerHTML='<div class="loading">Loading details...</div>';try{const e=await(await fetch(`${u}/load?url=${encodeURIComponent(t.url)}&provider=${encodeURIComponent(t.provider)}`)).json(),s=f.bookmarks.some(a=>a.url===e.url),l=f.history.find(a=>a.url===t.url||a.url===e.url),c=e.name&&e.name!==e.url?e.name:(l==null?void 0:l.title)||e.name||t.url,d=e.posterUrl||(l==null?void 0:l.posterUrl)||null;n.innerHTML=`
      <div class="detail-container">
        <img class="detail-poster" src="${d||"https://via.placeholder.com/300x450"}" alt="${c}">
        <div class="detail-content">
          <h2 class="detail-title">${c}</h2>
          <div class="detail-meta-row">
            <span>${e.type||"Movie"}</span>
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
              ${s?"Remove Bookmark":"Add Bookmark"}
            </button>
          </div>

          <div style="margin-top: 16px; max-height: 400px; overflow-y: auto;">
            ${e.episodes.map(a=>{const r=`${e.name} - ${a.name||`Episode ${a.episode||1}`}`;return`
                <div class="episode-row" style="display:flex; justify-content:space-between; align-items:center; padding: 12px; border-bottom: 1px solid rgba(255,255,255,0.03); cursor:pointer;" onclick="playMedia('${I(a.url)}', '${I(e.apiName)}', '${I(r)}', '${I(e.url)}', '${I(e.posterUrl||"")}', ${a.season!==void 0&&a.season!==null?a.season:"null"}, ${a.episode!==void 0&&a.episode!==null?a.episode:"null"})">
                  <span>${a.name||`Episode ${a.episode||1}`}</span>
                  <span style="color: var(--color-grayTextColor); font-size:14px;">Play</span>
                </div>
              `}).join("")}
          </div>
        </div>
      </div>
    `,document.getElementById("download-btn").addEventListener("click",async()=>{if(e.episodes.length>0){const a=e.episodes[0];g("Resolving stream link for download...");try{const p=await(await fetch(`${u}/links`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({data:a.url,provider:e.apiName})})).json();if(p.links.length>0){const $=p.links[0];await fetch(`${u}/downloads`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({id:a.url,title:`${e.name} - ${a.name||`Episode ${a.episode||1}`}`,url:$.url})}),g("Download started on server!")}else g("No download links found.")}catch{g("Failed to start download.")}}else g("No episodes available to download.")}),document.getElementById("bookmark-btn").addEventListener("click",async()=>{f.bookmarks.some(r=>r.url===e.url)?(await fetch(`${u}/bookmarks/${encodeURIComponent(e.url)}`,{method:"DELETE"}),g("Removed from bookmarks")):(await fetch(`${u}/bookmarks`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({id:e.url,name:e.name,url:e.url,apiName:e.apiName,posterUrl:e.posterUrl,type:e.type})}),g("Added to bookmarks")),await F(),z()}),document.getElementById("play-btn").addEventListener("click",()=>{if(e.episodes.length>0){const a=e.episodes[0];W(a.url,e.apiName,`${e.name}`,e.url,e.posterUrl||"",a.season!==void 0&&a.season!==null?a.season:null,a.episode!==void 0&&a.episode!==null?a.episode:null)}else g("No links available.")})}catch{n.innerHTML='<div class="loading">Failed to load details.</div>'}}async function W(n,t,o,e="",s="",l=null,c=null){G("Fetching sources…");try{const a=await(await fetch(`${u}/links`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({data:n,provider:t})})).json();if(q(),a.links.length===0){g("No playable video sources found.");return}K(a,o,n,t,e,s,l,c)}catch(d){q(),g(d.message||"Error loading video sources.")}}function K(n,t,o,e,s="",l="",c=null,d=null){var L,B;(L=document.getElementById("source-picker"))==null||L.remove(),sessionStorage.setItem(`cs_sources:${o}`,JSON.stringify(n));const a=document.createElement("div");a.id="source-picker",a.className="source-picker-backdrop",a.innerHTML=`
    <section class="source-picker" role="dialog" aria-modal="true" aria-labelledby="source-picker-title">
      <div class="source-picker-header">
        <div><p class="eyebrow">Playback setup</p><h2 id="source-picker-title">Choose how to play</h2><p class="source-picker-subtitle"></p></div>
        <button class="source-picker-close" type="button" aria-label="Close">×</button>
      </div>
      <div class="source-picker-section"><h3>Sources</h3><div class="source-list" id="source-list"></div><div class="native-player-actions"><button class="player-toolbar-btn" type="button" id="open-vlc-btn">Open in VLC</button><button class="player-toolbar-btn" type="button" id="open-infuse-btn">Open in Infuse</button><button class="player-toolbar-btn" type="button" id="download-vlc-btn">Download VLC playlist</button></div></div>
      <div class="source-picker-section"><h3>Subtitles</h3><div class="subtitle-list" id="subtitle-list"></div></div>
      <div class="source-picker-actions"><button class="btn" type="button" id="source-cancel-btn">Cancel</button><button class="btn btn-primary" type="button" id="source-play-btn">Play selected source</button></div>
    </section>
  `,document.body.appendChild(a),a.querySelector(".source-picker-subtitle").textContent=t;const r=s||o,p=localStorage.getItem(`cs_last_source_url:${r}`),$=localStorage.getItem(`cs_last_source_name:${r}`),v=JSON.parse(localStorage.getItem(`cs_last_subtitles:${r}`)||"[]"),b=localStorage.getItem("cs_pref_source_quality"),E=localStorage.getItem("cs_pref_source_name"),i=localStorage.getItem("cs_pref_subtitle_lang"),m=document.getElementById("source-list");let h=0;const T=n.links.findIndex(y=>p&&y.url===p||$&&y.name===$||b&&y.quality===b&&E&&y.name===E);T!==-1&&(h=T),n.links.forEach((y,k)=>{const w=document.createElement("label");w.className="source-choice",w.innerHTML=`<input type="radio" name="playback-source" value="${k}" ${k===h?"checked":""}><span class="source-choice-copy"><strong></strong><small></small></span>`,w.querySelector("strong").textContent=y.name||y.quality||`Source ${k+1}`,w.querySelector("small").textContent=[y.quality,y.referer?"Protected stream":"Direct stream"].filter(Boolean).join(" · "),m.appendChild(w)});const x=document.getElementById("subtitle-list");(B=n.subtitles)!=null&&B.length?n.subtitles.forEach((y,k)=>{const w=v.includes(y.lang)||i&&y.lang===i,C=document.createElement("label");C.className="subtitle-choice",C.innerHTML=`<input type="checkbox" value="${k}" ${w?"checked":""}><span></span>`,C.querySelector("span").textContent=y.lang||`Subtitle ${k+1}`,x.appendChild(C)}):x.innerHTML='<p class="source-empty">No subtitles were provided by this source.</p>';const S=()=>a.remove();a.querySelector(".source-picker-close").addEventListener("click",S),document.getElementById("source-cancel-btn").addEventListener("click",S),document.getElementById("source-play-btn").addEventListener("click",()=>{var w;const y=Number(((w=document.querySelector('input[name="playback-source"]:checked'))==null?void 0:w.value)||0),k=[...document.querySelectorAll("#subtitle-list input:checked")].map(C=>n.subtitles[Number(C.value)]);S(),le(n.links[y],k,t,o,e,y,s,l,c,d)});const N=()=>{var C;const y=Number(((C=document.querySelector('input[name="playback-source"]:checked'))==null?void 0:C.value)||0),k=n.links[y],w=k.referer||"";return new URL(`${u}/proxy?url=${encodeURIComponent(k.url)}&referer=${encodeURIComponent(w)}`,window.location.href).href},U=async y=>{var A;const k=N(),w=navigator.userAgent||"",C=Number(((A=document.querySelector('input[name="playback-source"]:checked'))==null?void 0:A.value)||0),P=n.links[C];localStorage.setItem(`cs_last_source_url:${r}`,P.url),localStorage.setItem(`cs_last_source_name:${r}`,P.name||""),P.quality&&localStorage.setItem("cs_pref_source_quality",P.quality),P.name&&localStorage.setItem("cs_pref_source_name",P.name);const X=s&&!s.startsWith("[")?s:o.startsWith("[")?null:o,M=await D(X,e),H=M.name||t,j=M.posterUrl||l||null;fetch(`${u}/history`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({id:o,parentId:s||null,positionMs:1e4,durationMs:5e5,title:H,posterUrl:j,plot:M.plot||null,type:M.type||null,year:M.year||null,score:M.score||null,provider:e,seasonNum:c,episodeNum:d}),keepalive:!0}).catch(console.error);const V={title:H,url:o,provider:e,timestamp:Date.now(),parentId:s,posterUrl:j,seasonNum:c,episodeNum:d};f.history=[V,...f.history.filter(Y=>Y.url!==o)].slice(0,50),localStorage.setItem("cs_history",JSON.stringify(f.history)),y==="vlc"&&/Android/i.test(w)?window.location.href=`intent://${k.replace(/^https?:\/\//,"")}#Intent;scheme=https;package=org.videolan.vlc;end`:y==="vlc"&&/iPad|iPhone|iPod/i.test(w)?window.location.href=`vlc-x-callback://x-callback-url/stream?url=${encodeURIComponent(k)}`:y==="vlc"?window.location.href=`vlc://${k}`:window.location.href=`infuse://x-callback-url/play?url=${encodeURIComponent(k)}`};document.getElementById("open-vlc-btn").addEventListener("click",()=>U("vlc")),document.getElementById("open-infuse-btn").addEventListener("click",()=>U("infuse")),document.getElementById("download-vlc-btn").addEventListener("click",()=>{const y=`#EXTM3U
#EXTINF:-1,${t}
${N()}
`,k=new Blob([y],{type:"audio/x-mpegurl"}),w=document.createElement("a");w.href=URL.createObjectURL(k),w.download=`${t.replace(/[^a-z0-9]+/gi,"-").replace(/^-|-$/g,"")||"video"}.m3u`,w.click(),URL.revokeObjectURL(w.href)})}async function le(n,t,o,e,s,l=0,c="",d="",a=null,r=null){let p=n.url,$=n.referer||"";(n.headers&&Object.keys(n.headers).length>0||$)&&(p=`${u}/proxy?url=${encodeURIComponent(p)}&referer=${encodeURIComponent($)}`);let v=null;try{v=JSON.parse(sessionStorage.getItem(`cs_sources:${e}`)||"null")}catch{v=null}sessionStorage.setItem(`cs_playback:${e}`,JSON.stringify({streamUrl:p,subtitles:t||[],sourceIndex:l,links:(v==null?void 0:v.links)||[{url:n.url,quality:n.quality,name:n.name,referer:n.referer}]}));const b=c||e;localStorage.setItem(`cs_last_source_url:${b}`,n.url),localStorage.setItem(`cs_last_source_name:${b}`,n.name||"");const E=(t||[]).map(S=>S.lang);localStorage.setItem(`cs_last_subtitles:${b}`,JSON.stringify(E)),n.quality&&localStorage.setItem("cs_pref_source_quality",n.quality),n.name&&localStorage.setItem("cs_pref_source_name",n.name),E.length>0&&localStorage.setItem("cs_pref_subtitle_lang",E[0]);const i=c&&!c.startsWith("[")?c:e.startsWith("[")?null:e,m=await D(i,s),h=m.name||o,T=m.posterUrl||d||null,x={title:h,url:e,provider:s,timestamp:Date.now(),parentId:c,posterUrl:T,seasonNum:a,episodeNum:r};f.history=[x,...f.history.filter(S=>S.url!==e)].slice(0,50),localStorage.setItem("cs_history",JSON.stringify(f.history)),window.location.hash=`#/player?url=${encodeURIComponent(p)}&title=${encodeURIComponent(h)}&provider=${encodeURIComponent(s)}&id=${encodeURIComponent(e)}&parentId=${encodeURIComponent(c)}&posterUrl=${encodeURIComponent(T||d)}&seasonNum=${a!==null?a:""}&episodeNum=${r!==null?r:""}`}function re(){const n=document.getElementById("view-outlet"),t=O();if(!t.url||!t.title||!t.id){window.location.hash="#/";return}let o=null;try{o=JSON.parse(sessionStorage.getItem(`cs_playback:${t.id}`)||"null")}catch{o=null}n.innerHTML=`
    <div class="player-container" id="player-shell">
      <a href="#/history" class="player-back-btn" id="player-close-btn">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
        Back
      </a>
      <div class="player-header">
        <span class="player-title" id="player-title-text">${t.title}</span>
      </div>
      <div class="player-stage" id="player-stage">
        <video id="video-element" class="player-video" controls autoplay playsinline preload="metadata"></video>
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
  `;const e=document.getElementById("video-element"),s=(o==null?void 0:o.streamUrl)||t.url,l=(o==null?void 0:o.subtitles)||[];let c=null;try{c=JSON.parse(sessionStorage.getItem(`cs_sources:${t.id}`)||"null")}catch{c=null}if(fetch(`${u}/history`).then(i=>i.json()).then(i=>{const m=i.find(h=>h.id===t.id);if(m&&m.positionMs>0&&m.durationMs>0){const h=m.positionMs/1e3;e.addEventListener("loadedmetadata",()=>{h<e.duration-10&&(e.currentTime=h,g(`Resumed from ${Math.floor(h/60)}m ${Math.floor(h%60)}s`))},{once:!0})}}).catch(console.error),Hls.isSupported()&&(s.includes(".m3u8")||s.includes("/proxy?url="))){const i=new Hls({maxMaxBufferLength:30});i.loadSource(s),i.attachMedia(e),window.currentHls=i,i.on(Hls.Events.MANIFEST_PARSED,()=>{var U;const m=document.getElementById("playback-quality"),h=document.getElementById("quality-option"),T=i.levels||[];T.forEach((L,B)=>{const y=document.createElement("option");y.value=B,y.textContent=L.height?`${L.height}p`:`${Math.round((L.bitrate||0)/1e3)} kbps`,m.appendChild(y)}),h.hidden=T.length<2,m.addEventListener("change",()=>{i.currentLevel=Number(m.value)});const x=document.getElementById("playback-audio"),S=document.getElementById("audio-option"),N=()=>{x.innerHTML='<option value="-1">Auto</option>',(i.audioTracks||[]).forEach((L,B)=>{const y=document.createElement("option");y.value=B,y.textContent=L.name||L.lang||`Audio ${B+1}`,x.appendChild(y)}),S.hidden=(i.audioTracks||[]).length<2};(U=i.audioTracks)!=null&&U.length&&N(),i.on(Hls.Events.AUDIO_TRACKS_UPDATED,N),x.addEventListener("change",()=>{i.audioTrack=Number(x.value)})}),i.on(Hls.Events.ERROR,(m,h)=>{h.fatal&&g("The stream could not be loaded.")})}else e.src=s;e.addEventListener("error",()=>{var h;g("This source is not browser-compatible. iPad supports MP4/HLS, but this source may be MKV or use an unsupported codec.");const m=document.createElement("div");m.className="player-error-notice",m.innerHTML="<strong>Unable to play this source</strong><span>Choose an MP4 or HLS source, or try another provider.</span>",(h=document.getElementById("player-stage"))==null||h.appendChild(m)},{once:!0});const d=document.getElementById("subtitle-select"),a=document.getElementById("subtitle-option");l.forEach((i,m)=>{var S,N;const h=document.createElement("track"),T=((S=i.headers)==null?void 0:S.Referer)||((N=i.headers)==null?void 0:N.referer)||"";h.kind="subtitles",h.label=i.lang||`Subtitle ${m+1}`,h.srclang=i.langTag||"en",h.src=T?`${u}/proxy?url=${encodeURIComponent(i.url)}&referer=${encodeURIComponent(T)}`:i.url,e.appendChild(h);const x=document.createElement("option");x.value=m,x.textContent=i.lang||`Subtitle ${m+1}`,d.appendChild(x)}),a.hidden=l.length===0,d.addEventListener("change",()=>{[...e.textTracks].forEach((i,m)=>{i.mode=m===Number(d.value)?"showing":"disabled"})});const r=document.getElementById("play-toggle-btn"),p=()=>{r.textContent=e.paused?"▶":"Ⅱ"};r.addEventListener("click",()=>e.paused?e.play():e.pause()),e.addEventListener("play",p),e.addEventListener("pause",p),document.getElementById("skip-back-btn").addEventListener("click",()=>{e.currentTime=Math.max(0,e.currentTime-10)}),document.getElementById("skip-forward-btn").addEventListener("click",()=>{e.currentTime=Math.min(e.duration||1/0,e.currentTime+10)}),document.getElementById("playback-speed").addEventListener("change",i=>{e.playbackRate=Number(i.target.value)}),document.getElementById("pip-btn").addEventListener("click",async()=>{document.pictureInPictureEnabled&&(document.pictureInPictureElement?await document.exitPictureInPicture():await e.requestPictureInPicture())}),document.getElementById("fullscreen-btn").addEventListener("click",()=>{var m;const i=document.getElementById("player-shell");document.fullscreenElement?document.exitFullscreen():(m=i.requestFullscreen)==null||m.call(i)}),document.getElementById("change-source-btn").addEventListener("click",()=>{c?K(c,t.title,t.id,t.provider):window.location.hash=`#/detail?url=${encodeURIComponent(t.id)}&provider=${encodeURIComponent(t.provider)}`}),document.getElementById("player-stage").addEventListener("dblclick",i=>{const m=i.currentTarget.getBoundingClientRect();e.currentTime+=i.clientX<m.left+m.width/2?-10:10}),document.addEventListener("keydown",window.playerKeyHandler=i=>{i.target.matches("input, select, textarea")||(i.key===" "&&(i.preventDefault(),e.paused?e.play():e.pause()),i.key==="ArrowLeft"&&(e.currentTime=Math.max(0,e.currentTime-10)),i.key==="ArrowRight"&&(e.currentTime=Math.min(e.duration||1/0,e.currentTime+10)),i.key.toLowerCase()==="f"&&document.getElementById("fullscreen-btn").click())});const $=t.seasonNum?Number(t.seasonNum):null,v=t.episodeNum?Number(t.episodeNum):null,b=()=>{e.duration&&fetch(`${u}/history`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({id:t.id,parentId:t.parentId||null,seasonNum:$,episodeNum:v,positionMs:Math.round(e.currentTime*1e3),durationMs:Math.round(e.duration*1e3),title:t.title,posterUrl:t.posterUrl||null,provider:t.provider}),keepalive:!0}).catch(console.error)};let E=0;e.addEventListener("timeupdate",()=>{const i=Date.now();i-E<8e3||(E=i,e.paused||b())}),e.addEventListener("pause",()=>{b()}),e.addEventListener("seeked",()=>{b()}),window.addEventListener("pagehide",()=>{b()}),window.savePlayerProgress=b}function ie(){const n=document.getElementById("view-outlet");if(f.bookmarks.length===0){n.innerHTML='<div class="loading">No bookmarks saved yet.</div>';return}n.innerHTML=`
    <div class="grid-container">
      ${f.bookmarks.map(t=>`
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
  `}async function Q(){const n=document.getElementById("view-outlet");n.innerHTML='<div class="loading">Loading downloads...</div>';try{const o=await(await fetch(`${u}/downloads`)).json();if(o.length===0){n.innerHTML='<div class="loading">No downloads found on server.</div>';return}n.innerHTML=`
      <div style="display:flex; flex-direction:column; gap:16px;">
        ${o.map(e=>{const s=e.bytesTotal>0?Math.round(e.bytesLoaded/e.bytesTotal*100):0,l=(e.bytesLoaded/(1024*1024)).toFixed(1),c=(e.bytesTotal/(1024*1024)).toFixed(1);return`
            <div style="background:rgba(255,255,255,0.02); border:1px solid rgba(255,255,255,0.05); padding:20px; border-radius:var(--border-radius-md);">
              <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:12px;">
                <div>
                  <div style="font-weight:600; font-size:16px;">${e.title}</div>
                  <div style="font-size:12px; color:var(--color-grayTextColor); margin-top:4px;">${e.status} • ${l} MB / ${e.bytesTotal>0?`${c} MB`:"Unknown"}</div>
                </div>
                <button class="btn" style="padding: 8px 16px; font-size:14px; background:#FF6F63;" onclick="deleteDownload('${e.id}')">Delete</button>
              </div>
              <div style="width:100%; height:6px; background:rgba(255,255,255,0.05); border-radius:3px; overflow:hidden;">
                <div style="width:${s}%; height:100%; background:var(--color-colorPrimary); transition:width 0.3s ease;"></div>
              </div>
            </div>
          `}).join("")}
      </div>
    `}catch{n.innerHTML='<div class="loading">Failed to load downloads list.</div>'}}async function ce(n){if(confirm("Are you sure you want to delete this download?")){g("Deleting download...");try{(await fetch(`${u}/downloads?id=${encodeURIComponent(n)}`,{method:"DELETE"})).ok?(g("Download deleted."),Q()):g("Failed to delete download.")}catch{g("Error deleting download.")}}}async function de(){const n=document.getElementById("view-outlet");n.innerHTML='<div class="loading">Loading History...</div>';try{const o=await(await fetch(`${u}/history`)).json();if(o.length===0){n.innerHTML='<div class="loading">No watch history found.</div>';return}n.innerHTML=`
      <div style="max-height: 600px; overflow-y: auto;">
        ${o.map(e=>{const s=e.title||"Untitled",l=e.provider||"",c=e.updatedAt?new Date(e.updatedAt).toLocaleString():"Recently",d=e.durationMs?Math.round(e.positionMs/e.durationMs*100):0;return`
            <div style="display:flex; justify-content:space-between; align-items:center; padding:16px; border-bottom:1px solid rgba(255,255,255,0.03);">
              <div>
                <div style="font-weight:600;">${s}</div>
                <div style="font-size:12px; color:var(--color-grayTextColor); margin-top:4px;">
                  Played via ${l} on ${c} · ${d}% watched
                </div>
              </div>
              <button class="btn" style="padding: 8px 16px; font-size:14px; background:var(--color-colorPrimary);" 
                      onclick="playMedia('${I(e.id)}', '${I(l)}', '${I(s)}', '${I(e.parentId||"")}', '${I(e.posterUrl||"")}', ${e.seasonNum!==null?e.seasonNum:"null"}, ${e.episodeNum!==null?e.episodeNum:"null"})">
                Replay
              </button>
            </div>
          `}).join("")}
      </div>
    `}catch{n.innerHTML='<div class="loading">Failed to load watch history.</div>'}}async function _(){const n=document.getElementById("view-outlet");n.innerHTML='<div class="loading">Loading plugins...</div>';try{const o=await(await fetch(`${u}/plugins`)).json();n.innerHTML=`
      <div style="margin-bottom: 24px; display:flex; justify-content:space-between; align-items:center;">
        <h3>Installed Plugins</h3>
        <label class="btn btn-primary" style="cursor:pointer; display:inline-flex; align-items:center;">
          Install JAR Plugin
          <input type="file" id="plugin-file-input" accept=".jar" style="display:none;" />
        </label>
      </div>
      <div style="display:flex; flex-direction:column; gap:12px;">
        ${o.length===0?'<div class="loading">No plugins installed.</div>':o.map(e=>`
          <div class="plugin-row ${e.enabled?"":"plugin-disabled"}">
            <div>
              <div style="font-weight:600; font-size:16px;">${e.name}</div>
              <div style="font-size:12px; color:var(--color-grayTextColor); margin-top:4px;">${e.jarName}${e.embedded?" · Embedded":" · Uploaded"}${e.pluginClassName?` · ${e.pluginClassName}`:""}</div>
            </div>
            <label class="plugin-toggle">
              <input type="checkbox" data-plugin-jar="${e.jarName}" ${e.enabled?"checked":""}>
              <span class="plugin-toggle-track"><span></span></span>
              <strong>${e.enabled?e.loaded?"Enabled":"Failed":"Disabled"}</strong>
            </label>
          </div>
        `).join("")}
      </div>
    `,n.querySelectorAll("[data-plugin-jar]").forEach(e=>{e.addEventListener("change",async()=>{const s=e.checked,l=n.scrollTop;e.disabled=!0,g(`${s?"Enabling":"Disabling"} ${e.dataset.pluginJar}...`);try{const c=await fetch(`${u}/plugins/${encodeURIComponent(e.dataset.pluginJar)}/enabled`,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({enabled:s})});if(!c.ok)throw new Error(await c.text());g(`${s?"Enabled":"Disabled"} ${e.dataset.pluginJar}`),await _(),requestAnimationFrame(()=>{const d=document.getElementById("view-outlet");d&&(d.scrollTop=l)})}catch(c){e.checked=!s,e.disabled=!1,g(c.message||"Failed to change plugin state.")}})}),document.getElementById("plugin-file-input").addEventListener("change",async e=>{const s=e.target.files[0];if(s){g("Uploading plugin...");try{const l=await s.arrayBuffer();(await fetch(`${u}/plugins/install`,{method:"POST",body:l})).ok?(g("Plugin installed successfully!"),_()):g("Failed to install plugin.")}catch{g("Error uploading plugin JAR.")}}})}catch{n.innerHTML='<div class="loading">Failed to load plugins.</div>'}}window.addEventListener("DOMContentLoaded",ee);window.playMedia=W;window.deleteDownload=ce;
