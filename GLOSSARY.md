# Retro-Common

Lexical analysis, immutable tokenization, SuperFX ISA modeling, modal transition evaluation, lexical scope indexing, and sandboxed workspace snapshots for 6502, 65816, SuperFX, and X-GSU macro assembly.

## Language

**Tokenized File**:
An immutable, thread-safe line-indexed representation of source code lines, immutable `Token` records, and lazily memoized structural views (`ScopeTree`, `OccurrenceIndex`).
_Avoid_: Mutable token buffer, parse tree

**Scope Tree**:
The immutable per-file hierarchy of lexical scopes (`FILE`, `FUNCTION` / `inline function`, `PROC`, `STRUCT`, `SCOPE`, `ENUM`, `MACRO`), sibling `ConditionalArm` blocks (`.if`/`.elseif`/`.else`/`.endif`), `.macro`/`.define` tables, anonymous labels, and per-line `DialectProfile` classifications.
_Avoid_: Symbol map, flat scope list

**Dialect Profile**:
The per-scope classification (`SUPERFX`, `CPU_65816`, or `NEUTRAL_HEADER`) inferred from explicit assembler directives, segments, opcodes, and file extensions to gate SuperFX-specific validation cleanly away from 65816 host code.
_Avoid_: File-type guess, regex mode flag

**SuperFX ISA Catalog**:
The declarative specification (`SuperFxIsa`) of SuperFX (GSU / FX3) instructions and assembler pseudo-ops, recording operand arity, register/immediate bounds, assembled byte weights, delay-slot behavior, and implicit hardware register reads/writes.
_Avoid_: Hardcoded opcode switch, ad-hoc regex table

**Modal Step Evaluator**:
The stateless transition function (`ModalStepEvaluator`) that steps `ModalState` (`with`, `to`, `from`, `alt1`–`alt3`) across lines—preserving state across zero-byte labels, composing `alt*` with register prefixes, detecting overwritten/dangling prefix hazards, and computing concrete `InstructionEffect`s.
_Avoid_: Ad-hoc prefix lookback, per-pass modal tracker

**Macro Effect Summary**:
The register read/write, indirect-address constraint, `R1..R15` constraint, and `r10` stack displacement summary (`MacroEffectSummary`) automatically derived from a ca65 `.macro` body via `ModalStepEvaluator`.
_Avoid_: Hardcoded userland macro table

**Occurrence Index**:
The per-file identifier -> line map (`OccurrenceIndex`) built once on `TokenizedFile` that accelerates cross-workspace reference resolution to `O(MatchingFiles)`.
_Avoid_: Full-workspace linear token scan

**Syntax Diagnostic**:
A structured record representing a syntax error, warning, or lint finding with exact 1-based line/column ranges, severity, and rule identifier.
_Avoid_: Error block, mutated token error flag

**Workspace Index**:
The unified thread-safe entry point (`WorkspaceIndex`) managing workspace sandbox confinement, on-demand directory indexing, open-buffer lifecycle (`openOrUpdateBuffer`, `closeBuffer`, `notifyDiskEvent`), and atomic publication of immutable `WorkspaceSnapshot`s.
_Avoid_: `ProjectService`, exposed `FileService`/`SymbolService` singletons

**Workspace Snapshot**:
An immutable point-in-time view (`WorkspaceSnapshot`) of effective workspace files (`diskFiles ⊕ openBuffers`) supporting lock-free symbol lookup, macro summary resolution, `.include` resolution, reference finding, and ephemeral `Transient Snippet` overlays.
_Avoid_: Live mutable symbol map

**Buffer Precedence**:
The invariant ensuring active open in-memory editor buffers transparently supersede on-disk file state across all snapshots until explicitly closed via `closeBuffer`.
_Avoid_: Dirty cache override, buffer shadow

**Workspace Sandbox**:
The normalized filesystem boundary enforced around the workspace root preventing path traversal (`..`), file reads, `.include` walks, or symbol extraction from escaping outside the permitted root directory.
_Avoid_: Chroot, jail, unnormalized prefix check

**Transient Snippet**:
An ephemeral in-memory `WorkspaceSnapshot` overlay (`snapshotFor(nullableUri, inlineCode)`) that supports full symbol, macro, scope, and reference queries without mutating persistent workspace state.
_Avoid_: Mock file, global index injection
