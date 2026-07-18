# AI Development Log (AILog.md)

## Current Status
- **Goal**: Dockerize CloudStream, expose provider functionality through a headless JVM backend server, and build a premium responsive web UI.
- **Phase**: Complete Plugin Bundling & Auto-syncing (Complete).
- **Progress**: All port bindings switched to `2106`. All requested plugin repositories parsed, downloaded, translated to standard JVM bytecodes using `dex2jar`, and verified. Dockerfile adjusted to bundle plugins automatically.

---

## Steps Taken

### 1. Research & Dependency Integrations
- Explored version catalogs in `gradle/libs.versions.toml`.
- Added new declarations for Ktor server libraries (Core, Netty, ContentNegotiation, CORS, Kotlinx-Json) and SQLite JDBC to enable a lightweight database stack.
- Registered the new `:server` module inside `settings.gradle.kts`.

### 2. Database & Persistence Setup
- Implemented `DatabaseHelper.kt` utilizing raw JDBC calls against SQLite for:
    - Settings configuration parameters.
    - Bookmarks (favorites/watchlist) persistence.
    - Watch History progress log targets.
    - Server-side Downloads tracker logs.

### 3. Headless Core Services
- Implemented `ServerContext.kt` to handle standard directory layouts inside containers (`/data/config`, `/data/plugins`, `/data/downloads`).
- Implemented `ServerPluginManager.kt` configured to load extensions as KMP JVM compiled `.jar` files using standard JVM `URLClassLoader` mappings.
- Implemented `ServerDownloadManager.kt` running downloads in dynamic IO Coroutines, downloading stream payloads block-by-block directly onto the server storage volume.

### 4. REST API & Proxy Router
- Implemented `Application.kt` carrying core routing paths:
    - `/health` -> Liveness check
    - `/api/v1/providers` -> Loaded plugins check
    - `/api/v1/search` -> Parallel provider search using async coroutines
    - `/api/v1/load` -> Movie / Show metadata and episode loading
    - `/api/v1/links` -> Streaming links and subtitle decryption hook
    - `/api/v1/proxy` -> Byte-range HTTP proxy forwarding referer headers
    - `/api/v1/bookmarks` -> Favorites CRUD
    - `/api/v1/history` -> Watch progress logging
    - `/api/v1/downloads` -> Downloads list, start, and cancel endpoints
    - `/api/v1/plugins/install` -> Binary upload endpoint for installing plugins
    - Integrated static assets mapping via `staticResources("/", "web", index = "index.html")`.

### 5. Asset Extraction & Conversion
- Created a custom zero-dependency Node script `server/web/extract-assets.js` that parses colors and translations using fast regex mappings directly from the Android app resource directories.
- Run `node server/web/extract-assets.js` -> Generated colors.css and locales/en.json.

### 6. Single Page Application (SPA) Web UI
- Configured Vite configuration rules inside `server/web/package.json` and `server/web/vite.config.js`.
- Loaded `hls.js` CDN library in `index.html` to support in-browser streaming of `.m3u8` streams.
- Modified `index.html` and `styles.css` to add downloads routing buttons, inline layouts, and full-screen video overlay rules.
- Implemented routes `/player` and `/downloads` in `app.js`.
    - Player View: Resumes playback position from database history and fires progress ticks back to the server every 8 seconds.
    - Downloads View: Fetches active server download records, formats MB sizes, and handles progress bar renders.
- Run `bun run build` -> Compiled frontend packages successfully (95ms).

### 7. Port Configurations Switch (2106)
- Modified the default fallback server port in `[Application.kt](file:///home/kaos/Documents/cloudstream/server/src/main/kotlin/com/lagradost/cloudstream3/server/Application.kt)` to `2106`.
- Updated dev proxy mappings to target port `2106` inside `[vite.config.js](file:///home/kaos/Documents/cloudstream/server/web/vite.config.js)`.
- Switched default `CS_PORT` configurations and `EXPOSE` directives to `2106` in `[Dockerfile](file:///home/kaos/Documents/cloudstream/Dockerfile)`.
- Replaced port binding arrays to `"2106:2106"` inside `[docker-compose.yml](file:///home/kaos/Documents/cloudstream/docker-compose.yml)`.
- Rebuilt frontend assets (`bun run build`) and recompiled the native package (`./gradlew :server:installDist`).

