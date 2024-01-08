package dev.secondsun.retro.util;

public enum TokenType {
    TOK_NONE,           /* Start value, invalid */
    TOK_EOF,            /* End of input file */
    TOK_SEP,            /* Separator (usually newline) */
    TOK_IDENT,          /* An identifier */
    TOK_LOCAL_IDENT,    /* A cheap local identifier */

    TOK_INTCON,         /* Integer constant */
    TOK_CHARCON,        /* Character constant */
    TOK_STRCON,         /* String constant */

    TOK_A,              /* A)ccumulator */
    TOK_X,              /* X register */
    TOK_Y,              /* Y register */
    TOK_Z,              /* Z register */
    TOK_S,              /* S register */
    TOK_REG,            /* Sweet16 R.. register (in sweet16 mode) */

    TOK_ASSIGN,         /* := */
    TOK_ULABEL,         /* :++ or :-- */

    TOK_EQ,             /* = */
    TOK_NE,             /* <> */
    TOK_LT,             /* < */
    TOK_GT,             /* > */
    TOK_LE,             /* <= */
    TOK_GE,             /* >= */

    TOK_BOOLAND,        /* .and */
    TOK_BOOLOR,         /* .or */
    TOK_BOOLXOR,        /* .xor */
    TOK_BOOLNOT,        /* .not */

    TOK_PLUS,           /* + */
    TOK_MINUS,          /* - */
    TOK_MUL,            /* * */
    TOK_STAR, /* Alias */
    TOK_DIV,            /* / */
    TOK_MOD,            /* ! */
    TOK_OR,             /* | */
    TOK_XOR,            /* ^ */
    TOK_AND,            /* & */
    TOK_SHL,            /* << */
    TOK_SHR,            /* >> */
    TOK_NOT,            /* ~ */

    TOK_PC,             /* $ if enabled */
    TOK_NAMESPACE,      /* :: */
    TOK_DOT,            /* . */
    TOK_COMMA,          /* , */
    TOK_HASH,           /* # */
    TOK_COLON,          /* : */
    TOK_LPAREN,         /* ( */
    TOK_RPAREN,         /* ) */
    TOK_LBRACK,         /* [ */
    TOK_RBRACK,         /* ] */
    TOK_LCURLY,         /* { */
    TOK_RCURLY,         /* } */
    TOK_AT,             /* @ - in Sweet16 mode */

    TOK_OVERRIDE_ZP,    /* z: */
    TOK_OVERRIDE_ABS,   /* a: */
    TOK_OVERRIDE_FAR,   /* f: */

    TOK_MACPARAM,       /* Macro parameter, not generated by scanner */
    TOK_REPCOUNTER,     /* Repeat counter, not generated by scanner */

    /* The next ones are tokens for the pseudo instructions. Keep together! */
    TOK_FIRSTPSEUDO,
    TOK_A16,            
    TOK_A8,
    TOK_ADDR,
    TOK_ADDRSIZE,
    TOK_ALIGN,
    TOK_ASCIIZ,
    TOK_ASIZE,
    TOK_ASSERT,
    TOK_AUTOIMPORT,
    TOK_BANK,
    TOK_BANKBYTE,
    TOK_BANKBYTES,
    TOK_BLANK,
    TOK_BSS,
    TOK_BYTE,
    TOK_CASE,
    TOK_CHARMAP,
    TOK_CODE,
    TOK_CONCAT,
    TOK_CONDES,
    TOK_CONST,
    TOK_CONSTRUCTOR,
    TOK_CPU,
    TOK_DATA,
    TOK_DBG,
    TOK_DBYT,
    TOK_DEBUGINFO,
    TOK_DEFINE,
    TOK_DEFINED,
    TOK_DEFINEDMACRO,
    TOK_DELMAC,
    TOK_DESTRUCTOR,
    TOK_DWORD,
    TOK_ELSE,
    TOK_ELSEIF,
    TOK_END,
    TOK_ENDENUM,
    TOK_ENDIF,
    TOK_ENDMACRO,
    TOK_ENDPROC,
    TOK_ENDREP,
    TOK_ENDSCOPE,
    TOK_ENDSTRUCT,
    TOK_ENDUNION,
    TOK_ENUM,
    TOK_ERROR,
    TOK_EXITMACRO,
    TOK_EXPORT,
    TOK_EXPORTZP,
    TOK_FARADDR,
    TOK_FATAL,
    TOK_FEATURE,
    TOK_FILEOPT,
    TOK_FORCEIMPORT,
    TOK_FORCEWORD,
    TOK_GLOBAL,
    TOK_GLOBALZP,
    TOK_HIBYTE,
    TOK_HIBYTES,
    TOK_HIWORD,
    TOK_I16,
    TOK_I8,
    TOK_MAKEIDENT,
    TOK_IF,
    TOK_IFBLANK,
    TOK_IFCONST,
    TOK_IFDEF,
    TOK_IFNBLANK,
    TOK_IFNCONST,
    TOK_IFNDEF,
    TOK_IFNREF,
    TOK_IFP02,
    TOK_IFP4510,
    TOK_IFP816,
    TOK_IFPC02,
    TOK_IFPDTV,
    TOK_IFPSC02,
    TOK_IFREF,
    TOK_IMPORT,
    TOK_IMPORTZP,
    TOK_INCBIN,
    TOK_INCLUDE,
    TOK_INTERRUPTOR,
    TOK_ISIZE,
    TOK_ISMNEMONIC,
    TOK_LEFT,
    TOK_LINECONT,
    TOK_LIST,
    TOK_LISTBYTES,
    TOK_LITERAL,
    TOK_LOBYTE,
    TOK_LOBYTES,
    TOK_LOCAL,
    TOK_LOCALCHAR,
    TOK_LOWORD,
    TOK_MACPACK,
    TOK_MACRO,
    TOK_MATCH,
    TOK_MAX,
    TOK_MID,
    TOK_MIN,
    TOK_NULL,
    TOK_ORG,
    TOK_OUT,
    TOK_P02,
    TOK_P4510,
    TOK_P816,
    TOK_PAGELENGTH,
    TOK_PARAMCOUNT,
    TOK_PC02,
    TOK_PDTV,
    TOK_POPCHARMAP,
    TOK_POPCPU,
    TOK_POPSEG,
    TOK_PROC,
    TOK_PSC02,
    TOK_PUSHCHARMAP,
    TOK_PUSHCPU,
    TOK_PUSHSEG,
    TOK_REFERENCED,
    TOK_REFERTO,
    TOK_RELOC,
    TOK_REPEAT,
    TOK_RES,
    TOK_RIGHT,
    TOK_RODATA,
    TOK_SCOPE,
    TOK_SEGMENT,
    TOK_SET,
    TOK_SETCPU,
    TOK_SIZEOF,
    TOK_SMART,
    TOK_SPRINTF,
    TOK_STRAT,
    TOK_STRING,
    TOK_STRLEN,
    TOK_STRUCT,
    TOK_TAG,
    TOK_TCOUNT,
    TOK_TIME,
    TOK_UNDEF,
    TOK_UNION,
    TOK_VERSION,
    TOK_WARNING,
    TOK_WORD,
    TOK_XMATCH,
    TOK_ZEROPAGE,
    TOK_LASTPSEUDO
}