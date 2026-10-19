// Yumenode AI — anonymous benchmark collector (Cloudflare Worker)
//
// POST /api/report  { model, backend, device, soc, version, tps_avg, tps_min,
//                     tps_max, latency_min, latency_max, tokens }
// GET  /            aggregated dashboard (HTML)
// GET  /api/stats   raw aggregate (JSON)
//
// Storage: Workers KV (binding BENCH_KV). No IPs, no identifiers are stored.

const MAX_STR = 120;

function sanitize(v, max = MAX_STR) {
  if (typeof v !== "string") return "";
  // strip control chars and cap length — keep it safe for the dashboard
  return v.replace(/[\u0000-\u001f\u007f]/g, "").slice(0, max).trim();
}

function num(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function esc(s) {
  return String(s ?? "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", ...CORS },
  });
}

async function loadAgg(env) {
  const raw = await env.BENCH_KV.get("agg", "json");
  return raw || { count: 0, models: {}, devices: {}, recent: [], updated: 0 };
}

export default {
  async fetch(request, env) {
    const { pathname } = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS });
    }

    if (pathname === "/api/report" && request.method === "POST") {
      let body;
      try {
        body = await request.json();
      } catch {
        return json({ error: "invalid json" }, 400);
      }

      const report = {
        model: sanitize(body.model),
        backend: sanitize(body.backend),
        device: sanitize(body.device),
        soc: sanitize(body.soc, 40),
        version: sanitize(body.version, 40),
        tps_avg: num(body.tps_avg),
        tps_min: num(body.tps_min),
        tps_max: num(body.tps_max),
        latency_min: num(body.latency_min),
        latency_max: num(body.latency_max),
        tokens: num(body.tokens),
        at: Date.now(),
      };
      if (!report.model || report.tps_avg == null) {
        return json({ error: "model and tps_avg are required" }, 400);
      }

      const agg = await loadAgg(env);
      agg.count = (agg.count || 0) + 1;

      const m = (agg.models[report.model] ||= { n: 0, tps_sum: 0, tps_min: null, tps_max: null });
      m.n += 1;
      m.tps_sum += report.tps_avg;
      m.tps_min = m.tps_min == null ? report.tps_avg : Math.min(m.tps_min, report.tps_avg);
      m.tps_max = m.tps_max == null ? report.tps_avg : Math.max(m.tps_max, report.tps_avg);

      const d = (agg.devices[report.device || "unknown"] ||= { n: 0, soc: "" });
      d.n += 1;
      if (report.soc) d.soc = report.soc;

      agg.recent.unshift(report);
      agg.recent = agg.recent.slice(0, 25);
      agg.updated = Date.now();

      await env.BENCH_KV.put("agg", JSON.stringify(agg));
      return json({ ok: true, count: agg.count });
    }

    if (pathname === "/api/stats" && request.method === "GET") {
      return json(await loadAgg(env));
    }

    if ((pathname === "/" || pathname === "/index.html") && request.method === "GET") {
      const agg = await loadAgg(env);
      return new Response(dashboard(agg), {
        headers: { "Content-Type": "text/html; charset=utf-8", ...CORS },
      });
    }

    return json({ error: "not found" }, 404);
  },
};

function dashboard(agg) {
  const models = Object.entries(agg.models || {})
    .sort((a, b) => b[1].n - a[1].n)
    .map(([k, v]) => `<tr><td>${esc(k)}</td><td>${v.n}</td><td>${(v.tps_sum / v.n).toFixed(1)}</td>
      <td>${v.tps_min == null ? "-" : v.tps_min.toFixed(1)}</td>
      <td>${v.tps_max == null ? "-" : v.tps_max.toFixed(1)}</td></tr>`)
    .join("");

  const devices = Object.entries(agg.devices || {})
    .sort((a, b) => b[1].n - a[1].n)
    .map(([k, v]) => `<tr><td>${esc(k)}</td><td>${esc(v.soc)}</td><td>${v.n}</td></tr>`)
    .join("");

  const recent = (agg.recent || [])
    .map((r) => `<tr><td class="muted">${new Date(r.at).toISOString().slice(0, 16).replace("T", " ")}</td>
      <td>${esc(r.model)}</td><td>${esc(r.backend)}</td><td>${esc(r.device)}</td>
      <td>${r.tps_avg == null ? "-" : r.tps_avg.toFixed(1)}</td></tr>`)
    .join("");

  const updated = agg.updated ? new Date(agg.updated).toISOString().slice(0, 16).replace("T", " ") : "never";

  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Yumenode AI · Benchmark</title>
<style>
  body{font:15px/1.5 system-ui,-apple-system,sans-serif;background:#0A0D1A;color:#E8ECF5;margin:0;padding:24px}
  h1{color:#00F0FF;font-size:22px;margin:0 0 4px}
  h2{color:#00F0FF;font-size:15px;margin:28px 0 8px;letter-spacing:.04em;text-transform:uppercase}
  table{border-collapse:collapse;width:100%;max-width:900px}
  th,td{text-align:left;padding:7px 10px;border-bottom:1px solid #21305a;font-size:14px}
  th{color:#8A96B4;font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.05em}
  .muted{color:#8A96B4}
  .pill{display:inline-block;background:#1A237E;color:#E8ECF5;padding:3px 10px;border-radius:999px;font-size:12px;margin-right:6px}
</style></head><body>
<h1>Yumenode AI · Benchmark</h1>
<p class="muted">Anonymous, aggregated on-device benchmark reports. No IPs or identifiers are stored.</p>
<p><span class="pill">${agg.count || 0} reports</span><span class="pill">updated ${esc(updated)}</span></p>
<h2>By model</h2>
<table><tr><th>Model</th><th>Reports</th><th>avg tok/s</th><th>min</th><th>max</th></tr>${models}</table>
<h2>Devices</h2>
<table><tr><th>Device</th><th>SoC</th><th>Reports</th></tr>${devices}</table>
<h2>Recent</h2>
<table><tr><th>When (UTC)</th><th>Model</th><th>Backend</th><th>Device</th><th>tok/s</th></tr>${recent}</table>
</body></html>`;
}
