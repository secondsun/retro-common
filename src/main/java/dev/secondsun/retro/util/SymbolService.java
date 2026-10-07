package dev.secondsun.retro.util;

import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.Scope;
import dev.secondsun.retro.util.vo.ScopeType;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service managing symbol definitions and documentation.
 * Provides context-aware lookups supporting lexical scopes (such as functions, procedures, structs, and blocks).
 */
public class SymbolService {

    public Map<String, Location> definitions = new HashMap<>();
    public Map<String, String> documentations = new HashMap<>();
    public Map<String, String> rawDocumentations = new HashMap<>();

    private final Scope globalScope = new Scope("<global>", ScopeType.GLOBAL, null, 0, Integer.MAX_VALUE, null);
    private final Map<URI, Scope> fileScopes = new HashMap<>();
    private final Map<String, List<Location>> allDefinitionsByName = new HashMap<>();

    public void addDefinition(String name, Location location) {
        if (name == null || location == null) {
            return;
        }
        recordDefinition(name, location);
        globalScope.addDefinition(name, location);
        if (location.filename() != null) {
            Scope fileScope = fileScopes.computeIfAbsent(
                    location.filename(),
                    uri -> new Scope(uri.toString(), ScopeType.FILE, uri, 0, Integer.MAX_VALUE, globalScope));
            fileScope.addDefinition(name, location);
        }
    }

    public Location getLocation(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Location resolved = globalScope.resolve(name);
        if (resolved != null) {
            return resolved;
        }
        return definitions.get(name);
    }

