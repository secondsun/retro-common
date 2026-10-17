package dev.secondsun.retro.util;

import dev.secondsun.retro.util.vo.TokenizedFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

public class FileService {

    private Set<URI> repositories = new HashSet<>();
    private static final Logger LOG = Logger.getLogger(FileService.class.getName());

    public FileService addSearchPath(URI repoURI) {
        if (Path.of(repoURI).toFile().exists()) {

            repositories.add(Util.normalize(repoURI));
        } else {
            LOG.fine(() -> repoURI + " does not exist.");
        }
        return this;
    }

    public TokenizedFile readLines(URI fileUri) throws IOException {
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
                var fileByLinesAsString = Util.toString(stream);
                var tokenized = new CA65Scanner().tokenize(fileByLinesAsString);
                tokenized.uri = possibleFile.toURI();
                return tokenized;
            }
        }

        final var fileUri2 = fileUri;
        final var fileToRead = repositories.stream()
                .map(it -> {
                    try {
                        return Path.of(it).resolve(fileUri2.toString()).toFile();
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(it -> it != null && it.exists() && it.isFile())
                .findFirst();

        if (fileToRead.isPresent()) {
            var file = fileToRead.get();
            try (var stream = new FileInputStream(file)) {
                var fileByLinesAsString = Util.toString(stream);
                var tokenized = new CA65Scanner().tokenize(fileByLinesAsString);
                tokenized.uri = file.toURI();
                return tokenized;
            }
        }

        return TokenizedFile.EMPTY;
    }

    public List<URI> find(URI file, URI... optionalSearchPaths) {

        var localRepos = new ArrayList<>(this.repositories);
        // We're allocating a copy of the localRepos and adding optionalSearchPaths

        if (optionalSearchPaths != null && optionalSearchPaths.length > 0) {
            localRepos.addAll(
                    List.of(optionalSearchPaths).stream().map(Util::normalize).toList());
        }

        List<URI> list = new ArrayList<>();

        localRepos.forEach(repo -> {
            var pathForRepo = Path.of(repo).resolve(file.toString()).toFile();

            if (pathForRepo.exists()) {
                list.add(pathForRepo.toURI());
            }
        });
        return list;
    }
}
