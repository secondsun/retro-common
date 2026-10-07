# retro-common

`retro-common` is a Java library that provides core utilities and classes for building retro-development tools,
specifically targeting assembly language development (CA65, 6502/65816) and Super FX (GSU / X-GSU / sfx-optimizer).
It provides components for file management, lexical analysis, symbol extraction, instruction matching, and parsing
for high-level control-flow pseudo-macros.

---

## 1. Supported Language Features & Extensions

In addition to standard CA65 assembly syntax and GSU instruction sets, `retro-common` provides first-class support
for high-level control-flow pseudo-macros used by Super FX toolchains and compilers (e.g., `sfx-optimizer`).

### 1.1 `function` Definition
Defines a structured procedure or function with optional parameters and an optional return variable/register.
```ca65
function <name> [<param1>, <param2>, ...] [: <return_var>]
```
- **Keyword**: `function` (case-insensitive, tokenized as `TOK_FUNCTION`).
- **`<name>`**: Function identifier (required).
- **Parameters**: Zero or more identifiers, separated by commas or whitespace. Duplicate parameter names are flagged as errors.
- **Optional Return Variable**: `: <return_var>` (colon `TOK_COLON` followed by an identifier or hardware register `r0`..`r15`).
- **Examples**:
  ```ca65
  function simple
  function add_one x
  function add_vectors v1, v2
  function add_vectors v1, v2 : result
  function get_tick : r0
  ```

### 1.2 `endfunction` Statement
Closes a function definition.
```ca65
endfunction
```
- **Keyword**: `endfunction` (case-insensitive, tokenized as `TOK_ENDFUNCTION`).
- **Arguments**: Must not take any arguments. Trailing tokens on the same line are flagged with `TokenAttribute.ERROR`.

### 1.3 `call` Statement
Invokes a function with arguments and an optional destination binding.
```ca65
call <function_name> [<arg1>, <arg2>, ...] [: <dest_var>]
```
- **Keyword**: `call` (case-insensitive, tokenized as `TOK_CALL`).
- **`<function_name>`**: Identifier of the target function (required).
- **Arguments**: Zero or more arguments (identifiers, hardware registers, or constants/numbers), separated by commas or whitespace.
- **Optional Destination**: `: <dest_var>` (colon `TOK_COLON` followed by an identifier or hardware register receiving the return value).
- **Examples**:
  ```ca65
  call initialize
  call add_vectors v1, v2
  call add_vectors v1, v2 : out
  call get_count : r3
  ```

### 1.4 `return` Statement
Returns from a function with an optional return value.
```ca65
return [<return_var>]
```
- **Keyword**: `return` (case-insensitive, tokenized as `TOK_RETURN`).
- **Return Value**: Zero or one argument (identifier, register, or constant). Multiple return values are flagged as errors.
- **Examples**:
  ```ca65
  return
  return result
  return r0
  ```

---

## 2. How to Use the API

### 2.1 Tokenizing Assembly Code
Use `CA65Scanner` to tokenize raw assembly text into `TokenizedFile` and `Tokens`:

```java
import dev.secondsun.retro.util.CA65Scanner;
import dev.secondsun.retro.util.vo.TokenizedFile;

var scanner = new CA65Scanner();
TokenizedFile file = scanner.tokenize("""
    function add_vec v1, v2 : result
        call compute v1, v2 : r0
        return r0
    endfunction
""");
```

### 2.2 Parsing and Validating Pseudo-Macro Statements
Use `FunctionSyntaxHelper` to inspect lines and parse structured AST/VO records:

```java
import dev.secondsun.retro.util.FunctionSyntaxHelper;
import dev.secondsun.retro.util.vo.*;

for (int i = 0; i < file.textLines(); i++) {
    var tokens = file.getLine(i);
    if (tokens == null) continue;

    if (FunctionSyntaxHelper.isFunction(tokens)) {
        FunctionDeclaration func = FunctionSyntaxHelper.parseFunction(tokens);
        if (!func.hasErrors()) {
            System.out.println("Function: " + func.name() + " Params: " + func.parameters().size());
            func.returnVariable().ifPresent(ret -> System.out.println("Returns into: " + ret.text()));
        }
    } else if (FunctionSyntaxHelper.isCall(tokens)) {
        CallStatement call = FunctionSyntaxHelper.parseCall(tokens);
        if (!call.hasErrors()) {
            System.out.println("Calling: " + call.targetName() + " Args: " + call.arguments().size());
            call.destinationVariable().ifPresent(dest -> System.out.println("Dest: " + dest.text()));
        }
    } else if (FunctionSyntaxHelper.isReturn(tokens)) {
        ReturnStatement ret = FunctionSyntaxHelper.parseReturn(tokens);
        if (!ret.hasErrors()) {
            ret.returnVariable().ifPresent(val -> System.out.println("Returning: " + val.text()));
        }
    } else if (FunctionSyntaxHelper.isEndFunction(tokens)) {
        boolean valid = FunctionSyntaxHelper.validateEndFunction(tokens);
        System.out.println("Endfunction valid: " + valid);
    }
}
```

