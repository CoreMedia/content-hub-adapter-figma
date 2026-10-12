package com.coremedia.blueprint.contenthub.adapters.figma;

import edu.umd.cs.findbugs.annotations.Nullable;

import java.util.List;

/**
 * Connection settings of a Figma Content Hub connection, read from the
 * <code>settings</code> substruct of a connection in the Content Hub settings.
 */
public interface FigmaContentHubSettings {

  /**
   * The name of the root folder shown in the Studio library. Defaults to "Figma".
   */
  @Nullable
  String getDisplayName();

  /**
   * A Figma personal access token or OAuth token. If not set, the Spring property
   * <code>contenthub.figma.access-token</code> is used.
   */
  @Nullable
  String getAccessToken();

  /**
   * IDs of the Figma teams whose folders are browsable. The team ID is the number in
   * a team URL: <code>https://www.figma.com/files/team/&lt;teamId&gt;/...</code>
   */
  @Nullable
  List<String> getTeamIds();

  /**
   * IDs of single Figma folders that are shown directly below the root folder.
   */
  @Nullable
  List<String> getFolderIds();

  /**
   * Keys of single Figma files that are shown directly below the root folder. The key is
   * the path segment after <code>/design/</code> or <code>/file/</code> in a file URL.
   */
  @Nullable
  List<String> getFileKeys();

  /**
   * The format of imported renditions: <code>png</code> (default) or <code>jpg</code>.
   */
  @Nullable
  String getImageFormat();

  /**
   * The scale factor (0.01 - 4) of imported renditions. Defaults to 1.
   */
  @Nullable
  String getImageScale();
}
