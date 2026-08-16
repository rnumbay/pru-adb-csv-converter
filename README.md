# lnaerror-excel-converter

Converts HR1's **`LNAERROR`** artifact — the tilde-delimited, fixed-325-byte LNA validation error
log written by [`pru-adb-poc`](../pru-adb-poc)'s `FileIngestionOutputWriter` — into a formatted
`.xlsx` workbook.

It reads `lnaerror-<cycle>.dat` and writes `lnaerror-<cycle>.xlsx`. Nothing else: no database, no
schema, no domain logic, no web server.

## Why it is a separate deployable

The `.dat` files are the artifacts operations reconciles a run against, and they have to stay
byte-exact at their legacy layout. The workbook is a convenience view for whoever has to actually
read the errors — a different consumer, and one whose format can change without anyone re-certifying
an interface. Keeping the conversion out of the ingestion run also means any past cycle's file can
be re-converted without touching the ingestion job.

## Commands

```bash
mvn test
```

```bash
mvn spring-boot:run
```

With no arguments it converts every `lnaerror-*.dat` in the configured input directory — by default
`../pru-adb-poc/local-data/ingestion-output`, i.e. hr-producer-sync's own `ingestion.output-directory`
with the two repos checked out side by side.

```bash
mvn clean package
```

```bash
java -jar target/lnaerror-excel-converter-0.1.0-SNAPSHOT.jar --input=lnaerror-20260815.dat --output=day1.xlsx
```

| Argument | Meaning |
|---|---|
| `--input=<file>` | Convert one `.dat`. |
| `--input=<dir>` | Convert every file in it matching `lnaerror-excel.file-pattern`. |
| `--output=<dir>` | Write workbooks there, each named after its source. |
| `--output=<name>.xlsx` | Write one workbook at exactly that path (single input only). |
| `--feed=<file>` | Use this feed instead of searching the feed directory. Rarely needed — see below. |
| `--lnaerror-excel.<property>=<value>` | Any property below, same names as in `application.yml`. |

Exit code is `0` when everything asked for converted, `1` when anything did not. One bad file does
not stop the rest of a batch. An input *directory* with nothing to convert is a success — a
scheduled run on a day with no error log has nothing to report.

## The record layout

Offsets are taken from the writer that produces the file and confirmed against a real generated
`lnaerror-20260815.dat`: every one of its 257 records carries its tildes at exactly these offsets.
`LnaErrorField` is the single source of truth — the parser cuts at these offsets and the workbook's
columns are generated from the same enum, so the two cannot drift apart.

| Field | Offset | Width | Notes |
|---|---:|---:|---|
| Record Type | 0 | 25 | |
| Record Position | 26 | 12 | zoned numeric, zero-padded |
| Contra Header Firm Name | 39 | 50 | blank for a record outside any bundle |
| Contra Header BD Allstate ID | 90 | 10 | |
| Distribution Channel | 101 | 50 | |
| ADB Org Code | 152 | 5 | |
| SSN / TIN | 158 | 9 | zoned numeric; nine zeros means *absent* |
| Person/Firm | 168 | 1 | |
| Entity Type | 170 | 3 | |
| Allstate ID | 174 | 10 | |
| Error Code | 185 | 5 | `B0100`–`F0113`, `O2711`, `P2721`, `D2731`… |
| Error Description | 191 | 125 | |

A 13th empty field and filler follow, out to the 325-byte `LRECL`.

**Fields are cut at their offsets, not split on `~`.** The record is delimited *and* fixed-width,
and only one of those can be trusted: a tilde inside a 125-character description would desynchronise
a `split("~")` for the rest of the record. The delimiter positions are checked instead — a tilde
missing from where the layout says it should be is the clearest signal that the file is not the
layout this app was built for, and it becomes a warning.

**Both record framings are read.** hr-producer-sync writes these newline-separated; the same
artifact off z/OS is a separator-less fixed block of 325-byte records. A file with no line separator
in it is chunked by record length instead of read as one enormous line.

## The workbook

