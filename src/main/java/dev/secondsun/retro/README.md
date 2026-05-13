# Retro Development Toolkit

This directory is the root of the retro-development toolkit, which provides core infrastructure for analyzing and processing assembly source code (specifically CA65 and GSU/Super FX).

Following the **Elephant and Goldfish** model (as described by [Drensin](https://drensin.medium.com/elephants-goldfish-and-the-new-golden-age-of-software-engineering-c33641a48874)), this toolkit is designed to separate long-term, persistent state management (the Elephant) from transient, high-speed processing (the Goldfish).

- **The Elephant**: Managed via services like `ProjectService` and `SymbolService`, which maintain the persistent state of the assembly project, including file mappings and symbol definitions across the entire codebase.
- **The Goldfish**: Implemented in components like `CA65Scanner` and various utility classes, which perform stateless, transient tasks such as tokenizing lines of code and matching instructions.

## Directory Contents

- **util/**: Contains the core utility classes, services, and value objects that implement the scanning, tokenization, and symbol management logic.
