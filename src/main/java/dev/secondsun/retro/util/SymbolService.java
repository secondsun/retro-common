package dev.secondsun.retro.util;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import dev.secondsun.retro.util.vo.TokenizedFile;

public class SymbolService {

    private static final String VARIABLE_DEFINITION = "variable.other.definition";

    public Map<String, Location> definitions = new HashMap<>();

    
    final CA65Scanner grammar = new CA65Scanner();
    public void addDefinition(String name, Location location) {
        definitions.put(name, location);
    }

    public Location getLocation(String name) {
        return definitions.get(name);
    }

    public void extractDefinitions(TokenizedFile file) {

        IntStream.range(0, file.lines()).forEach((idx) -> {

            String line = file.get(idx);
            var tokenized = grammar.tokenizeLine(line);
            // only one definition per line so find first is ok
            var foundLabelDef = tokenized.stream()
                    .filter(token -> token.getScopes().contains(VARIABLE_DEFINITION)).findFirst();

            foundLabelDef.ifPresent(
                    token -> {
                        var stringToken = line.substring(token.getStartIndex(), token.getEndIndex());
                        if (stringToken.endsWith(":")) {
                            // remove the colon
                            addDefinition(stringToken.replace(":", ""),
                                    new Location(fileName, idx, token.getStartIndex(), token.getEndIndex()));
                        } else if (stringToken.endsWith("=")) {
                            addDefinition(stringToken.split("=")[0].trim(),
                                    new Location(fileName, idx, token.getStartIndex(), token.getEndIndex()));
                        }
                    });

            // find structure
            {
                var split = line.split("(?i).*\\.struct");
                if (split.length > 1) {

                    var namePlusRight = split[1].trim();
                    var tok = grammar.tokenizeLine(namePlusRight).get(0);
                    var def = namePlusRight.subSequence(tok.getStartIndex(), tok.getEndIndex()).toString();
                    addDefinition(def,
                            new Location(fileName, idx, 0, line.length()));
                }
            } // find proc
            {
                var split = line.split("(?i).*\\.proc");
                if (split.length > 1) {

                    var namePlusRight = split[1].trim();
                    var tok = grammar.tokenizeLine(namePlusRight).get(0);
                    var def = namePlusRight.subSequence(tok.getStartIndex(), tok.getEndIndex()).toString();
                    addDefinition(def,
                            new Location(fileName, idx, 0, line.length()));
                }
            }
            {
                var split = line.split("(?i).*\\.enum");
                if (split.length > 1) {

                    var namePlusRight = split[1].trim();
                    var tok = grammar.tokenizeLine(namePlusRight).get(0);
                    var def = namePlusRight.subSequence(tok.getStartIndex(), tok.getEndIndex()).toString();
                    addDefinition(def,
                            new Location(fileName, idx, 0, line.length()));
                }
            }
            // find macro
            {
                var macro = line.split("(?i).*\\.macro");
                if (macro.length > 1) {

                    var namePlusRight = macro[1].trim();
                    var tok = grammar.tokenizeLine(namePlusRight).get(0);
                    var def = namePlusRight.subSequence(tok.getStartIndex(), tok.getEndIndex()).toString();
                    addDefinition(def,
                            new Location(fileName, idx, 0, line.length()));
                }
            }

            // find functions. Fuunctions are a summersism
            {
                var macro = line.split("(?i).*function");
                if (macro.length > 1) {
                    var namePlusRight = macro[1].trim();
                    if (!namePlusRight.isBlank()) {
                        var tok = grammar.tokenizeLine(namePlusRight).get(0);
                        var def = namePlusRight.subSequence(tok.getStartIndex(), tok.getEndIndex()).toString();

                        addDefinition(def,
                                new Location(fileName, idx, 0, line.length()));
                    }
                }
            }
        });

    }

}
