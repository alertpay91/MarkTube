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

  // ---- Pull down at the top of a feed to reload it (a browser would do this; GeckoView doesn't) ----
  const isFeedPage = () => !/^\/(watch|shorts|live|embed)/.test(location.pathname);
  const scrolledAncestor = (el) => {
    for (let e = el; e && e !== document.documentElement; e = e.parentElement) {
      if (e.scrollTop > 0) return true;
    }
    return false;
  };
  let ptr = null, ptrStart = -1, ptrDist = 0;
  const PTR_LIMIT = 140; // px of finger travel needed
  function ptrView() {
    if (ptr && ptr.isConnected) return ptr;
    ptr = document.createElement("div");
    ptr.style.cssText = "position:fixed;top:0;left:50%;width:40px;height:40px;margin-left:-20px;"
      + "border-radius:50%;background:#272727;color:#f1f1f1;z-index:2147483647;display:flex;"
      + "align-items:center;justify-content:center;font:22px sans-serif;box-shadow:0 2px 6px #0008;"
      + "transform:translateY(-60px);opacity:0;pointer-events:none;transition:none";
    ptr.textContent = "↻";
    document.documentElement.appendChild(ptr);
    return ptr;
  }
  function ptrHide() {
    if (!ptr) return;
    ptr.style.transition = "transform .2s, opacity .2s";
    ptr.style.transform = "translateY(-60px)";
    ptr.style.opacity = "0";
  }
  document.addEventListener("touchstart", (e) => {
    ptrStart = -1;
    if (e.touches.length !== 1 || !isFeedPage() || window.scrollY > 0
      || scrolledAncestor(e.target) || firstVisible([DIALOGS, "bottom-sheet-container", "ytm-bottom-sheet-renderer"])) return;
    ptrStart = e.touches[0].clientY;
    ptrDist = 0;
  }, { passive: true, capture: true });
  document.addEventListener("touchmove", (e) => {
    if (ptrStart < 0) return;
    ptrDist = e.touches[0].clientY - ptrStart;
    if (ptrDist <= 0 || window.scrollY > 0) {
      ptrStart = -1;
      ptrHide();
      return;
    }
    const v = ptrView();
    const k = Math.min(ptrDist / PTR_LIMIT, 1);
    v.style.transition = "none";
    v.style.transform = "translateY(" + Math.round(k * 70) + "px) rotate(" + Math.round(k * 270) + "deg)";
    v.style.opacity = String(k);
    v.style.color = k >= 1 ? "#3ea6ff" : "#f1f1f1";
  }, { passive: true, capture: true });
  const ptrEnd = () => {
    if (ptrStart < 0) return;
    ptrStart = -1;
    if (ptrDist >= PTR_LIMIT && window.scrollY <= 0) {
      port.postMessage({ type: "log", text: "pull to refresh" });
      location.reload();
    } else {
      ptrHide();
    }
  };
  document.addEventListener("touchend", ptrEnd, { capture: true });
  document.addEventListener("touchcancel", () => { ptrStart = -1; ptrHide(); }, { capture: true });

  // ---- "Preuzmi" (download) item in YouTube's bottom tab bar ----
  const DL_ICON = '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor" style="pointer-events:none">'
    + '<path d="M17 18v1H7v-1h10zm-.86-6.65-.7-.71L12.5 13.6V4h-1v9.6L8.56 10.64l-.7.71L12 15.5l4.14-4.15z"/></svg>';
  // Swallow every pointer event on our own button so YouTube never sees it, and open the
  // download screen on touchend: with touchstart cancelled, Gecko sends no click at all.
  let lastTap = 0;
  function guardTap(item, action) {
    let sx = 0, sy = 0;
    const fire = () => {
      const now = Date.now();
      if (now - lastTap < 800) return;
      lastTap = now;
      action();
    };
    const h = (e) => {
      e.stopImmediatePropagation();
      if (e.type === "touchstart") {
        sx = e.touches[0].clientX; sy = e.touches[0].clientY;
        return;
      }
      if (e.cancelable) e.preventDefault();
      if (e.type === "touchend") {
        const t = e.changedTouches[0];
        if (Math.abs(t.clientX - sx) < 20 && Math.abs(t.clientY - sy) < 20) fire();
      } else if (e.type === "click") {
        fire();
      }
    };
    for (const t of ["click", "pointerdown", "pointerup", "touchstart", "touchend", "mousedown", "mouseup"]) {
      item.addEventListener(t, h, { capture: true });
    }
  }
  function addDownloadTab() {
    const bar = document.querySelector("ytm-pivot-bar-renderer");
    if (!bar || bar.querySelector(".marktube-dl")) return;
    const items = bar.querySelectorAll("ytm-pivot-bar-item-renderer");
    if (!items.length) return;
    // Copy a real tab so it looks exactly like YouTube's own, then swap its icon and label.
    const item = items[items.length - 1].cloneNode(true);
    item.classList.add("marktube-dl");
    for (const el of [item, ...item.querySelectorAll("*")]) {
      el.removeAttribute("id");
      el.removeAttribute("href");
      el.removeAttribute("aria-selected");
      el.classList.remove("pivot-bar-item-tab-selected", "selected");
    }
    const svg = item.querySelector("svg");
    if (svg) {
      const holder = document.createElement("span");
      holder.innerHTML = DL_ICON;
      svg.replaceWith(holder.firstChild);
    }
    const walker = document.createTreeWalker(item, NodeFilter.SHOW_TEXT);
    let first = true;
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      if (!n.nodeValue.trim()) continue;
      n.nodeValue = first ? "Preuzmi" : "";
      first = false;
    }
    item.setAttribute("aria-label", "Preuzmi");
    guardTap(item, () => port.postMessage({ type: "download", url: location.href }));
    items[items.length - 1].after(item);
  }
  setInterval(() => { try { addDownloadTab(); } catch (e) { } }, 1000);
  // On a normal video page YouTube hides the bottom tab bar, so the "Preuzmi" tab is not
  // there. Put the same icon into the row under the video (like / dislike / share ...).
  const DL_ICON_BOLD = '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor" style="pointer-events:none;display:block">'
    + '<path d="M11.25 3.5h1.5v10.69l3.72-3.72 1.06 1.06L12 17.06l-5.53-5.53 1.06-1.06 3.72 3.72zM5 19h14v1.5H5z"/></svg>';
  function addWatchDownload() {
    if (!location.pathname.startsWith("/watch")) return;
    const bar = document.querySelector("ytm-slim-video-action-bar-renderer .slim-video-action-bar-actions")
      || document.querySelector("ytm-slim-video-action-bar-renderer");
    if (!bar || bar.querySelector(".marktube-dl-watch")) return;
    const buttons = [...bar.querySelectorAll("button-view-model.slim_video_action_bar_renderer_button")];
    const share = buttons.find((b) => !b.matches(".slim-action-more-button, .you-chat-entrypoint-button"))
      || buttons[0];
    if (!share) return;
    const item = share.cloneNode(true);
    item.classList.add("marktube-dl-watch");
    item.classList.remove("you-chat-entrypoint-button", "slim-action-more-button");
    const icon = item.querySelector("c3-icon, yt-icon, .ytSpecButtonShapeNextIcon");
    if (icon) {
      const holder = document.createElement("span");
      holder.innerHTML = DL_ICON_BOLD;
      icon.replaceChildren(holder.firstChild);
    }
    const btn = item.querySelector("button") || item;
    btn.setAttribute("aria-label", "Preuzmi");
    btn.setAttribute("title", "Preuzmi");
    guardTap(item, () => port.postMessage({ type: "download", url: location.href }));
    share.after(item);
  }
  setInterval(() => { try { addWatchDownload(); } catch (e) { } }, 1000);

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
