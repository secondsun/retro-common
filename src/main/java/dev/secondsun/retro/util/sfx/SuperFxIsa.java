package dev.secondsun.retro.util.sfx;

import dev.secondsun.retro.util.Token;
import dev.secondsun.retro.util.TokenType;
import dev.secondsun.retro.util.vo.ImmediateLiteral;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Authoritative declarative catalog of SuperFX (GSU / FX3) instructions and standard {@code
 * GSU_Assembler.i} / X-GSU pseudo-instructions, including operand bounds, implicit register
 * effects, control-flow properties, and exact byte weights.
 */
public final class SuperFxIsa {

  private SuperFxIsa() {}

  public enum Category {
    MODAL_PREFIX,
    PSEUDO_MOVE,
    ALU,
    LOAD_IMMEDIATE,
    MEMORY_INDIRECT,
    MEMORY_DIRECT,
    ROM_CACHE,
    PLOTTER,
    BRANCH,
    JUMP,
    CONTROL
  }

  /**
   * Declarative specification of a SuperFX opcode or assembler pseudo-op.
   *
   * @param mnemonic lowercase canonical mnemonic
   * @param category semantic opcode category
   * @param minArgs minimum operand count
   * @param maxArgs maximum operand count
   * @param baseByteWeight assembled byte weight (for register/implied form)
   * @param immByteWeight assembled byte weight when an immediate operand is used
   * @param minRegister minimum allowed physical register index for register operands
   * @param maxRegister maximum allowed physical register index for register operands
   * @param acceptsImmediate whether an immediate {@code #n} operand is legal
   * @param immediateOnly whether ONLY an immediate operand is legal (e.g. {@code link})
   * @param minImmediate minimum immediate literal value when {@code acceptsImmediate} is true
   * @param maxImmediate maximum immediate literal value when {@code acceptsImmediate} is true
   * @param readsSreg whether execution reads the active modal {@code Sreg}
   * @param writesDreg whether execution writes the active modal {@code Dreg}
   * @param implicitReads physical registers implicitly read regardless of operands
   * @param implicitWrites physical registers implicitly written regardless of operands
   */
  public record OpcodeSpec(
      String mnemonic,
      Category category,
      int minArgs,
      int maxArgs,
      int baseByteWeight,
      int immByteWeight,
      int minRegister,
      int maxRegister,
      boolean acceptsImmediate,
      boolean immediateOnly,
      int minImmediate,
      int maxImmediate,
      boolean readsSreg,
      boolean writesDreg,
      List<String> implicitReads,
      List<String> implicitWrites) {
    public OpcodeSpec {
      implicitReads = implicitReads != null ? List.copyOf(implicitReads) : List.of();
      implicitWrites = implicitWrites != null ? List.copyOf(implicitWrites) : List.of();
    }

    public boolean isZeroArg() {
      return minArgs == 0 && maxArgs == 0;
    }

    public boolean isModalPrefix() {
      return category == Category.MODAL_PREFIX;
    }

    public boolean isPseudoMove() {
      return category == Category.PSEUDO_MOVE;
    }

    public boolean isBranch() {
      return category == Category.BRANCH;
    }

    public boolean hasDelaySlot() {
      return category == Category.BRANCH || "jmp".equals(mnemonic) || "ljmp".equals(mnemonic);
    }
  }

  /**
   * Mnemonics shared with 6502/65816 assembly that require dialect disambiguation in `.s` files.
   */
  public static final Set<String> SHARED_65816_MNEMONICS =
      Set.of(
          "adc", "sbc", "cmp", "and", "inc", "dec", "lsr", "rol", "ror", "jmp", "bra", "bcc", "bcs",
          "beq", "bne", "bmi", "bpl", "bvc", "bvs", "nop");

  private static final Map<String, OpcodeSpec> CATALOG;

