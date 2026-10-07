# Agent Instructions for retro-common

Welcome to `retro-common`. This file contains architectural context, operational runbooks, and design constraints for AI coding agents.

## 1. Project Overview & Role
`retro-common` is a Java library providing lexical analysis, tokenization, instruction matching, symbol management, and documentation extraction for 6502/65816 assembly (CA65) and Super FX (GSU / X-GSU / sfx-optimizer) development. It acts as the core engine for IDE plugins, compilers, and Language Server Protocol (LSP) servers (such as `retro-lsp`).

- **JDK Target**: Java 26 (Release 26, modern features encouraged)
- **Build System**: Apache Maven via the Maven Wrapper (`./mvnw`)
- **Primary Consumers**: `retro-lsp`, `sfx-optimizer`, and retro-tooling IDE plugins
- **Publishing Target**: Maven Central via the Central Publisher Portal (`central.sonatype.com`)

## 2. Essential Commands

Always use the Maven Wrapper (`./mvnw`) rather than system `mvn`:

- **Compile and Test**:
  ```bash
  ./mvnw clean test
  ```
- **Run a Specific Test Class**:
  ```bash
  ./mvnw test -Dtest=FunctionSyntaxTest
  ```
- **Run a Specific Test Method**:
  ```bash
  ./mvnw test -Dtest=FunctionSyntaxTest#testFunctionWithParamsAndReturn
  ```
- **Apply Code Formatting**:
  ```bash
  ./mvnw spotless:apply
  ```
- **Check Code Formatting**:
  ```bash
  ./mvnw spotless:check
  ```
- **Full Verification (Packaging & Enforcer Checks)**:
  ```bash
  ./mvnw clean verify -DskipGpg=true
  ```
- **Local Install (for consuming libraries like sfx-optimizer)**:
  ```bash
  ./mvnw clean install -DskipGpg=true
  ```
- **Deploy to Maven Central**:
  ```bash
  ./mvnw clean deploy
  ```
  *(Requires GPG key configuration and Central Portal user token configured in `~/.m2/settings.xml` under server id `central`)*

## 3. Architecture: The Elephant and Goldfish
Follow the Elephant and Goldfish separation of concerns:

- **Goldfish (Stateless & Pure)**:
  - `dev.secondsun.retro.util.CA65Scanner`: Lexes raw text into token streams (`TokenizedFile`, `Tokens`, `Token`).
  - `dev.secondsun.retro.util.FunctionSyntaxHelper`: Pure parser and validator for pseudo-macro statements (`function`, `endfunction`, `call`, `return`), yielding immutable VO records (`FunctionDeclaration`, `CallStatement`, `ReturnStatement`).
  - `dev.secondsun.retro.util.instruction.*`: Matches instruction mnemonics and operands via `ArgumentMatcher` and `Instructions`.
  - `dev.secondsun.retro.util.Util`: Pure helper routines for string stripping, comment extraction, and URI normalization.
  - *Invariant*: Keep these classes pure, thread-safe, and independent of disk/workspace state. No mocking needed for tests.
- **Elephant (Stateful & Persistent)**:
  - `dev.secondsun.retro.util.ProjectService`: Coordinates workspace discovery and file ingestion.
  - `dev.secondsun.retro.util.FileService`: Handles search paths and file reading via `java.net.URI`.
  - `dev.secondsun.retro.util.SymbolService`: Stores symbol locations, scopes, documentation, and definitions.
  - *Invariant*: All file identities must use `java.net.URI` rather than platform-specific string paths.

## 4. Coding Standards & Invariants

1. **URI Normalization**:
   - Never use platform-dependent string path concatenation (e.g. `path + "/" + file`).
   - Use `URI.create(...)`, `Path.toUri()`, or `File.toURI()`.
   - Never hardcode user paths (e.g. `/Users/...` or `/home/...`) in production or test assertions.
2. **Java 26 Idioms**:
   - Prefer Java records (`Location`, `Tokens`, `FunctionDeclaration`, `CallStatement`, `ReturnStatement`), pattern matching, switch expressions, sealed hierarchies (`ArgumentMatcher`), and immutable collections (`List.copyOf`, `Map.copyOf`).
3. **Module Integrity**:
   - `src/main/java/module-info.java` declares exports. If creating a new public package, export it in `module-info.java`.
4. **CA65 & Assembly Semantics**:
   - Comments always start with `;` (CA65 has no multi-line block comment syntax).
   - Multi-line docblocks for symbols, functions, and macros precede definitions as consecutive comment lines.
   - Symbols may be standard labels (`label:`), macros (`.macro name` / `.endmacro`), procedures (`.proc name` / `.endproc`), structs (`.struct name` / `.endstruct`), or equ/assignments (`symbol = value`).
5. **Pseudo-Macro Grammar & Validation (`function`, `endfunction`, `call`, `return`)**:
   - High-level control flow pseudo-macros must be parsed using `FunctionSyntaxHelper`:
     - `function <name> [<param1>, <param2>, ...] [: <return_var>]`: Function identifier is mandatory; parameters must be identifiers; duplicate parameter names are errors; return variable is an identifier or hardware register (`r0`..`r15`).
     - `endfunction`: Must not have trailing arguments or tokens on the same line.
     - `call <target> [<arg1>, <arg2>, ...] [: <dest_var>]`: Target is mandatory; arguments can be identifiers, hardware registers, or numbers/constants; destination variable is an identifier or hardware register.
     - `return [<return_var>]`: Supports 0 or 1 return argument (identifier, register, or constant); multiple return values are syntax errors.
   - **Diagnostic Invariant**: On any syntax error, mark the offending `Token` with `token.addAttribute(TokenAttribute.ERROR)`, set a human-readable explanation on `token.message`, and set `hasErrors = true` on the returned record.
6. **No Destructive API Changes**:
   - Downstream tools (like `retro-lsp` and `sfx-optimizer`) depend on existing public method signatures. Prefer non-breaking additions and overloads.
7. **No External Network Calls in Tests**:
   - All tests must rely on local classpath fixtures under `src/test/resources`.

## 5. Definition of Done
Any change introduced by an agent is complete when:
1. `./mvnw clean test` passes with 0 failures and 0 errors.
2. `./mvnw spotless:check` passes without formatting violations.
3. No hardcoded file paths or absolute system URIs exist in test assertions.
4. `module-info.java` compiles without warnings.
