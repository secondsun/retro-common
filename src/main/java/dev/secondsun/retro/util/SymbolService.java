package dev.secondsun.retro.util;

import java.util.*;
import java.util.stream.IntStream;

import dev.secondsun.retro.util.vo.TokenizedFile;

public class SymbolService {

    private static final String VARIABLE_DEFINITION = "variable.other.definition";

    public Map<String, Location> definitions = new HashMap<>();

    

    public void addDefinition(String name, Location location) {
        definitions.put(name, location);
    }

    public Location getLocation(String name) {
        return definitions.get(name);
    }

    public void extractDefinitions(TokenizedFile file) {
        CA65Scanner grammar = new CA65Scanner();
        IntStream.range(0, file.textLines()).forEach((idx) -> {

            if (file.getLine(idx) == null) {
                return;
            }
            var line = file.getLineText(idx);
            var tokenized = file.getLineTokens(idx);

            // only one definition per line so find first is ok
            var foundLabelDef = tokenized.stream()
                    .filter(token -> token.type == TokenType.TOK_IDENT).findFirst();

            foundLabelDef.ifPresent(
                    token -> {
                        var stringToken = token.text();
                            addDefinition(stringToken,
                                    new Location(file.uri(), idx, token.getStartIndex(), token.getEndIndex()));

                    });

            //Add defs for structs,macros, procs, and enums
            if (tokenized.size() > 1) {
                var defineDirectives = List.of(TokenType.TOK_STRUCT, TokenType.TOK_ENUM, TokenType.TOK_PROC, TokenType.TOK_MACRO );
                if (defineDirectives.contains(tokenized.get(0).type)) {
                    var def = tokenized.get(1).text();
                    addDefinition(def,
                            new Location(file.uri(), idx, 0, tokenized.get(1).endIndex));
                }


            }


            // find functions. Fuunctions are a Summersism


            if (tokenized.size() == 2 && Objects.equals(tokenized.get(0).text(), "function")) {
                    var def = tokenized.get(1).text();
                    addDefinition(def,
                            new Location(file.uri(), idx, 0, line.length()));
            }


        });

    }

}