**One sheet, `Records`** — one row per feed record, filtered and frozen at the header. There is
nothing else in the file, and nowhere else for a reader to go looking.

It carries the test-records catalog's own eight columns in the catalog's order, so the two files line
up cell for cell, then three of ours appended after them:

| Column | Contents |
|---|---|
| **Error Code** | the `ERRORCD` value the row was reported under |
| **Feed Field** | the field of the inbound feed record that code was raised against |
| **Field Position (bytes)** | where that field sits in the 600-byte feed record |

They are appended rather than spliced in beside `Category`, where they would read more naturally:
columns A–H have to stay the catalog's own, in its order, or the comparison this sheet exists for
stops lining up.

Layout warnings no longer get a sheet of their own — they are logged, counted in the run summary, and
carried on the `ConversionResult`. `lnaerror-excel.fail-on-layout-warning=true` still stops a
non-conforming file outright.

## Where in the feed record is this error?

`LNAERROR` names the field in prose (`"BUSINESS ADDRESS" NOT VALUED OR INVALID IN THE "FIRM ENTITY"
RECORD`) and never says where it is. The last two columns answer that:

| Column | For `C0707` |
|---|---|
| Feed Field | Business address (LLE: commission address) |
| Field Position (bytes) | 115-306 |

So a `C0707` row means: **bytes 115–306 of that feed record** — address line 1 at 115-154, line 2 at
155-194, city 195-214, state 215-216, ZIP 217-226, phone 227-236, fax 237-246, email 247-306.

Two things this gets right that guessing from the report does not:

- **The mapping is keyed on the error code, not on the report's "Record Type" column.** That column
  is a label, not a record type: a broker-dealer's own firm (`C`) record is labelled `CONTRA HEADER`,
  so a `B0607` row says `CONTRA HEADER` while its bytes are the *firm* record's. Both `D01` and `D02`
  records are labelled `PRODUCER ENTITY` for the same reason.
- **The field is the one the validator actually checks, not the one the error text names.** `C0707`
  says "BUSINESS ADDRESS"; the decoder calls that same block `commissionAddress`, and it is a
  commission address for an LLE and a business address for a BD or HA. One block, 115–306, three
  names.

## The Records sheet is the catalog's own format

`ADB Allstate LNA Test Records Catalog.xlsx` (Day 1 / Day 2 / Day 3 Records sheets) has eight columns
and one row per record. So does the **Records** sheet, ahead of our own three:

| Column | Filled from | Notes |
|---|---|---|
| `Rec #` | the record's position in the feed | |
| `Type` | the feed record itself | `A`, `B`, `C`, `D01`, `D02`, `D03`, `X`, `Z` |
| `Bundle / Block` | the enclosing contra header | `File header` for the `A` record; `Bundle (no id)` when the header's own id is blank, which *is* the error for `B0100`/`B0200`/`B0300`; `Update block (bundle-less)` outside any bundle |
| `Key (ID / SSN)` | the record's own id in the feed | BD id for a `B`, firm id for a `C`, SSN for a producer — masked like the rest |
| `Name` | the feed record | firm name, or the producer's; a `D02` takes the name of the `D01` it belongs to, matched on SSN |
| `Category` | `LNAERROR`, then `ADBSKIP`, then the record type | `Error` / `Warning` / `Skip` / `Structural` / `Good` |
| `Scenario` | the record itself | a description of what the record *is*, built from its own fields — see below |
| `Actual Result` | what happened, in the catalog's phrasing | sits where `Expected Result` sits |
| `Error Code` | the `LNAERROR` row | **column I** — ours, appended after the catalog's eight so A-H stay aligned |
| `Feed Field` | the error code, via `ErrorCodeFieldIndex` | **column J** — blank for a code that is not about one field |
| `Field Position (bytes)` | the same table | **column K** — `115-306`, 1-based and inclusive, within the 600-byte record |

