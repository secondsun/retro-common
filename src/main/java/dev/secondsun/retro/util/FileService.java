package dev.secondsun.retro.util;

import dev.secondsun.retro.util.vo.TokenizedFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** Package-private disk reader and search-path resolver backing {@link DefaultWorkspaceIndex}. */
final class FileService {

  private static final Logger LOG = Logger.getLogger(FileService.class.getName());

  private final Set<URI> repositories = ConcurrentHashMap.newKeySet();

  FileService addSearchPath(URI repoUri) {
    if (repoUri == null) {
      return this;
    }
    try {
      if (Path.of(repoUri).toFile().exists()) {
        repositories.add(Util.normalize(repoUri));
      } else {
        LOG.fine(() -> repoUri + " does not exist.");
      }
    } catch (Exception e) {
      LOG.fine(() -> "Ignoring non-filesystem search path: " + repoUri);
    }
    return this;
  }

  Set<URI> searchPathsSnapshot() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(repositories));
  }

  TokenizedFile readLines(URI fileUri) throws IOException {
    if (fileUri == null) {
      return TokenizedFile.EMPTY;
    }

    File possibleFile = null;
    try {
      possibleFile = Path.of(fileUri).toFile();
    } catch (Exception e) {
      var raw = fileUri.getRawSchemeSpecificPart();
      if (raw != null) {
        possibleFile = new File(raw);
      }
    }

    if (possibleFile != null && possibleFile.exists() && possibleFile.isFile()) {
      try (var stream = new FileInputStream(possibleFile)) {
        var content = Util.toString(stream);
        URI canonical = Util.normalize(possibleFile.toURI());
        return new CA65Scanner().tokenize(content, canonical);
      }
    }

    final URI target = fileUri;
    var fileToRead =
        repositories.stream()
            .map(
                repo -> {
                  try {
                    return Path.of(repo).resolve(target.toString()).toFile();
                  } catch (Exception e) {
                    return null;
                  }
                })
            .filter(it -> it != null && it.exists() && it.isFile())
            .findFirst();

    if (fileToRead.isPresent()) {
      var file = fileToRead.get();
      try (var stream = new FileInputStream(file)) {
        var content = Util.toString(stream);
        URI canonical = Util.normalize(file.toURI());
        return new CA65Scanner().tokenize(content, canonical);
      }
    }

    return TokenizedFile.EMPTY;
  }

  List<URI> find(URI file, URI... optionalSearchPaths) {
    var localRepos = new ArrayList<>(this.repositories);
    if (optionalSearchPaths != null && optionalSearchPaths.length > 0) {
      localRepos.addAll(List.of(optionalSearchPaths).stream().map(Util::normalize).toList());
    }

    List<URI> list = new ArrayList<>();
    for (URI repo : localRepos) {
      try {
        var pathForRepo = Path.of(repo).resolve(file.toString()).toFile();
        if (pathForRepo.exists()) {
          list.add(Util.normalize(pathForRepo.toURI()));
        }
      } catch (Exception ignored) {
      }
    }
    return list;
  }
}
