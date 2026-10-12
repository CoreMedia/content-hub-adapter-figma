package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.cache.Cache;
import com.coremedia.cache.CacheKey;
import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Cache keys of the {@link FigmaClient} for the CoreMedia {@link Cache}.
 * <p>
 * Every key sets its cache time explicitly with {@link Cache#cacheFor(long, TimeUnit)}, see
 * {@link FigmaProperties#getCacheTtl()} and {@link FigmaProperties#getDownloadCacheTtl()}. API responses depend on
 * {@link FigmaClient#dependency()}, so that they are invalidated when a Content Hub folder is reloaded.
 * <p>
 * Responses depend on the permissions of the access token, so keys are distinguished by the
 * {@link FigmaClient#cacheId()} of their client. It is derived from the token, but does not reveal it,
 * since keys may be shown by cache monitoring.
 */
@DefaultAnnotation(NonNull.class)
final class FigmaCacheKeys {

  private FigmaCacheKeys() {
  }

  /**
   * A GET request to the Figma API, e.g. <code>/v1/files/:key</code>.
   */
  static final class ApiCacheKey extends CacheKey<JsonNode> {
    private final FigmaClient client;
    private final String path;

    ApiCacheKey(FigmaClient client, String path) {
      this.client = client;
      this.path = path;
    }

    @Override
    public JsonNode evaluate(Cache cache) {
      Cache.disableDependencies();
      Cache.cacheFor(client.cacheTtl().toMillis(), TimeUnit.MILLISECONDS);
      try {
        return client.fetchJson(path);
      } finally {
        Cache.enableDependencies();
      }

    }

    @Override
    public boolean equals(Object o) {
      return this == o || (o instanceof ApiCacheKey other
        && client.cacheId().equals(other.client.cacheId()) && path.equals(other.path));
    }

    @Override
    public int hashCode() {
      return Objects.hash(client.cacheId(), path);
    }

    @Override
    public String toString() {
      return "FigmaApiCacheKey[" + client.cacheId() + ", " + path + "]";
    }
  }

  /**
   * Renditions of several nodes of a file, rendered with one request: node id -&gt; image URL,
   * or no entry if Figma could not render the node.
   */
  static final class RenderCacheKey extends CacheKey<Map<String, String>> {
    private final FigmaClient client;
    private final String fileKey;
    private final List<String> nodeIds;
    private final String format;
    private final double scale;

    RenderCacheKey(FigmaClient client, String fileKey, List<String> nodeIds, String format, double scale) {
      this.client = client;
      this.fileKey = fileKey;
      this.nodeIds = List.copyOf(nodeIds);
      this.format = format;
      this.scale = scale;
    }

    @Override
    public Map<String, String> evaluate(Cache cache) {
      Cache.disableDependencies();
      Cache.cacheFor(client.cacheTtl().toMillis(), TimeUnit.MILLISECONDS);
      try {
        return client.fetchImageUrls(fileKey, nodeIds, format, scale);
      } finally {
        Cache.enableDependencies();
      }
    }

    @Override
    public boolean equals(Object o) {
      return this == o || (o instanceof RenderCacheKey other
        && client.cacheId().equals(other.client.cacheId()) && fileKey.equals(other.fileKey)
        && nodeIds.equals(other.nodeIds) && format.equals(other.format) && scale == other.scale);
    }

    @Override
    public int hashCode() {
      return Objects.hash(client.cacheId(), fileKey, nodeIds, format, scale);
    }

    @Override
    public String toString() {
      return "FigmaRenderCacheKey[" + client.cacheId() + ", " + fileKey + ", " + nodeIds + ", " + format + ", " + scale + "]";
    }
  }

  /**
   * A download of a rendition, thumbnail or image fill. The URLs are unique, so downloads are not invalidated on reload.
   */
  static final class DownloadCacheKey extends CacheKey<FigmaClient.Download> {
    private final FigmaClient client;
    private final String url;

    DownloadCacheKey(FigmaClient client, String url) {
      this.client = client;
      this.url = url;
    }

    @Override
    public FigmaClient.Download evaluate(Cache cache) {
      Cache.disableDependencies();
      Cache.cacheFor(client.downloadCacheTtl().toMillis(), TimeUnit.MILLISECONDS);
      try {
        return client.fetchDownload(url);
      } finally {
        Cache.enableDependencies();
      }
    }

    @Override
    public boolean equals(Object o) {
      return this == o || (o instanceof DownloadCacheKey other && url.equals(other.url));
    }

    @Override
    public int hashCode() {
      return url.hashCode();
    }

    @Override
    public String toString() {
      // the query of the signed S3 URLs is long and irrelevant
      int query = url.indexOf('?');
      return "FigmaDownloadCacheKey[" + (query < 0 ? url : url.substring(0, query)) + "]";
    }
  }
}
