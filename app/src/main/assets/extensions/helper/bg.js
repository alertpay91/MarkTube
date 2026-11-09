// Background play: YouTube pauses the video when the page reports that it is hidden (app in
// the background, screen off). The page is always told that it is visible, and the
// "visibility changed" events never reach it. This replaces the "Video Background Play Fix"
// add-on, which together with uBlock Origin delayed every video start by about 5 seconds.
(() => {
  const W = window.wrappedJSObject;
  const proto = W.Document.prototype;
  const define = (name, value) => {
    try {
      Object.defineProperty(proto, name, {
        configurable: true,
        enumerable: true,
        get: exportFunction(() => value, W),
      });
    } catch (e) { }
  };
  define("hidden", false);
  define("webkitHidden", false);
  define("visibilityState", "visible");
  define("webkitVisibilityState", "visible");
  try {
    Object.defineProperty(proto, "hasFocus", {
      configurable: true,
      writable: true,
      value: exportFunction(() => true, W),
    });
  } catch (e) { }
  // Runs before any of YouTube's scripts, so these capture listeners come first.
  const swallow = (e) => e.stopImmediatePropagation();
  for (const type of ["visibilitychange", "webkitvisibilitychange"]) {
    window.addEventListener(type, swallow, true);
    document.addEventListener(type, swallow, true);
  }
  // The window losing focus (not a text field losing it).
  window.addEventListener("blur", (e) => { if (e.target === window) swallow(e); }, true);
})();
