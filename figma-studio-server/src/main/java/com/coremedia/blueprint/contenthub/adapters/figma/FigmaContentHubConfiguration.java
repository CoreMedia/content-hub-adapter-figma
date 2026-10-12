package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.cap.common.CapConnection;
import com.coremedia.cap.content.ContentRepository;
import com.coremedia.cap.multisite.SitesService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(FigmaProperties.class)
public class FigmaContentHubConfiguration {

  @Bean
  FigmaViewtypes figmaViewtypes(ContentRepository contentRepository, SitesService sitesService) {
    return new FigmaViewtypes(contentRepository, sitesService);
  }

  @Bean
  public FigmaContentHubAdapterFactory figmaContentHubAdapterFactory(FigmaProperties figmaProperties,
                                                                     ContentRepository contentRepository,
                                                                     FigmaViewtypes figmaViewtypes,
                                                                     CapConnection capConnection) {
    return new FigmaContentHubAdapterFactory(figmaProperties, new FigmaTransformDescriptions(contentRepository),
            figmaViewtypes, capConnection.getCache());
  }

  @Bean
  FigmaMarkupWriteInterceptor figmaMarkupWriteInterceptor(ContentRepository contentRepository) {
    FigmaMarkupWriteInterceptor interceptor = new FigmaMarkupWriteInterceptor();
    interceptor.setType(contentRepository.getDocumentContentType());
    interceptor.setInterceptingSubtypes(true);
    return interceptor;
  }

  @Bean
  public FigmaMappingResource figmaMappingResource(FigmaContentHubAdapterFactory figmaContentHubAdapterFactory,
                                                   FigmaViewtypes figmaViewtypes,
                                                   CapConnection capConnection) {
    return new FigmaMappingResource(figmaContentHubAdapterFactory, figmaViewtypes, capConnection);
  }
}
