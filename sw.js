const CACHE = "shiftpilot-v4-3";
const SHARE_CACHE = "shiftpilot-share-v1";
const ASSETS = [
  "/",
  "/manifest.json",
  "/config.js",
  "/i18n.js",
  "/icon.svg",
  "/icon-192.png",
  "/icon-512.png"
];

self.addEventListener("install", event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(ASSETS)));
  self.skipWaiting();
});

self.addEventListener("activate", event => {
  event.waitUntil(
    caches.keys().then(keys =>
      Promise.all(
        keys
          .filter(key => key !== CACHE && key !== SHARE_CACHE)
          .map(key => caches.delete(key))
      )
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", event => {
  const url = new URL(event.request.url);

  // Android Web Share Target:
  // screenshot -> Share -> ShiftPilot.
  // The image stays in local Cache Storage and is never posted to the backend.
  if (
    event.request.method === "POST" &&
    url.origin === self.location.origin &&
    url.pathname === "/share-target"
  ) {
    event.respondWith((async () => {
      try {
        const form = await event.request.formData();
        let file = form.get("image");

        // Some Android share sheets/providers may use another form field.
        if (!(file instanceof File) || !file.size) {
          for (const value of form.values()) {
            if (value instanceof File && value.size && String(value.type || "").startsWith("image/")) {
              file = value;
              break;
            }
          }
        }

        if (file instanceof File && file.size) {
          const cache = await caches.open(SHARE_CACHE);
          const headers = new Headers({
            "Content-Type": file.type || "image/png",
            "X-ShiftPilot-Filename": encodeURIComponent(file.name || "wolt-order.png")
          });
          await cache.put(
            new Request(self.location.origin + "/__shared-order-image"),
            new Response(file, { status: 200, headers })
          );
        }

        return Response.redirect(self.location.origin + "/?shared-order=1", 303);
      } catch (err) {
        return Response.redirect(self.location.origin + "/?shared-order-error=1", 303);
      }
    })());
    return;
  }

  if (event.request.method !== "GET") return;
  if (url.origin !== self.location.origin || url.pathname.startsWith("/api/")) return;

  event.respondWith(
    fetch(event.request)
      .then(response => {
        if (response && response.ok) {
          const copy = response.clone();
          caches.open(CACHE).then(cache => cache.put(event.request, copy));
        }
        return response;
      })
      .catch(() => caches.match(event.request).then(hit => hit || caches.match("/")))
  );
});
