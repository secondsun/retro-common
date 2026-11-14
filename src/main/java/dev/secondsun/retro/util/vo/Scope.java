package dev.secondsun.retro.util.vo;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Represents a lexical scope (global, file, function, procedure, struct, enum, macro, or block
 * scope). Scopes form a hierarchy with parent and child relationships and provide context-aware
 * symbol, documentation, and register-variable resolution.
 */
public class Scope {

  private static final List<String> LOW_PRIORITY_FRAGMENTS =
      List.of("/src/test/resources/", "/.build/", "/.build_libsfx/", "/build_tools/");

  private final String name;
  private final ScopeType type;
  private final URI uri;
  private final int startLine;
  private int endLine;
  private final Scope parent;
  private final boolean inlineFunction;
  private final FunctionDeclaration functionDeclaration;
  private final List<Scope> children = new ArrayList<>();
  private final Map<String, Location> definitions = new LinkedHashMap<>();
  private final Map<String, String> documentations = new HashMap<>();
  private final Map<String, String> rawDocumentations = new HashMap<>();
  private final Set<String> registerLabels = new LinkedHashSet<>();
  private final Map<String, String> precoloredRegisters = new LinkedHashMap<>();

  public Scope(
      String name,
      ScopeType type,
      URI uri,
      int startLine,
      int endLine,
      Scope parent,
      boolean inlineFunction,
      FunctionDeclaration functionDeclaration) {
    this.name = name != null ? name : "";
    this.type = type != null ? type : ScopeType.GLOBAL;
    this.uri = uri;
    this.startLine = startLine;
    this.endLine = endLine;
    this.parent = parent;
    this.inlineFunction = inlineFunction;
    this.functionDeclaration = functionDeclaration;
    if (parent != null) {
      parent.addChild(this);
    }
  }

  public Scope(String name, ScopeType type, URI uri, int startLine, int endLine, Scope parent) {
    this(name, type, uri, startLine, endLine, parent, false, null);
  }

  public Scope(String name, ScopeType type, URI uri, int startLine, Scope parent) {
    this(name, type, uri, startLine, Integer.MAX_VALUE, parent, false, null);
  }

  public String name() {
    return name;
  }

  public ScopeType type() {
    return type;
  }

  public URI uri() {
    return uri;
  }

  public int startLine() {
    return startLine;
  }

  public int endLine() {
    return endLine;
  }

  public void setEndLine(int endLine) {
    this.endLine = endLine;
  }

  public Scope parent() {
    return parent;
  }

  public boolean isInlineFunction() {
    return inlineFunction;
  }

  public FunctionDeclaration functionDeclaration() {
    return functionDeclaration;
  }

  public List<Scope> children() {
    return Collections.unmodifiableList(children);
  }

  public void addChild(Scope child) {
    if (child != null && !children.contains(child)) {
      children.add(child);
    }
  }

  public void removeChild(Scope child) {
    children.remove(child);
  }

  public Map<String, Location> definitions() {
    return Collections.unmodifiableMap(definitions);
  }

  public Map<String, String> documentations() {
    return Collections.unmodifiableMap(documentations);
  }

  public Map<String, String> rawDocumentations() {
    return Collections.unmodifiableMap(rawDocumentations);
  }

  public Set<String> registerLabels() {
    return Collections.unmodifiableSet(registerLabels);
  }

  public Map<String, String> precoloredRegisters() {
    return Collections.unmodifiableMap(precoloredRegisters);
  }

  public void addRegisterLabel(String varName) {
    if (varName != null && !varName.isBlank()) {
      registerLabels.add(varName.toLowerCase(Locale.ROOT));
    }
  }

  public void addPrecoloredRegister(String varName, String physicalReg) {
    if (varName != null && !varName.isBlank() && physicalReg != null && !physicalReg.isBlank()) {
      precoloredRegisters.put(
          varName.toLowerCase(Locale.ROOT), physicalReg.toLowerCase(Locale.ROOT));
    }
  }

  public boolean isRegisterLabel(String varName) {
    if (varName == null || varName.isBlank()) {
      return false;
    }
    if (registerLabels.contains(varName.toLowerCase(Locale.ROOT))) {
      return true;
    }
    return parent != null && parent.isRegisterLabel(varName);
  }

  public String resolvePrecoloredRegister(String varName) {
    if (varName == null || varName.isBlank()) {
      return null;
    }
    String reg = precoloredRegisters.get(varName.toLowerCase(Locale.ROOT));
    if (reg != null) {
      return reg;
    }
    return parent != null ? parent.resolvePrecoloredRegister(varName) : null;
  }

  public void addDefinition(String name, Location location) {
    if (name != null && location != null) {
      if (this.type == ScopeType.GLOBAL) {
        Location existing = definitions.get(name);
        if (existing != null
            && !isLowPriorityUri(existing.filename())
            && isLowPriorityUri(location.filename())) {
          return;
        }
      }
      definitions.put(name, location);
    }
  }

  private static boolean isLowPriorityUri(URI uri) {
    if (uri == null) {
      return false;
    }
    String s = uri.toString();
    for (String frag : LOW_PRIORITY_FRAGMENTS) {
      if (s.contains(frag)) {
        return true;
      }
    }
    return false;
  }

  public void removeDefinition(String name) {
    if (name != null) {
      definitions.remove(name);
    }
  }

  public void addDocumentation(String name, String docs) {
    if (name != null && docs != null) {
      documentations.put(name, docs);
    }
  }

