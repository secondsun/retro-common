package dev.secondsun.retro.util.vo;

import java.net.URI;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import dev.secondsun.retro.util.Token;

public class TokenizedFile {
    public static final TokenizedFile EMPTY = null;
    private Map<Integer, Tokens> fileLines = new IdentityHashMap<>();
    private Integer lineCount =0;
    public URI uri;

    public int textLines() {
        return lineCount+1;//lineCount is ACTUALLY the max index.
    }

    public void addLine(String line,int index,  List<Token> tokens) {
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
