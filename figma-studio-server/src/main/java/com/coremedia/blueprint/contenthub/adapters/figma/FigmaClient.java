package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.cache.Cache;
import com.coremedia.cache.CacheKey;
import com.coremedia.cache.EvaluationException;
import com.coremedia.contenthub.api.exception.ContentHubException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Minimal client for the Figma REST API (https://www.figma.com/developers/api).
 * <p>
 * Responses, renditions and downloads are cached in the CoreMedia {@link Cache} (see {@link FigmaCacheKeys}) for
 * the configured cache times, because the Figma API is rate-limited rather strictly, especially the image
 * rendering endpoint.
 */
@DefaultAnnotation(NonNull.class)
class FigmaClient {
  private static final Logger LOG = LoggerFactory.getLogger(FigmaClient.class);

  private static final String TOKEN_HEADER = "X-Figma-Token";
  private static final String OCTET_STREAM = "application/octet-stream";
  /**
   * Maximum number of nodes rendered with a single images request.
   */
  private static final int MAX_RENDER_BATCH = 20;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient;
  private final String apiUrl;
  private final String token;
  private final Duration cacheTtl;
  private final Duration downloadCacheTtl;
  private final Duration requestTimeout;
  private final Cache cache;
  private final String cacheId;

  FigmaClient(String apiUrl, String token, Duration cacheTtl, Duration downloadCacheTtl, Duration requestTimeout,
              Cache cache) {
    this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
    this.token = token;
    this.cacheTtl = cacheTtl;
    this.downloadCacheTtl = downloadCacheTtl;
    this.requestTimeout = requestTimeout;
    this.cache = cache;
    this.cacheId = cacheId(this.apiUrl, token);
    this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  // --- API --------------------------------------------------------

  /**
   * GET /v2/teams/:team_id/folders, returns all folders of the team, including nested ones:
   * <code>{name, folders: [{id, name, parent_folder_id}]}</code>
   * <p>
   * Requires the <code>folders:read</code> scope (unlike the v1 projects API, which requires <code>projects:read</code>).
   */
  JsonNode getTeamFolders(String teamId) {
    return getJson("/v2/teams/" + encode(teamId) + "/folders");
  }

  /**
   * GET /v2/folders/:folder_id/folders, returns the direct sub folders: <code>{name, folders: [{id, name, parent_folder_id}]}</code>
   */
  JsonNode getSubFolders(String folderId) {
    return getJson("/v2/folders/" + encode(folderId) + "/folders");
  }

  /**
   * GET /v2/folders/:folder_id/files, returns <code>{name, files: [{key, name, thumbnail_url, last_modified}]}</code>
   */
  JsonNode getFolderFiles(String folderId) {
    return getJson("/v2/folders/" + encode(folderId) + "/files");
  }

  /**
   * GET /v1/files/:key with depth 2, i.e. the document with its pages and their top level nodes.
   */
  JsonNode getFile(String fileKey) {
    return getJson("/v1/files/" + encode(fileKey)+"?depth=10");
  }

  /**
   * GET /v1/files/:key/nodes?ids=:id, returns the <code>document</code> of the node with all its descendants,
   * or a missing node if the node does not exist.
   */
  JsonNode getNode(String fileKey, String nodeId) {
    return getJson("/v1/files/" + encode(fileKey) + "/nodes?ids=" + encode(nodeId))
            .path("nodes").path(nodeId).path("document");
  }

  /**
   * Returns the URL of a rendition of the given node.
   * <p>
   * The images endpoint accepts several node ids at once. In order to save requests, the
   * <code>batchCandidates</code> (typically the siblings of the node) are rendered along with the
   * requested node. The batches are derived deterministically from the sorted ids, so that the siblings
   * share the same cached batch.
   */
  Optional<String> getImageUrl(String fileKey, String nodeId, String format, double scale, Collection<String> batchCandidates) {
    List<String> ids = batch(nodeId, batchCandidates);
    Map<String, String> urls = get(new FigmaCacheKeys.RenderCacheKey(this, fileKey, ids, format, scale));
    return Optional.ofNullable(urls.get(nodeId));
  }

  /**
   * The batch of at most {@link #MAX_RENDER_BATCH} ids that contains the node.
   */
  static List<String> batch(String nodeId, Collection<String> batchCandidates) {
    SortedSet<String> all = new TreeSet<>(batchCandidates);
    all.add(nodeId);
    List<String> sorted = List.copyOf(all);
    int start = sorted.indexOf(nodeId) / MAX_RENDER_BATCH * MAX_RENDER_BATCH;
    return sorted.subList(start, Math.min(start + MAX_RENDER_BATCH, sorted.size()));
  }

  /**
   * GET /v1/images/:key, called by {@link FigmaCacheKeys.RenderCacheKey}.
   */
  Map<String, String> fetchImageUrls(String fileKey, List<String> ids, String format, double scale) {
    String path = "/v1/images/" + encode(fileKey)
            + "?ids=" + encode(String.join(",", ids))
            + "&format=" + encode(format)
            + "&scale=" + scale;
    JsonNode response = fetchJson(path);
    String error = response.path("err").asText(null);
    if (error != null && !"null".equals(error)) {
      throw new ContentHubException("Figma could not render " + ids + " of file " + fileKey + ": " + error);
    }
    Map<String, String> result = new HashMap<>();
    JsonNode images = response.path("images");
    for (String id : ids) {
      JsonNode url = images.get(id);
      // Figma returns null for nodes that could not be rendered (e.g. invisible, empty or deleted nodes).
      if (url != null && url.isTextual()) {
        result.put(id, url.asText());
      }
    }
    return Map.copyOf(result);
  }

  /**
   * GET /v1/files/:key/images, returns the download URL of an image fill by its <code>imageRef</code>
   * (the hash in the <code>fills</code> of a node), or empty if the file has no such image.
   */
  Optional<String> getImageFillUrl(String fileKey, String imageRef) {
    JsonNode url = getJson("/v1/files/" + encode(fileKey) + "/images").path("meta").path("images").path(imageRef);
    return url.isTextual() ? Optional.of(url.asText()) : Optional.empty();
  }

  /**
   * Downloads a file which is not part of the API itself, like a rendered image, a thumbnail or an image fill.
   */
  Download download(String url) {
    return get(new FigmaCacheKeys.DownloadCacheKey(this, url));
  }

  /**
   * Called by {@link FigmaCacheKeys.DownloadCacheKey}.
   */
  Download fetchDownload(String url) {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).GET().build();
    try {
      HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        throw new ContentHubException("Download of " + url + " failed with status " + response.statusCode());
      }
      return new Download(response.body(), contentType(response));
    } catch (IOException e) {
      throw new ContentHubException("Download of " + url + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ContentHubException("Download of " + url + " interrupted", e);
    }
  }

  /**
   * Image fills are served from S3, which does not always send an image content type, so it is guessed from the data then.
   */
  private static String contentType(HttpResponse<byte[]> response) throws IOException {
    String contentType = response.headers().firstValue("Content-Type").orElse(OCTET_STREAM);
    if (!contentType.startsWith(OCTET_STREAM) && !contentType.startsWith("binary/")) {
      return contentType;
    }
    String guessed = URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(response.body()));
    return guessed != null ? guessed : contentType;
  }

  /**
   * Invalidates the cached API responses and renditions of this client, so that changes in Figma become visible.
   * Downloads are kept, since their URLs are unique.
   */
  void clearCache() {
    cache.invalidate(dependency());
  }

  /**
   * The dependency of the cached API responses of this client.
   */
  Object dependency() {
    return "figma-api:" + cacheId;
  }

  /**
   * Identifies the API URL and token in cache keys, without revealing the token.
   */
  String cacheId() {
    return cacheId;
  }

  Duration cacheTtl() {
    return cacheTtl;
  }

  Duration downloadCacheTtl() {
    return downloadCacheTtl;
  }

  record Download(byte[] data, String contentType) {
  }

  // --- internal ---------------------------------------------------

  private JsonNode getJson(String path) {
    return get(new FigmaCacheKeys.ApiCacheKey(this, path));
  }

  /**
   * Looks up the key in the cache. The cache wraps checked exceptions of the evaluation, the original
   * exceptions are rethrown, so that e.g. {@link FigmaNotFoundException}s can be handled by the callers.
   */
  private <T> T get(CacheKey<T> key) {
    try {
      return cache.get(key);
    } catch (EvaluationException e) {
      if (e.getCause() instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new ContentHubException("Figma request failed: " + key, e);
    }
  }

  /**
   * GET request to the Figma API, called by {@link FigmaCacheKeys.ApiCacheKey}.
   */
  JsonNode fetchJson(String path) {
    String body = fetchJsonBody(path);
    try {
      return objectMapper.readTree(body);
    } catch (IOException e) {
      throw new ContentHubException("Figma request " + path + " returned invalid JSON", e);
    }
  }

  private String fetchJsonBody(String path) {
    HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl + path))
            .timeout(requestTimeout)
            .header(TOKEN_HEADER, token)
            .header("Accept", "application/json")
            .GET()
            .build();
    long start = System.currentTimeMillis();
    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      LOG.debug("GET {} -> {} ({} ms)", path, response.statusCode(), System.currentTimeMillis() - start);
      int status = response.statusCode();
      if (status == 200) {
        return response.body();
      }
      String message = errorMessage(response.body());
      if (status == 429) {
        // rate limits depend on the seat of the token owner: "low" (View/Collab seats) allows only a few requests per month
        HttpHeaders headers = response.headers();
        String retryAfter = headers.firstValue("Retry-After").orElse("?");
        throw new ContentHubException("Figma rate limit exceeded for " + path + ", retry after " + retryAfter + " seconds"
                + " (rate limit type: " + headers.firstValue("X-Figma-Rate-Limit-Type").orElse("?")
                + ", plan tier: " + headers.firstValue("X-Figma-Plan-Tier").orElse("?")
                + headers.firstValue("X-Figma-Upgrade-Link").map(link -> ", upgrade: " + link).orElse("") + ")");
      }
      if (status == 404) {
        throw new FigmaNotFoundException("Figma resource not found: " + path + ": " + message);
      }
      throw new ContentHubException("Figma request " + path + " failed with status " + status + ": " + message);
    } catch (IOException e) {
      throw new ContentHubException("Figma request " + path + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ContentHubException("Figma request " + path + " interrupted", e);
    }
  }

  private String errorMessage(String body) {
    try {
      JsonNode json = objectMapper.readTree(body);
      return json.path("err").asText(json.path("message").asText(body));
    } catch (IOException e) {
      return body;
    }
  }

  private static String cacheId(String apiUrl, String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest((apiUrl + "|" + token).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  static class FigmaNotFoundException extends ContentHubException {
    FigmaNotFoundException(String msg) {
      super(msg);
    }
  }
}