  static {
    Map<String, OpcodeSpec> m = new LinkedHashMap<>();

    // Modal prefixes
    reg(m, modal("with", 1));
    reg(m, modal("to", 1));
    reg(m, modal("from", 1));
    reg(
        m,
        new OpcodeSpec(
            "alt1",
            Category.MODAL_PREFIX,
            0,
            0,
            1,
            1,
            0,
            15,
            false,
            false,
            0,
            0,
            false,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "alt2",
            Category.MODAL_PREFIX,
            0,
            0,
            1,
            1,
            0,
            15,
            false,
            false,
            0,
            0,
            false,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "alt3",
            Category.MODAL_PREFIX,
            0,
            0,
            1,
            1,
            0,
            15,
            false,
            false,
            0,
            0,
            false,
            false,
            List.of(),
            List.of()));

    // Pseudo-moves
    reg(
        m,
        new OpcodeSpec(
            "move",
            Category.PSEUDO_MOVE,
            2,
            2,
            2,
            2,
            0,
            15,
            true,
            false,
            -32768,
            65535,
            false,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "moves",
            Category.PSEUDO_MOVE,
            2,
            2,
            4,
            4,
            0,
            15,
            false,
            false,
            0,
            0,
            false,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "moveb",
            Category.PSEUDO_MOVE,
            2,
            2,
            3,
            3,
            0,
            15,
            false,
            false,
            0,
            0,
            false,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "movew",
            Category.PSEUDO_MOVE,
            2,
            2,
            2,
            2,
            0,
            15,
            false,
            false,
            0,
            0,
            false,
            false,
            List.of(),
            List.of()));

    // Immediate loads
    reg(
        m,
        new OpcodeSpec(
            "ibt",
            Category.LOAD_IMMEDIATE,
            2,
            2,
            2,
            2,
            0,
            15,
            true,
            false,
            0,
            255,
            false,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "iwt",
            Category.LOAD_IMMEDIATE,
            2,
            2,
            3,
            3,
            0,
            15,
            true,
            false,
            -32768,
            65535,
            false,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "lea",
            Category.LOAD_IMMEDIATE,
            2,
            2,
            3,
            3,
            0,
            15,
            true,
            false,
            -32768,
            65535,
            false,
            false,
            List.of(),
            List.of()));

    // ALU (R0..R15 + #0..#15)
    reg(
        m,
        new OpcodeSpec(
            "add",
            Category.ALU,
            1,
            1,
            1,
            2,
            0,
            15,
            true,
            false,
            0,
            15,
            true,
            true,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "adc",
            Category.ALU,
            1,
            1,
            2,
            2,
            0,
            15,
            true,
            false,
            0,
            15,
            true,
            true,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "sub",
            Category.ALU,
            1,
            1,
            1,
            2,
            0,
            15,
            true,
            false,
            0,
            15,
            true,
            true,
            List.of(),
            List.of()));

    // ALU register-only (R0..R15, NO immediate form!)
    reg(
        m,
        new OpcodeSpec(
            "sbc",
            Category.ALU,
            1,
            1,
            2,
            2,
            0,
            15,
            false,
            false,
            0,
            0,
            true,
            true,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "cmp",
            Category.ALU,
            1,
            1,
            2,
            2,
            0,
            15,
            false,
            false,
            0,
            0,
            true,
            false,
            List.of(),
            List.of()));

    // ALU R1..R15 + #0..#15
    reg(
        m,
        new OpcodeSpec(
            "and",
            Category.ALU,
            1,
            1,
            1,
            2,
            1,
            15,
            true,
            false,
            0,
            15,
            true,
            true,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "bic",
            Category.ALU,
            1,
            1,
            2,
            2,
            1,
            15,
            true,
            false,
            0,
            15,
            true,
            true,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "or",
            Category.ALU,
            1,
            1,
            1,
            2,
            1,
            15,
            true,
            false,
            0,
            15,
            true,
            true,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "xor",
            Category.ALU,
            1,
            1,
            2,
            2,
            1,
            15,
            true,
            false,
            0,
            15,
            true,
            true,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "mult",
            Category.ALU,
            1,
            1,
            1,
            2,
            1,
            15,
            true,
            false,
            0,
            15,
            true,
            true,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "umult",
            Category.ALU,
            1,
            1,
            2,
            2,
            1,
            15,
            true,
            false,
            0,
            15,
            true,
            true,
            List.of(),
            List.of()));

    // INC / DEC (R0..R14 explicit target, does not use Sreg/Dreg)
    reg(
        m,
        new OpcodeSpec(
            "inc",
            Category.ALU,
            1,
            1,
            1,
            1,
            0,
            14,
            false,
            false,
            0,
            0,
            false,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "dec",
            Category.ALU,
            1,
            1,
            1,
            1,
            0,
            14,
            false,
            false,
            0,
            0,
            false,
            false,
            List.of(),
            List.of()));

    // SHL macro pseudo-op (0 or 1 register arg, 3B: add #0 + [with R] + rol)
    reg(
        m,
        new OpcodeSpec(
            "shl",
            Category.ALU,
            0,
            1,
            3,
            3,
            0,
            15,
            false,
            false,
            0,
            0,
            false,
            false,
            List.of(),
            List.of()));

    // Zero-arg ALU & multipliers
    reg(m, zeroArg("asr", Category.ALU, 1, true, true, List.of(), List.of()));
    reg(m, zeroArg("div2", Category.ALU, 2, true, true, List.of(), List.of()));
    reg(m, zeroArg("lsr", Category.ALU, 1, true, true, List.of(), List.of()));
    reg(m, zeroArg("rol", Category.ALU, 1, true, true, List.of(), List.of()));
    reg(m, zeroArg("ror", Category.ALU, 1, true, true, List.of(), List.of()));
    reg(m, zeroArg("hib", Category.ALU, 1, true, true, List.of(), List.of()));
    reg(m, zeroArg("lob", Category.ALU, 1, true, true, List.of(), List.of()));
    reg(m, zeroArg("sex", Category.ALU, 1, true, true, List.of(), List.of()));
    reg(m, zeroArg("swap", Category.ALU, 1, true, true, List.of(), List.of()));
    reg(m, zeroArg("not", Category.ALU, 1, true, true, List.of(), List.of()));
    reg(m, zeroArg("fmult", Category.ALU, 1, true, true, List.of("r6"), List.of()));
    reg(m, zeroArg("lmult", Category.ALU, 2, true, true, List.of("r6"), List.of("r4")));
    reg(m, zeroArg("merge", Category.CONTROL, 1, false, true, List.of("r7", "r8"), List.of()));

    // Indirect GSURAM memory: (R0..R11)
    reg(
        m,
        new OpcodeSpec(
            "ldw",
            Category.MEMORY_INDIRECT,
            1,
            1,
            1,
            1,
            0,
            11,
            false,
            false,
            0,
            0,
            false,
            true,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "ldb",
            Category.MEMORY_INDIRECT,
            1,
            1,
            2,
            2,
            0,
            11,
            false,
            false,
            0,
            0,
            false,
            true,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "stw",
            Category.MEMORY_INDIRECT,
            1,
            1,
            1,
            1,
            0,
            11,
            false,
            false,
            0,
            0,
            true,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "stb",
            Category.MEMORY_INDIRECT,
            1,
            1,
            2,
            2,
            0,
            11,
            false,
            false,
            0,
            0,
            true,
            false,
            List.of(),
            List.of()));
    reg(m, zeroArg("sbk", Category.MEMORY_INDIRECT, 1, true, false, List.of(), List.of()));

    // Direct GSURAM memory
    reg(
        m,
        new OpcodeSpec(
            "lm",
            Category.MEMORY_DIRECT,
            2,
            2,
            4,
            4,
            0,
            15,
            false,
            false,
            0,
            0,
            false,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "sm",
            Category.MEMORY_DIRECT,
            2,
            2,
            4,
            4,
            0,
            15,
            false,
            false,
            0,
            0,
            false,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "lms",
            Category.MEMORY_DIRECT,
            2,
            2,
            3,
            3,
            0,
            15,
            false,
            false,
            0,
            510,
            false,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "sms",
            Category.MEMORY_DIRECT,
            2,
            2,
            3,
            3,
            0,
            15,
            false,
            false,
            0,
            510,
            false,
            false,
            List.of(),
            List.of()));

    // ROM / RAM bank & byte fetchers
    reg(m, zeroArg("getb", Category.ROM_CACHE, 1, false, true, List.of("r14"), List.of()));
    reg(m, zeroArg("getbh", Category.ROM_CACHE, 2, true, true, List.of("r14"), List.of()));
    reg(m, zeroArg("getbl", Category.ROM_CACHE, 2, true, true, List.of("r14"), List.of()));
    reg(m, zeroArg("getbs", Category.ROM_CACHE, 2, false, true, List.of("r14"), List.of()));
    reg(m, zeroArg("getc", Category.ROM_CACHE, 1, false, false, List.of("r14"), List.of()));
    reg(m, zeroArg("romb", Category.ROM_CACHE, 2, true, false, List.of(), List.of()));
    reg(m, zeroArg("ramb", Category.ROM_CACHE, 2, true, false, List.of(), List.of()));

    // Plotter
    reg(m, zeroArg("plot", Category.PLOTTER, 1, false, false, List.of("r1", "r2"), List.of("r1")));
    reg(m, zeroArg("rpix", Category.PLOTTER, 2, false, false, List.of(), List.of()));
    reg(m, zeroArg("color", Category.PLOTTER, 1, true, false, List.of(), List.of()));
    reg(m, zeroArg("cmode", Category.PLOTTER, 2, true, false, List.of(), List.of()));

    // Branches & Hardware Loop
    for (String br :
        List.of("bra", "bcc", "bcs", "beq", "bne", "bmi", "bpl", "bvc", "bvs", "bge", "blt")) {
      reg(
          m,
          new OpcodeSpec(
              br,
              Category.BRANCH,
              1,
              1,
              2,
              2,
              0,
              15,
              false,
              false,
              0,
              0,
              false,
              false,
              List.of(),
              List.of()));
    }
    reg(
        m,
        zeroArg("loop", Category.BRANCH, 1, false, false, List.of("r12", "r13"), List.of("r12")));

    // Jumps & Link
    reg(
        m,
        new OpcodeSpec(
            "jmp",
            Category.JUMP,
            1,
            1,
            1,
            1,
            8,
            13,
            false,
            false,
            0,
            0,
            false,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "ljmp",
            Category.JUMP,
            1,
            1,
            2,
            2,
            8,
            13,
            false,
            false,
            0,
            0,
            true,
            false,
            List.of(),
            List.of()));
    reg(
        m,
        new OpcodeSpec(
            "link",
            Category.JUMP,
            1,
            1,
            1,
            1,
            0,
            15,
            true,
            true,
            1,
            4,
            false,
            false,
            List.of("r15"),
            List.of("r11")));

    // Control
    reg(m, zeroArg("nop", Category.CONTROL, 1, false, false, List.of(), List.of()));
    reg(m, zeroArg("stop", Category.CONTROL, 1, false, false, List.of(), List.of()));
    reg(m, zeroArg("cache", Category.CONTROL, 1, false, false, List.of(), List.of()));

    CATALOG = Collections.unmodifiableMap(m);
  }

