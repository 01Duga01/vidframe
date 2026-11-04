/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

/*
 * Player features of the native mobile app, added to the mobile site:
 *  - pinch to zoom the video in fullscreen (snaps to "fill screen" and back to the original size, pans with two
 *    fingers, up to 8x);
 *  - a miniplayer bar that keeps the video playing when leaving the watch page, until it is closed or expanded.
 * Only standard DOM APIs and the documented embed postMessage API are used, so site redesigns break at most the
 * selectors in findMainVideo().
 */
(() => {
  const ZOOM_MAX = 8;
  const SNAP = 0.08;
  const TICK_MS = 500;
  const OWN_PLAYER_GRACE_MS = 900;

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

  function applyZoom() {
    const root = zoom.root;
    if (!root) {
      return;
    }
    const maxX = (root.clientWidth * (zoom.scale - 1)) / 2;
    const maxY = (root.clientHeight * (zoom.scale - 1)) / 2;
    zoom.tx = clamp(zoom.tx, -maxX, maxX);
    zoom.ty = clamp(zoom.ty, -maxY, maxY);
    if (zoom.scale === 1) {
      root.style.removeProperty("--vf-zoom");
      root.classList.remove("vf-zoom-root");
      return;
    }
    root.classList.add("vf-zoom-root");
    root.style.setProperty("--vf-zoom", `translate(${zoom.tx}px, ${zoom.ty}px) scale(${zoom.scale})`);
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

  window.addEventListener(
    "touchstart",
    event => {
      const target = fullscreenVideo();
      if (!target || event.touches.length !== 2) {
        return;
      }
      zoom.root = target.root;
      zoom.gesture = {
        distance: distance(event.touches[0], event.touches[1]),
        midpoint: midpoint(event.touches[0], event.touches[1]),
        scale: zoom.scale,
        tx: zoom.tx,
        ty: zoom.ty,
      };
      swallow(event);
    },
    { capture: true, passive: false }
  );

  window.addEventListener(
    "touchmove",
    event => {
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
    },
    { capture: true, passive: false }
  );

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
    applyZoom();
    swallow(event);
  }
  window.addEventListener("touchend", endGesture, { capture: true, passive: false });
  window.addEventListener("touchcancel", endGesture, { capture: true, passive: false });

  document.addEventListener("fullscreenchange", () => {
    if (!document.fullscreenElement) {
      resetZoom();
    }
  });
  document.addEventListener("loadedmetadata", resetZoom, true);

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

  function closeMini() {
    if (!mini) {
      return;
    }
    mini.bar.remove();
    window.removeEventListener("message", mini.onMessage);
    mini = null;
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
    frame.src = embedUrl(state.id, state.time);
    frame.allow = "autoplay; encrypted-media; picture-in-picture";
    frame.title = state.title;
    const shield = document.createElement("div");
    shield.className = "vf-mini-shield";
    media.append(frame, shield);

    const title = document.createElement("div");
    title.className = "vf-mini-title";
    title.textContent = state.title;

    const playPause = button("Пауза", "pause", () => sendCommand(mini.playing ? "pauseVideo" : "playVideo"));
    const close = button("Закрыть", "close", closeMini);

    bar.append(media, title, playPause, close);
    document.body.appendChild(bar);

    mini = { bar, frame, playPause, id: state.id, time: state.time, playing: true, onMessage: null };

    const expand = () => {
      const { id, time } = mini;
      closeMini();
      openWatchPage(id, time);
    };
    shield.addEventListener("click", expand);
    title.addEventListener("click", expand);

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
        closeMini();
        return;
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
    frame.addEventListener("load", () =>
      frame.contentWindow?.postMessage(JSON.stringify({ event: "listening", id: "vf-mini", channel: "widget" }), "*")
    );
  }

  setInterval(() => {
    const id = watchVideoId();
    if (id) {
      closeMini();
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
