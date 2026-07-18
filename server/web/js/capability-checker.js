/**
 * Client-Side Video & Audio Capability Checker
 * Determines native browser capabilities and decides the optimal playback route.
 */

export function checkBrowserCapabilities() {
  const video = document.createElement('video');
  const hasMSE = 'MediaSource' in window;
  const hasWebCodecs = 'VideoDecoder' in window && 'AudioDecoder' in window;

  const capabilities = {
    // Platform Engines
    mse: hasMSE,
    webCodecs: hasWebCodecs,

    // Containers (Native direct playback)
    containers: {
      mp4: video.canPlayType('video/mp4') !== '',
      webm: video.canPlayType('video/webm') !== '',
      ogg: video.canPlayType('video/ogg') !== '',
      mov: video.canPlayType('video/quicktime') !== '',
      mkv: video.canPlayType('video/x-matroska') !== '' || video.canPlayType('video/mkv') !== '',
      avi: video.canPlayType('video/x-msvideo') !== '' || video.canPlayType('video/avi') !== '',
    },

    // Video Codecs
    videoCodecs: {
      h264: video.canPlayType('video/mp4; codecs="avc1.42E01E"') !== '',
      h265: video.canPlayType('video/mp4; codecs="hvc1.1.6.L120.90"') !== '' || 
            video.canPlayType('video/mp4; codecs="hev1.1.6.L120.90"') !== '',
      av1: video.canPlayType('video/mp4; codecs="av01.0.08M.08"') !== '' ||
           video.canPlayType('video/webm; codecs="av01.0.08M.08"') !== '',
      vp9: video.canPlayType('video/webm; codecs="vp9"') !== '' ||
           video.canPlayType('video/mp4; codecs="vp09.00.10.08"') !== '',
      vp8: video.canPlayType('video/webm; codecs="vp8"') !== '',
      mpeg4: video.canPlayType('video/mp4; codecs="mp4v.20.8"') !== '',
    },

    // Audio Codecs
    audioCodecs: {
      aac: video.canPlayType('audio/mp4; codecs="mp4a.40.2"') !== '',
      mp3: video.canPlayType('audio/mpeg') !== '',
      opus: video.canPlayType('audio/webm; codecs="opus"') !== '' || 
            video.canPlayType('audio/mp4; codecs="opus"') !== '',
      flac: video.canPlayType('audio/mp4; codecs="flac"') !== '' || 
            video.canPlayType('audio/flac') !== '',
      ac3: video.canPlayType('audio/mp4; codecs="ac-3"') !== '',
      ec3: video.canPlayType('audio/mp4; codecs="ec-3"') !== '',
      dts: video.canPlayType('audio/mp4; codecs="dts"') !== '' ||
           video.canPlayType('audio/mp4; codecs="dtsc"') !== '',
    }
  };

  return capabilities;
}

/**
 * Determines the playback pathway for a given file configuration.
 * 
 * @param {string} fileUrl - The source URL of the video file.
 * @param {Object} fileMeta - Metadata containing track info if known.
 * @param {string} fileMeta.container - e.g. "mkv", "mp4", "avi"
 * @param {string} fileMeta.videoCodec - e.g. "h264", "hevc", "av1", "mpeg4"
 * @param {string} fileMeta.audioCodec - e.g. "aac", "mp3", "ac3", "dts"
 */
export function getPlaybackRoute(fileUrl, fileMeta = {}) {
  const caps = checkBrowserCapabilities();
  
  // 1. Parse container extension from URL if not explicitly provided
  let container = fileMeta.container;
  if (!container) {
    const extension = fileUrl.split('?')[0].split('.').pop().toLowerCase();
    container = ['mkv', 'mp4', 'avi', 'webm', 'mov', 'flv', 'ts'].includes(extension) ? extension : 'mp4';
  }

  const vCodec = fileMeta.videoCodec || 'h264';
  const aCodec = fileMeta.audioCodec || 'aac';

  // Output Route object
  const route = {
    method: 'DIRECT', // DIRECT, REMUX_COPY, REMUX_TRANSCODE, SOFT_DECODE
    reasons: [],
    videoCodec: vCodec,
    audioCodec: aCodec,
    container: container
  };

  const containerNative = caps.containers[container];
  const videoNative = caps.videoCodecs[vCodec];
  const audioNative = caps.audioCodecs[aCodec];

  // Case 1: Everything is native (Perfect happy path)
  if (containerNative && videoNative && audioNative) {
    route.method = 'DIRECT';
    route.reasons.push(`Browser natively supports ${container.toUpperCase()} with ${vCodec.toUpperCase()}/${aCodec.toUpperCase()}`);
    return route;
  }

  // Without MSE support, we have no choice but to let the device native OS try direct URL playback
  if (!caps.mse) {
    route.method = 'DIRECT';
    route.reasons.push('MediaSource Extensions (MSE) is unsupported on this browser. Falling back to native direct play.');
    return route;
  }

  // Case 2: Video codec is unsupported -> Must use WASM Software Decoding
  if (!videoNative) {
    route.method = 'SOFT_DECODE';
    if (caps.webCodecs) {
      route.reasons.push(`Video codec ${vCodec.toUpperCase()} is unsupported. Initiating WASM Soft-Decoding with WebCodecs.`);
    } else {
      route.reasons.push(`Video codec ${vCodec.toUpperCase()} and WebCodecs are unsupported. Falling back to CPU soft-decoding.`);
    }
    return route;
  }

  // Case 3: Container is unsupported, but both codecs are native -> Remux & copy bitstreams
  if (!containerNative && videoNative && audioNative) {
    route.method = 'REMUX_COPY';
    route.reasons.push(`Container ${container.toUpperCase()} is unsupported, but codecs are native. Demuxing and copying bitstreams.`);
    return route;
  }

  // Case 4: Audio codec is unsupported, but video codec is native (whether container is native or not) -> Remux & transcode audio to Opus/Vorbis
  if (videoNative && !audioNative) {
    route.method = 'REMUX_TRANSCODE';
    route.reasons.push(`Audio codec ${aCodec.toUpperCase()} is unsupported. Transcoding audio to Opus/Vorbis while copying video.`);
    return route;
  }

  // Default fallback
  route.method = 'DIRECT';
  route.reasons.push('Defaulted to DIRECT play path.');
  return route;
}
