package dev.secondsun.retro.util;

/** Kind of filesystem event reported to {@link WorkspaceIndex#notifyDiskEvent}. */
public enum DiskEventKind {
  CREATED,
  MODIFIED,
  DELETED
}