`Category` cells carry the catalog's own colours, read out of the catalog rather than matched by eye:
`Good` `C6EFCE`/`006100`, `Error` `FFC7CE`/`9C0006`, `Warning` `FFEB9C`/`9C6500`, `Skip`
`D9D9D9`/`595959`, `Structural` `DDEBF7`/`1F4E79`, over the same `1F3864` header. Like the catalog,
only the Category cell is filled.

**`Scenario` is generated, not copied.** The catalog's scenarios read like test design, but nearly
every part of them is a fact about the record: its type, its identifiers, its status and dates, the
code it raised and what that code means. On the real Day 1 file **430 of 601 scenarios come out
word-for-word identical to the catalog's**, and the mean similarity is 93%. The one part that is
genuinely unavailable — whether a firm record is an insert or an update — is a question about ADB
rather than the feed, and is left off. Expect the same substance, not always the same words.

**`Actual Result` is deliberately not called `Expected Result`.** A run cannot know what was expected;
it knows what happened. Putting actual outcomes under the catalog's heading would read as agreement
to anyone who opened the file later. It occupies the same column, so a positional or side-by-side
diff still lines up — expected against actual is the comparison worth making.

`Category` needs one more artifact than the error log: `ADBSKIP`, read from beside the `.dat`
(`adbskip-20260815.dat` next to `lnaerror-20260815.dat`). A record leaves a run applied, rejected or
skipped, and `LNAERROR` only knows the second — without the skip log, Day 1's 21 skipped records
would look like they passed.

### What it produces on the real Day 1 file

**601 rows against the catalog's 601**, with `Type`, `Bundle / Block`, `Structural` (64), `Skip` (21)
and `Warning` (6) all matching the catalog exactly. Two columns differ, both meaningfully:

- **`Category` differs on 36 rows** — the catalog calls them `Good`, this file calls them `Error`.
  The catalog's Category is the record's *test intent*: whether it was written to be good or bad.
  This one is *what happened*. All 36 are well-formed records that failed anyway — 33 to a `P2721`
  ADB processing failure, 3 to a `B0400` bundle collapsing around them. On a parallel run those are
  the rows worth looking at first, not noise to reconcile away.
- **`Name` is blank on 13 rows** where the catalog has one. Each is blank in the feed itself, and on
  twelve of them a blank name *is* the error being reported (`B0100`, `B0602`, `C0702`).

A record that failed more than once keeps **one row per error** — the codes point at different fields
and different byte ranges, and merging them would lose exactly what the two field columns are for.
Rows for records that did not fail can be turned off with
`lnaerror-excel.include-records-without-errors=false`.

## The feed: who actually failed, and what was in the bytes

**`LNAERROR` never names the record that failed.** Its only name field is the *contra header's* — the
bundle's BD — so every row of a ninety-record bundle says `APEX SECURITIES GROUP INC` and none of
them says the record that failed was `APEX GAP NOL1 LLC`. That name exists only in the feed, at bytes
11-60 of the firm record, so the converter reads the record back.

**The feed is found automatically** — `lnaerror-excel.feed-directory` defaults to hr-producer-sync's
own feed-landing directory, so the plain command already produces the feed-derived columns:

```bash
mvn spring-boot:run
```

Beyond `Name`, the feed supplies `Type`, `Bundle / Block`, `Key (ID / SSN)` and `Scenario`, plus the
rows for records that did not fail. `Feed Field` and `Field Position (bytes)` do **not** need it —
they come from the error code, so they are filled whether a feed was found or not.

**The right feed is found by matching, not by name.** Nothing in either file says which other file it
belongs to, and names only work for a production feed like `ALLSTATE.LNA.D20260807` — not for
`ALLSTATE_LNA_TEST_INPUT_DAY1.txt`. So each candidate in the feed directory is *checked*: for every
row whose error code implies a record type, the record at that position should be of that type. A
candidate that disagrees on more than a quarter of what can be checked is passed over, and if nothing
matches, the feed-derived columns are omitted and the run says so. Names still order the search — a
file whose name carries the report's cycle date is tried first — but they never decide it.

