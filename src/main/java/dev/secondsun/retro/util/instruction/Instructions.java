package dev.secondsun.retro.util.instruction;

import dev.secondsun.retro.util.vo.Tokens;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public enum Instructions {
  JMP(new GSUInstruction("JMP", "R8-R13")),
  JMP_INDIRECT(new GSUInstruction("JMP", "(R8-R13)")),
  LJMP(new GSUInstruction("LJMP", "R8-R13")),
  LJMP_INDIRECT(new GSUInstruction("LJMP", "(R8-R13)")),
  BRA(new GSUInstruction("BRA", "E")),
  IWT_JUMP(new GSUInstruction("IWT", "R15", "#n")),
  ADC_REGISTER(new GSUInstruction("ADC", "Rn")),
  ADC_CONST(new GSUInstruction("ADC", "#n")),
  ADD_REGISTER(new GSUInstruction("ADD", "Rn")),
  ADD_CONST(new GSUInstruction("ADD", "#n")),
  ALT1(new GSUInstruction("ALT1")),
  ALT2(new GSUInstruction("ALT2")),
  ALT3(new GSUInstruction("ALT3")),
  AND_REGISTER(new GSUInstruction("AND", "R1-R15")),
  AND_CONST(new GSUInstruction("AND", "#n")),
  ASR(new GSUInstruction("ASR")),
  BCC(new GSUInstruction("BCC", "e")),
  BCS(new GSUInstruction("BCS", "e")),
  BEQ(new GSUInstruction("BEQ", "e")),
  BGE(new GSUInstruction("BGE", "e")),
  BIC_REGISTER(new GSUInstruction("BIC", "R1-R15")),
  BIC_CONST(new GSUInstruction("BIC", "#n")),
  BLT(new GSUInstruction("BLT", "e")),
  BMI(new GSUInstruction("BMI", "e")),
  BNE(new GSUInstruction("BNE", "e")),
  BPL(new GSUInstruction("BPL", "e")),
  BVC(new GSUInstruction("BVC", "e")),
  BVS(new GSUInstruction("BVS", "e")),
  CACHE(new GSUInstruction("CACHE")),
  CMODE(new GSUInstruction("CMODE")),
  CMP(new GSUInstruction("CMP", "Rn")),
  COLOR(new GSUInstruction("COLOR")),
  DEC(new GSUInstruction("DEC", "R0-R14")),
  DIV2(new GSUInstruction("DIV2")),
  FMULT(new GSUInstruction("FMULT")),
  FROM(new GSUInstruction("FROM", "Rn")),
  GETB(new GSUInstruction("GETB")),
  GETBH(new GSUInstruction("GETBH")),
  GETBL(new GSUInstruction("GETBL")),
  GETBS(new GSUInstruction("GETBS")),
  GETC(new GSUInstruction("GETC")),
  HIB(new GSUInstruction("HIB")),
  IBT(new GSUInstruction("IBT", "Rn", "#pp")),
  INC(new GSUInstruction("INC", "R0-R14")),
  IWT(new GSUInstruction("IWT", "Rn", "#x")),
  LDB(new GSUInstruction("LDB", "(Rm)")),
  LDW(new GSUInstruction("LDW", "(Rm)")),
  LEA(new GSUInstruction("LEA", "Rn", "x")),
  LEA_IMM(new GSUInstruction("LEA", "Rn", "#x")),
  LINK(new GSUInstruction("LINK", "#n")),
  LM(new GSUInstruction("LM", "Rn", "(xx)")),
  LMS(new GSUInstruction("LMS", "Rn", "(yy)")),
  LMULT(new GSUInstruction("LMULT")),
  LOB(new GSUInstruction("LOB")),
  LOOP(new GSUInstruction("LOOP")),
  LSR(new GSUInstruction("LSR")),
  MERGE(new GSUInstruction("MERGE")),
  MOVE(new GSUInstruction("MOVE", "Rn", "Rn")),
  MOVE_IMM(new GSUInstruction("MOVE", "Rn", "#x")),
  MOVE_LOAD(new GSUInstruction("MOVE", "Rn", "(xx)")),
  MOVE_STORE(new GSUInstruction("MOVE", "(xx)", "Rn")),
  MOVEB_LOAD(new GSUInstruction("MOVEB", "Rn", "(R0-R15)")),
  MOVEB_STORE(new GSUInstruction("MOVEB", "(R0-R15)", "Rn")),
  MOVES(new GSUInstruction("MOVES", "Rn", "Rn")),
  MOVEW_LOAD(new GSUInstruction("MOVEW", "Rn", "(R0-R15)")),
  MOVEW_STORE(new GSUInstruction("MOVEW", "(R0-R15)", "Rn")),
  MULT_REGISTER(new GSUInstruction("MULT", "R1-R15")),
  MULT_CONST(new GSUInstruction("MULT", "#n")),
  NOP(new GSUInstruction("NOP")),
  NOT(new GSUInstruction("NOT")),
  OR_REGISTER(new GSUInstruction("OR", "R1-R15")),
  OR_CONST(new GSUInstruction("OR", "#n")),
  PLOT(new GSUInstruction("PLOT")),
  RAMB(new GSUInstruction("RAMB")),
  ROL(new GSUInstruction("ROL")),
  ROMB(new GSUInstruction("ROMB")),
  ROR(new GSUInstruction("ROR")),
  RPIX(new GSUInstruction("RPIX")),
  SBC(new GSUInstruction("SBC", "Rn")),
  SBK(new GSUInstruction("SBK")),
  SEX(new GSUInstruction("SEX")),
  SHL(new GSUInstruction("SHL")),
  SHL_REG(new GSUInstruction("SHL", "Rn")),
  SHL_CONST(new GSUInstruction("SHL", "#n")),
  SM(new GSUInstruction("SM", "(xx)", "Rn")),
  SMS(new GSUInstruction("SMS", "(yy)", "Rn")),
  SMS_LEGACY(new GSUInstruction("SMS", "Rn", "(yy)")),
  STB(new GSUInstruction("STB", "(Rm)")),
  STOP(new GSUInstruction("STOP")),
  STW(new GSUInstruction("STW", "(Rm)")),
  SUB_REGISTER(new GSUInstruction("SUB", "Rn")),
  SUB_CONST(new GSUInstruction("SUB", "#n")),
  SWAP(new GSUInstruction("SWAP")),
  TO(new GSUInstruction("TO", "Rn")),
  UMULT_REGISTER(new GSUInstruction("UMULT", "R1-R15")),
  UMULT_CONST(new GSUInstruction("UMULT", "#n")),
  WITH(new GSUInstruction("WITH", "Rn")),
  XOR_REGISTER(new GSUInstruction("XOR", "R1-R15")),
  XOR_CONST(new GSUInstruction("XOR", "#n"));

  public static final Map<String, Instructions> instructionLookupTable;

  public static final List<Instructions> unconditionalJumpInstructions;
  public static final List<Instructions> conditionalJumpInstructions;

  static {
    instructionLookupTable = new HashMap<>(Instructions.values().length);
    for (Instructions i : Instructions.values()) {
      instructionLookupTable.putIfAbsent(i.instruction.instruction, i);
    }
    instructionLookupTable.put("IWT", Instructions.IWT);

    unconditionalJumpInstructions =
        List.of(
            Instructions.JMP,
            Instructions.JMP_INDIRECT,
            Instructions.LJMP,
            Instructions.LJMP_INDIRECT,
            Instructions.BRA,
            Instructions.IWT_JUMP);

    conditionalJumpInstructions =
        List.of(
            Instructions.BGE,
            Instructions.BNE,
            Instructions.BCC,
            Instructions.BCS,
            Instructions.BEQ,
            Instructions.BLT,
            Instructions.BMI,
            Instructions.BPL,
            Instructions.BVS,
            Instructions.BVC);
  }

  public final GSUInstruction instruction;

  Instructions(GSUInstruction gsuInstruction) {
    this.instruction = gsuInstruction;
  }

  public boolean matches(Tokens tokens) {
    return this.instruction.matches(tokens);
  }

  public boolean matches(Tokens tokens, Predicate<String> isRegisterLabel) {
    return this.instruction.matches(tokens, isRegisterLabel);
  }
}