    /**
     * Resolves a symbol definition within the context of a specific file URI and line number.
     * Searches from the innermost lexical scope outward to parent scopes, falling back to global definitions.
     */
    public Location getLocation(String name, URI uri, int line) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Scope scope = findScopeAt(uri, line);
        if (scope != null) {
            Location resolved = scope.resolve(name);
            if (resolved != null) {
                return resolved;
            }
        }
        return getLocation(name);
    }

    /**
     * Resolves a symbol definition within the context of a given location (e.g. cursor or reference site).
     */
    public Location getLocation(String name, Location context) {
        if (context == null) {
            return getLocation(name);
        }
        return getLocation(name, context.filename(), context.line());
    }

    /**
     * Resolves a symbol definition for a token at a file URI.
     */
    public Location getLocation(Token token, URI uri) {
        if (token == null) {
            return null;
        }
        return getLocation(token.text(), uri, token.lineNumber);
    }

    /**
     * Resolves a symbol definition for a token within a tokenized file.
     */
    public Location getLocation(Token token, TokenizedFile file) {
        if (token == null) {
            return null;
        }
        URI uri = file != null ? file.uri() : null;
        return getLocation(token.text(), uri, token.lineNumber);
    }

    /**
     * Returns all known definition locations for a symbol name across all scopes.
     */
    public List<Location> getLocations(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        if (name.contains("::")) {
            Location loc = globalScope.resolve(name);
            return loc != null ? List.of(loc) : List.of();
        }
        List<Location> list = allDefinitionsByName.get(name);
        if (list != null && !list.isEmpty()) {
            return List.copyOf(list);
        }
        Location def = definitions.get(name);
        return def != null ? List.of(def) : List.of();
    }

    /**
     * Returns definition locations for a symbol name, prioritizing the lexical scope at the given context.
     */
    public List<Location> getLocations(String name, URI uri, int line) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        Scope scope = findScopeAt(uri, line);
        if (scope != null) {
            Location resolved = scope.resolve(name);
            if (resolved != null) {
                return List.of(resolved);
            }
        }
        return getLocations(name);
    }

    /**
     * Returns definition locations for a symbol name prioritizing the given context location.
     */
    public List<Location> getLocations(String name, Location context) {
        if (context == null) {
            return getLocations(name);
        }
        return getLocations(name, context.filename(), context.line());
    }

    /**
     * Returns definition locations for a token in a file URI.
     */
    public List<Location> getLocations(Token token, URI uri) {
        if (token == null) {
            return List.of();
        }
        return getLocations(token.text(), uri, token.lineNumber);
    }

    /**
     * Returns definition locations for a token in a tokenized file.
     */
    public List<Location> getLocations(Token token, TokenizedFile file) {
        if (token == null) {
            return List.of();
        }
        URI uri = file != null ? file.uri() : null;
        return getLocations(token.text(), uri, token.lineNumber);
    }

    public void addDocumentation(String name, String docs) {
        if (name != null && docs != null) {
            documentations.put(name, docs);
            globalScope.addDocumentation(name, docs);
        }
    }

    public String getDocumentation(String name) {
        if (name == null) {
            return null;
        }
        String doc = globalScope.resolveDocumentation(name);
        if (doc != null) {
            return doc;
        }
        return documentations.get(name);
    }

    /**
     * Retrieves documentation for a symbol name resolved within the context of a file URI and line.
     */
    public String getDocumentation(String name, URI uri, int line) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Scope scope = findScopeAt(uri, line);
        if (scope != null) {
            String doc = scope.resolveDocumentation(name);
            if (doc != null) {
                return doc;
            }
        }
        return getDocumentation(name);
    }

    /**
     * Retrieves documentation for a symbol name resolved within the given context location.
     */
    public String getDocumentation(String name, Location context) {
        if (context == null) {
            return getDocumentation(name);
        }
        return getDocumentation(name, context.filename(), context.line());
    }

    public String getRawDocumentation(String name) {
        if (name == null) {
            return null;
        }
        String doc = globalScope.resolveRawDocumentation(name);
        if (doc != null) {
            return doc;
        }
        return rawDocumentations.get(name);
    }

    /**
     * Retrieves raw documentation for a symbol name resolved within the context of a file URI and line.
     */
    public String getRawDocumentation(String name, URI uri, int line) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Scope scope = findScopeAt(uri, line);
        if (scope != null) {
            String doc = scope.resolveRawDocumentation(name);
            if (doc != null) {
                return doc;
            }
        }
        return getRawDocumentation(name);
    }

    /**
     * Retrieves raw documentation for a symbol name resolved within the given context location.
     */
    public String getRawDocumentation(String name, Location context) {
        if (context == null) {
            return getRawDocumentation(name);
        }
        return getRawDocumentation(name, context.filename(), context.line());
    }

    public boolean hasDocumentation(String name) {
        return getDocumentation(name) != null;
    }

    /**
     * Checks if documentation exists for a symbol in the given file URI and line context.
     */
    public boolean hasDocumentation(String name, URI uri, int line) {
        return getDocumentation(name, uri, line) != null;
    }

    /**
     * Checks if documentation exists for a symbol in the given context location.
     */
    public boolean hasDocumentation(String name, Location context) {
        return getDocumentation(name, context) != null;
    }

    public Scope getGlobalScope() {
        return globalScope;
    }

    public Scope getFileScope(URI uri) {
        return uri != null ? fileScopes.get(uri) : null;
    }

    /**
     * Finds the innermost scope matching the given URI and line number.
     */
    public Optional<Scope> getScopeAt(URI uri, int line) {
        return Optional.ofNullable(findScopeAt(uri, line));
    }

    /**
     * Finds the innermost scope matching the given context location.
     */
    public Optional<Scope> getScopeAt(Location context) {
        if (context == null) {
            return Optional.empty();
        }
        return getScopeAt(context.filename(), context.line());
    }

    /**
     * Returns all scopes defined within a given file.
     */
    public List<Scope> getScopes(URI uri) {
        Scope fileScope = getFileScope(uri);
        if (fileScope == null) {
            return List.of();
        }
        List<Scope> all = new ArrayList<>();
        collectScopes(fileScope, all);
        return List.copyOf(all);
    }

    /**
     * Returns all scopes in the project.
     */
    public List<Scope> getAllScopes() {
        List<Scope> all = new ArrayList<>();
        collectScopes(globalScope, all);
        return List.copyOf(all);
    }

    /**
     * Removes all definitions and scopes associated with a specific file URI.
     */
    public void removeFile(URI uri) {
        if (uri == null) {
            return;
        }
        Scope fileScope = fileScopes.remove(uri);
        if (fileScope != null) {
            globalScope.removeChild(fileScope);
        }
        definitions
                .entrySet()
                .removeIf(e -> e.getValue() != null && uri.equals(e.getValue().filename()));
        for (var list : allDefinitionsByName.values()) {
            list.removeIf(loc -> loc != null && uri.equals(loc.filename()));
        }
        allDefinitionsByName.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public void extractDefinitions(TokenizedFile file) {
        if (file == null) {
            return;
        }

        URI fileUri = file.uri();
        if (fileUri != null) {
            removeFile(fileUri);
        }

        Scope fileScope = new Scope(
                fileUri != null ? fileUri.toString() : "<snippet>",
                ScopeType.FILE,
                fileUri,
                0,
                file.textLines(),
                globalScope);
        if (fileUri != null) {
            fileScopes.put(fileUri, fileScope);
        }

        Deque<Scope> scopeStack = new ArrayDeque<>();
        scopeStack.push(fileScope);

        for (int idx = 0; idx < file.textLines(); idx++) {
            if (file.getLine(idx) == null) {
                continue;
            }
            var line = file.getLineText(idx);
            var tokenized = file.getLineTokens(idx);
            if (tokenized == null || tokenized.isEmpty()) {
                continue;
            }

            Token first = tokenized.get(0);

            // Scope closures
            if (FunctionSyntaxHelper.isEndFunction(tokenized)) {
                closeScope(scopeStack, ScopeType.FUNCTION, idx);
                continue;
            }
            if (first.type == TokenType.TOK_ENDPROC || ".endproc".equalsIgnoreCase(first.text())) {
                closeScope(scopeStack, ScopeType.PROC, idx);
                continue;
            }
            if (first.type == TokenType.TOK_ENDSCOPE || ".endscope".equalsIgnoreCase(first.text())) {
                closeScope(scopeStack, ScopeType.SCOPE, idx);
                continue;
            }
            if (first.type == TokenType.TOK_ENDSTRUCT || ".endstruct".equalsIgnoreCase(first.text())) {
                closeScope(scopeStack, ScopeType.STRUCT, idx);
                continue;
            }
            if (first.type == TokenType.TOK_ENDENUM || ".endenum".equalsIgnoreCase(first.text())) {
                closeScope(scopeStack, ScopeType.ENUM, idx);
                continue;
            }
            if (first.type == TokenType.TOK_ENDMACRO || ".endmacro".equalsIgnoreCase(first.text())) {
                closeScope(scopeStack, ScopeType.MACRO, idx);
                continue;
            }

            // Scope openers
            if (FunctionSyntaxHelper.isFunction(tokenized)) {
                var fn = FunctionSyntaxHelper.parseFunction(tokenized);
                var fnName = fn.name();
                if (!fnName.isEmpty()) {
                    var loc = new Location(fileUri, idx, 0, line.length());
                    scopeStack.peek().addDefinition(fnName, loc);
                    globalScope.addDefinition(fnName, loc);
                    recordDefinition(fnName, loc);
                    extractDoc(file, idx, fnName, scopeStack.peek());

                    var fnScope = new Scope(fnName, ScopeType.FUNCTION, fileUri, idx, scopeStack.peek());
                    scopeStack.push(fnScope);

                    for (var p : fn.parameters()) {
                        var pLoc = new Location(fileUri, idx, p.getStartIndex(), p.getEndIndex());
                        fnScope.addDefinition(p.text(), pLoc);
                        recordDefinition(fnName + "::" + p.text(), pLoc);
                    }
                    if (fn.returnVariable().isPresent()) {
                        var ret = fn.returnVariable().get();
                        if (!isRegister(ret)) {
                            var rLoc = new Location(fileUri, idx, ret.getStartIndex(), ret.getEndIndex());
                            fnScope.addDefinition(ret.text(), rLoc);
                            recordDefinition(fnName + "::" + ret.text(), rLoc);
                        }
                    }
                }
                continue;
            }

            if (tokenized.size() > 1 && (first.type == TokenType.TOK_PROC || ".proc".equalsIgnoreCase(first.text()))) {
                var procName = tokenized.get(1).text();
                var loc = new Location(fileUri, idx, 0, tokenized.get(1).endIndex);
                scopeStack.peek().addDefinition(procName, loc);
                globalScope.addDefinition(procName, loc);
                recordDefinition(procName, loc);
                extractDoc(file, idx, procName, scopeStack.peek());

                var procScope = new Scope(procName, ScopeType.PROC, fileUri, idx, scopeStack.peek());
                scopeStack.push(procScope);
                continue;
            }

            if (tokenized.size() > 1
                    && (first.type == TokenType.TOK_STRUCT || ".struct".equalsIgnoreCase(first.text()))) {
                var structName = tokenized.get(1).text();
                var loc = new Location(fileUri, idx, 0, tokenized.get(1).endIndex);
                scopeStack.peek().addDefinition(structName, loc);
                globalScope.addDefinition(structName, loc);
                recordDefinition(structName, loc);
                extractDoc(file, idx, structName, scopeStack.peek());

                var structScope = new Scope(structName, ScopeType.STRUCT, fileUri, idx, scopeStack.peek());
                scopeStack.push(structScope);
                continue;
            }

            if (tokenized.size() > 1 && (first.type == TokenType.TOK_ENUM || ".enum".equalsIgnoreCase(first.text()))) {
                var enumName = tokenized.get(1).text();
                var loc = new Location(fileUri, idx, 0, tokenized.get(1).endIndex);
                scopeStack.peek().addDefinition(enumName, loc);
                globalScope.addDefinition(enumName, loc);
                recordDefinition(enumName, loc);
                extractDoc(file, idx, enumName, scopeStack.peek());

                var enumScope = new Scope(enumName, ScopeType.ENUM, fileUri, idx, scopeStack.peek());
                scopeStack.push(enumScope);
                continue;
            }

            if (tokenized.size() > 1
                    && (first.type == TokenType.TOK_MACRO || ".macro".equalsIgnoreCase(first.text()))) {
                var macroName = tokenized.get(1).text();
                var loc = new Location(fileUri, idx, 0, tokenized.get(1).endIndex);
                scopeStack.peek().addDefinition(macroName, loc);
                globalScope.addDefinition(macroName, loc);
                recordDefinition(macroName, loc);
                extractDoc(file, idx, macroName, scopeStack.peek());

                var macroScope = new Scope(macroName, ScopeType.MACRO, fileUri, idx, scopeStack.peek());
                scopeStack.push(macroScope);
                continue;
            }

            if (first.type == TokenType.TOK_SCOPE || ".scope".equalsIgnoreCase(first.text())) {
                var scopeName = tokenized.size() > 1 ? tokenized.get(1).text() : "";
                if (!scopeName.isEmpty()) {
                    var loc = new Location(fileUri, idx, 0, tokenized.get(1).endIndex);
                    scopeStack.peek().addDefinition(scopeName, loc);
                    recordDefinition(scopeName, loc);
                    extractDoc(file, idx, scopeName, scopeStack.peek());
                }
                var sc = new Scope(scopeName, ScopeType.SCOPE, fileUri, idx, scopeStack.peek());
                scopeStack.push(sc);
                continue;
            }

            // Definitions inside current scope:

            // 1. Label definition (token:)
            if (tokenized.size() > 1 && tokenized.get(1).type == TokenType.TOK_COLON) {
                var token = tokenized.get(0);
                var stringToken = token.text();
                var loc = new Location(fileUri, idx, token.getStartIndex(), token.getEndIndex());
                Scope current = scopeStack.peek();
                current.addDefinition(stringToken, loc);
                if (current.type() == ScopeType.FILE || current.type() == ScopeType.GLOBAL) {
                    globalScope.addDefinition(stringToken, loc);
                } else if (!current.name().isEmpty()) {
                    recordDefinition(current.name() + "::" + stringToken, loc);
                }
                recordDefinition(stringToken, loc);
                extractDoc(file, idx, stringToken, current);
                continue;
            }

            // 2. Assignment (token = or token :=)
            if (tokenized.size() > 1
                    && (tokenized.get(1).type == TokenType.TOK_EQ || tokenized.get(1).type == TokenType.TOK_ASSIGN)) {
                var token = tokenized.get(0);
                var stringToken = token.text();
                var loc = new Location(fileUri, idx, token.getStartIndex(), token.getEndIndex());
                Scope current = scopeStack.peek();
                current.addDefinition(stringToken, loc);
                if (current.type() == ScopeType.FILE || current.type() == ScopeType.GLOBAL) {
                    globalScope.addDefinition(stringToken, loc);
                } else if (!current.name().isEmpty()) {
                    recordDefinition(current.name() + "::" + stringToken, loc);
                }
                recordDefinition(stringToken, loc);
                extractDoc(file, idx, stringToken, current);
                continue;
            }

            // 3. Struct fields inside a STRUCT scope
            if (scopeStack.peek().type() == ScopeType.STRUCT) {
                if (tokenized.size() > 1 && tokenized.get(0).type == TokenType.TOK_IDENT) {
                    var token = tokenized.get(0);
                    var stringToken = token.text();
                    var loc = new Location(fileUri, idx, token.getStartIndex(), token.getEndIndex());
                    Scope current = scopeStack.peek();
                    current.addDefinition(stringToken, loc);
                    if (!current.name().isEmpty()) {
                        recordDefinition(current.name() + "::" + stringToken, loc);
                    }
                    recordDefinition(stringToken, loc);
                    extractDoc(file, idx, stringToken, current);
                }
            }
        }

        while (scopeStack.size() > 1) {
            Scope s = scopeStack.pop();
            s.setEndLine(file.textLines() - 1);
        }
        fileScope.setEndLine(file.textLines() - 1);
    }

    private Scope findScopeAt(URI uri, int line) {
        if (uri != null) {
            Scope fileScope = fileScopes.get(uri);
            if (fileScope != null) {
                Scope inner = fileScope.findInnermostScope(uri, line);
                if (inner != null) {
                    return inner;
                }
                return fileScope;
            }
        }
        return globalScope.findInnermostScope(uri, line);
    }

    private void closeScope(Deque<Scope> stack, ScopeType type, int endLine) {
        while (stack.size() > 1) {
            Scope top = stack.peek();
            if (top.type() == type) {
                top.setEndLine(endLine);
                stack.pop();
                return;
            } else if (top.type() == ScopeType.FILE || top.type() == ScopeType.GLOBAL) {
                break;
            } else {
                top.setEndLine(endLine);
                stack.pop();
            }
        }
    }

    private void recordDefinition(String name, Location location) {
        definitions.put(name, location);
        allDefinitionsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(location);
    }

    private boolean isRegister(Token token) {
        if (token == null) {
            return false;
        }
        if (token.type == TokenType.TOK_REGISTER) {
            return true;
        }
        String text = token.text();
        return text != null && text.matches("(?i)r\\d{1,2}|a|x|y|z|s");
    }

    private void extractDoc(TokenizedFile file, int defLineIdx, String symbolName, Scope scope) {
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
            if (scope != null) {
                scope.addDocumentation(symbolName, cleanDoc);
                scope.addRawDocumentation(symbolName, rawDoc);
                if (scope.type() != ScopeType.GLOBAL
                        && scope.type() != ScopeType.FILE
                        && !scope.name().isEmpty()) {
                    String qualified = scope.name() + "::" + symbolName;
                    addDocumentation(qualified, cleanDoc);
                    rawDocumentations.put(qualified, rawDoc);
                }
            }
        }
    }

    private void collectScopes(Scope scope, List<Scope> target) {
        target.add(scope);
        for (Scope child : scope.children()) {
            collectScopes(child, target);
        }
    }
}
