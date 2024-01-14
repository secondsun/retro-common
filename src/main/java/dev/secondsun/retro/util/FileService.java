package dev.secondsun.retro.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import dev.secondsun.retro.util.vo.TokenizedFile;

public class FileService {

    private Set<URI> repositories = new HashSet<>();
    private static final Logger LOG = Logger.getAnonymousLogger();
    
    
    public FileService addSearchPath(URI repoURI) {
        if (Path.of(repoURI).toFile().exists()) {

            repositories.add(Util.normalize(repoURI));
        } else {
            System.out.println(repoURI + " does not exist.");
        }
        return this;
    }


    public TokenizedFile readLines(URI fileUri) throws IOException {

        var possibleFile = new File(fileUri.getRawSchemeSpecificPart());
        if (possibleFile.exists()) {
            try (var stream = new FileInputStream(possibleFile)) {
                var fileByLinesAsString = Util.toString(stream);
                var tokenized =  new CA65Scanner().tokenize(fileByLinesAsString);
                tokenized.uri = possibleFile.toURI();
                return tokenized;
            }
        }

        final var fileUri2 = fileUri;
        final var fileToRead = repositories.stream()
        .map( it -> Path.of(it).resolve(fileUri2.toString()).toFile())
        .filter(File::exists)
        .findFirst();

        if (fileToRead.isPresent()) {
            var file  = fileToRead.get();
            try (var stream = new FileInputStream(file)) {
                var fileByLinesAsString = Util.toString(stream);
                fileByLinesAsString = Util.removeComments(fileByLinesAsString);
                var tokenized =  new CA65Scanner().tokenize(fileByLinesAsString);
                tokenized.uri = file.toURI();
                return tokenized;
            }
        }
        
        return TokenizedFile.EMPTY;
        
    }

    public List<URI> find(URI file, URI... optionalSearchPaths) {
        
        //We're allocating a copy of the localRepos and adding optionalSearchPaths
        if (optionalSearchPaths != null && optionalSearchPaths.length >0){

            
        }

        var localRepos = new ArrayList<>(this.repositories);
        if (optionalSearchPaths != null && optionalSearchPaths.length >0) {
            localRepos.addAll(List.of(optionalSearchPaths).stream().map(Util::normalize).toList());
            
        }
        
        
        List<URI> list = new ArrayList<>();

        localRepos.forEach(repo->{
            var pathForRepo = Path.of(repo).resolve(file.toString()).toFile();
            
            if (pathForRepo.exists()) {
                list.add(pathForRepo.toURI());
            }
        });
        return list;
    }

    
    
}
