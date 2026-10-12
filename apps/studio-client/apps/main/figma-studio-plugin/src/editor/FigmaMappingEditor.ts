import PanelSkin from "@coremedia/studio-client.ext.ui-components/skins/PanelSkin";
import ToolbarSkin from "@coremedia/studio-client.ext.ui-components/skins/ToolbarSkin";
import editorContext from "@coremedia/studio-client.main.editor-components/sdk/editorContext";
import MessageBoxUtil from "@coremedia/studio-client.main.editor-components/sdk/util/MessageBoxUtil";
import Button from "@jangaroo/ext-ts/button/Button";
import ArrayStore from "@jangaroo/ext-ts/data/ArrayStore";
import ComboBox from "@jangaroo/ext-ts/form/field/ComboBox";
import FitLayout from "@jangaroo/ext-ts/layout/container/Fit";
import Panel from "@jangaroo/ext-ts/panel/Panel";
import Fill from "@jangaroo/ext-ts/toolbar/Fill";
import Separator from "@jangaroo/ext-ts/toolbar/Separator";
import TextItem from "@jangaroo/ext-ts/toolbar/TextItem";
import Toolbar from "@jangaroo/ext-ts/toolbar/Toolbar";
import { as, bind } from "@jangaroo/runtime";
import Config from "@jangaroo/runtime/Config";
import ConfigUtils from "@jangaroo/runtime/ConfigUtils";
import FigmaStudioPlugin_properties from "../FigmaStudioPlugin_properties";
import FigmaApi from "./FigmaApi";
import FigmaNodeEditor from "./FigmaNodeEditor";
import MappingGraph from "./MappingGraph";

interface FigmaMappingEditorConfig extends Config<Panel> {}

/**
 * Work area tab to edit the transform description of a Figma component graphically. The component JSON is loaded
 * from the Content Hub item selected in the library (or pasted), the description is loaded from and saved to the
 * CMSettings <code>/Settings/Options/Settings/Figma/&lt;cm-type&gt;</code>, which are offered in a drop-down; a new name
 * can be typed to create a mapping. The viewtypes offered for the contents are the
 * global ones and the ones of the preferred site.
 */
class FigmaMappingEditor extends Panel {
  declare Config: FigmaMappingEditorConfig;

  static override readonly xtype: string = "com.coremedia.blueprint.studio.contenthub.figma.figmaMappingEditor";

  static readonly ID: string = "figmaMappingEditor";
  static readonly #NAME_FIELD: string = "figmaMappingName";
  static readonly #STATUS: string = "figmaMappingStatus";
  static readonly #NODE_EDITOR: string = "figmaNodeEditor";

  #selectedComponentId: string = null;
  #contentTypesLoaded: Promise<any> = null;

