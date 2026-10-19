package dev.secondsun.retro.util;

import java.nio.file.Path;

/**
 * ProjectService coordinates workspace source file loading and symbol extraction.
 *
 * <p>Extends {@link DefaultWorkspaceIndex} to provide the deep {@link WorkspaceIndex} seam while
 * preserving backwards compatibility for all existing callers.
 */
public class ProjectService extends DefaultWorkspaceIndex {

  public ProjectService(FileService fileService, SymbolService symbolService) {
    super(fileService, symbolService, null);
  }

  public ProjectService(FileService fileService, SymbolService symbolService, Path workspaceRoot) {
    super(fileService, symbolService, workspaceRoot);
  }
}
