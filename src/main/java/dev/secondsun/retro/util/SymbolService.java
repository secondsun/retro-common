package dev.secondsun.retro.util;

import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SymbolService {

    public Map<String, Location> definitions = new HashMap<>();
    public Map<String, String> documentations = new HashMap<>();
    public Map<String, String> rawDocumentations = new HashMap<>();

    public void addDefinition(String name, Location location) {
        definitions.put(name, location);
    }

    public Location getLocation(String name) {
        return definitions.get(name);
    }

    public void addDocumentation(String name, String docs) {
        documentations.put(name, docs);
    }

    public String getDocumentation(String name) {
        return documentations.get(name);
    }

    public String getRawDocumentation(String name) {
        return rawDocumentations.get(name);
    }

    public boolean hasDocumentation(String name) {
        return documentations.containsKey(name);
    }

    public void extractDefinitions(TokenizedFile file) {
        IntStream.range(0, file.textLines()).forEach((idx) -> {
            if (file.getLine(idx) == null) {
                return;
            }
            var line = file.getLineText(idx);
            var tokenized = file.getLineTokens(idx);

            // only one definition per line so find first is ok
            if (tokenized.size() > 1) {
                var foundLabelDef = Optional.<Token>empty();
                if (tokenized.get(1).type == TokenType.TOK_COLON) {
                    foundLabelDef = Optional.of(tokenized.get(0));
                } else if (tokenized.get(1).type == TokenType.TOK_EQ) {
                    foundLabelDef = Optional.of(tokenized.get(0));
                }
                foundLabelDef.ifPresent(token -> {
                    var stringToken = token.text();
                    addDefinition(
                            stringToken, new Location(file.uri(), idx, token.getStartIndex(), token.getEndIndex()));
                    extractDoc(file, idx, stringToken);
                });
            }

            // Add defs for structs,macros, procs, and enums
            if (tokenized.size() > 1) {
                var defineDirectives =
                        List.of(TokenType.TOK_STRUCT, TokenType.TOK_ENUM, TokenType.TOK_PROC, TokenType.TOK_MACRO);
                if (defineDirectives.contains(tokenized.get(0).type)) {
                    var def = tokenized.get(1).text();
                    addDefinition(def, new Location(file.uri(), idx, 0, tokenized.get(1).endIndex));
                    extractDoc(file, idx, def);
                }
            }

            // find functions. Functions are a Summersism

            if (tokenized.size() >= 2 && Objects.equals(tokenized.get(0).text(), "function")) {
                var def = tokenized.get(1).text();
                addDefinition(def, new Location(file.uri(), idx, 0, line.length()));
                extractDoc(file, idx, def);
            }
        });
    }

    private void extractDoc(TokenizedFile file, int defLineIdx, String symbolName) {
        List<String> commentBlock = new ArrayList<>();
        for (int i = defLineIdx - 1; i >= 0; i--) {
            String rawLine = file.getRawLine(i);
            if (rawLine == null) {
                break;
            }
            String trimmed = rawLine.trim();
            if (trimmed.startsWith(";")) {
                commentBlock.add(0, rawLine);
            } else {
                break;
            }
        }

        if (!commentBlock.isEmpty()) {
            String rawDoc = String.join("\n", commentBlock);
            String cleanDoc = commentBlock.stream()
                    .map(l -> l.trim().replaceFirst("^;+\\s?", ""))
                    .collect(Collectors.joining("\n"));
            addDocumentation(symbolName, cleanDoc);
            rawDocumentations.put(symbolName, rawDoc);
        }
    }
}

