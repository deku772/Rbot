export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === '/version') {
      return handleVersion(request, env, url);
    }

    if (url.pathname === '/purge') {
      return handlePurge(request, env, url);
    }

    return new Response('Not found', { status: 404 });
  },
};

async function handlePurge(request, env, url) {
  if (!env.CACHE) {
    return jsonResponse({ error: 'no_cache' }, 500);
  }

  await env.CACHE.delete('latest_release');
  return jsonResponse({ ok: true, message: 'cache purged' });
}

async function handleVersion(request, env, url) {
  const clientVersion = url.searchParams.get('v') || 'unknown';
  const clientVersionCode = url.searchParams.get('vc') || '0';

  // Try KV cache first
  const cacheKey = 'latest_release';
  const staleCacheKey = 'latest_release_stale';
  let release = null;

  if (env.CACHE) {
    const cached = await env.CACHE.get(cacheKey, 'json');
    if (cached) release = cached;
  }

  // Cache miss — fetch from GitHub
  if (!release) {
    try {
      const repo = env.GITHUB_REPO || 'zhixianio/botdrop-android';
      const res = await fetch(
        `https://api.github.com/repos/${repo}/releases/latest`,
        {
          headers: { 'User-Agent': 'andbott-api-worker', Accept: 'application/vnd.github.v3+json' },
          cf: { cacheTtl: 0, cacheEverything: false },
        },
      );

      if (!res.ok) {
        // Serve stale cache if GitHub API fails
        if (env.CACHE) {
          const stale = await env.CACHE.get(staleCacheKey, 'json');
          if (stale) return jsonResponse(stale);
        }
        return jsonResponse({ error: 'upstream_error' }, 502);
      }

      const data = await res.json();
      const tagName = (data.tag_name || '').replace(/^v/, '');

      release = {
        latest_version: tagName,
        latest_version_code: extractVersionCode(tagName),
        download_url: data.html_url || '',
        release_notes: (data.body || '').slice(0, 500),
        min_supported: '0.1.0',
      };

      // Store in KV with TTL + a long-lived stale copy as fallback
      if (env.CACHE) {
        const ttl = parseInt(env.CACHE_TTL_SECONDS, 10) || 21600;
        await env.CACHE.put(cacheKey, JSON.stringify(release), { expirationTtl: ttl });
        await env.CACHE.put(staleCacheKey, JSON.stringify(release));
      }
    } catch (err) {
      // Serve stale cache on network errors
      if (env.CACHE) {
        const stale = await env.CACHE.get(staleCacheKey, 'json');
        if (stale) return jsonResponse(stale);
      }
      return jsonResponse({ error: 'fetch_failed' }, 502);
    }
  }

  // Log analytics data point
  if (env.ANALYTICS) {
    env.ANALYTICS.writeDataPoint({
      blobs: [clientVersion, request.cf?.country || 'XX'],
      doubles: [parseInt(clientVersionCode, 10) || 0],
      indexes: [clientVersion],
    });
  }

  return jsonResponse(release);
}

function extractVersionCode(semver) {
  const parts = semver.split('.');
  if (parts.length < 3) return 0;
  return parseInt(parts[0], 10) * 10000 + parseInt(parts[1], 10) * 100 + parseInt(parts[2], 10);
}

function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*',
      'Cache-Control': status === 200 ? 'public, max-age=300' : 'no-cache',
    },
  });
}