That check is what makes searching a directory safe. Getting it wrong would be silent and convincing:
every row would carry a real name from a real record, just not the record that failed. A named
`--feed` is checked the same way, for the same reason.

Codes that reject a *record* or a *bundle* rather than a field — `E0100` (no `D02` followed the
`D01`), `C0714` (a second active BD), `C0717`/`F0113` (functional manager not found in ADB), the
`ADB` processing errors — get a note and deliberately **no** byte range. Inventing one would send
someone to bytes that have nothing wrong with them.

Ranges are transcribed from the code that decodes the feed in `pru-adb-poc` — `FeedRecordMapper`,
`Bundle.ContraHeader.from`, the `BundleStructureValidator` constants — and each code is paired with
the field `FeedRecordValidator` actually raises it against. Positions are 1-based and inclusive,
the way the layout tables and `FeedRecord.field(start, end)` express them, and they are positions
within the 600-byte record, not within the feed file.

Identifiers are written as **text** cells, never numbers — Excel silently drops the leading zeros of
a numeric cell, and an Allstate ID or org code that has lost one no longer matches the source system.
`Rec #` is the one numeric column, so it sorts numerically.

The workbook is written temp-file-then-renamed, the same idiom as hr-producer-sync's
`AtomicFileWriter`: an `.xlsx` is a zip, and a half-written one is not a slightly-short spreadsheet,
it is a file Excel refuses to open.

## Tax identifiers are masked by default

`lnaerror-excel.mask-tax-id` defaults to **true** — a `Key (ID / SSN)` that holds an SSN or TIN
rather than an Allstate id renders as `*****6789`. The workbook
is a convenience copy that gets mailed around and parked in shared drives, unlike the `.dat` it comes
from, so full nine-digit identifiers are not spread by default. Set it to `false` deliberately, for
a run whose output stays inside the same controls as the source artifact.

An all-zero value renders blank: the writer that produced the `.dat` zero-pads its numeric fields, so
nine zeros is how an absent tax id is stored, not a real identifier.

## Configuration

All under `lnaerror-excel.*` in `src/main/resources/application.yml`, where each property is
documented inline.

| Property | Default | |
|---|---|---|
| `input-directory` | `../pru-adb-poc/local-data/ingestion-output` | `LNAERROR_INPUT_DIRECTORY` |
| `output-directory` | `./local-data/excel` | `LNAERROR_OUTPUT_DIRECTORY` |
| `file-pattern` | `lnaerror-*.dat` | glob, directory input only |
| `feed-directory` | `../pru-adb-poc/local-data/feed-landing` | `LNAERROR_FEED_DIRECTORY`; searched and matched automatically |
| `feed-file-pattern` | `ALLSTATE*` | which files in it are worth checking |
| `feed-file` | *(none)* | one specific feed, overriding the search |
| `charset` | `UTF-8` | undecodable bytes are replaced, never fatal |
| `mask-tax-id` | `true` | `LNAERROR_MASK_TAX_ID` |
| `fail-on-layout-warning` | `false` | abort a non-conforming file instead of reporting it |
| `include-records-without-errors` | `true` | a row per feed record, not only per error; needs a feed |
| `data-sheet-name` | `Records` | the workbook's one sheet |
| `overwrite-existing` | `true` | `false` fails a re-run rather than replacing a workbook |

## Tests

`mvn test` — 93 tests, no Docker, no database. Records are rendered by `LnaErrorRecordFixture` the
way `FileIngestionOutputWriter` writes them, rather than checking in a copy of a real
`lnaerror-*.dat`: the real artifact carries firm names and tax identifiers, and a fixture is not the
place for either.

`LnaErrorFieldTest` pins the `LNAERROR` layout against the delimiter offsets observed in a real
generated file — if a field width ever drifts from what hr-producer-sync emits, that test stops
matching. `ErrorCodeFieldIndexTest` pins the code→field table against the full 60-code `ERRORCD`
catalog, so a blank "Feed Field" column always means "this code is not about one field", never
"nobody filled this in".
