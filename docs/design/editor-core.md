# Editor core and diff display model (lane F2)

Status: design written before code (wave 2). Pure Kotlin under
`core-engine/src/main/java/dev/fonebrew/domain/editor/`, JVM-tested. **No UI is touched.**
A later touch-first editor surface sits on this; today CodeLens
(`ui/codelens/CodeLensScreen.kt`) is a read-only highlighted `Text` and is left alone.

## What this builds

| Package | Contents |
|---|---|
| `editor/buffer/` | `Rope` (immutable balanced chunk tree, O(log n) edits, line index), `TextBuffer` (mutable holder + snapshots), `TextUnits` (UTF-16 / code point / grapheme conversions), `GraphemeBreaks`, `LineEnding` profile, `TextEncoding` (detect / decode / encode), `OpenPolicy` (large-file guard) |
| `editor/edit/` | `Selection`/`SelectionSet`, `TextEdit` + `Transaction`, `UndoHistory`, `IndentStyle`, `CommentSyntax`, `Editor` (EditOps over a buffer + selections + history) |
| `editor/find/` | `FindQuery`, `FindEngine`, `FindSession` (literal, whole-word, case, bounded regex, replace one/all) |
| `editor/lex/` | `Token`/`TokenCategory`/`StyleHint`, `LexState`, `LineLexer`, `IncrementalLexer`, 15 language definitions, `Languages` registry |
| `editor/diff/` | `DiffModel` (unified + side-by-side rows, intra-line spans, hunks with stable ids), `FoldedView`, `HunkNavigator`, `HunkIntent`, `ThreeWayDisplay` + the conflict-model interface |

Reused, not duplicated: `domain/diff/LineDiff` (alignment), `ReviewSession`/`Decision`
(per-hunk decisions), `domain/workspace/grep/RegexLint` + `BudgetCharSequence` (the same
catastrophic-backtracking protection as the workspace search lane), the `"UTF-8"` /
`LF|CRLF|CR|MIXED` vocabulary of `document-buffer.schema.json`.

## What this does not build

* No UI, no Compose, no IME composition, no selection handles, no gestures.
* No merge / diff3 / conflict resolution (another lane owns `domain/gitcore/merge`). Only a
  *display* interface (`ThreeWayRegion`) that a conflict model can be adapted to.
* No file I/O. Encoding functions take and return `ByteArray`/`String`; the workspace provider
  stays the I/O owner and `DocumentBufferMachine` the state-machine owner. The editor does not
  duplicate the Closed/OpenClean/OpenDirty lifecycle; it exposes `isDirty` (a content-version
  comparison that undo restores) for the machine's `Edit`/`SaveSucceeded` events.
* No embedded-language lexing (JS inside HTML, SQL in strings), no semantic/LSP highlighting.
* No bidi/RTL cursor semantics, no visual-column (tab-width, wide glyph) mapping: columns here are
  UTF-16 code units; the renderer owns visual columns.
* Shell heredocs are handled; Python/JS f-string / template-literal *interior* expressions are
  lexed as part of the string (no nesting).

## Key decisions

1. **Raw text is the document.** The buffer holds the file's decoded characters exactly,
   including `\r\n` / `\r` / `\n` terminators. Save is `encode(text)` with the original BOM, so
   a no-op round trip is byte-exact by construction (no normalise-and-restore step to get
   wrong): line endings, mixed endings and a missing final newline are all preserved. The
   detected `LineEnding` (`LF`/`CRLF`/`CR`/`MIXED`, dominant style for MIXED) only decides what
   a *newly typed* newline looks like.
2. **Rope = persistent treap of chunks (<= 1024 UTF-16 units).** Aggregates per node: length and
   line-break count. Chunk seams never split `\r\n` (merge on seam), so per-chunk break counts
   are self-contained. Immutable nodes make `snapshot()` free, which the lexer, find and diff
   consume without locking.
3. **Offsets are UTF-16 code units everywhere**; conversion to code points / grapheme boundaries
   is explicit (`TextUnits`). Cursor movement and backspace go through `GraphemeBreaks`, a
   self-contained simplified UAX #29 segmenter (CR LF, controls, Extend/ZWJ sequences, emoji
   modifiers, regional-indicator pairs, Hangul L/V/T, spacing marks). It is deterministic across
   JVM and Android on purpose (the platform `BreakIterator` differs between ICU and the JDK).
   Known gap: Indic conjunct clusters and a few Prepend cases follow the simple rules only.
4. **Edits are `Transaction`s** (a sorted, non-overlapping edit list applied atomically, with
   its exact inverse). Every command, multi-cursor included, produces one. History stores
   transactions, so undo is the inverse by construction, not a re-diff.
5. **Undo grouping:** typing bursts coalesce (contiguous inserts, same kind, gap <= 1000 ms
   by the caller-supplied clock, never across a newline or a kind change); bounded by entry
   count and total stored characters (oldest dropped, newest never); a new edit clears redo.
   A dropped entry means undo stops there: undo-all returns the original text only within the
   bound (stated; the property test runs inside it).
