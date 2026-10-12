package com.coremedia.blueprint.contenthub.adapters.figma;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Application wide defaults for all Figma Content Hub connections.
 */
@ConfigurationProperties(prefix = "contenthub.figma")
public class FigmaProperties {

  /**
   * Base URL of the Figma REST API.
   */
  private String apiUrl = "https://api.figma.com";

  /**
   * Fallback access token, used for connections that do not define an accessToken.
   */
  private String accessToken;

  /**
   * Cache time of Figma API responses and rendition URLs in the CoreMedia cache. Figma rate-limits its API,
   * so do not choose this too short. Reloading a Content Hub folder in Studio invalidates them earlier.
   */
  private Duration cacheTtl = Duration.ofMinutes(5);

  /**
   * Cache time of downloaded images (renditions, thumbnails and image fills) in the CoreMedia cache.
   * Their URLs are unique, so they are not invalidated by a reload.
   */
  private Duration downloadCacheTtl = Duration.ofHours(1);

  /**
   * Timeout for requests to the Figma API and for downloading rendered images.
   */
  private Duration requestTimeout = Duration.ofSeconds(60);

  public String getApiUrl() {
    return apiUrl;
  }

  public void setApiUrl(String apiUrl) {
    this.apiUrl = apiUrl;
  }

  public String getAccessToken() {
    return accessToken;
  }

  public void setAccessToken(String accessToken) {
    this.accessToken = accessToken;
  }

  public Duration getCacheTtl() {
    return cacheTtl;
  }

  public void setCacheTtl(Duration cacheTtl) {
    this.cacheTtl = cacheTtl;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }

  public Duration getDownloadCacheTtl() {
    return downloadCacheTtl;
  }

  public void setDownloadCacheTtl(Duration downloadCacheTtl) {
    this.downloadCacheTtl = downloadCacheTtl;
  }
}
