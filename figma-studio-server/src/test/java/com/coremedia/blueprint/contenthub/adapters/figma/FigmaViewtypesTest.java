package com.coremedia.blueprint.contenthub.adapters.figma;

import com.coremedia.cap.common.CapPropertyDescriptor;
import com.coremedia.cap.common.CapPropertyDescriptorType;
import com.coremedia.cap.common.descriptors.LinkPropertyDescriptor;
import com.coremedia.cap.content.Content;
import com.coremedia.cap.content.ContentRepository;
import com.coremedia.cap.content.ContentType;
import com.coremedia.cap.multisite.Site;
import com.coremedia.cap.multisite.SitesService;
import com.coremedia.contenthub.api.ContentHubContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FigmaViewtypesTest {

  private ContentRepository contentRepository;
  private SitesService sitesService;
  private FigmaViewtypes viewtypes;
  private ContentType viewtypeType;
  private ContentType teaserType;
  private Content global;
  private Content siteFolder;
  private Site site;

  @BeforeEach
  void setUp() {
    contentRepository = mock(ContentRepository.class);
    sitesService = mock(SitesService.class);
    viewtypes = new FigmaViewtypes(contentRepository, sitesService);

    viewtypeType = type("CMViewtype", null);
    when(viewtypeType.isSubtypeOf(viewtypeType)).thenReturn(true);
    ContentType document = type("Document_", null);
    ContentType linkable = type("CMLinkable", document);
    teaserType = type("CMTeaser", linkable);

    global = folder("/Settings/Options/Viewtypes");
    when(contentRepository.getChild("/Settings/Options/Viewtypes")).thenReturn(global);
    Content siteRoot = folder("/Sites/Corporate");
    siteFolder = folder("/Sites/Corporate/Options/Viewtypes");
    when(siteRoot.getChild("Options/Viewtypes")).thenReturn(siteFolder);
    site = mock(Site.class);
    when(site.getSiteRootFolder()).thenReturn(siteRoot);
  }

  @Test
  void applicableViewtypesAreLookedUpByTypeHierarchy() {
    Content hero = viewtype("Hero", "hero");
    Content linkableDefault = viewtype("Default", "default");
    Content generic = viewtype("Generic", "");
    Content excluded = viewtype("Excluded", "excluded");
    children(global, generic);
    children(child(global, "CMTeaser"), hero);
    children(child(global, "CMLinkable"), linkableDefault);
    children(child(global, "Document_"), excluded);

    assertThat(viewtypes.applicable("CMTeaser", null)).containsExactly(hero, linkableDefault, generic);
    assertThat(FigmaViewtypes.key(generic)).isEqualTo("Generic");
  }

  @Test
  void siteViewtypesComeFirst() {
    Content globalHero = viewtype("Hero", "hero");
    Content siteHero = viewtype("Hero (Corporate)", "hero");
    children(child(global, "CMTeaser"), globalHero);
    children(child(siteFolder, "CMTeaser"), siteHero);

    assertThat(viewtypes.applicable("CMTeaser", site)).containsExactly(siteHero, globalHero);
    assertThat(viewtypes.find("CMTeaser", "hero", site)).contains(siteHero);
    assertThat(viewtypes.find("CMTeaser", "hero", null)).contains(globalHero);
    assertThat(viewtypes.find("CMTeaser", "unknown", site)).isEmpty();
  }

  @Test
  void unknownTypeHasNoViewtypes() {
    assertThat(viewtypes.applicable("Unknown", site)).isEmpty();
    assertThat(viewtypes.hasViewtype("Unknown")).isFalse();
  }

  @Test
  void hasViewtype() {
    LinkPropertyDescriptor descriptor = mock(LinkPropertyDescriptor.class);
    when(descriptor.getType()).thenReturn(CapPropertyDescriptorType.LINK);
    when(descriptor.getLinkType()).thenReturn(viewtypeType);
    when(teaserType.getDescriptorsByName()).thenReturn(Map.of("viewtype", descriptor));

    assertThat(viewtypes.hasViewtype("CMTeaser")).isTrue();
  }

  @Test
  void siteIsIdentifiedByNameAndLocale() {
    Site english = site("Corporate", Locale.ENGLISH);
    Site german = site("Corporate", Locale.GERMAN);
    when(sitesService.getSites()).thenReturn(Set.of(english, german));
    ContentHubContext context = mock(ContentHubContext.class);
    when(context.getSiteName()).thenReturn("Corporate");
    when(context.getSiteLocale()).thenReturn(Locale.GERMAN);

    assertThat(viewtypes.site(context)).isSameAs(german);
    when(context.getSiteName()).thenReturn(null);
    assertThat(viewtypes.site(context)).isNull();
  }

  private ContentType type(String name, ContentType parent) {
    ContentType type = mock(ContentType.class);
    when(type.getName()).thenReturn(name);
    when(type.getParent()).thenReturn(parent);
    when(type.getDescriptorsByName()).thenReturn(Map.<String, CapPropertyDescriptor>of());
    when(contentRepository.getContentType(name)).thenReturn(type);
    return type;
  }

  private static Content folder(String path) {
    Content folder = mock(Content.class);
    when(folder.isFolder()).thenReturn(true);
    when(folder.getPath()).thenReturn(path);
    when(folder.getChildDocuments()).thenReturn(Set.of());
    return folder;
  }

  private static Content child(Content parent, String name) {
    Content child = folder(parent.getPath() + "/" + name);
    when(parent.getChild(name)).thenReturn(child);
    return child;
  }

  private static void children(Content folder, Content... documents) {
    when(folder.getChildDocuments()).thenReturn(Set.of(documents));
  }

  private Content viewtype(String name, String layout) {
    Content viewtype = mock(Content.class);
    CapPropertyDescriptor layoutDescriptor = mock(CapPropertyDescriptor.class);
    ContentType type = mock(ContentType.class);
    when(type.isSubtypeOf(viewtypeType)).thenReturn(true);
    when(type.getDescriptorsByName()).thenReturn(Map.of("layout", layoutDescriptor));
    when(viewtype.getType()).thenReturn(type);
    when(viewtype.getName()).thenReturn(name);
    when(viewtype.getString("layout")).thenReturn(layout);
    return viewtype;
  }

  private static Site site(String name, Locale locale) {
    Site site = mock(Site.class);
    when(site.getName()).thenReturn(name);
    when(site.getLocale()).thenReturn(locale);
    return site;
  }
}
