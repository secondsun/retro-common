# retro-common

`retro-common` is a pure-Java 26 library providing immutable lexical analysis, SuperFX ISA modeling, modal transition evaluation, lexical scope indexing, and sandboxed workspace snapshots for 6502, 65816, SuperFX (GSU / FX3), and X-GSU macro assembly.

---

## 1. Supported Language Features & Extensions

In addition to standard CA65 assembly syntax and 65816 / SuperFX instruction sets, `retro-common` provides first-class support for X-GSU high-level pseudo-macros:

### 1.1 `function` & `inline function` Definition
Defines a structured procedure or compiler-inlineable function with optional parameters and an optional return variable/register:
```ca65
[inline] function <name> [<param1>, <param2>, ...] [: <return_var>]
    ...
endfunction
```

### 1.2 `call` & `return` Statements
```ca65
call <function_name> [<arg1>, <arg2>, ...] [: <dest_var>]
return [<return_var>]
```

---

## 2. Core Architecture & API

### 2.1 Immutable Tokenization & Lazy Structural Views
`CA65Scanner` produces an immutable, thread-safe `TokenizedFile` with lazily memoized `ScopeTree` and `OccurrenceIndex` views:

```java
var scanner = new CA65Scanner();
TokenizedFile file = scanner.tokenize(sourceText, URI.create("file:///demo.sgs"));

ScopeTree scopes = file.scopeTree();
DialectProfile dialect = scopes.dialectAtLine(0);
List<SyntaxDiagnostic> diagnostics = new SyntaxValidator().validate(file);
```

### 2.2 Declarative `SuperFxIsa` & `ModalStepEvaluator`
`dev.secondsun.retro.util.sfx` provides the authoritative SuperFX instruction catalog (`SuperFxIsa`), pure modal state transitions (`ModalStepEvaluator`), and automatic `.macro` summary derivation (`MacroEffectSummary`):

```java
var state = ModalStepEvaluator.ModalState.DEFAULT;
var step = ModalStepEvaluator.step(state, file.getLine(0), 0, null);
```

### 2.3 Sandboxed `WorkspaceIndex` & Immutable `WorkspaceSnapshot`
`WorkspaceIndex` manages workspace sandbox confinement, open editor buffer precedence, on-demand directory indexing, and atomic `WorkspaceSnapshot` publication:

```java
WorkspaceIndex index = WorkspaceIndex.create(workspaceRoot);
index.openOrUpdateBuffer(uri, liveEditorText);

WorkspaceSnapshot snapshot = index.snapshot();
Location def = snapshot.resolveSymbol("add_vec", uri, lineIndex);
List<ReferenceLocation> refs = snapshot.findReferencesByName("add_vec", false);

// Ephemeral Transient Snippet overlay (zero persistent mutation):
WorkspaceSnapshot snippetView = index.snapshotFor(null, inlineSnippetCode);
```

---

## 3. Key Packages (`src/main/java/dev/secondsun/retro/util/`)
- `CA65Scanner.java`, `Token.java`, `TokenType.java`, `FunctionSyntaxHelper.java`: Immutable lexer and X-GSU statement parser.
- `WorkspaceIndex.java`, `DefaultWorkspaceIndex.java`, `WorkspaceSnapshot.java`: Thread-safe workspace state and immutable query snapshots (`FileService`, `SymbolService`, and `ReferenceService` are package-private implementation details).
- `sfx/`: `SuperFxIsa`, `ModalStepEvaluator`, and `MacroEffectSummary`.
- `vo/`: `TokenizedFile`, `ScopeTree`, `Scope`, `DialectProfile`, `OccurrenceIndex`, `ImmediateLiteral`, `FunctionDeclaration`, `CallStatement`, `ReturnStatement`.
- `diagnostic/`: Pure, non-mutating `SyntaxValidator`, `BlockStructureValidator`, `InstructionSyntaxValidator`, `DuplicateSymbolValidator`, and `AnonymousLabelValidator`.

---

## 4. Development & Build Commands

Always use the Maven Wrapper (`./mvnw`):

```bash
# Format, test, generate Javadoc, and install locally
./mvnw spotless:apply
./mvnw clean install -DskipGpg=true -Dgpg.skip=true javadoc:javadoc
```
