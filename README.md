# retro-common

`retro-common` is a Java library that provides core utilities and classes for building retro-development tools,
specifically targeting assembly language development (such as CA65). It includes components for file management,
tokenizing assembly source code, and symbol extraction, making it a foundation for other projects like Language Server
Protocol (LSP) implementations for retro platforms.

## Directory Structure and Files

### Root Directory

- `LICENSE`: The legal terms under which this software is distributed (GNU LESSER GENERAL PUBLIC LICENSE v3.0).
- `pom.xml`: The Maven project configuration file, defining dependencies, build process, and project metadata.
- `src/`: The source directory containing all production and test code.
- `target/`: The directory where Maven stores compiled classes, packaged JARs, and other build artifacts.

### Key Source Components (`src/main/java/dev/secondsun/retro/util/`)

- `CA65Scanner.java`: A tokenizer and scanner for CA65 assembly language, responsible for breaking down source text into
  meaningful tokens.
- `FileService.java`: Manages file discovery and reading, supporting search paths and handling URI-based file access.
- `ProjectService.java`: A high-level service that initializes workspace directories, loads source files, and
  coordinates symbol extraction.
- `SymbolService.java`: Tracks and manages symbol definitions (labels, macros, structs, etc.) extracted from source
  files.
- `Util.java`: Provides general-purpose utility methods for string manipulation, URI normalization, and comment removal.
- `Token.java`, `TokenType.java`, `TokenAttribute.java`: Define the structure and types of tokens used by the scanner.
- `GsuInstructionAttributeAdder.java`: Specialized utility for adding attributes to GSU (Super FX) instructions.
- `instruction/`: Subpackage containing logic for instruction parsing and matching (e.g., `GSUInstruction.java`,
  `ArgumentMatcher.java`).
- `vo/`: Value Object subpackage containing data structures like `Location.java`, `TokenizedFile.java`, and
  `DotKeywords.java`.

### Resources (`src/main/resources/`)

- `snes.json`: Textmate language configuration for SNES development.

### Tests (`src/test/`)

- `java/dev/secondsun/`: Contains JUnit tests for verifying the functionality of the scanner, symbol service, and
  utilities.
- `resources/`: Contains sample assembly files (`.s`, `.i`, `.sgs`) and mock workspaces used for integration and unit
  testing.
