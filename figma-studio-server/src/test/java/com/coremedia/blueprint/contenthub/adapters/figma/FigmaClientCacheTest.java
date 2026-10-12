package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.cache.Cache;
import com.coremedia.cache.CacheKey;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FigmaClientCacheTest {

  private static final String TOKEN = "figd_secret-token";

  private HttpServer server;
  private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
  private Cache cache;
  private FigmaClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      requests.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
      int status = path.startsWith("/v1/files/missing") ? 404 : 200;
      String contentType = path.startsWith("/download") ? "image/png" : "application/json";
      String body = status == 404 ? "{\"status\":404,\"err\":\"Not found\"}"
              : path.startsWith("/download") ? "PNG" : "{\"name\":\"File " + requests.get(path).get() + "\"}";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", contentType);
      exchange.sendResponseHeaders(status, bytes.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    });
    server.start();
    cache = new Cache("figma-test");
    cache.setCapacity(CacheKey.CACHE_CLASS_DEFAULT, 100);
    client = new FigmaClient("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN, Duration.ofMinutes(5),
            Duration.ofHours(1), Duration.ofSeconds(10), cache);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void responsesAreCachedAndInvalidatedOnReload() {
    assertThat(client.getFile("abc").path("name").asText()).isEqualTo("File 1");
    assertThat(client.getFile("abc").path("name").asText()).isEqualTo("File 1");
    assertThat(requests.get("/v1/files/abc").get()).isEqualTo(1);

    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/download/a.png";
    assertThat(client.download(url).contentType()).isEqualTo("image/png");

    client.clearCache();

    assertThat(client.getFile("abc").path("name").asText()).isEqualTo("File 2");
    // downloads are not invalidated, their URLs are unique
    client.download(url);
    assertThat(requests.get("/download/a.png").get()).isEqualTo(1);
  }

  @Test
  void notFoundIsNotWrapped() {
    assertThatThrownBy(() -> client.getFile("missing")).isInstanceOf(FigmaClient.FigmaNotFoundException.class);
  }

  @Test
  void cacheTimeIsSet() {
    FigmaClient shortLived = new FigmaClient("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN,
            Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofSeconds(10), cache);
    shortLived.getFile("ttl");
    sleep(1500);
    shortLived.getFile("ttl");
    assertThat(requests.get("/v1/files/ttl").get()).isEqualTo(2);
  }

  @Test
  void keysDoNotRevealTheToken() {
    String key = new FigmaCacheKeys.ApiCacheKey(client, "/v1/files/abc").toString();
    assertThat(key).doesNotContain("secret").contains(client.cacheId());
    assertThat(client.cacheId()).hasSize(16);
  }

  @Test
  void batchesAreDeterministic() {
    List<String> siblings = List.of("1:3", "1:1", "1:2");
    assertThat(FigmaClient.batch("1:2", siblings)).containsExactly("1:1", "1:2", "1:3");
    assertThat(FigmaClient.batch("1:3", siblings)).isEqualTo(FigmaClient.batch("1:1", siblings));
    List<String> many = java.util.stream.IntStream.range(10, 55).mapToObj(i -> "1:" + i).toList();
    List<String> first = FigmaClient.batch("1:10", many);
    assertThat(first).hasSize(20).contains("1:10");
    assertThat(FigmaClient.batch(first.get(19), many)).isEqualTo(first);
    assertThat(FigmaClient.batch("1:54", many)).hasSizeLessThanOrEqualTo(20).contains("1:54");
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
