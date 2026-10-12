import ValueExpression from "@coremedia/studio-client.client-core/data/ValueExpression";
import ValueExpressionFactory from "@coremedia/studio-client.client-core/data/ValueExpressionFactory";
import ContentHubObject from "@coremedia/studio-client.content-hub-models/ContentHubObject";
import ButtonSkin from "@coremedia/studio-client.ext.ui-components/skins/ButtonSkin";
import ContentHubUtil from "@coremedia/studio-client.main.content-hub-editor-components/ContentHubUtil";
import ContentHubRepositoryToolbar from "@coremedia/studio-client.main.content-hub-editor-components/library/list/ContentHubRepositoryToolbar";
import WorkArea from "@coremedia/studio-client.main.editor-components/sdk/desktop/WorkArea";
import editorContext from "@coremedia/studio-client.main.editor-components/sdk/editorContext";
import Ext from "@jangaroo/ext-ts";
import Component from "@jangaroo/ext-ts/Component";
import Button from "@jangaroo/ext-ts/button/Button";
import Panel from "@jangaroo/ext-ts/panel/Panel";
import AbstractPlugin from "@jangaroo/ext-ts/plugin/Abstract";
import { as, is } from "@jangaroo/runtime";
import Config from "@jangaroo/runtime/Config";
import FigmaStudioPlugin_properties from "./FigmaStudioPlugin_properties";
import FigmaMappingEditor from "./editor/FigmaMappingEditor";

interface FigmaToolbarPluginConfig extends Config<AbstractPlugin> {}

/**
 * Adds the "Figma Mapping" button to the Content Hub toolbar of the library. The button is only visible
 * while a Figma connection is shown, and opens the {@link FigmaMappingEditor} for the selected component.
 */
class FigmaToolbarPlugin extends AbstractPlugin {
  declare Config: FigmaToolbarPluginConfig;

  /** the connection id of the Figma connection, see the Content Hub settings */
  static readonly CONNECTION_ID: string = "figma";
  static readonly #TYPE_PREFIX: string = "figma";
  /** the Content Hub types of importable Figma nodes: components (and component sets) and instances */
  static readonly #ITEM_TYPES: Array<string> = ["figmaComponent", "figmaInstance"];
  static readonly BUTTON_ITEM_ID: string = "figmaMappingButton";

  #selectionExpression: ValueExpression = null;

  constructor(config: Config<FigmaToolbarPlugin> = null) {
    super(config);
  }

  override init(component: Component): void {
    super.init(component);
    const toolbar = as(component, ContentHubRepositoryToolbar);
    const selectedItems = toolbar.selectedItemsValueExpression;
    const selectedFolder = toolbar.selectedFolderValueExpression;
    this.#selectionExpression = ValueExpressionFactory.createFromFunction((): ContentHubObject => {
      const item = FigmaToolbarPlugin.#first(selectedItems ? selectedItems.getValue() : null);
      return item || FigmaToolbarPlugin.#first(selectedFolder ? selectedFolder.getValue() : null);
    });

    const button = new Button({
      itemId: FigmaToolbarPlugin.BUTTON_ITEM_ID,
      text: FigmaStudioPlugin_properties.FigmaMapping_button_text,
      tooltip: FigmaStudioPlugin_properties.FigmaMapping_button_tooltip,
      ui: ButtonSkin.TOOLBAR.getSkin(),
      hidden: true,
      handler: (): void => FigmaToolbarPlugin.openEditor(this.#selectedComponentId()),
    });
    const reload = toolbar.queryById(ContentHubRepositoryToolbar.RELOAD_ITEM_ID);
    const index = reload ? toolbar.items.indexOf(reload) + 1 : toolbar.items.getCount();
    toolbar.insert(index, button);

    const update = (): void => {
      button.setHidden(!FigmaToolbarPlugin.#isFigma(this.#selectionExpression.getValue()));
    };
    this.#selectionExpression.addChangeListener(update);
    update();
    toolbar.on("destroy", (): void => {
      this.#selectionExpression.removeChangeListener(update);
    });
  }

  /**
   * Opens the mapping editor tab (or activates it) and loads the component.
   */
  static openEditor(componentId: string): void {
    const workArea = as(editorContext._.getWorkArea(), WorkArea);
    const existing = as(Ext.getCmp(FigmaMappingEditor.ID), FigmaMappingEditor);
    if (existing) {
      workArea.setActiveTab(existing);
      existing.openComponent(componentId);
      return;
    }
    const tabType = workArea.getTabTypeById(FigmaMappingEditor.xtype);
    tabType.createTab(null, (tab: Panel): void => {
      const editor = as(tab, FigmaMappingEditor);
      workArea.addTab(tabType, editor);
      workArea.setActiveTab(editor);
      editor.openComponent(componentId);
    });
  }

  #selectedComponentId(): string {
    const selected = this.#selectionExpression.getValue();
    if (!selected || !is(selected, ContentHubObject)) {
      return null;
    }
    const type = selected.getContentHubType();
    return type && FigmaToolbarPlugin.#ITEM_TYPES.indexOf(type.name) >= 0 ? selected.getContentHubObjectId() : null;
  }

  static #first(value: any): ContentHubObject {
    const candidate = Array.isArray(value) ? value[0] : value;
    return candidate && is(candidate, ContentHubObject) ? candidate : null;
  }

  /**
   * A Figma connection is recognized by its connection id or by the Figma specific Content Hub types.
   */
  static #isFigma(object: ContentHubObject): boolean {
    if (!object) {
      return false;
    }
    const id = object.getContentHubObjectId();
    if (id && ContentHubUtil.parseConnectionId(id) === FigmaToolbarPlugin.CONNECTION_ID) {
      return true;
    }
    const type = object.getContentHubType();
    return !!(type && type.name && type.name.indexOf(FigmaToolbarPlugin.#TYPE_PREFIX) === 0);
  }
}

export default FigmaToolbarPlugin;
