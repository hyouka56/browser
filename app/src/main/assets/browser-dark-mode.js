(() => {
  "use strict";

  const INSTALL_KEY = "__searchBrowserDarkModeV1";
  if (window[INSTALL_KEY]) return;

  const BG_ATTRIBUTE = "data-search-browser-dark-bg";
  const TEXT_ATTRIBUTE = "data-search-browser-dark-text";
  const EXCLUDED_TAGS = new Set([
    "IMG", "PICTURE", "VIDEO", "AUDIO", "CANVAS", "IFRAME",
    "OBJECT", "EMBED", "SOURCE"
  ]);

  try {
    const sheet = new CSSStyleSheet();
    sheet.replaceSync(`
      :root { color-scheme: dark !important; }
      html, body { background-color: #0b0b0b !important; }
      body { color: #e8e8e8 !important; }
      [${BG_ATTRIBUTE}="1"] { background-color: #181818 !important; }
      [${TEXT_ATTRIBUTE}="1"] {
        color: var(--search-browser-dark-text, #e8e8e8) !important;
      }
      input, textarea, select, button { color-scheme: dark !important; }
      input::placeholder, textarea::placeholder { color: #a6a6a6 !important; }
    `);
    document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
    window[INSTALL_KEY] = true;
    if (document.documentElement) {
      document.documentElement.setAttribute("data-search-browser-dark-mode", "enabled");
    }

    const parseColor = (value) => {
      const match = /^rgba?\((.+)\)$/i.exec(value || "");
      if (!match) return null;
      const parts = match[1].trim().replace(/\s*\/\s*/g, " ")
        .split(/[\s,]+/).filter(Boolean);
      if (parts.length < 3) return null;
      const rgb = parts.slice(0, 3).map((part) => {
        const number = Number.parseFloat(part);
        return part.endsWith("%") ? number * 2.55 : number;
      });
      const alphaPart = parts[3];
      const alpha = alphaPart === undefined ? 1 :
        (alphaPart.endsWith("%") ? Number.parseFloat(alphaPart) / 100 : Number.parseFloat(alphaPart));
      if (rgb.some((channel) => !Number.isFinite(channel)) || !Number.isFinite(alpha)) return null;
      return {
        rgb: rgb.map((channel) => Math.max(0, Math.min(255, Math.round(channel)))),
        alpha: Math.max(0, Math.min(1, alpha))
      };
    };

    const luminance = ([red, green, blue]) => {
      const linear = (channel) => {
        const value = channel / 255;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
      };
      return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue);
    };

    const readableColor = (rgb) =>
      `rgb(${rgb.map((channel) => Math.round(channel + (255 - channel) * 0.68)).join(", ")})`;

    const effectiveBackgroundLuminance = (element) => {
      for (let current = element; current && current.nodeType === Node.ELEMENT_NODE;
           current = current.parentElement) {
        if (current.hasAttribute(BG_ATTRIBUTE)) return luminance([24, 24, 24]);
        const style = getComputedStyle(current);
        // Preserve photographs and gradients rather than guessing their colours.
        if (style.backgroundImage && style.backgroundImage !== "none") return null;
        const background = parseColor(style.backgroundColor);
        if (background && background.alpha >= 0.88) return luminance(background.rgb);
      }
      return luminance([11, 11, 11]);
    };

    const hasDirectText = (element) =>
      Array.from(element.childNodes).some((node) =>
        node.nodeType === Node.TEXT_NODE && Boolean((node.nodeValue || "").trim())
      );

    const isTextControl = (element) => {
      if (element.tagName === "TEXTAREA" || element.tagName === "SELECT") return true;
      if (element.tagName !== "INPUT") return false;
      return !new Set(["checkbox", "radio", "file", "image", "color", "range", "hidden"]).has(
        (element.type || "text").toLowerCase()
      );
    };

    const normalizeElement = (element) => {
      if (!element || EXCLUDED_TAGS.has(element.tagName) ||
          element.namespaceURI === "http://www.w3.org/2000/svg") return;
      const style = getComputedStyle(element);
      if (style.display === "none" || style.visibility === "hidden") return;

      if (!element.hasAttribute(BG_ATTRIBUTE) && style.backgroundImage === "none") {
        const background = parseColor(style.backgroundColor);
        if (background && background.alpha >= 0.88 && luminance(background.rgb) >= 0.74) {
          element.setAttribute(BG_ATTRIBUTE, "1");
        }
      }

      if (element.hasAttribute(TEXT_ATTRIBUTE) ||
          (!hasDirectText(element) && !isTextControl(element))) return;
      const foreground = parseColor(getComputedStyle(element).color);
      const backgroundLuminance = effectiveBackgroundLuminance(element);
      if (foreground && backgroundLuminance !== null &&
          backgroundLuminance < 0.45 && luminance(foreground.rgb) < 0.45) {
        element.setAttribute(TEXT_ATTRIBUTE, "1");
        element.style.setProperty("--search-browser-dark-text", readableColor(foreground.rgb));
      }
    };

    const runWhenIdle = (callback) => {
      if (typeof window.requestIdleCallback === "function") {
        window.requestIdleCallback(callback, { timeout: 200 });
      } else {
        window.setTimeout(callback, 0);
      }
    };

    const processSubtree = (root) => {
      if (!root || root.nodeType !== Node.ELEMENT_NODE) return;
      normalizeElement(root);
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
      const processBatch = () => {
        let node = null;
        let count = 0;
        while (count < 200 && (node = walker.nextNode())) {
          normalizeElement(node);
          count += 1;
        }
        if (node) runWhenIdle(processBatch);
      };
      processBatch();
    };

    const pendingRoots = new Set();
    let flushScheduled = false;
    const enqueue = (root) => {
      if (!root || root.nodeType !== Node.ELEMENT_NODE) return;
      pendingRoots.add(root);
      if (flushScheduled) return;
      flushScheduled = true;
      runWhenIdle(() => {
        flushScheduled = false;
        const roots = Array.from(pendingRoots);
        pendingRoots.clear();
        roots.forEach(processSubtree);
        if (pendingRoots.size) enqueue(pendingRoots.values().next().value);
      });
    };

    const installObserver = () => {
      const root = document.documentElement;
      if (!root) return;
      const observer = new MutationObserver((mutations) => {
        mutations.forEach((mutation) => {
          if (mutation.type === "childList") {
            mutation.addedNodes.forEach((node) => {
              if (node.nodeType === Node.ELEMENT_NODE) enqueue(node);
              else if (node.parentElement) enqueue(node.parentElement);
            });
          } else if (mutation.type === "characterData") {
            if (mutation.target.parentElement) enqueue(mutation.target.parentElement);
          } else {
            enqueue(mutation.target);
          }
        });
      });
      observer.observe(root, {
        childList: true,
        subtree: true,
        characterData: true,
        attributes: true,
        attributeFilter: ["class", "style"]
      });
      enqueue(root);
    };

    if (document.documentElement) installObserver();
    else document.addEventListener("DOMContentLoaded", installObserver, { once: true });
  } catch (error) {
    console.warn("[SearchBrowser] Could not initialize adaptive dark mode", error);
  }
})();