  constructor(config: Config<FigmaMappingEditor> = null) {
    // @ts-expect-error Ext JS semantics
    const this$ = this;
    super(
      ConfigUtils.apply(
        Config(FigmaMappingEditor, {
          id: FigmaMappingEditor.ID,
          title: FigmaStudioPlugin_properties.FigmaMapping_title,
          closable: true,
          ui: PanelSkin.DEFAULT.getSkin(),
          layout: Config(FitLayout),
          items: [Config(FigmaNodeEditor, { itemId: FigmaMappingEditor.#NODE_EDITOR })],
          tbar: Config(Toolbar, {
            ui: ToolbarSkin.WORKAREA.getSkin(),
            items: [
              Config(Button, {
                text: FigmaStudioPlugin_properties.FigmaMapping_loadSelection_text,
                tooltip: FigmaStudioPlugin_properties.FigmaMapping_loadSelection_tooltip,
                handler: (): void => {
                  this$.loadComponent(this$.#selectedComponentId);
                },
              }),
              Config(Button, {
                text: FigmaStudioPlugin_properties.FigmaMapping_pasteJson_text,
                handler: (): void => this$.#pasteJson(),
              }),
              Config(Separator),
              Config(ComboBox, {
                itemId: FigmaMappingEditor.#NAME_FIELD,
                emptyText: FigmaStudioPlugin_properties.FigmaMapping_name_emptyText,
                width: 220,
                // existing mappings are selected, a new name can be typed
                editable: true,
                forceSelection: false,
                queryMode: "local",
                anyMatch: true,
                triggerAction: "all",
                store: new ArrayStore(Config(ArrayStore, { fields: ["name"], data: [] })),
                valueField: "name",
                displayField: "name",
                listeners: {
                  select: (): void => this$.#loadMapping(this$.#getName(), true),
                  expand: (): void => this$.#loadNames(),
                },
              }),
              Config(Button, {
                text: FigmaStudioPlugin_properties.FigmaMapping_loadMapping_text,
                handler: (): void => {
                  this$.#loadMapping(this$.#getName(), true);
                },
              }),
              Config(Separator),
              Config(Button, {
                text: FigmaStudioPlugin_properties.FigmaMapping_addNode_text,
                handler: (): void => this$.#getNodeEditor().addContentNode(),
              }),
              Config(Button, {
                text: FigmaStudioPlugin_properties.FigmaMapping_addMarkup_text,
                tooltip: FigmaStudioPlugin_properties.FigmaMapping_addMarkup_tooltip,
                handler: (): void => this$.#getNodeEditor().addMarkupNode(),
              }),
              Config(Button, {
                text: FigmaStudioPlugin_properties.FigmaMapping_layout_text,
                handler: (): void => this$.#getNodeEditor().layout(),
              }),
              Config(Fill),
              Config(TextItem, {
                itemId: FigmaMappingEditor.#STATUS,
                text: FigmaStudioPlugin_properties.FigmaMapping_help,
              }),
              Config(Button, {
                text: FigmaStudioPlugin_properties.FigmaMapping_save_text,
                handler: (): void => this$.#save(),
              }),
            ],
          }),
        }),
        config,
      ),
    );
  }

  protected override afterRender(): void {
    super.afterRender();
    this.#getNodeEditor().setMessageHandler((message: string): void => this.#status(message));
    this.#getNodeEditor().setViewtypeLoader((contentType: string): Promise<any> =>
      FigmaApi.loadViewtypes(contentType, editorContext._.getSitesService().getPreferredSiteId()),
    );
    editorContext._.getSitesService()
      .getPreferredSiteIdExpression()
      .addChangeListener(bind(this, this.#preferredSiteChanged));
    this.#contentTypes();
    this.#loadNames();
  }

  /**
   * Loads the names of the existing mappings into the drop-down.
   */
  #loadNames(): void {
    FigmaApi.loadDescriptionNames()
      .then((names: Array<string>): void => {
        const combo = this.#getNameField();
        if (combo && Array.isArray(names)) {
          (combo.getStore() as ArrayStore).loadData(names.map((name: string): Array<string> => [name]));
        }
      })
      .catch((error: Error): void => this.#status("Cannot load the mappings: " + (error && error.message)));
  }

  protected override onDestroy(): void {
    editorContext._.getSitesService()
      .getPreferredSiteIdExpression()
      .removeChangeListener(bind(this, this.#preferredSiteChanged));
    super.onDestroy();
  }

  #preferredSiteChanged(): void {
    this.#getNodeEditor().resetViewtypes();
  }

  /**
   * Remembers the Content Hub object selected in the library and loads it.
   */
  openComponent(contentHubObjectId: string): void {
    this.#selectedComponentId = contentHubObjectId;
    if (contentHubObjectId) {
      this.loadComponent(contentHubObjectId);
    }
  }

  /**
   * Loads the JSON of a Figma component and, if the component has a cm-type, the existing mapping of that name.
   */
  loadComponent(contentHubObjectId: string): void {
    if (!contentHubObjectId) {
      this.#status("Select a Figma component in the library first.");
      return;
    }
    this.#status("Loading component…");
    Promise.all([FigmaApi.loadComponent(contentHubObjectId), this.#contentTypes()])
      .then((results: Array<any>): void => {
        const component = results[0];
        const graph = this.#getGraph();
        graph.setFigmaNode(component.node, component.name);
        if (component.cmType) {
          this.#setName(component.cmType);
          this.#loadMapping(component.cmType, false);
        } else {
          this.#status(
            "Loaded " +
              component.name +
              ". The component has no cm-type property yet: enter the name to save the mapping as.",
          );
          this.#getNodeEditor().redraw();
        }
      })
      .catch((error: Error): void => this.#error("Cannot load the Figma component", error));
  }

  #pasteJson(): void {
    MessageBoxUtil.prompt(
      FigmaStudioPlugin_properties.FigmaMapping_pasteJson_text,
      FigmaStudioPlugin_properties.FigmaMapping_pasteJson_prompt,
      (button: string, text: string): void => {
        if (button !== "ok" || !text) {
          return;
        }
        let json: any;
        try {
          json = JSON.parse(text);
        } catch (e) {
          this.#error("Invalid JSON", e as Error);
          return;
        }
        // accept a node, a nodes response or a file response
        let node = json.document || json;
        if (json.nodes) {
          const first = json.nodes[Object.keys(json.nodes)[0]];
          node = first && first.document;
        }
        this.#contentTypes().then((): void => {
          this.#getGraph().setFigmaNode(node, node && node.name);
          this.#getNodeEditor().redraw();
          this.#status("Pasted " + (node && node.name));
        });
      },
    );
  }

  #loadMapping(name: string, reportMissing: boolean): void {
    if (!name) {
      this.#status("Enter the cm-type, i.e. the name of the mapping.");
      return;
    }
    Promise.all([FigmaApi.loadDescription(name), this.#contentTypes()])
      .then((results: Array<any>): void => {
        this.#getGraph().fromSettings(results[0].settings);
        this.#getNodeEditor().redraw();
        this.#status("Loaded mapping '" + name + "'.");
      })
      .catch((error: Error): void => {
        if (reportMissing) {
          this.#error("Cannot load mapping '" + name + "'", error);
        } else {
          this.#status("No mapping '" + name + "' yet, add a content node to start.");
          this.#getNodeEditor().redraw();
        }
      });
  }

  #save(): void {
    const name = this.#getName();
    if (!name) {
      this.#status("Enter the cm-type, i.e. the name of the mapping.");
      return;
    }
    let settings: any;
    try {
      settings = this.#getGraph().toSettings();
    } catch (e) {
      this.#status((e as Error).message);
      return;
    }
    const unlinked = this.#getGraph().getUnlinkedNodes();
    FigmaApi.saveDescription(name, settings)
      .then((): void => {
        this.#loadNames();
        this.#status(
          "Saved mapping '" +
            name +
            "'" +
            (unlinked.length > 0 ? " (" + unlinked.length + " unlinked content node(s) are not part of it)." : "."),
        );
      })
      .catch((error: Error): void => this.#error("Cannot save mapping '" + name + "'", error));
  }

  /**
   * Loads the content types once.
   */
  #contentTypes(): Promise<any> {
    if (!this.#contentTypesLoaded) {
      this.#contentTypesLoaded = FigmaApi.loadContentTypes().then((types: any): any => {
        if (!Array.isArray(types)) {
          throw new Error("Unexpected response: " + JSON.stringify(types));
        }
        this.#getGraph().contentTypes = types;
        this.#getNodeEditor().redraw();
        return types;
      });
      this.#contentTypesLoaded.catch((error: Error): void => {
        this.#contentTypesLoaded = null;
        this.#error("Cannot load the content types", error);
      });
    }
    return this.#contentTypesLoaded;
  }

  #getNodeEditor(): FigmaNodeEditor {
    return as(this.queryById(FigmaMappingEditor.#NODE_EDITOR), FigmaNodeEditor);
  }

  #getGraph(): MappingGraph {
    return this.#getNodeEditor().getGraph();
  }

  #getNameField(): ComboBox {
    return as(this.queryById(FigmaMappingEditor.#NAME_FIELD), ComboBox);
  }

  /**
   * The selected or typed name.
   */
  #getName(): string {
    const value: string = this.#getNameField().getRawValue();
    return value ? value.trim() : "";
  }

  #setName(name: string): void {
    this.#getNameField().setValue(name);
  }

  #status(message: string): void {
    const item = as(this.queryById(FigmaMappingEditor.#STATUS), TextItem);
    if (item) {
      item.setText(message);
    }
  }

  #error(title: string, error: Error): void {
    this.#status(title + ": " + (error && error.message));
    MessageBoxUtil.showError(title, error && error.message);
  }
}

export default FigmaMappingEditor;
