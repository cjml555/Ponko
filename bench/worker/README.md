# Yumenode AI · Benchmark endpoint (Cloudflare Worker)

Endpoint público y anónimo para las estadísticas del benchmark local de la app.
Guarda solo datos agregados (modelo, backend, dispositivo, SoC, tok/s, latencia).
**No** guarda IPs, IDs ni datos personales.

## Rutas

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/api/report` | Recibe un reporte JSON (ver abajo) |
| `GET` | `/` | Dashboard HTML con agregados |
| `GET` | `/api/stats` | Agregado crudo en JSON |

### Payload aceptado

```json
{
  "model": "llama-3.2-1b-instruct-q4_k_m.gguf",
  "backend": "llama.cpp (CPU)",
  "device": "motorola edge 50 fusion",
  "soc": "SM6450",
  "version": "1.1.0",
  "tps_avg": 19.8, "tps_min": 17.2, "tps_max": 22.2,
  "latency_min": 7411, "latency_max": 8731,
  "tokens": 148
}
```

Solo `model` y `tps_avg` son obligatorios. El resto se ignora si falta.

## Deploy

```bash
cd bench/worker
npm install
npx wrangler login
npx wrangler kv namespace create BENCH_KV   # copia el id a wrangler.toml
npx wrangler deploy
```

Al terminar, `wrangler` imprime la URL pública, p. ej.
`https://yumenode-bench.<tu-subdominio>.workers.dev`.

En la app: **Ajustes → Benchmark → endpoint** pega
`https://yumenode-bench.<tu-subdominio>.workers.dev/api/report` y usa
**Share anonymously** tras correr el benchmark.

## Desarrollo local

```bash
npx wrangler dev
```
