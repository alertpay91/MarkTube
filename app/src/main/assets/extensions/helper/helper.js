// Lets the app's back button close whatever YouTube has open on top of the page.
(() => {
  const port = browser.runtime.connectNative("slimtube");

  const visible = (el) => {
    if (!el || !el.isConnected) return false;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    for (let e = el; e && e !== document.documentElement; e = e.parentElement) {
      const s = getComputedStyle(e);
      if (s.display === "none" || s.visibility === "hidden" || s.opacity === "0") return false;
    }
    return true;
  };
  const firstVisible = (selectors) => {
    for (const sel of selectors) {
      for (const el of document.querySelectorAll(sel)) if (visible(el)) return el;
    }
    return null;
  };
  const tap = (el) => {
    el.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true }));
    el.dispatchEvent(new PointerEvent("pointerup", { bubbles: true }));
    el.click();
  };

  // Only explicit "Close" buttons of panels that YouTube opens without a history entry
  // (comments, description, chapters). Menus/sheets are closed via history (#bottom-sheet).
  const CLOSE_LABEL = /^(close|zatvori|затвори)$/i;
  function tryClose() {
    const panels = document.querySelectorAll(
      "ytm-engagement-panel, ytm-engagement-panel-section-list-renderer, ytm-playlist-panel-renderer");
    for (const panel of panels) {
      if (!visible(panel)) continue;
      for (const b of panel.querySelectorAll("button[aria-label]")) {
        if (CLOSE_LABEL.test(b.getAttribute("aria-label").trim()) && visible(b)) {
          tap(b);
          return "panel";
        }
      }
    }
    return null;
  }

  // Confirmation dialogs (e.g. "Unsubscribe?") have no history entry: back presses their Cancel.
  const DIALOGS = "dialog, [role=dialog], [role=alertdialog], ytm-dialog, tp-yt-paper-dialog, "
    + "ytm-confirm-dialog-renderer, .dialog-container, c3-dialog";
  const CANCEL_LABEL = /^(cancel|otkaži|откажи|close|zatvori|затвори|no thanks|not now|ne, hvala|ne sada)$/i;
  function tryCloseDialog() {
    for (const d of document.querySelectorAll(DIALOGS)) {
      if (!visible(d)) continue;
      for (const b of d.querySelectorAll("button, [role=button], a")) {
        const t = (b.getAttribute("aria-label") || b.textContent || "").trim();
        if (CANCEL_LABEL.test(t) && visible(b)) {
          tap(b);
          return "dialog";
        }
      }
    }
    return null;
  }

  // Shorts are swiped without history entries, so remember the ones seen in this run of Shorts.
  // Back then steps to the previous short instead of leaving Shorts altogether.
  let shortsStack = [];
  const shortId = () => {
    const m = location.pathname.match(/^\/shorts\/([^/?#]+)/);
    return m ? m[1] : null;
  };
  function trackShorts() {
    const id = shortId();
    if (!id) { shortsStack = []; return; }
    const n = shortsStack.length;
    if (n && shortsStack[n - 1] === id) return;
    if (n >= 2 && shortsStack[n - 2] === id) shortsStack.pop();
    else shortsStack.push(id);
  }
  // Sponsored shorts slip past the ad blocker: skip them like the user would.
  const AD_LABEL = /^(sponsored|sponzorisano|спонзорисано|ad|oglas|оглас)$/i;
  const NEXT_LABEL = /^(next video|sledeći video|следећи видео)$/i;
  let lastSkip = 0;
  function skipAdShort() {
    if (!shortId() || Date.now() - lastSkip < 1500) return;
    const mid = innerHeight / 2;
    for (const el of document.querySelectorAll("span, div, ytm-badge-renderer")) {
      if (el.childElementCount > 1) continue;
      const t = (el.textContent || "").trim();
      if (t.length > 12 || !AD_LABEL.test(t)) continue;
      const r = el.getBoundingClientRect();
      if (r.height < 2 || r.bottom < mid - innerHeight || r.top > innerHeight || r.bottom < 0 || !visible(el)) continue;
      for (const b of document.querySelectorAll("button[aria-label]")) {
        if (NEXT_LABEL.test(b.getAttribute("aria-label").trim())) {
          lastSkip = Date.now();
          tap(b);
          port.postMessage({ type: "log", text: "skipped sponsored short" });
          return;
        }
      }
    }
  }
  setInterval(() => { trackShorts(); try { skipAdShort(); } catch (e) { } }, 250);
  trackShorts();

  const PREV_LABEL = /^(previous video|prethodni video|претходни видео)$/i;
  function tryPreviousShort() {
    trackShorts();
    if (!shortId() || shortsStack.length < 2) return null;
    for (const b of document.querySelectorAll("button[aria-label]")) {
      if (PREV_LABEL.test(b.getAttribute("aria-label").trim())) {
        tap(b);
        return "short";
      }
    }
    return null;
  }

  // Short description of what is on screen, so problems can be diagnosed from logcat.
  function dump() {
    const out = [];
    for (const el of document.querySelectorAll("body *")) {
      const tag = el.tagName.toLowerCase();
      if (!/^(ytm-|c3-|bottom-sheet|tp-yt)/.test(tag)) continue;
      const s = getComputedStyle(el);
      if ((s.position === "fixed" || tag.includes("panel") || tag.includes("sheet") || tag.includes("dialog")) && visible(el)) {
        out.push(tag + (el.id ? "#" + el.id : "") + (el.className && typeof el.className === "string" ? "." + el.className.trim().split(/\s+/).slice(0, 2).join(".") : ""));
      }
    }
    const closers = [...document.querySelectorAll("button[aria-label]")]
      .filter(visible).map((b) => b.getAttribute("aria-label")).slice(0, 25);
    const dialogs = [...document.querySelectorAll(DIALOGS)].filter(visible)
      .map((d) => d.tagName.toLowerCase() + "[" + [...d.querySelectorAll("button, [role=button]")]
        .map((b) => (b.getAttribute("aria-label") || b.textContent || "").trim().slice(0, 20)).join("|") + "]");
    return { fixed: [...new Set(out)].slice(0, 40), buttons: closers, url: location.href, shorts: shortsStack.length, dialogs };
  }

  function leaveShorts() {
    // The Shorts video player sits on top of the page and stays black until the next page
    // has been built. Stop and hide it right away; also use the normal dark background
    // (Shorts uses pure black) so the wait looks like any other tab change.
    const hidden = [...document.querySelectorAll("video, .player-container, shorts-page")];
    for (const v of document.querySelectorAll("video")) {
      try { v.pause(); } catch (e) { }
    }
    const restore = hidden.map((el) => [el, el.style.visibility]);
    for (const el of hidden) el.style.visibility = "hidden";
    const style = document.createElement("style");
    style.textContent = "html,body{background:#0f0f0f!important}";
    document.documentElement.appendChild(style);
    // YouTube reuses the player element for normal videos, so put it back soon, or at the
    // first touch if the user opens something before that.
    let done = false;
    const undo = () => {
      if (done) return;
      done = true;
      for (const [el, v] of restore) el.style.visibility = v;
      style.remove();
      document.removeEventListener("touchstart", undo, true);
    };
    setTimeout(undo, 2500);
    document.addEventListener("touchstart", undo, true);
  }

  port.onMessage.addListener((msg) => {
    if (msg.type === "back") {
      let closed = null;
      try { closed = tryCloseDialog() || tryClose() || tryPreviousShort(); } catch (e) { closed = null; }
      port.postMessage({ type: "backResult", id: msg.id, handled: !!closed, what: closed });
    } else if (msg.type === "nav") {
      // Leaving Shorts: YouTube keeps the black Shorts player on screen until the next page
      // has rendered. Stop and hide it right away so the new page shows up sooner.
      if (location.pathname.startsWith("/shorts")) {
        try { leaveShorts(); } catch (e) { }
      }
      // A link click lets YouTube switch pages itself, which is much faster than a reload.
      const a = document.createElement("a");
      a.href = msg.url;
      a.style.display = "none";
      document.body.appendChild(a);
      a.click();
      a.remove();
    } else if (msg.type === "dump") {
      port.postMessage({ type: "dump", data: dump() });
    }
  });
})();
