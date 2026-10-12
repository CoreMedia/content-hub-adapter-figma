const { jangarooConfig } = require("@jangaroo/core");

module.exports = jangarooConfig({
  type: "code",
  sencha: {
    name: "com.coremedia.blueprint__content-hub-figma-studio",
    namespace: "com.coremedia.blueprint.studio.contenthub.figma",
    css: [
      {
        path: "resources/css/figma-mapping.css",
      },
    ],
    studioPlugins: [
      {
        mainClass: "com.coremedia.blueprint.studio.contenthub.figma.FigmaStudioPlugin",
        name: "Figma Content Hub",
      },
    ],
  },
});
