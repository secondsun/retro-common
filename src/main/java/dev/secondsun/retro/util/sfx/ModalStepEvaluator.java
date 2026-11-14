package dev.secondsun.retro.util.sfx;

import dev.secondsun.retro.util.FunctionSyntaxHelper;
import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.ImmediateLiteral;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure, stateless transition evaluator for SuperFX modal prefixes ({@code with}, {@code to}, {@code
 * from}, {@code alt1}–{@code alt3}) and concrete instruction effects.
 *
 * <p>Guarantees consistent semantics across syntax auditing, liveness analysis, peephole
 * optimization, stack depth tracking, and macro effect derivation:
 *
 * <ul>
 *   <li>Zero-byte labels and assembler directives preserve {@link ModalState} unchanged.
 *   <li>{@code alt1}–{@code alt3} compose with {@code with}/{@code to}/{@code from} without
 *       resetting {@code Sreg}/{@code Dreg}.
 *   <li>{@code move}/{@code moves}/{@code moveb}/{@code movew} overwrite any active {@code
 *       with}/{@code to}/{@code from} prefix and reset modal state to {@code (r0, r0, alt=0)}.
 *   <li>Every concrete SuperFX instruction consumes {@link ModalState}, emits its exact {@link
 *       InstructionEffect}, and resets modal state to {@link ModalState#DEFAULT}.
 * </ul>
 */
public final class ModalStepEvaluator {

  private ModalStepEvaluator() {}

  /** Provenance of an active modal prefix token. */
  public record ActivePrefix(String mnemonic, String targetReg, Token token, int lineIndex) {}

  /** Report of a modal prefix overwritten or left dangling without a valid target instruction. */
  public record ModalHazard(
      HazardKind kind, ActivePrefix overwritten, String triggeringMnemonic, int triggeringLine) {
    public enum HazardKind {
      OVERWRITTEN_BY_MODAL,
      OVERWRITTEN_BY_MOVE,
      DANGLING_BEFORE_CONTROL
    }
  }

  /** Immutable snapshot of active SuperFX modal register/alt state. */
  public record ModalState(
      String sourceReg,
      String destReg,
      int altMode,
      ActivePrefix withPrefix,
      ActivePrefix fromPrefix,
      ActivePrefix toPrefix,
      ActivePrefix altPrefix) {

    public static final ModalState DEFAULT = new ModalState("r0", "r0", 0, null, null, null, null);

    public boolean isDefault() {
      return withPrefix == null
          && fromPrefix == null
          && toPrefix == null
          && altMode == 0
          && "r0".equalsIgnoreCase(sourceReg)
          && "r0".equalsIgnoreCase(destReg);
    }

    public boolean hasRegisterPrefix() {
      return withPrefix != null || fromPrefix != null || toPrefix != null;
    }

    public List<ActivePrefix> activeRegisterPrefixes() {
      List<ActivePrefix> list = new ArrayList<>(3);
      if (fromPrefix != null) list.add(fromPrefix);
      if (toPrefix != null) list.add(toPrefix);
      if (withPrefix != null) list.add(withPrefix);
      return list;
    }

    /** Joins two incoming CFG edge states conservatively. */
    public static ModalState join(ModalState a, ModalState b) {
      if (a == null) return b != null ? b : DEFAULT;
      if (b == null) return a;
      if (a.equals(b)) return a;
      if (a.isDefault()) return b;
      if (b.isDefault()) return a;
      return a;
    }
  }

  public enum ControlFlowKind {
    SEQUENTIAL,
    CONDITIONAL_BRANCH,
    UNCONDITIONAL_BRANCH,
    DYNAMIC_JUMP,
    LOOP_BACKEDGE,
    CALL,
    RETURN,
    STOP
  }

  /**
   * Concrete register, address, stack, and control-flow effect of a single evaluated statement.
   *
   * @param mnemonic lowercase opcode or directive name
   * @param opcodeIndex token index of the primary opcode
   * @param consumedState modal state active immediately before this statement
   * @param reads registers/variables read by this statement
   * @param writes registers/variables written by this statement
   * @param addressOperands registers/variables used as indirect RAM pointers {@code (Rm)}
   * @param r1ToR15Operands registers/variables constrained to {@code R1..R15}
   * @param immediate parsed immediate operand if present
   * @param branchTarget target label name for branches or {@code iwt r15, #label}
   * @param controlFlow control-flow classification
   * @param hazards modal prefix hazards triggered on this step
   */
  public record InstructionEffect(
      String mnemonic,
      int opcodeIndex,
      ModalState consumedState,
      Set<String> reads,
      Set<String> writes,
      Set<String> addressOperands,
      Set<String> r1ToR15Operands,
      Optional<ImmediateLiteral> immediate,
      Optional<String> branchTarget,
      ControlFlowKind controlFlow,
      List<ModalHazard> hazards) {
    public InstructionEffect {
      reads = reads != null ? Collections.unmodifiableSet(new LinkedHashSet<>(reads)) : Set.of();
      writes = writes != null ? Collections.unmodifiableSet(new LinkedHashSet<>(writes)) : Set.of();
      addressOperands =
          addressOperands != null
              ? Collections.unmodifiableSet(new LinkedHashSet<>(addressOperands))
              : Set.of();
      r1ToR15Operands =
          r1ToR15Operands != null
              ? Collections.unmodifiableSet(new LinkedHashSet<>(r1ToR15Operands))
              : Set.of();
      immediate = immediate != null ? immediate : Optional.empty();
      branchTarget = branchTarget != null ? branchTarget : Optional.empty();
      hazards = hazards != null ? List.copyOf(hazards) : List.of();
    }
  }

  public enum StepKind {
    NO_OP_PRESERVE_MODAL,
    MODAL_PREFIX,
    INSTRUCTION,
    DSL_STATEMENT,
    MACRO_OR_OTHER
  }

  public record StepResult(
      StepKind kind, ModalState nextState, InstructionEffect effect, List<ModalHazard> hazards) {
    public StepResult {
      hazards = hazards != null ? List.copyOf(hazards) : List.of();
    }
  }

  /** Finds the primary opcode index on a line after any leading label definition. */
  public static int findOpcodeIndex(List<Token> tokens) {
    if (tokens == null || tokens.isEmpty()) {
      return -1;
    }
    int idx = 0;
    while (idx < tokens.size()) {
      Token t = tokens.get(idx);
      if (t.type == TokenType.TOK_COLON) {
        // Leading anonymous label ':'
        idx++;
        continue;
      }
      if (t.text() != null && t.text().endsWith(":") && t.text().length() > 1) {
        idx++;
        continue;
      }
      if (idx + 1 < tokens.size()
          && tokens.get(idx + 1).type == TokenType.TOK_COLON
          && !FunctionSyntaxHelper.isFunction(tokens.subList(idx, tokens.size()))
          && !FunctionSyntaxHelper.isCall(tokens.subList(idx, tokens.size()))) {
        idx += 2;
        continue;
      }
      break;
    }
    return idx < tokens.size() ? idx : -1;
  }

  /** Evaluates a single tokenized line starting from {@code state}. */
  public static StepResult step(
      ModalState state, List<Token> tokens, int lineIndex, String lineText) {
    ModalState current = state != null ? state : ModalState.DEFAULT;
    int opIdx = findOpcodeIndex(tokens);
    if (opIdx < 0) {
      return new StepResult(StepKind.NO_OP_PRESERVE_MODAL, current, null, List.of());
    }

    Token opTok = tokens.get(opIdx);
    String mnemonic = opTok.text().toLowerCase(Locale.ROOT);

    // Assembler directives preserve modal state across conditional/scope boundaries
    if (mnemonic.startsWith(".")) {
      return new StepResult(StepKind.NO_OP_PRESERVE_MODAL, current, null, List.of());
    }

    // X-GSU high-level DSL keywords
    List<Token> tail = tokens.subList(opIdx, tokens.size());
    if (FunctionSyntaxHelper.isFunction(tail)
        || FunctionSyntaxHelper.isEndFunction(tail)
        || FunctionSyntaxHelper.isCall(tail)
        || FunctionSyntaxHelper.isReturn(tail)
        || opTok.type == TokenType.TOK_REGISTER_KEYWORD
        || "register".equals(mnemonic)
        || "for".equals(mnemonic)
        || "forr".equals(mnemonic)
        || "endfor".equals(mnemonic)
        || "if_lt".equals(mnemonic)
        || "if_gt".equals(mnemonic)
        || "if_eq".equals(mnemonic)
        || "if_lte".equals(mnemonic)) {
      List<ModalHazard> hazards = new ArrayList<>();
      for (ActivePrefix ap : current.activeRegisterPrefixes()) {
        hazards.add(
            new ModalHazard(
                ModalHazard.HazardKind.DANGLING_BEFORE_CONTROL, ap, mnemonic, lineIndex));
      }
      InstructionEffect dslEffect = buildDslEffect(mnemonic, opIdx, tail, current, hazards);
      return new StepResult(StepKind.DSL_STATEMENT, ModalState.DEFAULT, dslEffect, hazards);
    }

    Optional<SuperFxIsa.OpcodeSpec> specOpt = SuperFxIsa.lookup(mnemonic);
    if (specOpt.isEmpty()) {
      // Userland macro invocation or non-GSU opcode: pass current state in effect, reset nextState
      InstructionEffect macroEffect =
          new InstructionEffect(
              mnemonic,
              opIdx,
              current,
              Set.of(),
              Set.of(),
              Set.of(),
              Set.of(),
              Optional.empty(),
              Optional.empty(),
              ControlFlowKind.SEQUENTIAL,
              List.of());
      return new StepResult(StepKind.MACRO_OR_OTHER, ModalState.DEFAULT, macroEffect, List.of());
    }

    SuperFxIsa.OpcodeSpec spec = specOpt.get();

    // 1. Modal prefixes: with, to, from, alt1, alt2, alt3
    if (spec.isModalPrefix()) {
      return evaluateModalPrefix(current, mnemonic, opTok, tokens, opIdx, lineIndex);
    }

    // 2. Concrete instruction or pseudo-move
    return evaluateInstruction(current, spec, opTok, tokens, opIdx, lineIndex, lineText);
  }

  private static StepResult evaluateModalPrefix(
      ModalState current,
      String mnemonic,
      Token opTok,
      List<Token> tokens,
      int opIdx,
      int lineIndex) {
    List<ModalHazard> hazards = new ArrayList<>();
    if ("alt1".equals(mnemonic) || "alt2".equals(mnemonic) || "alt3".equals(mnemonic)) {
      int alt = mnemonic.charAt(3) - '0';
      ActivePrefix altPref = new ActivePrefix(mnemonic, null, opTok, lineIndex);
      ModalState next =
          new ModalState(
              current.sourceReg(),
              current.destReg(),
              alt,
              current.withPrefix(),
              current.fromPrefix(),
              current.toPrefix(),
              altPref);
      return new StepResult(StepKind.MODAL_PREFIX, next, null, hazards);
    }

    String targetReg =
        (opIdx + 1 < tokens.size())
            ? SuperFxIsa.normalizeRegisterOrVar(tokens.get(opIdx + 1).text())
            : "r0";
    ActivePrefix pref = new ActivePrefix(mnemonic, targetReg, opTok, lineIndex);

    switch (mnemonic) {
      case "with" -> {
        if (current.fromPrefix() != null) {
          hazards.add(
              new ModalHazard(
                  ModalHazard.HazardKind.OVERWRITTEN_BY_MODAL,
                  current.fromPrefix(),
                  mnemonic,
                  lineIndex));
        }
        if (current.toPrefix() != null) {
          hazards.add(
              new ModalHazard(
                  ModalHazard.HazardKind.OVERWRITTEN_BY_MODAL,
                  current.toPrefix(),
                  mnemonic,
                  lineIndex));
        }
        if (current.withPrefix() != null) {
          hazards.add(
              new ModalHazard(
                  ModalHazard.HazardKind.OVERWRITTEN_BY_MODAL,
                  current.withPrefix(),
                  mnemonic,
                  lineIndex));
        }
        ModalState next =
            new ModalState(
                targetReg, targetReg, current.altMode(), pref, null, null, current.altPrefix());
        return new StepResult(StepKind.MODAL_PREFIX, next, null, hazards);
      }
      case "from" -> {
        if (current.withPrefix() != null) {
          hazards.add(
              new ModalHazard(
                  ModalHazard.HazardKind.OVERWRITTEN_BY_MODAL,
                  current.withPrefix(),
                  mnemonic,
                  lineIndex));
        }
        if (current.fromPrefix() != null) {
          hazards.add(
              new ModalHazard(
                  ModalHazard.HazardKind.OVERWRITTEN_BY_MODAL,
                  current.fromPrefix(),
                  mnemonic,
                  lineIndex));
        }
        String nextDest = current.toPrefix() != null ? current.destReg() : "r0";
        ModalState next =
            new ModalState(
                targetReg,
                nextDest,
                current.altMode(),
                null,
                pref,
                current.toPrefix(),
                current.altPrefix());
        return new StepResult(StepKind.MODAL_PREFIX, next, null, hazards);
      }
      case "to" -> {
        if (current.withPrefix() != null) {
          hazards.add(
              new ModalHazard(
                  ModalHazard.HazardKind.OVERWRITTEN_BY_MODAL,
                  current.withPrefix(),
                  mnemonic,
                  lineIndex));
        }
        if (current.toPrefix() != null) {
          hazards.add(
              new ModalHazard(
                  ModalHazard.HazardKind.OVERWRITTEN_BY_MODAL,
                  current.toPrefix(),
                  mnemonic,
                  lineIndex));
        }
        String nextSrc = current.fromPrefix() != null ? current.sourceReg() : "r0";
        ModalState next =
            new ModalState(
                nextSrc,
                targetReg,
                current.altMode(),
                null,
                current.fromPrefix(),
                pref,
                current.altPrefix());
        return new StepResult(StepKind.MODAL_PREFIX, next, null, hazards);
      }
      default -> {
        return new StepResult(StepKind.MODAL_PREFIX, current, null, hazards);
      }
    }
  }

  private static StepResult evaluateInstruction(
      ModalState current,
      SuperFxIsa.OpcodeSpec spec,
      Token opTok,
      List<Token> tokens,
      int opIdx,
      int lineIndex,
      String lineText) {
    String m = spec.mnemonic();
    List<ModalHazard> hazards = new ArrayList<>();
    Set<String> reads = new LinkedHashSet<>();
    Set<String> writes = new LinkedHashSet<>();
    Set<String> addressOperands = new LinkedHashSet<>();
    Set<String> r1ToR15Operands = new LinkedHashSet<>();
    Optional<ImmediateLiteral> parsedImm = Optional.empty();
    Optional<String> branchTarget = Optional.empty();
    ControlFlowKind cf = ControlFlowKind.SEQUENTIAL;

    // Check modal hazards before pseudo-moves or control instructions
    if (spec.isPseudoMove() && current.hasRegisterPrefix()) {
      for (ActivePrefix ap : current.activeRegisterPrefixes()) {
        hazards.add(new ModalHazard(ModalHazard.HazardKind.OVERWRITTEN_BY_MOVE, ap, m, lineIndex));
      }
    } else if (isControlWithoutModalTarget(m) && current.hasRegisterPrefix()) {
      for (ActivePrefix ap : current.activeRegisterPrefixes()) {
        hazards.add(
            new ModalHazard(ModalHazard.HazardKind.DANGLING_BEFORE_CONTROL, ap, m, lineIndex));
      }
    }

    // Parse immediate if present
    for (int i = opIdx + 1; i < tokens.size(); i++) {
      if (tokens.get(i).type == TokenType.TOK_HASH) {
        parsedImm = ImmediateLiteral.parse(tokens, i, lineText);
        break;
      }
    }

    // Split arguments by top-level comma
    List<List<Token>> args = splitArguments(tokens, opIdx + 1);

    switch (m) {
      case "move", "moves" -> {
        if (args.size() >= 2) {
          String dst = firstRegOrIdent(args.get(0));
          if (dst != null) writes.add(dst);
          if (args.get(1).get(0).type != TokenType.TOK_HASH) {
            String src = firstRegOrIdent(args.get(1));
            if (src != null) reads.add(src);
          }
          if ("r15".equalsIgnoreCase(dst)) {
            cf = ControlFlowKind.DYNAMIC_JUMP;
          }
        }
      }
      case "moveb", "movew" -> {
        if (args.size() >= 2) {
          if (args.get(0).get(0).type == TokenType.TOK_LPAREN) {
            // movew (Rm), Rn -> store Rn to (Rm)
            String ptr = extractParenRegister(args.get(0));
            String src = firstRegOrIdent(args.get(1));
            if (ptr != null) {
              reads.add(ptr);
              addressOperands.add(ptr);
            }
            if (src != null) reads.add(src);
          } else {
            // movew Rn, (Rm) -> load (Rm) to Rn
            String dst = firstRegOrIdent(args.get(0));
            String ptr = extractParenRegister(args.get(1));
            if (ptr != null) {
              reads.add(ptr);
              addressOperands.add(ptr);
            }
            if (dst != null) writes.add(dst);
          }
        }
      }
      case "ibt", "iwt", "lea" -> {
        if (!args.isEmpty()) {
          String dst = firstRegOrIdent(args.get(0));
          if (dst != null) {
            writes.add(dst);
            if ("r15".equalsIgnoreCase(dst) && args.size() >= 2) {
              cf = ControlFlowKind.UNCONDITIONAL_BRANCH;
              branchTarget = extractJumpLabel(args.get(1));
            }
          }
        }
      }
      case "inc", "dec" -> {
        if (!args.isEmpty()) {
          String reg = firstRegOrIdent(args.get(0));
          if (reg != null) {
            reads.add(reg);
            writes.add(reg);
          }
        }
      }
      case "shl" -> {
        if (!args.isEmpty()) {
          String reg = firstRegOrIdent(args.get(0));
          if (reg != null) {
            reads.add(reg);
            writes.add(reg);
          }
        } else {
          reads.add("r0");
          writes.add("r0");
        }
      }
      case "ldw", "ldb", "stw", "stb" -> {
        if (spec.readsSreg()) {
          reads.add(current.sourceReg());
        }
        if (!args.isEmpty()) {
          String ptr = extractParenRegister(args.get(0));
          if (ptr != null) {
            reads.add(ptr);
            addressOperands.add(ptr);
          }
        }
        if (spec.writesDreg()) {
          writes.add(current.destReg());
        }
      }
      case "lm", "lms" -> {
        if (args.size() >= 2) {
          String dst = firstRegOrIdent(args.get(0));
          if (dst != null) writes.add(dst);
        }
      }
      case "sm", "sms" -> {
        if (args.size() >= 2) {
          if (args.get(0).get(0).type == TokenType.TOK_LPAREN) {
            String src = firstRegOrIdent(args.get(1));
            if (src != null) reads.add(src);
          } else {
            // SMS_LEGACY: sms Rn, (yy)
            String src = firstRegOrIdent(args.get(0));
            if (src != null) reads.add(src);
          }
        }
      }
      case "jmp", "ljmp" -> {
        if (spec.readsSreg()) {
          reads.add(current.sourceReg());
        }
        if (!args.isEmpty()) {
          String target =
              args.get(0).get(0).type == TokenType.TOK_LPAREN
                  ? extractParenRegister(args.get(0))
                  : firstRegOrIdent(args.get(0));
          if (target != null) reads.add(target);
        }
        cf = ControlFlowKind.DYNAMIC_JUMP;
      }
      case "loop" -> {
        reads.addAll(spec.implicitReads());
        writes.addAll(spec.implicitWrites());
        cf = ControlFlowKind.LOOP_BACKEDGE;
      }
      case "stop" -> cf = ControlFlowKind.STOP;
      default -> {
        if (spec.isBranch()) {
          cf =
              "bra".equals(m)
                  ? ControlFlowKind.UNCONDITIONAL_BRANCH
                  : ControlFlowKind.CONDITIONAL_BRANCH;
          if (!args.isEmpty()) {
            branchTarget = Optional.of(reconstructTokens(args.get(0)));
          }
        } else {
          if (spec.readsSreg()) {
            reads.add(current.sourceReg());
          }
          reads.addAll(spec.implicitReads());
          if (!args.isEmpty() && args.get(0).get(0).type != TokenType.TOK_HASH) {
            String argReg = firstRegOrIdent(args.get(0));
            if (argReg != null) {
              reads.add(argReg);
              if (spec.minRegister() == 1) {
                r1ToR15Operands.add(argReg);
              }
            }
          }
          if (spec.writesDreg()) {
            writes.add(current.destReg());
            if ("r15".equalsIgnoreCase(current.destReg())) {
              cf = ControlFlowKind.DYNAMIC_JUMP;
            }
          }
          writes.addAll(spec.implicitWrites());
        }
      }
    }

    InstructionEffect effect =
        new InstructionEffect(
            m,
            opIdx,
            current,
            reads,
            writes,
            addressOperands,
            r1ToR15Operands,
            parsedImm,
            branchTarget,
            cf,
            hazards);
    return new StepResult(StepKind.INSTRUCTION, ModalState.DEFAULT, effect, hazards);
  }

  private static InstructionEffect buildDslEffect(
      String mnemonic, int opIdx, List<Token> tail, ModalState current, List<ModalHazard> hazards) {
    Set<String> reads = new LinkedHashSet<>();
    Set<String> writes = new LinkedHashSet<>();
    ControlFlowKind cf = ControlFlowKind.SEQUENTIAL;

    if (FunctionSyntaxHelper.isCall(tail)) {
      cf = ControlFlowKind.CALL;
      var call = FunctionSyntaxHelper.parseCall(tail);
      for (Token arg : call.arguments()) {
        if (arg.type != TokenType.TOK_INTCON
            && arg.type != TokenType.TOK_STRCON
            && arg.type != TokenType.TOK_CHARCON) {
          reads.add(SuperFxIsa.normalizeRegisterOrVar(arg.text()));
        }
      }
      call.destinationVariable()
          .ifPresent(dst -> writes.add(SuperFxIsa.normalizeRegisterOrVar(dst.text())));
    } else if (FunctionSyntaxHelper.isReturn(tail)) {
      cf = ControlFlowKind.RETURN;
      var ret = FunctionSyntaxHelper.parseReturn(tail);
      ret.returnVariable().ifPresent(rv -> reads.add(SuperFxIsa.normalizeRegisterOrVar(rv.text())));
    } else if ("forr".equals(mnemonic) && tail.size() >= 2) {
      reads.add(SuperFxIsa.normalizeRegisterOrVar(tail.get(1).text()));
      writes.add("r12");
      writes.add("r13");
    } else if ("for".equals(mnemonic)) {
      writes.add("r12");
      writes.add("r13");
    } else if ("endfor".equals(mnemonic)) {
      reads.add("r12");
      reads.add("r13");
      writes.add("r12");
      writes.add("r13");
      cf = ControlFlowKind.LOOP_BACKEDGE;
    }

    return new InstructionEffect(
        mnemonic,
        opIdx,
        current,
        reads,
        writes,
        Set.of(),
        Set.of(),
        Optional.empty(),
        Optional.empty(),
        cf,
        hazards);
  }

  private static boolean isControlWithoutModalTarget(String m) {
    return switch (m) {
      case "nop",
          "stop",
          "cache",
          "loop",
          "rpix",
          "getc",
          "plot",
          "link",
          "jmp",
          "bra",
          "bcc",
          "bcs",
          "beq",
          "bne",
          "bmi",
          "bpl",
          "bvc",
          "bvs",
          "bge",
          "blt" ->
          true;
      default -> false;
    };
  }

  public static List<List<Token>> splitArguments(List<Token> tokens, int startIdx) {
    List<List<Token>> args = new ArrayList<>();
    if (tokens == null || startIdx >= tokens.size()) {
      return args;
    }
    int parenDepth = 0;
    List<Token> current = new ArrayList<>();
    for (int i = startIdx; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.type == TokenType.TOK_LPAREN) {
        parenDepth++;
        current.add(t);
      } else if (t.type == TokenType.TOK_RPAREN) {
        parenDepth--;
        current.add(t);
      } else if (t.type == TokenType.TOK_COMMA && parenDepth <= 0) {
        if (!current.isEmpty()) {
          args.add(List.copyOf(current));
          current.clear();
        }
      } else {
        current.add(t);
      }
    }
    if (!current.isEmpty()) {
      args.add(List.copyOf(current));
    }
    return args;
  }

  private static String firstRegOrIdent(List<Token> arg) {
    if (arg == null || arg.isEmpty()) {
      return null;
    }
    Token t = arg.get(0);
    if (t.type == TokenType.TOK_REGISTER
        || t.type == TokenType.TOK_IDENT
        || t.type == TokenType.TOK_LOCAL_IDENT
        || t.type == TokenType.TOK_A
        || t.type == TokenType.TOK_X
        || t.type == TokenType.TOK_Y
        || t.type == TokenType.TOK_S) {
      return SuperFxIsa.normalizeRegisterOrVar(t.text());
    }
    return null;
  }

  private static String extractParenRegister(List<Token> arg) {
    if (arg == null || arg.size() < 3) {
      return null;
    }
    if (arg.get(0).type != TokenType.TOK_LPAREN
        || arg.get(arg.size() - 1).type != TokenType.TOK_RPAREN) {
      return null;
    }
    if (arg.size() == 3) {
      return firstRegOrIdent(List.of(arg.get(1)));
    }
    return null;
  }

  private static Optional<String> extractJumpLabel(List<Token> arg) {
    if (arg == null || arg.isEmpty()) {
      return Optional.empty();
    }
    int idx = arg.get(0).type == TokenType.TOK_HASH ? 1 : 0;
    if (idx >= arg.size()) {
      return Optional.empty();
    }
    List<Token> slice = arg.subList(idx, arg.size());
    if (slice.size() == 1
        && (slice.get(0).type == TokenType.TOK_IDENT
            || slice.get(0).type == TokenType.TOK_LOCAL_IDENT)) {
      return Optional.of(slice.get(0).text());
    }
    if (slice.stream()
        .allMatch(
            t ->
                t.type == TokenType.TOK_IDENT
                    || t.type == TokenType.TOK_LOCAL_IDENT
                    || t.type == TokenType.TOK_NAMESPACE)) {
      return Optional.of(reconstructTokens(slice));
    }
    return Optional.empty();
  }

  private static String reconstructTokens(List<Token> slice) {
    StringBuilder sb = new StringBuilder();
    for (Token t : slice) {
      sb.append(Objects.requireNonNullElse(t.text(), ""));
    }
    return sb.toString();
  }
}