### 8. Plugin Translation & Docker Bundling
- Wrote `[bundle_plugins.py](file:///home/kaos/Documents/cloudstream/bundle_plugins.py)` to automate:
    - Downloading `dex-tools-v2.4.zip` from GitHub releases and unzipping it.
    - Querying the user-provided plugin repository definitions:
        - Megix Repo (`CSX`)
        - `cs-Karma`
        - `ReflexRepo`
        - `CuxPlug`
        - `cloudstream-extensions-phisher`
        - `cloudstream-extensions-uk`
        - `cinephile`
        - `SkillShare-Repo`
    - Downloading the corresponding `.cs3` files.
    - Running `dex-tools` to convert Dalvik `.dex` bytecode files into standard JVM `.class` bytecode jar files.
    - Repackaging the class files with their manifests into standard JVM plugin `.jar` files in `bundled-plugins/` folder (148 plugins successfully generated).
- Modified `[ServerPluginManager.kt](file:///home/kaos/Documents/cloudstream/server/src/main/kotlin/com/lagradost/cloudstream3/server/plugins/ServerPluginManager.kt)` to automatically scan and copy any pre-bundled `.jar` files from `/app/bundled-plugins` into the working plugins directory (`/data/plugins`) at boot. This guarantees full compatibility with any volume mounting type (named volumes or bind-mounts).
- Updated `[Dockerfile](file:///home/kaos/Documents/cloudstream/Dockerfile)`:
    - Installed `python3` and `curl` inside the builder stage.
    - Executed `python3 bundle_plugins.py` to compile all 148 JVM plugins during the build stage.
    - Copied the compiled `bundled-plugins/` folder to the final runtime JRE layer.

---

## Issues & Mitigations

### 1. Repository Configuration Conflict
- *Issue*: Gradle threw a `FAIL_ON_PROJECT_REPOS` exception because repositories were declared locally inside `server/build.gradle.kts`.
- *Mitigation*: Deleted the local `repositories` block from the module configurations to leverage the global settings declaration.

### 2. Ktor stream copying channel mismatch
- *Issue*: Standard Java InputStream from URLConnection did not copy directly to Ktor's `ByteWriteChannel`.
- *Mitigation*: Replaced `respondBytesWriter` with `respondOutputStream` to copy streams directly to the servlet output using Kotlin stdlib utilities.

### 3. Java CLI missing on compiler path
- *Issue*: Calling `d2j-dex2jar.sh` locally during build phase returned exit code 127 because Java was not available globally in the bash environment.
- *Mitigation*: Updated `bundle_plugins.py` to auto-detect and prepend the local Gradle-cached JDK 17 bin path (`/home/kaos/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2/bin`) into the subprocess environment `PATH`.

---

## Steps Taken (Continued)