6. **Find** runs literal search directly and regex through the `RegexLint` screen then a
   `BudgetCharSequence` budget; over budget is a typed `Timeout`, a lint hit is `RiskyPattern`
   (caller may override, still budgeted). Replace-all is one `Transaction` (one undo step).
   Caveat inherited from the search lane: on Android `java.util.regex` may copy the input so the
   budget is JVM-proven only; the lint is the engine-independent layer.
7. **Tokens carry category + style hint.** `TokenCategory` is the listed set plus `PLAIN` and
   `PROPERTY` (object/YAML/TOML/CSS keys). These two are additions made because configs have no
   honest home for keys in the listed set: flagged as an owner question below. `StyleHint` is
   a bit set (bold, italic, underline, strike) with a default per category, so the UI can encode
   meaning without hue. Categories whose glyphs are self-evident (string quotes, digits,
   punctuation) are listed in `TokenStyle.glyphIntrinsic`; a test fails if a category has
   neither a hint nor that listing.
8. **Incremental lexing:** per-line start state (`LexState`, an int code + int arg + optional
   tag) is stored. After an edit the lexer re-lexes from the first changed line and stops at the
   first line, past the edited region, whose end state equals the previously stored one.
   Lines over 4096 chars are lexed up to the cap, the remainder is one token in the category
   active at the cap (bounded work; stated limit).
9. **Diff display** is derived from `LineDiff` run at full context (so one ordered op stream),
   then re-cut. Hunk i here is hunk i of `ReviewSession` (both are maximal runs of changed
   lines), so `HunkIntent`s map 1:1 onto `Decision`s. Hunk ids are content-derived
   (old-side start + FNV of removed/added lines, collision-suffixed), stable across re-folding.

## Failure modes handled

* Edit past end / negative / overlapping edits in one transaction: rejected with a typed error,
  buffer unchanged.
* Lone surrogates can live in the buffer (UTF-16 edits); encode refuses them explicitly rather
  than writing replacement characters.
* Encoding: UTF-32 BOMs, binary (NUL bytes without a UTF-16 BOM), malformed UTF-16 are
  refused with a reason; invalid UTF-8 falls back to a **flagged** Latin-1 (byte-exact round
  trip), and saving Latin-1 text containing chars above U+00FF is refused, never `?`-substituted.
* Large file: `OpenPolicy` -> editable / read-only (whole) / chunked read-only / refused; the
  numbers are stated constants, no OOM path.
* Regex: lint + budget + invalid-pattern typed outcome; match count capped.
* Lexer: every loop advances; fuzz tests over all 15 languages with random bytes.
* Diff: LCS safety valve of `LineDiff` (whole-file replace) is inherited; intra-line diff caps
  token counts and falls back to one whole-line span.
* `ReviewSession.applied()` joins with `"\n"` and drops a trailing newline: noted as an
  out-of-scope defect (reported, not edited); `DiffModel.apply` tracks the final newline itself.

## Stated limits (found by the tests)

* `LineDiff` bounds its LCS table at 8M cells (after trimming common head and tail). A file whose
  changed core exceeds about 2800 x 2800 lines gets one whole-region hunk instead of fine hunks. The
  display model inherits that; it stays lossless but coarse. Raising it is a `domain/diff` decision.
* `java.util.regex` MULTILINE `^` does not match at the very end of the text after a final line
  terminator, so a "prefix every line" replace does not touch the empty last line.
* Find regexes run per call against a character budget (50M reads by default); the lint screens the
  classic nested-quantifier shapes, the budget catches high-degree polynomial blow-ups the lint
  cannot see. JDK 17 memoises some nested-quantifier patterns, so those can finish instead of timing
  out: a test only requires "terminates quickly" for them.
* Pairing of changed lines in the side-by-side view is positional (i-th removed against i-th added),
  not similarity-based.
* Raw Python strings that span lines (`r"""..."""`) lex their continuation lines with escapes on.
* The line moves (up/down) act on the primary selection and collapse the set to it.

## Owner-verified only (never claimed here)

* Feel on a real touch screen (caret placement, scroll, long-press, drag handles).
* IME composition (Gboard, swipe typing, CJK candidates) interacting with the transactions.
* Selection handles and the accessibility selection actions.
* Performance on phone hardware: rope edit latency on large files, lexer cost per keystroke,
  diff on large files; the stated limits are conservative guesses, not measurements.
* Grapheme behaviour for scripts the simplified segmenter does not model (Indic conjuncts).
* Android `java.util.regex` budget behaviour (JVM-proven only).

## Owner questions

1. May `TokenCategory` carry `PLAIN` and `PROPERTY` beyond the ten listed categories?
2. Is `ReviewSession.applied()` dropping the trailing newline wanted, or a defect to fix in
   `domain/diff`? (This lane did not touch it.)
3. Large-file thresholds (editable <= 4 MiB, read-only <= 32 MiB, then chunked): confirm or
   replace after device measurement.
