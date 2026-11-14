package dev.secondsun.retro.util;

import dev.secondsun.retro.util.vo.Location;
import dev.secondsun.retro.util.vo.Scope;
import dev.secondsun.retro.util.vo.ScopeTree;
import dev.secondsun.retro.util.vo.TokenizedFile;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Package-private, immutable symbol, documentation, macro, and define table constructed from a
 * {@link WorkspaceSnapshot}'s effective {@link TokenizedFile} map.
 */
final class SymbolService {

  private final Map<URI, TokenizedFile> effectiveFiles;
  private final Map<String, Location> globalVisibleDefs;
  private final Map<String, Location> fallbackDefs;
  private final Map<String, List<Location>> allDefsByName;
  private final Map<String, String> globalCleanDocs;
  private final Map<String, String> globalRawDocs;
  private final Map<String, String> fallbackCleanDocs;
  private final Map<String, String> fallbackRawDocs;
  private final Map<String, ScopeTree.MacroDefinition> macrosByLowerName;
  private final Map<String, String> definesByName;
  private final Map<String, List<Scope>> topNamedScopes;
  private final Set<String> allSymbolNames;

  SymbolService(Map<URI, TokenizedFile> effectiveFiles) {
    this.effectiveFiles = effectiveFiles;

    Map<String, Location> globalVisible = new LinkedHashMap<>();
    Map<String, Location> fallback = new LinkedHashMap<>();
    Map<String, List<Location>> allByName = new LinkedHashMap<>();
    Map<String, String> gClean = new LinkedHashMap<>();
    Map<String, String> gRaw = new LinkedHashMap<>();
    Map<String, String> fClean = new LinkedHashMap<>();
    Map<String, String> fRaw = new LinkedHashMap<>();
    Map<String, ScopeTree.MacroDefinition> macros = new LinkedHashMap<>();
    Map<String, URI> macroSourceUris = new LinkedHashMap<>();
    Map<String, String> defines = new LinkedHashMap<>();
    Map<String, List<Scope>> namedScopes = new LinkedHashMap<>();
    Set<String> symNames = new LinkedHashSet<>();

    for (Map.Entry<URI, TokenizedFile> entry : effectiveFiles.entrySet()) {
      URI uri = entry.getKey();
      TokenizedFile tf = entry.getValue();
      if (tf == null || tf.isEmpty()) {
        continue;
      }
      boolean lowPrio = ReferenceService.isLowPriority(uri);
      ScopeTree tree = tf.scopeTree();

      for (Scope child : tree.fileScope().children()) {
        if (!child.name().isEmpty()) {
          namedScopes.computeIfAbsent(child.name(), k -> new ArrayList<>()).add(child);
        }
      }

      for (Map.Entry<String, ScopeTree.MacroDefinition> mEntry : tree.macros().entrySet()) {
        String key = mEntry.getKey();
        URI existingUri = macroSourceUris.get(key);
        if (existingUri == null || ReferenceService.isLowPriority(existingUri) || !lowPrio) {
          macros.put(key, mEntry.getValue());
          macroSourceUris.put(key, uri);
        }
      }

      defines.putAll(tree.defines());

      for (ScopeTree.SymbolDef def : tree.allDefinitions()) {
        String name = def.name();
        String qual = def.qualifiedName();
        Location loc = def.location();
        boolean isParamOrReturn = "parameter".equals(def.kind()) || "return_var".equals(def.kind());

        // Always index qualified name when distinct from unqualified
        if (!qual.equals(name)) {
          putPreferred(fallback, qual, loc, lowPrio);
          addLocation(allByName, qual, loc);
          if (def.cleanDoc() != null) {
            putDocPreferred(fClean, qual, def.cleanDoc(), lowPrio);
          }
          if (def.rawDoc() != null) {
            putDocPreferred(fRaw, qual, def.rawDoc(), lowPrio);
          }
        }

        if (def.globalVisible()) {
          putPreferred(globalVisible, name, loc, lowPrio);
          if (def.cleanDoc() != null) {
            putDocPreferred(gClean, name, def.cleanDoc(), lowPrio);
          }
          if (def.rawDoc() != null) {
            putDocPreferred(gRaw, name, def.rawDoc(), lowPrio);
          }
        }

        if (!isParamOrReturn) {
          putPreferred(fallback, name, loc, lowPrio);
          addLocation(allByName, name, loc);
          if (def.cleanDoc() != null) {
            putDocPreferred(fClean, name, def.cleanDoc(), lowPrio);
          }
          if (def.rawDoc() != null) {
            putDocPreferred(fRaw, name, def.rawDoc(), lowPrio);
          }
          if (!"function_alias".equals(def.kind())) {
            symNames.add(name);
          }
        }
      }
    }

    Map<String, List<Location>> frozenAll = new LinkedHashMap<>();
    Comparator<Location> prioCmp =
        Comparator.comparingInt(
            loc -> ReferenceService.isLowPriority(loc != null ? loc.filename() : null) ? 1 : 0);
    for (Map.Entry<String, List<Location>> e : allByName.entrySet()) {
      List<Location> sorted = new ArrayList<>(e.getValue());
      sorted.sort(prioCmp);
      frozenAll.put(e.getKey(), Collections.unmodifiableList(sorted));
    }

    Map<String, List<Scope>> frozenNamedScopes = new LinkedHashMap<>();
    for (Map.Entry<String, List<Scope>> e : namedScopes.entrySet()) {
      frozenNamedScopes.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
    }

    this.globalVisibleDefs = Collections.unmodifiableMap(globalVisible);
    this.fallbackDefs = Collections.unmodifiableMap(fallback);
    this.allDefsByName = Collections.unmodifiableMap(frozenAll);
    this.globalCleanDocs = Collections.unmodifiableMap(gClean);
    this.globalRawDocs = Collections.unmodifiableMap(gRaw);
    this.fallbackCleanDocs = Collections.unmodifiableMap(fClean);
    this.fallbackRawDocs = Collections.unmodifiableMap(fRaw);
    this.macrosByLowerName = Collections.unmodifiableMap(macros);
    this.definesByName = Collections.unmodifiableMap(defines);
    this.topNamedScopes = Collections.unmodifiableMap(frozenNamedScopes);
    this.allSymbolNames = Collections.unmodifiableSet(symNames);
  }

