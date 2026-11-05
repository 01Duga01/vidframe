/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

/*
 * Player features of the native mobile app, added to the mobile site:
 *  - pinch to zoom the video in fullscreen (snaps to "fill screen" and back to the original size, pans with two
 *    fingers, up to 8x);
 *  - a miniplayer bar that keeps the video playing when leaving the watch page, until it is closed or expanded;
 *    swipe it sideways to dismiss, up to expand;
 *  - "Download" opens a download service for the video in another browser (see VideoDownloader.kt);
 *  - site motion close to the native app: thumbnails fade in, like/dislike/subscribe pop (with m-youtube.css);
 *  - larger Shorts action buttons with generous touch areas;
 *  - on regular videos, the site's press-and-hold 2x speed only after holding for 3 seconds;
 *  - the "Ask a question" (Gemini) field does not grab focus, and so does not open the keyboard, until it is tapped.
 * Only standard DOM APIs and the documented embed postMessage API are used, so site redesigns break at most the
 * selectors in findMainVideo().
 */
(() => {
  const ZOOM_MAX = 8;
  const SNAP = 0.08;
  const TICK_MS = 500;
  const OWN_PLAYER_GRACE_MS = 900;
  const LISTEN_RETRY_MS = 250;
  const LISTEN_MAX_TRIES = 40;
  const ERROR_AUTOCLOSE_MS = 8000;
  const SNAP_ANIMATION_MS = 280;
  const MINI_OUT_MS = 220;
  const SWIPE_DISMISS_FRACTION = 0.35;
  const SWIPE_FLING_PX_PER_MS = 0.6;
  const SWIPE_FLING_MIN_PX = 48;
  const SWIPE_EXPAND_PX = 56;
  const reducedMotion = () => matchMedia("(prefers-reduced-motion: reduce)").matches;
  const afterAnimation = (ms, fn) => setTimeout(fn, reducedMotion() ? 0 : ms);
  const log = (...args) => console.info("[vidframe]", ...args);

  const toast = (() => {
    let el;
    let timer;
    return text => {
      if (!el) {
        el = document.createElement("div");
        el.className = "vf-toast";
      }
      (document.fullscreenElement || document.body).appendChild(el);
      el.textContent = text;
      el.classList.remove("vf-toast-visible");
      void el.offsetWidth; // restart the pop-in animation for consecutive toasts
      el.classList.add("vf-toast-visible");
      clearTimeout(timer);
      timer = setTimeout(() => el.classList.remove("vf-toast-visible"), 1200);
    };
  })();

  // ---------------------------------------------------------------- pinch zoom

  const zoom = { scale: 1, tx: 0, ty: 0, gesture: null, root: null };

  const distance = (a, b) => Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
  const midpoint = (a, b) => ({ x: (a.clientX + b.clientX) / 2, y: (a.clientY + b.clientY) / 2 });
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  function fullscreenVideo() {
    const root = document.fullscreenElement;
    if (!root) {
      return null;
    }
    const video = root.matches("video") ? root : root.querySelector("video");
    return video && video.videoWidth ? { root, video } : null;
  }

  /** Scale at which the letterboxed video covers the whole screen. */
  function fillScale(root, video) {
    const w = root.clientWidth;
    const h = root.clientHeight;
    const contain = Math.min(w / video.videoWidth, h / video.videoHeight);
    const cover = Math.max(w / video.videoWidth, h / video.videoHeight);
    return contain > 0 ? cover / contain : 1;
  }

  function applyZoom({ animate = false } = {}) {
    const root = zoom.root;
    if (!root) {
      return;
    }
    const maxX = (root.clientWidth * (zoom.scale - 1)) / 2;
    const maxY = (root.clientHeight * (zoom.scale - 1)) / 2;
    zoom.tx = clamp(zoom.tx, -maxX, maxX);
    zoom.ty = clamp(zoom.ty, -maxY, maxY);
    if (zoom.scale === 1 && !animate) {
      root.style.removeProperty("--vf-zoom");
      root.classList.remove("vf-zoom-root", "vf-zoom-animate");
      return;
    }
    root.classList.add("vf-zoom-root");
    // Live pinching follows the fingers 1:1; only the settle after release is animated.
    root.classList.toggle("vf-zoom-animate", animate);
    root.style.setProperty("--vf-zoom", `translate(${zoom.tx}px, ${zoom.ty}px) scale(${zoom.scale})`);
    if (animate) {
      clearTimeout(zoom.settleTimer);
      zoom.settleTimer = afterAnimation(SNAP_ANIMATION_MS, () => {
        root.classList.remove("vf-zoom-animate");
        if (zoom.scale === 1) {
          applyZoom();
        }
      });
    }
  }

  function resetZoom() {
    zoom.scale = 1;
    zoom.tx = 0;
    zoom.ty = 0;
    zoom.gesture = null;
    if (zoom.root) {
      zoom.root.style.removeProperty("--vf-zoom");
      zoom.root.classList.remove("vf-zoom-root");
    }
    zoom.root = null;
  }

  function swallow(event) {
    event.preventDefault();
    event.stopImmediatePropagation();
  }

  function onTouchStart(event) {
    const target = fullscreenVideo();
    if (!target || event.touches.length !== 2) {
      return;
    }
    zoom.root = target.root;
    zoom.root.classList.remove("vf-zoom-animate");
    zoom.gesture = {
      distance: distance(event.touches[0], event.touches[1]),
      midpoint: midpoint(event.touches[0], event.touches[1]),
      scale: zoom.scale,
      tx: zoom.tx,
      ty: zoom.ty,
    };
    swallow(event);
  }

  function onTouchMove(event) {
    const g = zoom.gesture;
    if (!g || event.touches.length !== 2) {
      return;
    }
    const m = midpoint(event.touches[0], event.touches[1]);
    zoom.scale = clamp((g.scale * distance(event.touches[0], event.touches[1])) / g.distance, 1, ZOOM_MAX);
    zoom.tx = g.tx + (m.x - g.midpoint.x);
    zoom.ty = g.ty + (m.y - g.midpoint.y);
    applyZoom();
    swallow(event);
  }

  function endGesture(event) {
    if (!zoom.gesture || event.touches.length >= 2) {
      return;
    }
    zoom.gesture = null;
    const target = fullscreenVideo();
    const fill = target ? fillScale(target.root, target.video) : 1;
    if (zoom.scale < 1 + SNAP) {
      zoom.scale = 1;
      zoom.tx = 0;
      zoom.ty = 0;
      toast("Исходный размер");
    } else if (fill > 1 + SNAP && Math.abs(zoom.scale - fill) / fill < SNAP) {
      zoom.scale = fill;
      zoom.tx = 0;
      zoom.ty = 0;
      toast("Растянуто по размеру экрана");
    } else {
      toast(`${zoom.scale.toFixed(1)}×`);
    }
    applyZoom({ animate: true });
    swallow(event);
  }

  /*
   * Non-passive touch listeners make the engine wait for script on every touch before it may scroll, which shows as
   * jank in the Shorts feed and in normal scrolling. They are therefore only attached while a video is fullscreen.
   */
  const TOUCH_OPTIONS = { capture: true, passive: false };
  let touchListenersAttached = false;
  function setTouchListeners(attach) {
    if (attach === touchListenersAttached) {
      return;
    }
    touchListenersAttached = attach;
    const method = attach ? "addEventListener" : "removeEventListener";
    window[method]("touchstart", onTouchStart, TOUCH_OPTIONS);
    window[method]("touchmove", onTouchMove, TOUCH_OPTIONS);
    window[method]("touchend", endGesture, TOUCH_OPTIONS);
    window[method]("touchcancel", endGesture, TOUCH_OPTIONS);
  }

  document.addEventListener("fullscreenchange", () => {
    setTouchListeners(!!document.fullscreenElement);
    if (!document.fullscreenElement) {
      resetZoom();
    }
  });
  document.addEventListener(
    "loadedmetadata",
    () => {
      if (zoom.root) {
        resetZoom();
      }
    },
    true
  );

  // ---------------------------------------------------------------- miniplayer

  /** Last known state of the watch page, captured while it is open. */
  let watched = null;
  let wasOnWatchPage = false;
  let mini = null;

  const watchVideoId = () =>
    location.pathname === "/watch" ? new URLSearchParams(location.search).get("v") : null;

  function findMainVideo() {
    return document.querySelector("#player-container-id video, .html5-video-player video, video.video-stream");
  }

  const cleanTitle = () => document.title.replace(/\s*-\s*YouTube\s*$/i, "").trim();

  /** True when the site itself keeps audible playback going (for example a miniplayer of its own). */
  function siteKeepsPlaying() {
    return [...document.querySelectorAll("video")].some(v => !v.paused && !v.muted && !v.closest("#vf-mini"));
  }

  function embedUrl(id, start) {
    const params = new URLSearchParams({
      autoplay: "1",
      playsinline: "1",
      enablejsapi: "1",
      rel: "0",
      start: String(Math.max(0, Math.floor(start))),
      origin: location.origin,
      widget_referrer: location.href,
    });
    return `https://www.youtube.com/embed/${encodeURIComponent(id)}?${params}`;
  }

  const ICONS = {
    pause: "M6 5h4v14H6zM14 5h4v14h-4z",
    play: "M8 5v14l11-7z",
    close: "M18.3 5.71 12 12l6.3 6.29-1.41 1.42L10.59 13.4 4.3 19.71 2.89 18.3 9.17 12 2.89 5.71 4.3 4.29l6.29 6.3 6.3-6.3z",
  };

  function setIcon(b, name) {
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    const path = document.createElementNS(ns, "path");
    path.setAttribute("d", ICONS[name]);
    svg.appendChild(path);
    if (b.firstChild && b.dataset.icon !== name && !reducedMotion()) {
      svg.classList.add("vf-icon-in");
    }
    b.dataset.icon = name;
    b.replaceChildren(svg);
  }

  function button(label, icon, onClick) {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "vf-mini-btn";
    b.setAttribute("aria-label", label);
    setIcon(b, icon);
    b.addEventListener("click", event => {
      event.stopPropagation();
      onClick();
    });
    return b;
  }

  function sendCommand(func) {
    mini?.frame.contentWindow?.postMessage(JSON.stringify({ event: "command", func, args: [] }), "*");
  }

  function openWatchPage(id, time) {
    const link = document.createElement("a");
    link.href = `/watch?v=${encodeURIComponent(id)}&t=${Math.max(0, Math.floor(time))}s`;
    link.hidden = true;
    document.body.appendChild(link);
    link.click(); // lets the site's own router take over when it handles link clicks
    link.remove();
  }

  /**
   * Removes the miniplayer. [exit] picks the leaving animation: "down" (close button, timeout), "left"/"right" (swipe
   * dismiss) or "up" (expand to the watch page); without it the bar disappears at once.
   */
  function closeMini(reason, exit) {
    if (!mini) {
      return;
    }
    log("miniplayer closed:", reason);
    const { bar } = mini;
    clearInterval(mini.listenTimer);
    clearTimeout(mini.errorTimer);
    window.removeEventListener("message", mini.onMessage);
    mini = null;
    if (!exit || reducedMotion()) {
      bar.remove();
      return;
    }
    bar.style.transition = "";
    bar.style.transform = "";
    bar.style.opacity = "";
    bar.classList.add(`vf-mini-out-${exit}`);
    sendCommandTo(bar.querySelector("iframe"), exit === "up" ? null : "pauseVideo");
    afterAnimation(MINI_OUT_MS, () => bar.remove());
  }

  function sendCommandTo(frame, func) {
    if (frame && func) {
      frame.contentWindow?.postMessage(JSON.stringify({ event: "command", func, args: [] }), "*");
    }
  }

  /** Swipe sideways to dismiss (follows the finger, flings), swipe up to expand; small moves spring back. */
  function attachSwipe(bar, expand) {
    let start = null;
    bar.addEventListener(
      "touchstart",
      event => {
        if (event.touches.length !== 1) {
          start = null;
          return;
        }
        const t = event.touches[0];
        start = { x: t.clientX, y: t.clientY, time: performance.now(), axis: null };
        bar.style.transition = "none";
      },
      { passive: true }
    );
    bar.addEventListener(
      "touchmove",
      event => {
        if (!start || !mini || mini.bar !== bar) {
          return;
        }
        const t = event.touches[0];
        const dx = t.clientX - start.x;
        const dy = t.clientY - start.y;
        start.axis ||= Math.abs(dx) > 8 ? "x" : Math.abs(dy) > 8 ? "y" : null;
        if (start.axis === "x") {
          bar.style.transform = `translateX(${dx}px)`;
          bar.style.opacity = String(1 - Math.min(1, Math.abs(dx) / bar.clientWidth) * 0.8);
          event.preventDefault();
        } else if (start.axis === "y" && dy < 0) {
          bar.style.transform = `translateY(${Math.max(dy, -SWIPE_EXPAND_PX * 1.5) * 0.5}px)`;
          event.preventDefault();
        }
      },
      { passive: false }
    );
    bar.addEventListener("touchend", event => {
      if (!start || !mini || mini.bar !== bar) {
        start = null;
        return;
      }
      const t = event.changedTouches[0];
      const dx = t.clientX - start.x;
      const dy = t.clientY - start.y;
      const speed = Math.abs(dx) / Math.max(1, performance.now() - start.time);
      const axis = start.axis;
      start = null;
      bar.style.transition = "";
      const far = Math.abs(dx) > bar.clientWidth * SWIPE_DISMISS_FRACTION;
      const fling = Math.abs(dx) > SWIPE_FLING_MIN_PX && speed > SWIPE_FLING_PX_PER_MS;
      if (axis === "x" && (far || fling)) {
        closeMini("swipe", dx > 0 ? "right" : "left");
      } else if (axis === "y" && dy < -SWIPE_EXPAND_PX) {
        expand();
      } else {
        bar.style.transform = "";
        bar.style.opacity = "";
      }
    });
  }

  /**
   * The embedded player refused this video (codes 101/150: the owner disabled embedding; 153: the request arrived
   * without a referrer; 100: removed or private). Keep the bar, say why, and offer the full watch page instead of
   * vanishing.
   */
  function showMiniError(code) {
    log("miniplayer embed error", code);
    const current = mini;
    current.failed = true;
    current.frame.remove();
    current.media.classList.add("vf-mini-media-error");
    current.title.textContent =
      code === 101 || code === 150
        ? "Автор запретил мини-плеер для этого видео. Нажмите, чтобы открыть."
        : `Мини-плеер недоступен (ошибка ${code}). Нажмите, чтобы открыть видео.`;
    current.playPause.hidden = true;
    current.bar.classList.add("vf-mini-error");
    current.errorTimer = setTimeout(() => {
      if (mini === current) {
        closeMini("error timeout", "down");
      }
    }, ERROR_AUTOCLOSE_MS);
  }

  function showMini(state) {
    closeMini();
    const pivotBar = document.querySelector("ytm-pivot-bar-renderer");
    const bottom = pivotBar ? pivotBar.getBoundingClientRect().height : 0;

    const bar = document.createElement("div");
    bar.id = "vf-mini";
    bar.style.bottom = `${bottom}px`;

    const media = document.createElement("div");
    media.className = "vf-mini-media";
    const frame = document.createElement("iframe");
    // The embedded player needs the embedding origin as referrer (otherwise error 153).
    frame.referrerPolicy = "strict-origin-when-cross-origin";
    frame.allow = "autoplay; encrypted-media; picture-in-picture";
    frame.src = embedUrl(state.id, state.time);
    frame.title = state.title;
    const shield = document.createElement("div");
    shield.className = "vf-mini-shield";
    media.append(frame, shield);

    const title = document.createElement("div");
    title.className = "vf-mini-title";
    title.textContent = state.title;

    const playPause = button("Пауза", "pause", () => sendCommand(mini.playing ? "pauseVideo" : "playVideo"));
    const close = button("Закрыть", "close", () => closeMini("close button", "down"));

    bar.append(media, title, playPause, close);
    document.body.appendChild(bar);

    mini = {
      bar,
      media,
      frame,
      title,
      playPause,
      id: state.id,
      time: state.time,
      playing: true,
      failed: false,
      connected: false,
      onMessage: null,
      listenTimer: 0,
      errorTimer: 0,
    };
    log("miniplayer shown for", state.id, "at", Math.floor(state.time));

    const expand = () => {
      if (!mini) {
        return;
      }
      const { id, time } = mini;
      closeMini("expand", "up");
      afterAnimation(MINI_OUT_MS / 2, () => openWatchPage(id, time));
    };
    shield.addEventListener("click", expand);
    title.addEventListener("click", expand);
    attachSwipe(bar, expand);

    mini.onMessage = event => {
      if (!mini || event.source !== mini.frame.contentWindow || typeof event.data !== "string") {
        return;
      }
      let data;
      try {
        data = JSON.parse(event.data);
      } catch (e) {
        return;
      }
      if (data.event === "onError") {
        showMiniError(Number(data.info));
        return;
      }
      if (!mini.connected) {
        mini.connected = true;
        clearInterval(mini.listenTimer);
      }
      const info = data.info;
      if (data.event !== "infoDelivery" || !info) {
        return;
      }
      if (typeof info.currentTime === "number") {
        mini.time = info.currentTime;
      }
      if (typeof info.playerState === "number") {
        mini.playing = info.playerState === 1 || info.playerState === 3;
        setIcon(mini.playPause, mini.playing ? "pause" : "play");
        mini.playPause.setAttribute("aria-label", mini.playing ? "Пауза" : "Воспроизвести");
      }
    };
    window.addEventListener("message", mini.onMessage);

    // The embed only reports state after a "listening" handshake, and only once its own script runs, which can be
    // after the frame's load event. Repeat the handshake until the first message arrives.
    const current = mini;
    let tries = 0;
    const listen = () => {
      if (mini !== current || current.connected || current.failed || ++tries > LISTEN_MAX_TRIES) {
        clearInterval(current.listenTimer);
        return;
      }
      current.frame.contentWindow?.postMessage(
        JSON.stringify({ event: "listening", id: "vf-mini", channel: "widget" }),
        "*"
      );
    };
    frame.addEventListener("load", listen);
    current.listenTimer = setInterval(listen, LISTEN_RETRY_MS);
  }

  // ---------------------------------------------------------------- site motion

  const FADE_IMAGES_IN =
    "ytm-rich-item-renderer, ytm-video-with-context-renderer, ytm-compact-video-renderer, ytm-reel-item-renderer, " +
    "ytm-shorts-lockup-view-model, ytm-channel-list-sub-menu-avatar-renderer, .media-item-thumbnail-container";

  /** Images that arrive after the page has rendered fade in; cached ones (already complete) are left alone. */
  document.addEventListener(
    "load",
    event => {
      const img = event.target;
      if (img instanceof HTMLImageElement && !reducedMotion() && img.closest(FADE_IMAGES_IN)) {
        img.classList.remove("vf-img-in");
        void img.offsetWidth;
        img.classList.add("vf-img-in");
      }
    },
    true
  );

  const POP_TARGETS =
    "like-button-view-model, dislike-button-view-model, ytm-subscribe-button-renderer, subscribe-button-view-model";
  const POP_LABEL = /^(нравится|не нравится|like|dislike|i like|i dislike|подписаться|subscribe|вы подписаны|subscribed)/i;

  function pop(el, cls) {
    el.classList.remove("vf-pop", "vf-pop-like");
    void el.offsetWidth; // restart when tapped repeatedly
    el.classList.add(cls);
    el.addEventListener("animationend", () => el.classList.remove(cls), { once: true });
  }

  /** Like, dislike and subscribe pop when tapped, the like icon with a little tilt like in the app. */
  document.addEventListener(
    "click",
    event => {
      if (reducedMotion() || !(event.target instanceof Element)) {
        return;
      }
      const control = event.target.closest("button, [role='button']");
      if (!control || control.closest("#vf-mini")) {
        return;
      }
      const host = control.closest(POP_TARGETS);
      const label = (control.getAttribute("aria-label") || control.textContent || "").trim();
      if (!host && !POP_LABEL.test(label)) {
        return;
      }
      const isLike = (host && host.matches("like-button-view-model")) || /^(нравится|like|i like)/i.test(label);
      const icon = control.querySelector("svg, yt-icon, c3-icon, .yt-spec-button-shape-next__icon") || control;
      pop(icon, isLike ? "vf-pop-like" : "vf-pop");
    },
    true
  );

  // ---------------------------------------------------------------- Shorts buttons

  const SHORTS_ACTION_EDGE_PX = 96;

  /**
   * Tags the Shorts action column (like, dislike, comments, share, ...) for m-youtube.css: buttons near the right edge,
   * below the top bar. Each button is measured once; ones not laid out yet (reels further down) are retried later.
   */
  function tagShortsButtons() {
    const width = innerWidth;
    const height = innerHeight;
    for (const button of document.querySelectorAll("button:not([data-vf-seen])")) {
      const rect = button.getBoundingClientRect();
      if (!rect.width || !rect.height) {
        continue;
      }
      button.dataset.vfSeen = "1";
      const inActionColumn =
        rect.right > width - SHORTS_ACTION_EDGE_PX && rect.width < SHORTS_ACTION_EDGE_PX && rect.top > height * 0.2;
      if (inActionColumn && !button.closest("#vf-mini")) {
        button.classList.add("vf-shorts-btn");
        if (getComputedStyle(button).position === "static") {
          button.classList.add("vf-shorts-btn-anchor");
        }
      }
    }
  }

  // ---------------------------------------------------------------- hold for 2x

  const HOLD_FAST_MS = 3000;
  const HOLD_MOVE_PX = 12;
  const hold = { active: false, ours: false, timer: 0, x: 0, y: 0, video: null, rate: 1 };

  function playerVideo(target) {
    if (location.pathname !== "/watch" || !(target instanceof Element)) {
      return null;
    }
    const player = target.closest("#player-container-id, .html5-video-player");
    return player ? player.querySelector("video") : null;
  }

  function endHold() {
    clearTimeout(hold.timer);
    if (hold.ours && hold.video) {
      hold.video.playbackRate = hold.rate;
    }
    hold.active = false;
    hold.ours = false;
    hold.video = null;
  }

  /*
   * The site speeds a video up to 2x shortly after a press-and-hold. On regular videos that now needs a 3 second hold:
   * until then any speed change made during the hold is undone, after it 2x is applied here; letting go restores the
   * previous speed. Passive listeners only, so scrolling is never delayed. Shorts keep the site's behaviour.
   */
  window.addEventListener(
    "touchstart",
    event => {
      if (event.touches.length !== 1) {
        endHold();
        return;
      }
      const video = playerVideo(event.target);
      if (!video) {
        return;
      }
      endHold();
      Object.assign(hold, {
        active: true,
        video,
        rate: video.playbackRate,
        x: event.touches[0].clientX,
        y: event.touches[0].clientY,
      });
      hold.timer = setTimeout(() => {
        if (!hold.active || hold.video !== video || video.paused) {
          return;
        }
        hold.ours = true;
        video.playbackRate = 2;
        navigator.vibrate?.(12);
        toast("2×");
      }, HOLD_FAST_MS);
    },
    { capture: true, passive: true }
  );
  window.addEventListener(
    "touchmove",
    event => {
      const t = event.touches[0];
      if (hold.active && t && Math.hypot(t.clientX - hold.x, t.clientY - hold.y) > HOLD_MOVE_PX) {
        endHold();
      }
    },
    { capture: true, passive: true }
  );
  window.addEventListener("touchend", endHold, { capture: true, passive: true });
  window.addEventListener("touchcancel", endHold, { capture: true, passive: true });
  document.addEventListener("visibilitychange", endHold);
  document.addEventListener(
    "ratechange",
    event => {
      const video = event.target;
      if (hold.active && !hold.ours && video === hold.video && video.playbackRate !== hold.rate) {
        video.playbackRate = hold.rate;
      }
    },
    true
  );

  // ---------------------------------------------------------------- Ask (Gemini) field focus

  const ASK_FIELD = /^(ask a question|ask about|ask anything|задайте вопрос|задать вопрос|спросите|спросить)/i;
  const TAP_FOCUS_WINDOW_MS = 1000;
  let lastPointer = { target: null, time: 0 };

  window.addEventListener(
    "pointerdown",
    event => {
      lastPointer = { target: event.target, time: performance.now() };
    },
    { capture: true, passive: true }
  );

  function fieldLabel(el) {
    return (
      el.getAttribute("placeholder") ||
      el.getAttribute("aria-placeholder") ||
      el.getAttribute("data-placeholder") ||
      el.getAttribute("aria-label") ||
      ""
    ).trim();
  }

  const isTextField = el =>
    el instanceof HTMLTextAreaElement ||
    (el instanceof HTMLInputElement && !["button", "checkbox", "radio", "submit"].includes(el.type)) ||
    (el instanceof HTMLElement && el.isContentEditable);

  /** True if [node] is [field]'s box: one of its three closest ancestors. */
  function isNearAncestor(node, field) {
    let el = field.parentElement;
    for (let i = 0; el && i < 3; i++, el = el.parentElement) {
      if (el === node) {
        return true;
      }
    }
    return false;
  }

  /**
   * Opening the Ask panel focuses its question field, which pops up the keyboard over the answer. Focus that does not
   * come from a tap on the field itself (or on the box around it) is taken back; tapping the field works as usual.
   */
  document.addEventListener(
    "focusin",
    event => {
      const field = event.target;
      if (!(field instanceof Element) || !isTextField(field) || !ASK_FIELD.test(fieldLabel(field))) {
        return;
      }
      const tapped = lastPointer.target;
      const recent = performance.now() - lastPointer.time < TAP_FOCUS_WINDOW_MS;
      const tappedField = recent && tapped instanceof Node && (field.contains(tapped) || isNearAncestor(tapped, field));
      if (!tappedField) {
        log("kept the keyboard closed: automatic focus of", fieldLabel(field));
        field.blur();
      }
    },
    true
  );

  // ---------------------------------------------------------------- download

  const DOWNLOAD_LABEL = /^(скачать|download)( видео| video)?$/i;
  const isVideoPage = () => location.pathname === "/watch" || location.pathname.startsWith("/shorts/");
  const labelOf = el => (el.getAttribute("aria-label") || el.textContent || "").replace(/\s+/g, " ").trim();

  /** Click-only (not touch) listener, so it never delays scrolling. */
  document.addEventListener(
    "click",
    event => {
      if (!isVideoPage() || !(event.target instanceof Element)) {
        return;
      }
      const control = event.target.closest(
        "ytm-download-button-renderer, button, a, [role='button'], [role='menuitem'], ytm-menu-service-item-renderer"
      );
      if (!control || control.closest("#vf-mini")) {
        return;
      }
      const isDownload = control.matches("ytm-download-button-renderer") || DOWNLOAD_LABEL.test(labelOf(control));
      if (!isDownload) {
        return;
      }
      event.preventDefault();
      event.stopImmediatePropagation();
      log("download via external service for", location.href);
      control.classList.add("vf-pressed");
      setTimeout(() => control.classList.remove("vf-pressed"), 300);
      location.assign(`vidframe-download://open?u=${encodeURIComponent(location.href)}`);
    },
    true
  );

  setInterval(() => {
    if (location.pathname.startsWith("/shorts/")) {
      tagShortsButtons();
    }
    const id = watchVideoId();
    if (id) {
      if (mini) {
        closeMini("watch page opened");
      }
      const video = findMainVideo();
      if (video && video.duration) {
        watched = { id, time: video.currentTime, playing: !video.paused && !video.ended, title: cleanTitle() };
      }
      wasOnWatchPage = true;
      return;
    }
    if (wasOnWatchPage) {
      wasOnWatchPage = false;
      const state = watched;
      watched = null;
      if (state && state.playing) {
        setTimeout(() => {
          if (!watchVideoId() && !siteKeepsPlaying()) {
            showMini(state);
          }
        }, OWN_PLAYER_GRACE_MS);
      }
    }
  }, TICK_MS);
})();
