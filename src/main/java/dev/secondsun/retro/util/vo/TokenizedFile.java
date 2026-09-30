package dev.secondsun.retro.util.vo;

import dev.secondsun.retro.util.Token;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public class TokenizedFile {
    public static final TokenizedFile EMPTY = null;
    private Map<Integer, Tokens> fileLines = new HashMap<>();
    private Integer lineCount = 0;
    public URI uri;
    private List<String> rawLines = new ArrayList<>();

    public int textLines() {
        return Math.max(lineCount + 1, rawLines.size());
    }

    public void setRawLines(List<String> rawLines) {
        this.rawLines = rawLines;
        if (!rawLines.isEmpty() && rawLines.size() - 1 > lineCount) {
            lineCount = rawLines.size() - 1;
        }
    }

    public List<String> getRawLines() {
        return rawLines;
    }

    public String getRawLine(int idx) {
        if (idx >= 0 && idx < rawLines.size()) {
            return rawLines.get(idx);
        }
        var thing = fileLines.get(idx);
        return thing != null ? thing.line() : "";
    }

    public void addLine(String line, int index, List<Token> tokens) {
        fileLines.put(index, new Tokens(line, tokens));
        if (index > lineCount) {
            lineCount = index;
        }
    }

    public String getLineText(int idx) {
        var thing = fileLines.get(idx);
        if (thing == null) {
            return "";
        }
        return fileLines.get(idx).line();
    }

    public List<Token> getLineTokens(int idx) {
        var thing = fileLines.get(idx);
        if (thing == null) {
            return List.of();
        }
        return fileLines.get(idx).tokens();
    }

    public URI uri() {
        return uri;
    }

    public void forEach(BiConsumer<Integer, Tokens> object) {
        fileLines.forEach(object);
    }

    public Tokens getLine(int idx) {
        return fileLines.get(idx);
    }
}