### 2.3 Diagnostic and Error Handling
When a syntax violation occurs (e.g. duplicate parameters, dangling colons, multiple return targets):
1. The offending `Token` has `token.hasAttribute(TokenAttribute.ERROR) == true`.
2. A human-readable diagnostic message is placed in `token.message`.
3. The AST statement record returns `hasErrors() == true`.

### 2.4 Context-Aware Symbol Resolution & Scopes
`SymbolService` tracks lexical scopes (`GLOBAL`, `FILE`, `FUNCTION`, `PROC`, `STRUCT`, `SCOPE`, `ENUM`, `MACRO`) and enables context-aware symbol and documentation lookup for language servers (`retro-lsp` Go-To-Definition, Hover):

```java
var symbolService = new SymbolService();
symbolService.extractDefinitions(file);

// Context-aware lookup: resolves local symbol inside a function or procedure
Location loc = symbolService.getLocation("loop", file.uri(), currentLine);

// Resolves parameters and return variables defined in a function
Location paramLoc = symbolService.getLocation("v1", file.uri(), currentLine);

// Qualified scope resolution:
Location fooLoop = symbolService.getLocation("foo::loop");

// Root resolution bypassing local scope:
Location rootLoop = symbolService.getLocation("::loop", file.uri(), currentLine);

// Multi-definition queries across scopes:
List<Location> allDefs = symbolService.getLocations("loop");

// Context-aware documentation lookup (e.g. for LSP hover):
String doc = symbolService.getDocumentation("loop", file.uri(), currentLine);
```

---

## 3. Directory Structure and Files

### Root Directory
- `LICENSE`: The legal terms under which this software is distributed (GNU LESSER GENERAL PUBLIC LICENSE v3.0).
- `pom.xml`: The Maven project configuration file, defining dependencies, build process, and project metadata.
- `AGENTS.md`: Architectural runbook and coding constraints for AI coding agents.
- `src/`: The source directory containing all production and test code.
- `target/`: The directory where Maven stores compiled classes, packaged JARs, and other build artifacts.

### Key Source Components (`src/main/java/dev/secondsun/retro/util/`)
- `CA65Scanner.java`: Lexer and scanner for CA65 assembly and pseudo-macro keywords (`function`, `endfunction`, `call`, `return`).
- `FunctionSyntaxHelper.java`: Syntax validator and parser helper for pseudo-macro statements.
- `FileService.java`: Manages file discovery and reading, supporting search paths and URI-based file access.
- `ProjectService.java`: High-level service that initializes workspace directories and coordinates symbol extraction.
- `SymbolService.java`: Tracks and manages symbol definitions and lexical scopes, providing context-aware symbol and documentation resolution.
- `Util.java`: General-purpose utilities for string manipulation, URI normalization, and comment removal.
- `Token.java`, `TokenType.java`, `TokenAttribute.java`: Define token structures, keyword types, and attribute sets (such as `TokenAttribute.ERROR`).
- `GsuInstructionAttributeAdder.java`: Specialized utility for adding attributes to GSU (Super FX) instructions.
- `instruction/`: Subpackage containing logic for instruction parsing and matching (`GSUInstruction.java`, `ArgumentMatcher.java`, `Instructions.java`).
- `vo/`: Value Object subpackage containing data structures:
  - `Scope.java`: Hierarchical lexical scope container supporting parent-child chaining, local definitions, and qualified resolution.
  - `ScopeType.java`: Enumeration of scope types (`GLOBAL`, `FILE`, `FUNCTION`, `PROC`, `STRUCT`, `SCOPE`, `ENUM`, `MACRO`).
  - `FunctionDeclaration.java`: AST record for `function` declarations.
  - `CallStatement.java`: AST record for `call` statements.
  - `ReturnStatement.java`: AST record for `return` statements.
  - `Location.java`: Line and column tracking for symbols.
  - `TokenizedFile.java` and `Tokens.java`: Token container representations.
  - `DotKeywords.java`: Directive definitions for CA65 dot commands.

### Resources (`src/main/resources/`)
- `snes.json`: Textmate language configuration for SNES development.

### Tests (`src/test/`)
- `java/dev/secondsun/`: Contains JUnit tests for scanner, symbols, instructions, and `FunctionSyntaxTest`.
- `resources/`: Contains sample assembly files (`.s`, `.i`, `.sgs`) and mock workspaces used for testing.

---

## 4. Development & Build Commands

Always use the Maven Wrapper (`./mvnw`):

```bash
# Compile and run test suite
./mvnw clean test

# Check code formatting
./mvnw spotless:check

# Apply code formatting
./mvnw spotless:apply

# Build, package, and verify
./mvnw clean verify -DskipGpg=true
```