  private static void putPreferred(
      Map<String, Location> map, String key, Location loc, boolean candidateLowPrio) {
    Location existing = map.get(key);
    if (existing == null
        || ReferenceService.isLowPriority(existing.filename())
        || !candidateLowPrio) {
      map.put(key, loc);
    }
  }

  private static void putDocPreferred(
      Map<String, String> map, String key, String doc, boolean candidateLowPrio) {
    if (!map.containsKey(key) || !candidateLowPrio) {
      map.put(key, doc);
    }
  }

  private static void addLocation(Map<String, List<Location>> map, String key, Location loc) {
    List<Location> list = map.computeIfAbsent(key, k -> new ArrayList<>());
    if (!list.contains(loc)) {
      list.add(loc);
    }
  }

  Location resolveLocation(String name, URI callingUri, Integer line) {
    if (name == null || name.isBlank()) {
      return null;
    }
    if (name.startsWith("::")) {
      String rootSym = name.substring(2);
      return resolveGlobalOnly(rootSym);
    }

    if (callingUri != null) {
      TokenizedFile tf = effectiveFiles.get(callingUri);
      if (tf != null && !tf.isEmpty()) {
        ScopeTree tree = tf.scopeTree();
        if (line != null) {
          Scope scope = tree.scopeAtLine(line);
          Location local = scope.resolve(name);
          if (local != null) {
            return local;
          }
          // If line is outside file bounds (e.g. test checking out-of-scope parameter at line 10),
          // only fall back to globalVisibleDefs + non-local file scopes, never another function's
          // parameter.
        }
        Location inFile = tree.fileScope().resolve(name);
        if (inFile != null) {
          return inFile;
        }
      }
    }

    return resolveGlobalFallback(name);
  }

  private Location resolveGlobalOnly(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    if (name.contains("::")) {
      return resolveQualifiedAcrossWorkspace(name);
    }
    return globalVisibleDefs.get(name);
  }

  private Location resolveGlobalFallback(String name) {
    if (name.contains("::")) {
      Location direct = fallbackDefs.get(name);
      if (direct != null) {
        return direct;
      }
      return resolveQualifiedAcrossWorkspace(name);
    }
    Location global = globalVisibleDefs.get(name);
    if (global != null) {
      return global;
    }
    return fallbackDefs.get(name);
  }

