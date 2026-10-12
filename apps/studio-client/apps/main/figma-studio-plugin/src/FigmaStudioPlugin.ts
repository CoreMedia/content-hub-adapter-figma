import ContentHubRepositoryToolbar from "@coremedia/studio-client.main.content-hub-editor-components/library/list/ContentHubRepositoryToolbar";
import StudioPlugin from "@coremedia/studio-client.main.editor-components/configuration/StudioPlugin";
import ComponentBasedWorkAreaTabType from "@coremedia/studio-client.main.editor-components/sdk/desktop/ComponentBasedWorkAreaTabType";
import WorkArea from "@coremedia/studio-client.main.editor-components/sdk/desktop/WorkArea";
import WorkAreaTabTypesPlugin from "@coremedia/studio-client.main.editor-components/sdk/desktop/WorkAreaTabTypesPlugin";
import Config from "@jangaroo/runtime/Config";
import ConfigUtils from "@jangaroo/runtime/ConfigUtils";
import FigmaToolbarPlugin from "./FigmaToolbarPlugin";
import FigmaMappingEditor from "./editor/FigmaMappingEditor";

interface FigmaStudioPluginConfig extends Config<StudioPlugin> {}

/**
 * Extends the Content Hub for Figma connections with a graphical editor for the transform descriptions,
 * i.e. the CMSettings in /Settings/Options/Settings/Figma which map Figma components to content.
 */
class FigmaStudioPlugin extends StudioPlugin {
  declare Config: FigmaStudioPluginConfig;

  constructor(config: Config<FigmaStudioPlugin> = null) {
    super(
      ConfigUtils.apply(
        Config(FigmaStudioPlugin, {
          rules: [
            Config(ContentHubRepositoryToolbar, {
              plugins: [Config(FigmaToolbarPlugin)],
            }),
            Config(WorkArea, {
              plugins: [
                Config(WorkAreaTabTypesPlugin, {
                  tabTypes: [
                    new ComponentBasedWorkAreaTabType({ tabComponent: Config(FigmaMappingEditor, { closable: true }) }),
                  ],
                }),
              ],
            }),
          ],
        }),
        config,
      ),
    );
  }
}

export default FigmaStudioPlugin;