  public void addRawDocumentation(String name, String rawDocs) {
    if (name != null && rawDocs != null) {
      rawDocumentations.put(name, rawDocs);
    }
  }

  public Location getLocalDefinition(String name) {
    return definitions.get(name);
  }

  public String getLocalDocumentation(String name) {
    return documentations.get(name);
  }

  public String getLocalRawDocumentation(String name) {
    return rawDocumentations.get(name);
  }

  public boolean hasLocalDocumentation(String name) {
    return documentations.containsKey(name);
  }

  /** Checks if this scope encompasses the given URI and line number. */
  public boolean contains(URI uri, int line) {
    if (this.type == ScopeType.GLOBAL) {
      return true;
    }
    if (this.uri == null) {
      return uri == null && line >= startLine && line <= endLine;
    }
    return Objects.equals(this.uri, uri) && line >= startLine && line <= endLine;
  }

  /**
   * Recursively locates the innermost scope containing the specified line in the given file URI.
   */
  public Scope findInnermostScope(URI uri, int line) {
    if (!contains(uri, line)) {
      return null;
    }
    for (Scope child : children) {
      Scope found = child.findInnermostScope(uri, line);
      if (found != null) {
        return found;
      }
    }
    return this;
  }

  /**
   * Finds a child scope by name, traversing transparently through file scopes if at global level.
   */
  public Scope findChildScope(String scopeName) {
    if (scopeName == null || scopeName.isEmpty()) {
      return null;
    }
    for (Scope child : children) {
      if (child.name.equals(scopeName) || child.name.equalsIgnoreCase(scopeName)) {
        return child;
      }
      if (child.type == ScopeType.FILE || child.type == ScopeType.GLOBAL) {
        Scope found = child.findChildScope(scopeName);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  /**
   * Resolves a symbol name starting from this scope and moving outward to parent scopes. Supports
   * root references (e.g. {@code ::global_var}) and qualified names (e.g. {@code foo::label}).
   */
  public Location resolve(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }

    if (name.startsWith("::")) {
      Scope root = this;
      while (root.parent != null) {
        root = root.parent;
      }
      return root.resolve(name.substring(2));
    }

    if (name.contains("::")) {
      int colonIdx = name.indexOf("::");
      String scopeName = name.substring(0, colonIdx);
      String rest = name.substring(colonIdx + 2);

      Scope child = findChildScope(scopeName);
      if (child != null) {
        Location loc = child.resolve(rest);
        if (loc != null) {
          return loc;
        }
      }

      if (parent != null) {
        return parent.resolve(name);
      }
      return null;
    }

    Location loc = definitions.get(name);
    if (loc != null) {
      return loc;
    }

    if (parent != null) {
      return parent.resolve(name);
    }

    return null;
  }

  /** Resolves documentation for a symbol name starting from this scope and moving outward. */
  public String resolveDocumentation(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }

    if (name.startsWith("::")) {
      Scope root = this;
      while (root.parent != null) {
        root = root.parent;
      }
      return root.resolveDocumentation(name.substring(2));
    }

    if (name.contains("::")) {
      int colonIdx = name.indexOf("::");
      String scopeName = name.substring(0, colonIdx);
      String rest = name.substring(colonIdx + 2);

      Scope child = findChildScope(scopeName);
      if (child != null) {
        String doc = child.resolveDocumentation(rest);
        if (doc != null) {
          return doc;
        }
      }

      if (parent != null) {
        return parent.resolveDocumentation(name);
      }
      return null;
    }

    String doc = documentations.get(name);
    if (doc != null) {
      return doc;
    }

    if (parent != null) {
      return parent.resolveDocumentation(name);
    }

    return null;
  }

  /**
   * Resolves raw comment documentation for a symbol name starting from this scope and moving
   * outward.
   */
  public String resolveRawDocumentation(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }

    if (name.startsWith("::")) {
      Scope root = this;
      while (root.parent != null) {
        root = root.parent;
      }
      return root.resolveRawDocumentation(name.substring(2));
    }

    if (name.contains("::")) {
      int colonIdx = name.indexOf("::");
      String scopeName = name.substring(0, colonIdx);
      String rest = name.substring(colonIdx + 2);

      Scope child = findChildScope(scopeName);
      if (child != null) {
        String doc = child.resolveRawDocumentation(rest);
        if (doc != null) {
          return doc;
        }
      }

      if (parent != null) {
        return parent.resolveRawDocumentation(name);
      }
      return null;
    }

    String doc = rawDocumentations.get(name);
    if (doc != null) {
      return doc;
    }

    if (parent != null) {
      return parent.resolveRawDocumentation(name);
    }

    return null;
  }

  /** Collects all definitions of a symbol in this scope and any enclosing ancestor scopes. */
  public List<Location> resolveAll(String name) {
    if (name == null || name.isBlank()) {
      return List.of();
    }
    List<Location> results = new ArrayList<>();
    Scope current = this;
    while (current != null) {
      Location loc = current.definitions.get(name);
      if (loc != null && !results.contains(loc)) {
        results.add(loc);
      }
      current = current.parent;
    }
    return List.copyOf(results);
  }

  /**
   * Recursively collects all definitions matching the name from this scope and all descendant child
   * scopes.
   */
  public void collectAllDefinitions(String name, List<Location> target) {
    if (name == null || target == null) {
      return;
    }
    Location loc = definitions.get(name);
    if (loc != null && !target.contains(loc)) {
      target.add(loc);
    }
    for (Scope child : children) {
      child.collectAllDefinitions(name, target);
    }
  }
}