  private Location resolveQualifiedAcrossWorkspace(String qualified) {
    int idx = qualified.indexOf("::");
    if (idx <= 0) {
      return null;
    }
    String head = qualified.substring(0, idx);
    String rest = qualified.substring(idx + 2);
    List<Scope> scopes = topNamedScopes.get(head);
    if (scopes != null) {
      for (Scope sc : scopes) {
        Location loc = sc.resolve(rest);
        if (loc != null) {
          return loc;
        }
      }
    }
    return null;
  }

  List<Location> resolveLocations(String name, URI callingUri, Integer line) {
    if (name == null || name.isBlank()) {
      return List.of();
    }
    if (name.startsWith("::")) {
      Location rootLoc = resolveGlobalOnly(name.substring(2));
      return rootLoc != null ? List.of(rootLoc) : List.of();
    }

    if (callingUri != null && line != null) {
      TokenizedFile tf = effectiveFiles.get(callingUri);
      if (tf != null && !tf.isEmpty()) {
        Scope scope = tf.scopeTree().scopeAtLine(line);
        Location local = scope.resolve(name);
        if (local != null) {
          return List.of(local);
        }
      }
    }

    if (name.contains("::")) {
      List<Location> list = allDefsByName.get(name);
      if (list != null && !list.isEmpty()) {
        return list;
      }
      Location resolved = resolveQualifiedAcrossWorkspace(name);
      return resolved != null ? List.of(resolved) : List.of();
    }

    List<Location> all = allDefsByName.get(name);
    return all != null ? all : List.of();
  }

  String resolveDocumentation(String name, URI callingUri, Integer line) {
    return resolveDocInternal(name, callingUri, line, false);
  }

  String resolveRawDocumentation(String name, URI callingUri, Integer line) {
    return resolveDocInternal(name, callingUri, line, true);
  }

  private String resolveDocInternal(String name, URI callingUri, Integer line, boolean raw) {
    if (name == null || name.isBlank()) {
      return null;
    }
    if (name.startsWith("::")) {
      String sub = name.substring(2);
      return raw ? globalRawDocs.get(sub) : globalCleanDocs.get(sub);
    }

    if (callingUri != null) {
      TokenizedFile tf = effectiveFiles.get(callingUri);
      if (tf != null && !tf.isEmpty()) {
        Scope scope = line != null ? tf.scopeTree().scopeAtLine(line) : tf.scopeTree().fileScope();
        String local = raw ? scope.resolveRawDocumentation(name) : scope.resolveDocumentation(name);
        if (local != null) {
          return local;
        }
      }
    }

    if (name.contains("::")) {
      String direct = raw ? fallbackRawDocs.get(name) : fallbackCleanDocs.get(name);
      if (direct != null) {
        return direct;
      }
      int idx = name.indexOf("::");
      if (idx > 0) {
        String head = name.substring(0, idx);
        String rest = name.substring(idx + 2);
        List<Scope> scopes = topNamedScopes.get(head);
        if (scopes != null) {
          for (Scope sc : scopes) {
            String doc = raw ? sc.resolveRawDocumentation(rest) : sc.resolveDocumentation(rest);
            if (doc != null) {
              return doc;
            }
          }
        }
      }
      return null;
    }

    String gDoc = raw ? globalRawDocs.get(name) : globalCleanDocs.get(name);
    if (gDoc != null) {
      return gDoc;
    }
    return raw ? fallbackRawDocs.get(name) : fallbackCleanDocs.get(name);
  }

  Optional<Scope> getScopeAt(URI uri, int line) {
    if (uri == null) {
      return Optional.empty();
    }
    TokenizedFile tf = effectiveFiles.get(uri);
    if (tf == null || tf.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(tf.scopeTree().scopeAtLine(line));
  }

  List<Scope> getScopes(URI uri) {
    if (uri == null) {
      return List.of();
    }
    TokenizedFile tf = effectiveFiles.get(uri);
    if (tf == null || tf.isEmpty()) {
      return List.of();
    }
    return tf.scopeTree().allScopes();
  }

  Set<String> allSymbolNames() {
    return allSymbolNames;
  }

  Optional<ScopeTree.MacroDefinition> resolveMacro(String name) {
    if (name == null || name.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(macrosByLowerName.get(name.toLowerCase(Locale.ROOT)));
  }

  Map<String, ScopeTree.MacroDefinition> allMacros() {
    return macrosByLowerName;
  }

  Map<String, String> allDefines() {
    return definesByName;
  }

  boolean isMacroName(String name) {
    return name != null && macrosByLowerName.containsKey(name.toLowerCase(Locale.ROOT));
  }
}
