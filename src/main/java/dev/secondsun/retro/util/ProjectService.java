package dev.secondsun.retro.util;

import static dev.secondsun.retro.util.Util.normalize;

import dev.secondsun.retro.util.vo.TokenizedFile;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This service is used for initializing the root directory.
 *
 * <p>Initialization means loading the directory, importing all files into the fileservice and
 * symbols into the symbol service. It works on the project level
 */
public class ProjectService {

  private static final Logger LOG = Logger.getLogger(ProjectService.class.getName());

  private FileService fileService;
  private SymbolService symbolService;
  Map<URI, TokenizedFile> files = new ConcurrentHashMap<>();
  private final java.util.Set<URI> visitedDirs = ConcurrentHashMap.newKeySet();

  public ProjectService(FileService fileService, SymbolService symbolService) {
    this.fileService = fileService;
    this.symbolService = symbolService;
  }

  /**
   * Loads and begins watching all source (*.s, *.i, and *.sgs) files in dir.
   *
   * @param directory URI of path to directory
   */
  public synchronized void includeDir(URI directory) {
    if (directory == null) {
      return;
    }

    directory = normalize(directory);
    this.fileService.addSearchPath(directory);
    _includeDir(directory);
  }

  private void _includeDir(URI directory) {
    if (!visitedDirs.add(directory)) {
      return;
    }
    // Include everything in workspace root
    var workspacePath = Path.of(directory);

    var thing =
        (workspacePath
            .toFile()
            .listFiles(
                new FileFilter() {

                  @Override
                  public boolean accept(File pathname) {
                    return pathname.getName().endsWith(".s")
                        || pathname.getName().endsWith(".sgs")
                        || pathname.getName().endsWith(".i")
                        || pathname.getName().endsWith(".inc");
                  }
                }));

    if (thing == null) {
      return;
    }

    var includeDirsToScan = new ArrayList<URI>();

    Arrays.stream(thing)
        .forEach(
            (file) -> {
              var fileUri = normalize(file.toPath().toUri());

              if (!files.containsKey(fileUri)) {
                try {
                  var tokenized = fileService.readLines(fileUri);
                  if (tokenized != null && tokenized != TokenizedFile.EMPTY) {
                    files.put(fileUri, tokenized);
                    symbolService.extractDefinitions(tokenized);

                    tokenized.forEach(
                        (line, tokens) -> {
                          if (Util.isIncludeDirective(tokens.line().trim())
                              && tokens.tokens().size() > 1) {
                            var includeTarget =
                                tokens.tokens().get(1).text().replace("\"", "").trim();
                            for (var foundUri :
                                fileService.find(URI.create("./" + includeTarget))) {
                              var f = new File(foundUri);
                              if (f.exists() && f.getParentFile() != null) {
                                includeDirsToScan.add(f.getParentFile().getAbsoluteFile().toURI());
                              }
                            }
                          }
                        });
                  }
                } catch (IOException e) {
                  LOG.log(Level.WARNING, "Failed to read lines for " + fileUri, e);
                }
              }
            });

    for (var incDir : includeDirsToScan) {
      includeDir(incDir);
    }

    var subDirs = workspacePath.toFile().listFiles(File::isDirectory);
    if (subDirs != null) {
      Arrays.stream(subDirs)
          .filter(
              dir ->
                  !dir.getName().startsWith(".")
                      && !dir.getName().equals("node_modules")
                      && !dir.getName().equals("target")
                      && !dir.getName().equals("dist")
                      && !dir.getName().equals("out")
                      && !dir.getName().equals("build"))
          .map(File::toURI)
          .forEach(this::_includeDir);
    }
  }

  public synchronized void refreshFileContents(URI uri) {
    if (uri == null) {
      return;
    }
    var fileUri = normalize(uri);
    try {
      var tokenized = fileService.readLines(fileUri);
      if (tokenized != null && tokenized != TokenizedFile.EMPTY) {
        files.put(fileUri, tokenized);
        symbolService.extractDefinitions(tokenized);
      }
    } catch (IOException e) {
      LOG.log(Level.SEVERE, e.getMessage(), e);
    }
  }

  public synchronized void updateFileContents(URI uri, String text) {
    if (uri == null || text == null) {
      return;
    }
    var fileUri = normalize(uri);
    try {
      var tokenized = new CA65Scanner().tokenize(text);
      tokenized.uri = fileUri;
      files.put(fileUri, tokenized);
      symbolService.extractDefinitions(tokenized);
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Failed to tokenize and update file contents for " + fileUri, e);
    }
  }

  public synchronized void removeFile(URI uri) {
    if (uri == null) {
      return;
    }
    var fileUri = normalize(uri);
    files.remove(fileUri);
    symbolService.removeFile(fileUri);
  }

  public FileService getFileService() {
    return fileService;
  }

  public SymbolService getSymbolService() {
    return symbolService;
  }

  public TokenizedFile getFileContents(URI uri) {
    var normalizedUri = normalize(uri);
    var lines = files.get(normalizedUri);
    if (lines == null) {
      LOG.fine(
          () -> normalizedUri.getRawSchemeSpecificPart() + " could not be found in read files.");
      return TokenizedFile.EMPTY;
    }
    return lines;
  }

  private boolean isNullOrEmpty(List<String> lines) {
    return lines == null || lines.isEmpty();
  }
}