### 9. Headful Browser Solver & Automation Bypass
- Configured Playwright Chromium in `server.js` to run in **headful mode** (`headless: false`) and inject an init script that hides the `navigator.webdriver` property.
- Wrapped the challenge browser's startup command inside `xvfb-run` within the Dockerfile to support virtual framebuffer GUI execution on a headless Linux host.
- Programmed an automatic background daemon that polls the active frame layout for Cloudflare Turnstile iframes and simulates a physical click on their center.
- Created `escAttr` wrapper utility to sanitise templated strings in inline javascript listeners, resolving playback crashes for media titles with apostrophes (e.g. *Grey's Anatomy*).

### 10. Multi-Device "Resume Watching" Section
- Added `title`, `poster_url`, and `provider` columns to `watch_history` schema in `DatabaseHelper.kt` to record full metadata alongside watch progress.
- Implemented robust `ALTER TABLE` upgrades within the DB initializer to dynamically migrate existing database schemas.
- Modified the Web UI Homepage to show a premium "Resume Watching" section at the top of the feed featuring cover posters, progress metrics, and direct link bindings.
- Converted the History page to render synced records retrieved from the SQLite server-side backend instead of local storage.

### 11. Interactive Overlay Challenge Dialog
- Modified the client challenge-handling logic from full-route URL redirection to a seamless modal overlay.
- The challenge dialog appears automatically on any active page if a challenge is pending, streams screenshots in real-time, accepts click/type commands, and automatically closes once solved.

---

## Current Status & Next Steps
- **Status**: The entire CloudStream server, challenge proxy, and responsive Web application are fully deployed and running on `G3NAS` at port `2106`.

### 12. Non-blocking Homepage Streaming Population
- **Problem**: When fetching homepages with "All Providers", the system originally waited for *every single provider* to finish loading (often taking 5-10s if a provider lagged or timed out) before showing anything, leaving the user with a blank loading screen.
- **Solution**:
  - Refactored the Ktor `/api/v1/home` endpoint to fetch all provider categories concurrently using Kotlin coroutines.
  - Converted the route to return an NDJSON (Newline Delimited JSON) stream, flushing each category section immediately to the socket writer as soon as it completes.
  - Modified the Javascript client's `renderHome()` to request watch progress first and render the "Resume Watching" section instantly (<50ms).
  - Wired a `ReadableStream` reader in the client to process the incoming NDJSON stream line-by-line, dynamically appending homepage sections as they arrive, removing the loading spinner only when all providers complete.

### 13. Event-Driven Watch Progress & Season/Episode Metadata Sync
- **Problem 1**: Mobile Safari (on iOS/iPadOS) suspends `setInterval` timer ticks completely when in native fullscreen or when tabs lose focus, preventing the 8-second progress logging from saving.
- **Problem 2**: Database foreign key constraints in `watch_history` to `bookmarks` blocked progress tracking of non-bookmarked shows.
- **Problem 3**: External player streams (VLC, Infuse) did not log watch progress to the backend DB, and show/anime episodes lacked season and episode number logging, defaulting all series items in the resume section to "Movie".
- **Solution**:
  - Replaced the video player interval in `app.js` with robust event-driven hooks (throttling on native `<video>` `timeupdate` events, and listening to `pause`, `seeked`, and `pagehide` with `keepalive: true` fetches).
  - Patched the SQLite schema setup in `DatabaseHelper.kt` to drop the bookmark constraint, synchronizing watch progress across all played titles.
  - Hooked native player launcher clicks (`openNative`) to write a 2% watched progress trace (`positionMs = 10000`, `durationMs = 500000`) before hand-off, forcing external players to appear instantly in the homepage "Resume Watching" feed.
  - Propagated `seasonNum` and `episodeNum` values from `renderDetail`'s episode selectors, through `playMedia` and `startPlayback`, all the way to player query params and backend POST requests.
  - History items now display `S<season>E<episode>` instead of "Movie", for both native and external players.

### 14. Rich Watch History Metadata, Headless Solver, and History Deletion
- **Problem 1**: External player launches (VLC/Infuse) and native playback start initially lacked a rich metadata snapshot (plot, type, year, rating, cover poster), leading to a "Failed to load data" appearance on details screens when resuming external players.
- **Problem 2**: Clicking "Open challenge" for cloudflare-guarded providers (like AnimePahe) failed with "Connection refused" because Chromium crashed silently on boot inside the container under headful mode (`headless: false`) when no X11 display server was available.
- **Problem 3**: Clicking the delete button in the "Resume Watching" carousel failed silently for items representing multi-link series lists because the ID was serialized as a JSON string array containing double-quotes, which got HTML-escaped (`&quot;`) inside the inline `onclick` handler, producing a malformed URI component.
- **Solution**:
  - Implemented `fetchAndCacheDetailSnapshot(url, provider)` helper on the frontend to fetch provider detail payloads (`/api/v1/load`) and cache them in `sessionStorage` to avoid redundant requests.
  - Awaited the snapshot before initiating external playback (`openNative`) and native playback (`startPlayback`) to save rich metadata (`plot`, `type`, `year`, `score`) into both browser history and the backend database.
  - Upgraded the database schema in `DatabaseHelper.kt` with `plot`, `type`, `year`, and `score` columns, and implemented safe `ALTER TABLE` upgrades to preserve user data.
  - Set `headless: true` and added `--disable-gpu --single-process` to Chromium's launch config in `server.js`, enabling Playwright to run correctly inside the Docker container.
  - Refactored the "Delete Watch Progress" card button to store raw IDs as Base64 strings in a `data-remove-id` attribute, wiring the listener via event delegation after rendering. This completely avoids inline onclick HTML entity escaping issues.
  - Verified and confirmed that all history items delete cleanly and update the home feed instantly.

### 15. Browser Challenge Modal Suppression, Session Expiration, and Persistent Context Fixes
- **Problem 1**: The client-side challenge poller repeatedly popped up the challenge modal every 2 seconds even if the user closed it, completely locking users out of using the application.
- **Problem 2**: Unresolved pending challenge sessions remained active in the Node server map indefinitely, causing the poller to continually pop up modals across all active client screens.
- **Problem 3**: `xvfb-run` inside Docker noble has a known initialization hang where it gets stuck in `wait` waiting for `SIGUSR1` from `Xvfb`.
- **Solution**:
  - Modified [app.js](file:///home/kaos/Documents/cloudstream/server/web/js/app.js) to store closed challenge session IDs in `sessionStorage` when the modal is dismissed, skipping them from triggering subsequent popup requests on the client.
  - Refactored [server.js](file:///home/kaos/Documents/cloudstream/server/challenge/server.js) to unconditionally expire and delete any session from memory after 5 minutes of creation, preventing stuck pending sessions from lingering.
  - Switched from buggy `xvfb-run` to direct manual background launch of `Xvfb` (with `-ac`) followed by a 3-second sleep before launching node, eliminating container startup hangs.
  - Upgraded Playwright launch logic in [server.js](file:///home/kaos/Documents/cloudstream/server/challenge/server.js) to run dedicated `launchPersistentContext` contexts per session (using `/tmp/playwright-profile-XXXX`) with `ignoreDefaultArgs: ['--enable-automation']` and custom stealth overrides, ensuring clean isolation and strong anti-bot profiles.
  - Verified that home page renders cleanly and modal suppression works perfectly.

### 16. Codebase Indexing Status Check
- Checked status of `kaos-ast` codebase indexer.
- Verified index is healthy, loaded, and ready to search. Index serves 486 files and 3,335 chunks with detected languages dominated by Kotlin.

### 17. Client-Side WASM MKV Streaming Research
- Performed detailed research on in-browser MKV/HEVC/AV1 playback.
- Investigated `cineby.cc` and `yomi.to` media aggregation/streaming strategies.
- Analyzed WASM-based demuxing/remuxing libraries (`libav.js`, `web-demuxer`, `ffmpeg.wasm`) for container conversion without full video transcoding.
- Documented server configuration requirements (CORS/COOP/COEP headers) and client player state synchronization in a dedicated research artifact [mkv_wasm_streaming_research.md](file:///home/kaos/.gemini/antigravity-cli/brain/e393fdc8-8513-47dc-bd3c-e23b6d461716/mkv_wasm_streaming_research.md).

### 18. Video & Subtitle Playback Coverage Mapping
- Researched popular video formats (MKV, MP4, WebM, AVI, MOV, FLV, TS) and codecs (H.264, H.265 / HEVC / h256, AV1, VP9, AC3, DTS).
- Researched popular subtitle formats (WebVTT, SRT, ASS, SSA, TTML, PGS, VobSub).
- Created a comprehensive integration blueprint mapping each format to its in-browser playback/WASM-rendering strategy in a new artifact [mkv_wasm_subtitle_video_coverage.md](file:///home/kaos/.gemini/antigravity-cli/brain/e393fdc8-8513-47dc-bd3c-e23b6d461716/mkv_wasm_subtitle_video_coverage.md).
- Added dedicated platform profiles for iPad, Android, Windows, and Linux native browsers to guide direct copy vs soft-decoding behavior.
- Implemented client-side detection utility [capability-checker.js](file:///home/kaos/Documents/cloudstream/server/web/js/capability-checker.js) to resolve play route actions dynamically.

### 19. WASM Playback Implementation & Remote Sync Deployment
- Implemented the client-side capability checking integrations directly in [app.js](file:///home/kaos/Documents/cloudstream/server/web/js/app.js) and [styles.css](file:///home/kaos/Documents/cloudstream/server/web/css/styles.css) to support transparent styled subtitle overlays and dynamic WASM loading from CDNs.
- Configured Ktor [Application.kt](file:///home/kaos/Documents/cloudstream/server/src/main/kotlin/com/lagradost/cloudstream3/server/Application.kt) with an interceptor to serve security headers (`Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp`) so that browsers enable SharedArrayBuffer.
- Successfully built Vite assets and synced all modifications to `kaos@g3nas.local:/home/kaos/cloudstream`.
- Triggered remote docker-compose rebuilds and confirmed the deployment is healthy and responding to web clients.

### 20. Interactive Browser Stream Testing
- Initiated a controlled Chrome DevTools browser session targeting `http://localhost:2106/` via an SSH tunnel (to ensure a secure/trustworthy origin for COOP/COEP compliance).
- Evaluated player source loading for the movie "Obsession" via the CineStream provider.
- Identified that the user-reported "Protected stream" is not an error or block, but a label in the playback UI (`link.referer ? 'Protected stream' : 'Direct stream'`) indicating that the link requires a custom referer header (which the proxy endpoints handle automatically).
- Confirmed that clicking and playing "Protected streams" works perfectly, loading the video stream with standard native playback controls (DIRECT mode) and proper resolution mapping.
- Investigated a "stream could not be loaded" failure caused by relative HLS (`.m3u8`) segment paths resolving to the local proxy origin instead of the remote content host.
- Re-deployed, rebuilt remote containers, and verified that `.m3u8` playlists and all resolved `.ts` chunks now load with HTTP 200 OK, resulting in successful playback.

### 21. Expanded Android API Stubs for Plugin Bytecode Compatibility
- **Problem**: When loading and running compiled Android plugin JARs (such as `MovieBox`), the server-side JVM environment threw `NoSuchMethodError` for `android.net.Uri.getQueryParameterNames()` or had potential classpath linkage failures for missing classes like `android.util.Base64` and `android.os.Bundle`.
- **Solution**:
  - Expanded [Uri.kt](file:///home/kaos/Documents/cloudstream/server/src/main/kotlin/android/net/Uri.kt) with comprehensive Android-compatible properties (`lastPathSegment`, `pathSegments`, `encodedQuery`, etc.), helper constructors (`EMPTY`, `fromParts`, `fromFile`), full `Uri.Builder` compatibility overloads, and a complete `getQueryParameterNames()` implementation.
  - Added exception/throwable logging overloads to [Log.kt](file:///home/kaos/Documents/cloudstream/server/src/main/kotlin/android/util/Log.kt).
  - Created [Base64.kt](file:///home/kaos/Documents/cloudstream/server/src/main/kotlin/android/util/Base64.kt) stub delegate using standard Java `java.util.Base64`.
  - Created [Stubs.kt](file:///home/kaos/Documents/cloudstream/server/src/main/kotlin/android/os/Stubs.kt) defining stubs for `Build`, `Bundle`, `Parcelable`, and `Parcel`.
  - Rsynced to `g3nas`, rebuilt containers, and verified that `MovieBox` search and homepage fallbacks execute cleanly and return correct results.

### 22. Client-Side WASM MP4 Muxer and Demuxer Loop Refactoring for Safari / iPadOS
- **Problem**: Playing MKV/HEVC files on iPadOS/Safari failed during WASM initialization with the error: `Failed to setup MSE demuxer: Failed to allocate output context.`
- **Cause**: 
  - The player previously loaded `@libav.js/variant-default`, which only bundles simple audio codecs and completely lacks video/MP4 container muxing capabilities.
  - The `libav.ff_init_muxer` invocation passed a dummy `streams` property inside the options block rather than supplying the required stream contexts (`[codecpar, time_base_num, time_base_den]`) as the second positional argument.
  - The demuxing loop was using a made-up method `libav.ff_write_multi_packet` and calling `libav.av_read_frame(fmt_ctx)` without passing a packet reference pointer, which returned invalid structures.
- **Solution**:
  - Switched the client to load `@libav.js/variant-webcodecs` which bundles full MP4/Ogg/WebM demuxing and muxing support alongside H.264 and AAC parsers.
  - Created a virtual writer device `output.mp4` and registered a global `libav.onwrite` hook to stream fragmented MP4 chunks directly into the player's `SourceBuffer`.
  - Refactored `initiateWasmPlayback` to allocate a packet `libav.av_packet_alloc()` and run `libav.av_read_frame(fmt_ctx, pkt)`.
  - Dynamically mapped the packet's stream index using `libav.AVPacket_stream_index_s` to the output muxer's indices, and wrote packets using `libav.av_interleaved_write_frame(out_fmt_ctx, pkt)`.
  - Configured `movflags` with `fragmented+empty_moov+default_base_moof+frag_keyframe` via `libav.av_opt_set` to support streaming chunks.
  - Added a robust update end queue (`writeQueue`) to prevent `SourceBuffer` append failures during active updates.
  - Integrated proper packet deallocation (`libav.av_packet_unref` and `libav.av_packet_free`) to prevent client-side memory leaks.