  private static void reg(Map<String, OpcodeSpec> map, OpcodeSpec spec) {
    map.put(spec.mnemonic(), spec);
  }

  private static OpcodeSpec modal(String mnemonic, int bytes) {
    return new OpcodeSpec(
        mnemonic,
        Category.MODAL_PREFIX,
        1,
        1,
        bytes,
        bytes,
        0,
        15,
        false,
        false,
        0,
        0,
        false,
        false,
        List.of(),
        List.of());
  }

  private static OpcodeSpec zeroArg(
      String mnemonic,
      Category cat,
      int bytes,
      boolean readsSreg,
      boolean writesDreg,
      List<String> implicitReads,
      List<String> implicitWrites) {
    return new OpcodeSpec(
        mnemonic,
        cat,
        0,
        0,
        bytes,
        bytes,
        0,
        15,
        false,
        false,
        0,
        0,
        readsSreg,
        writesDreg,
        implicitReads,
        implicitWrites);
  }

  /** Returns the immutable opcode catalog keyed by lowercase mnemonic. */
  public static Map<String, OpcodeSpec> catalog() {
    return CATALOG;
  }

  /** Looks up an {@link OpcodeSpec} case-insensitively by mnemonic. */
  public static Optional<OpcodeSpec> lookup(String mnemonic) {
    if (mnemonic == null || mnemonic.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(CATALOG.get(mnemonic.toLowerCase(Locale.ROOT)));
  }

  /** Returns true if {@code mnemonic} is a SuperFX hardware opcode or GSU assembler pseudo-op. */
  public static boolean isMnemonic(String mnemonic) {
    return lookup(mnemonic).isPresent();
  }

  /** Returns true if {@code mnemonic} is exclusive to SuperFX (not shared with 65816 assembly). */
  public static boolean isExclusiveSuperFxMnemonic(String mnemonic) {
    if (mnemonic == null || mnemonic.isEmpty()) {
      return false;
    }
    String lower = mnemonic.toLowerCase(Locale.ROOT);
    return CATALOG.containsKey(lower) && !SHARED_65816_MNEMONICS.contains(lower);
  }

  /** Parses a physical register token name (`r0`..`r15` / `R0`..`R15`) into `0..15`. */
  public static OptionalInt parsePhysicalRegister(String text) {
    if (text == null || (text.length() != 2 && text.length() != 3)) {
      return OptionalInt.empty();
    }
    char c0 = text.charAt(0);
    if (c0 != 'r' && c0 != 'R') {
      return OptionalInt.empty();
    }
    char c1 = text.charAt(1);
    if (text.length() == 2) {
      if (c1 >= '0' && c1 <= '9') {
        return OptionalInt.of(c1 - '0');
      }
      return OptionalInt.empty();
    }
    char c2 = text.charAt(2);
    if (c1 == '1' && c2 >= '0' && c2 <= '5') {
      return OptionalInt.of(10 + (c2 - '0'));
    }
    return OptionalInt.empty();
  }

  /**
   * Normalizes a physical register name to canonical lowercase (`"r0"`..`"r15"`), or returns
   * lowercase identifier.
   */
  public static String normalizeRegisterOrVar(String text) {
    if (text == null) {
      return "";
    }
    OptionalInt reg = parsePhysicalRegister(text);
    if (reg.isPresent()) {
      return "r" + reg.getAsInt();
    }
    return text;
  }

  /**
   * Computes the assembled byte weight of a statement starting at {@code opcodeIdx} in {@code
   * tokens}. Handles both SuperFX instructions and X-GSU DSL macros (`for`, `forR`, `endfor`,
   * `call`, `return`, `function`, `endfunction`).
   */
  public static int byteWeight(List<Token> tokens, int opcodeIdx, String lineText) {
    if (tokens == null || opcodeIdx < 0 || opcodeIdx >= tokens.size()) {
      return 0;
    }
    String m = tokens.get(opcodeIdx).text().toLowerCase(Locale.ROOT);
    if (m.startsWith(".")
        || "function".equals(m)
        || "inline".equals(m)
        || "endfunction".equals(m)
        || "register".equals(m)) {
      return 0;
    }
    switch (m) {
      case "for":
        return 3;
      case "forr":
        return 5;
      case "endfor":
      case "return":
        return 2;
      case "call":
        return 4;
      case "if_lt":
      case "if_gt":
      case "if_eq":
      case "if_lte":
        return 8;
      default:
        break;
    }

    OpcodeSpec spec = CATALOG.get(m);
    if (spec == null) {
      return 4; // conservative estimate for userland macro invocation
    }

    int hashIdx = -1;
    for (int i = opcodeIdx + 1; i < tokens.size(); i++) {
      if (tokens.get(i).type == TokenType.TOK_HASH) {
        hashIdx = i;
        break;
      }
    }

    if ("move".equals(m) && hashIdx >= 0) {
      Optional<ImmediateLiteral> imm = ImmediateLiteral.parse(tokens, hashIdx, lineText);
      if (imm.isPresent() && imm.get().isConstant()) {
        int val = imm.get().signedValue();
        return (val >= 0 && val <= 255) ? 2 : 3;
      }
      return 3; // symbolic #imm expands to iwt (3B)
    }

    return hashIdx >= 0 ? spec.immByteWeight() : spec.baseByteWeight();
  }
}
