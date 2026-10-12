package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.cache.Cache;
import com.coremedia.contenthub.api.ContentHubAdapter;
import com.coremedia.contenthub.api.ContentHubAdapterFactory;
import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@DefaultAnnotation(NonNull.class)
public class FigmaContentHubAdapterFactory implements ContentHubAdapterFactory<FigmaContentHubSettings> {

  static final String FACTORY_ID = "figma";

  private final FigmaProperties properties;
  private final FigmaTransformDescriptions transformDescriptions;
  private final FigmaViewtypes viewtypes;
  private final Cache cache;
  /**
   * The adapters by connection id, used by the {@link FigmaMappingResource}.
   */
  private final Map<String, FigmaContentHubAdapter> adapters = new ConcurrentHashMap<>();

  public FigmaContentHubAdapterFactory(FigmaProperties properties, FigmaTransformDescriptions transformDescriptions,
                                       FigmaViewtypes viewtypes, Cache cache) {
    this.properties = properties;
    this.transformDescriptions = transformDescriptions;
    this.viewtypes = viewtypes;
    this.cache = cache;
  }

  @Override
  public String getId() {
    return FACTORY_ID;
  }

  @Override
  public ContentHubAdapter createAdapter(FigmaContentHubSettings settings, String connectionId) {
    String token = StringUtils.defaultIfBlank(settings.getAccessToken(), properties.getAccessToken());
    if (StringUtils.isBlank(token)) {
      throw new IllegalArgumentException("No Figma access token configured for Content Hub connection '" + connectionId
              + "'. Set 'accessToken' in the connection settings or the property 'contenthub.figma.access-token'.");
    }
    FigmaClient client = new FigmaClient(properties.getApiUrl(), token, properties.getCacheTtl(),
            properties.getDownloadCacheTtl(), properties.getRequestTimeout(), cache);
    FigmaContentHubAdapter adapter = new FigmaContentHubAdapter(settings, connectionId, client, transformDescriptions,
            viewtypes);
    adapters.put(connectionId, adapter);
    return adapter;
  }

  /**
   * Returns the adapter of the connection, if the Content Hub has created it already.
   */
  Optional<FigmaContentHubAdapter> adapter(String connectionId) {
    return Optional.ofNullable(adapters.get(connectionId));
  }
}
