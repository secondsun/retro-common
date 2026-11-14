package dev.secondsun.retro.util.sfx;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Automatically derived register, address-pool, and stack effect summary for a ca65 {@code .macro}
 * definition, computed by stepping {@link ModalStepEvaluator} across the macro body.
 *
 * @param name macro identifier
 * @param parameters declared macro parameter names in positional order
 * @param upwardReadParams 0-based indices of parameters read before being overwritten
 * @param writtenParams 0-based indices of parameters written by the macro
 * @param addressParams 0-based indices of parameters used as indirect RAM pointers {@code (Rm)}
 * @param r1ToR15Params 0-based indices of parameters constrained to {@code R1..R15}
 * @param physicalReads physical registers ({@code "r0".."r15"}) read before being overwritten
 * @param physicalClobbers physical registers ({@code "r0".."r15"}) modified by the macro
 * @param stackDelta net byte displacement applied to {@code r10} ({@code stackPointer})
 */
public record MacroEffectSummary(
    String name,
    List<String> parameters,
    Set<Integer> upwardReadParams,
    Set<Integer> writtenParams,
    Set<Integer> addressParams,
    Set<Integer> r1ToR15Params,
    Set<String> physicalReads,
    Set<String> physicalClobbers,
    int stackDelta) {

  private static final int MAX_MACRO_NESTING = 8;

  public MacroEffectSummary {
    parameters = parameters != null ? List.copyOf(parameters) : List.of();
    upwardReadParams =
        upwardReadParams != null
            ? Collections.unmodifiableSet(new LinkedHashSet<>(upwardReadParams))
            : Set.of();
    writtenParams =
        writtenParams != null
            ? Collections.unmodifiableSet(new LinkedHashSet<>(writtenParams))
            : Set.of();
    addressParams =
        addressParams != null
            ? Collections.unmodifiableSet(new LinkedHashSet<>(addressParams))
            : Set.of();
    r1ToR15Params =
        r1ToR15Params != null
            ? Collections.unmodifiableSet(new LinkedHashSet<>(r1ToR15Params))
            : Set.of();
    physicalReads =
        physicalReads != null
            ? Collections.unmodifiableSet(new LinkedHashSet<>(physicalReads))
            : Set.of();
    physicalClobbers =
        physicalClobbers != null
            ? Collections.unmodifiableSet(new LinkedHashSet<>(physicalClobbers))
            : Set.of();
  }

  /** Lexical definition of a ca65 {@code .macro ... .endmacro} block. */
  public record MacroDefinition(
      String name, List<String> parameters, List<List<Token>> bodyLines, URI uri, int startLine) {
    public MacroDefinition {
      parameters = parameters != null ? List.copyOf(parameters) : List.of();
      bodyLines = bodyLines != null ? List.copyOf(bodyLines) : List.of();
    }
  }

  /**
   * Derives a {@link MacroEffectSummary} from a {@link MacroDefinition}, resolving simple {@code
   * .define} aliases (such as {@code .define stackPointer r10}) and nested macro invocations.
   */
  public static MacroEffectSummary derive(
      MacroDefinition macro,
      Map<String, String> defines,
      Function<String, MacroDefinition> macroResolver) {
    return deriveInternal(
        macro, defines != null ? defines : Map.of(), macroResolver, new HashSet<>(), 0);
  }

  private static MacroEffectSummary deriveInternal(
      MacroDefinition macro,
      Map<String, String> defines,
      Function<String, MacroDefinition> macroResolver,
      Set<String> activeMacros,
      int depth) {
    Map<String, Integer> paramIndex = new HashMap<>();
    for (int i = 0; i < macro.parameters().size(); i++) {
      paramIndex.put(macro.parameters().get(i).toLowerCase(Locale.ROOT), i);
    }

    Set<Integer> upwardReadParams = new LinkedHashSet<>();
    Set<Integer> writtenParams = new LinkedHashSet<>();
    Set<Integer> addressParams = new LinkedHashSet<>();
    Set<Integer> r1ToR15Params = new LinkedHashSet<>();
    Set<String> physicalReads = new LinkedHashSet<>();
    Set<String> physicalClobbers = new LinkedHashSet<>();
    int stackDelta = 0;

    String macroKey = macro.name().toLowerCase(Locale.ROOT);
    if (depth > MAX_MACRO_NESTING || !activeMacros.add(macroKey)) {
      return new MacroEffectSummary(
          macro.name(),
          macro.parameters(),
          upwardReadParams,
          writtenParams,
          addressParams,
          r1ToR15Params,
          physicalReads,
          physicalClobbers,
          0);
    }

    try {
      ModalStepEvaluator.ModalState modal = ModalStepEvaluator.ModalState.DEFAULT;
      for (int i = 0; i < macro.bodyLines().size(); i++) {
        List<Token> rawLine = macro.bodyLines().get(i);
        if (rawLine == null || rawLine.isEmpty()) {
          continue;
        }
        List<Token> line = applySimpleDefines(rawLine, defines, paramIndex);
        ModalStepEvaluator.StepResult step = ModalStepEvaluator.step(modal, line, i, null);
        modal = step.nextState();

        if (step.kind() == ModalStepEvaluator.StepKind.INSTRUCTION
            || step.kind() == ModalStepEvaluator.StepKind.DSL_STATEMENT) {
          ModalStepEvaluator.InstructionEffect eff = step.effect();
          if (eff == null) continue;

          for (String addr : eff.addressOperands()) {
            Integer pIdx = paramIndex.get(addr.toLowerCase(Locale.ROOT));
            if (pIdx != null) {
              addressParams.add(pIdx);
            }
          }
          for (String r1 : eff.r1ToR15Operands()) {
            Integer pIdx = paramIndex.get(r1.toLowerCase(Locale.ROOT));
            if (pIdx != null) {
              r1ToR15Params.add(pIdx);
            }
          }
          for (String r : eff.reads()) {
            String key = r.toLowerCase(Locale.ROOT);
            Integer pIdx = paramIndex.get(key);
            if (pIdx != null) {
              if (!writtenParams.contains(pIdx)) {
                upwardReadParams.add(pIdx);
              }
            } else if (SuperFxIsa.parsePhysicalRegister(key).isPresent()) {
              if (!physicalClobbers.contains(key)) {
                physicalReads.add(key);
              }
            }
          }
          for (String w : eff.writes()) {
            String key = w.toLowerCase(Locale.ROOT);
            Integer pIdx = paramIndex.get(key);
            if (pIdx != null) {
              writtenParams.add(pIdx);
            } else if (SuperFxIsa.parsePhysicalRegister(key).isPresent()) {
              physicalClobbers.add(key);
            }
          }

          stackDelta += computeStepStackDelta(eff);
        } else if (step.kind() == ModalStepEvaluator.StepKind.MACRO_OR_OTHER
            && step.effect() != null
            && macroResolver != null) {
          String calleeName = step.effect().mnemonic();
          MacroDefinition nested = macroResolver.apply(calleeName);
          if (nested != null) {
            MacroEffectSummary sub =
                deriveInternal(nested, defines, macroResolver, activeMacros, depth + 1);
            List<List<Token>> callArgs =
                ModalStepEvaluator.splitArguments(line, step.effect().opcodeIndex() + 1);

            for (int argIdx : sub.addressParams()) {
              String callerSym = extractArgSymbol(callArgs, argIdx);
              if (callerSym != null) {
                Integer pIdx = paramIndex.get(callerSym.toLowerCase(Locale.ROOT));
                if (pIdx != null) addressParams.add(pIdx);
              }
            }
            for (int argIdx : sub.r1ToR15Params()) {
              String callerSym = extractArgSymbol(callArgs, argIdx);
              if (callerSym != null) {
                Integer pIdx = paramIndex.get(callerSym.toLowerCase(Locale.ROOT));
                if (pIdx != null) r1ToR15Params.add(pIdx);
              }
            }
            for (int argIdx : sub.upwardReadParams()) {
              String callerSym = extractArgSymbol(callArgs, argIdx);
              if (callerSym != null) {
                String key = callerSym.toLowerCase(Locale.ROOT);
                Integer pIdx = paramIndex.get(key);
                if (pIdx != null) {
                  if (!writtenParams.contains(pIdx)) upwardReadParams.add(pIdx);
                } else if (SuperFxIsa.parsePhysicalRegister(key).isPresent()
                    && !physicalClobbers.contains(key)) {
                  physicalReads.add(key);
                }
              }
            }
            for (String pr : sub.physicalReads()) {
              if (!physicalClobbers.contains(pr)) {
                physicalReads.add(pr);
              }
            }
            for (int argIdx : sub.writtenParams()) {
              String callerSym = extractArgSymbol(callArgs, argIdx);
              if (callerSym != null) {
                String key = callerSym.toLowerCase(Locale.ROOT);
                Integer pIdx = paramIndex.get(key);
                if (pIdx != null) {
                  writtenParams.add(pIdx);
                } else if (SuperFxIsa.parsePhysicalRegister(key).isPresent()) {
                  physicalClobbers.add(key);
                }
              }
            }
            physicalClobbers.addAll(sub.physicalClobbers());
            stackDelta += sub.stackDelta();
          }
        }
      }
    } finally {
      activeMacros.remove(macroKey);
    }

    return new MacroEffectSummary(
        macro.name(),
        macro.parameters(),
        upwardReadParams,
        writtenParams,
        addressParams,
        r1ToR15Params,
        physicalReads,
        physicalClobbers,
        stackDelta);
  }

  private static List<Token> applySimpleDefines(
      List<Token> line, Map<String, String> defines, Map<String, Integer> paramIndex) {
    boolean changed = false;
    for (Token t : line) {
      String lower = t.text().toLowerCase(Locale.ROOT);
      if ("stackpointer".equals(lower)
          || (!paramIndex.containsKey(lower) && defines.containsKey(lower))) {
        changed = true;
        break;
      }
    }
    if (!changed) {
      return line;
    }
    List<Token> mapped = new ArrayList<>(line.size());
    for (Token t : line) {
      String lower = t.text().toLowerCase(Locale.ROOT);
      String replacement = null;
      if (!paramIndex.containsKey(lower)) {
        replacement = defines.get(lower);
        if (replacement == null && "stackpointer".equals(lower)) {
          replacement = "r10";
        }
      }
      if (replacement != null && SuperFxIsa.parsePhysicalRegister(replacement).isPresent()) {
        mapped.add(
            new Token(
                TokenType.TOK_REGISTER,
                t.startIndex,
                t.endIndex,
                replacement,
                t.message,
                t.lineNumber,
                t.intVal,
                t.attributes()));
      } else {
        mapped.add(t);
      }
    }
    return mapped;
  }

  private static String extractArgSymbol(List<List<Token>> callArgs, int idx) {
    if (idx < 0 || idx >= callArgs.size()) {
      return null;
    }
    List<Token> arg = callArgs.get(idx);
    if (arg.isEmpty()) {
      return null;
    }
    if (arg.size() == 3
        && arg.get(0).type == TokenType.TOK_LPAREN
        && arg.get(2).type == TokenType.TOK_RPAREN) {
      return arg.get(1).text();
    }
    return arg.get(0).text();
  }

  private static int computeStepStackDelta(ModalStepEvaluator.InstructionEffect eff) {
    String m = eff.mnemonic();
    if ("inc".equals(m) && eff.writes().contains("r10")) {
      return 1;
    }
    if ("dec".equals(m) && eff.writes().contains("r10")) {
      return -1;
    }
    if (("add".equals(m) || "sub".equals(m))
        && "r10".equalsIgnoreCase(eff.consumedState().sourceReg())
        && "r10".equalsIgnoreCase(eff.consumedState().destReg())
        && eff.immediate().isPresent()
        && eff.immediate().get().isConstant()) {
      int imm = eff.immediate().get().signedValue();
      return "add".equals(m) ? imm : -imm;
    }
    return 0;
  }
}
