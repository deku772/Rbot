# Auto npm registry switching (by network)

Goal: improve npm install success in China Mainland networks by automatically selecting a reachable npm registry/mirror.

## Behavior

- Before running any `npm view` / `npm install -g ...` in Termux, Rbot runs an auto-selection snippet.
- If `BOTDROP_NPM_REGISTRY` is set, it wins (explicit override).
- Otherwise Rbot detects **current network** via default gateway IP (`ip route`), and caches a chosen registry for that gateway for 24h.
- The selection first probes registry reachability (2s timeout): `registry.npmjs.org` vs `registry.npmmirror.com` and picks the working one.
- When cache exists for current gateway, Rbot re-validates that registry; stale cache is automatically re-resolved.
- If probes are inconclusive, it falls back to a lightweight GeoIP heuristic (`ipinfo.io/country`).

## Registries

- Default: `https://registry.npmjs.org/`
- CN mirror: `https://registry.npmmirror.com/`

## Files

- Cache: `$HOME/.rbot_npm_registry_cache` (simple key/value lines)

## Notes

- This is best-effort. If all probes and GeoIP fallback are inconclusive, it falls back to default registry.
- We also export `NPM_CONFIG_REGISTRY` to make npm consistently use the chosen registry.
