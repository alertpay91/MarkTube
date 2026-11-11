// On video pages the app plays the video with its own (native) player, so YouTube's web
// player must not download it too: its requests for the video data are cancelled. Shorts
// and videos the native player can't play (the app says so) still use the web player.
let nativeOn = true;

browser.runtime.onMessage.addListener((msg) => {
  if (msg && msg.type === "native") nativeOn = !!msg.value;
});

browser.webRequest.onBeforeRequest.addListener((d) => {
  if (!nativeOn) return {};
  const doc = d.documentUrl || d.originUrl || "";
  if (/^https:\/\/(m|www)\.youtube\.com\/watch/.test(doc)) return { cancel: true };
  return {};
}, { urls: ["*://*.googlevideo.com/videoplayback*"] }, ["blocking"]);
