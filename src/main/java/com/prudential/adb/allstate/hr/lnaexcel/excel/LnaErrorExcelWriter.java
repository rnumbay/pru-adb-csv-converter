package com.prudential.adb.allstate.hr.lnaexcel.excel;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import com.prudential.adb.allstate.hr.lnaexcel.config.LnaErrorExcelProperties;
import com.prudential.adb.allstate.hr.lnaexcel.feed.ErrorCodeCatalog;
import com.prudential.adb.allstate.hr.lnaexcel.feed.ErrorCodeCatalog.ErrorCodeMeaning;
import com.prudential.adb.allstate.hr.lnaexcel.feed.ErrorCodeFieldIndex;
import com.prudential.adb.allstate.hr.lnaexcel.feed.ErrorCodeTarget;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedFile;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedRecordKind;
import com.prudential.adb.allstate.hr.lnaexcel.parse.LnaErrorRecord;
import com.prudential.adb.allstate.hr.lnaexcel.parse.ParsedLnaErrorFile;
import com.prudential.adb.allstate.hr.lnaexcel.parse.SkipLog;

/**
 * Renders a parsed {@code LNAERROR} file as an {@code .xlsx} workbook.
 *
 * <h2>One sheet</h2>
 *
 * The workbook is a single sheet - {@code Records}, one row per feed record, filtered and frozen at
 * the header. It carries the eight columns of the test-records catalog
 * ({@code ADB Allstate LNA Test Records Catalog.xlsx}) in the catalog's own order, so the two files
 * can be compared cell for cell, and three columns of our own appended after them:
 *
 * <ul>
 *   <li><b>Error Code</b> - the {@code ERRORCD} value the row was reported under.
 *   <li><b>Feed Field</b> - the field of the inbound feed record that error was raised against.
 *   <li><b>Field Position (bytes)</b> - where that field sits in the 600-byte feed record, 1-based
 *       and inclusive, so {@code C0707} sends someone to bytes 115-306 rather than to prose.
 * </ul>
 *
 * All three are appended rather than spliced in beside {@code Category}, where they would read more
 * naturally: columns A-H are the catalog's, in the catalog's order, and a column of ours in the
 * middle would shift {@code Scenario} and {@code Expected Result} out of line with the file this
 * sheet exists to be compared against.
 *
 * <h2>Codes that are not about one field</h2>
 *
 * Some codes reject a <em>record</em> or a <em>bundle</em> rather than a field - a missing
 * {@code D02}, a second active BD, a parent that does not exist in ADB. Their two field columns are
 * left <b>blank</b>. Inventing a byte range for them would be worse than leaving it empty: someone
 * would go and read those bytes and find nothing wrong with them. What became of such a record is
 * the {@code Category} and {@code Actual Result} columns' business either way.
 *
 * <h2>The key column</h2>
 *
 * {@code Key (ID / SSN)} is masked to its last four digits whenever it holds a tax id, unless
 * {@code lnaerror-excel.mask-tax-id} is turned off - see that property's own documentation for why
 * that is the default. An all-zero value renders as blank: the writer that produced the {@code .dat}
 * zero-pads its numeric fields, so nine zeros is how an absent tax id is stored, not a real
 * identifier.
 *
 * <p>Tax ids and every other identifier are written as <em>text</em> cells, never numbers. Excel
 * silently drops the leading zeros of a numeric cell, and an Allstate id or an org code that has
 * lost its leading zero no longer matches the source system.
 */
@Component
public class LnaErrorExcelWriter {

    private final LnaErrorExcelProperties properties;

    public LnaErrorExcelWriter(LnaErrorExcelProperties properties) {
        this.properties = properties;
    }

    /**
     * Builds the workbook for {@code parsed} and writes it to {@code out}. The stream is not closed
     * here - the caller owns it, because the caller is the one doing the write-then-rename.
     *
     * @param feed the inbound feed the report was produced from, or {@code null} when none was
     *             supplied. It is the only source for the failing record's own name, so that column
     *             exists only when it does
     */
    public void write(ParsedLnaErrorFile parsed, FeedFile feed, SkipLog skipped, OutputStream out)
            throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            writeRecordsSheet(workbook, styles, reportRows(parsed, feed), feed, skipped);
            workbook.write(out);
        }
    }

    /**
     * The workbook's one sheet: the eight columns of
     * {@code ADB Allstate LNA Test Records Catalog.xlsx}'s per-day Records sheets, in their order,
     * then the error code and the two columns that say where in the feed record it was raised.
     *
     * <p>Two of the catalog's columns cannot be filled from a run, and are handled differently for a
     * reason:
     *
     * <ul>
     *   <li><b>Scenario</b> is generated from the record itself - see {@link RecordScenario}. The
     *       catalog's scenarios read like test design, but nearly every part of them is a fact about
     *       the record; the one part that is not, whether a firm record is an insert or an update,
     *       is a question about ADB rather than the feed and is left off. Expect the same substance,
     *       not the same words.
     *   <li><b>Actual Result</b> holds what happened, phrased the way the catalog phrases its
     *       expectation, and sits where the catalog's {@code Expected Result} sits. It is
     *       deliberately not <em>called</em> {@code Expected Result}: a run cannot know what was
     *       expected, and a column of actual outcomes under that heading would be read as agreement
     *       by anyone who opened the file later.
     * </ul>
     */
    private void writeRecordsSheet(
            Workbook workbook, Styles styles, List<ReportRow> rows, FeedFile feed, SkipLog skipped) {
        Sheet sheet = workbook.createSheet(properties.dataSheetName());

        String[] headings = {"Rec #", "Type", "Bundle / Block", "Key (ID / SSN)", "Name",
                "Category", "Scenario", "Actual Result", "Error Code", "Feed Field",
                "Field Position (bytes)"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < headings.length; i++) {
            headerCell(header, i, headings[i], styles);
        }

        int rowIndex = 1;
        for (ReportRow reportRow : rows) {
            Row row = sheet.createRow(rowIndex++);
            numberCell(row, 0, reportRow.position(), styles);
            textCell(row, 1, recordType(reportRow, feed), styles);
            textCell(row, 2, bundleOrBlock(reportRow, feed), styles);
            textCell(row, 3, key(reportRow, feed), styles);
            textCell(row, 4, feed == null ? "" : feed.nameAt(reportRow.position()).orElse(""), styles);
            String category = category(reportRow, feed, skipped);
            categoryCell(row, 5, category, styles);
            textCell(row, 6, RecordScenario.describe(
                    reportRow.position(), feed, reportRow.error(), skipped), styles);
            textCell(row, 7, actualResult(reportRow, feed, skipped), styles);
            textCell(row, 8, reportRow.hasError() ? reportRow.error().errorCode() : "", styles);
            textCell(row, 9, feedField(reportRow), styles);
            textCell(row, 10, fieldPosition(reportRow), styles);
        }

        sheet.createFreezePane(0, 1);
        if (rowIndex > 1) {
            sheet.setAutoFilter(new CellRangeAddress(0, rowIndex - 1, 0, headings.length - 1));
        }
        int[] widths = {10, 10, 30, 18, 34, 12, 60, 60, 12, 44, 22};
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
    }

    /**
     * The field of the inbound feed record the error was raised against.
     *
     * <p>{@code LNAERROR} names the field in prose ({@code "BUSINESS ADDRESS" NOT VALUED OR INVALID
     * IN THE "FIRM ENTITY" RECORD}) and the prose does not always match what the validator actually
     * checks - {@code C0707} says "BUSINESS ADDRESS" and is raised against the field the decoder
     * calls {@code commissionAddress}. This column comes from {@link ErrorCodeFieldIndex}, which is
     * keyed on the code and transcribed from the validators themselves. Blank for a code that is not
     * about one field - see the class Javadoc.
     */
    private static String feedField(ReportRow reportRow) {
        return target(reportRow)
                .filter(ErrorCodeTarget::hasField)
                .map(found -> found.field().label())
                .orElse("");
    }

    /**
     * Where that field sits in the feed record: {@code 115-306}, 1-based and inclusive, within the
     * 600-byte record - not an offset into the feed file. Blank whenever {@link #feedField} is.
     */
    private static String fieldPosition(ReportRow reportRow) {
        return target(reportRow)
                .filter(ErrorCodeTarget::hasField)
                .map(found -> found.field().byteRange())
                .orElse("");
    }

    /** The catalog's {@code Type}: the feed's own record type, or what the error code implies. */
    private static String recordType(ReportRow reportRow, FeedFile feed) {
        return feedLookup(feed, reportRow, FeedFile::typeAt)
                .or(() -> target(reportRow).map(ErrorCodeTarget::recordKind)
                        .map(FeedRecordKind::typeCode))
                .orElse("");
    }

    /**
     * The catalog's {@code Category} - what became of the record.
     *
     * <p>Three sources, in the order that decides: an error in {@code LNAERROR} outranks everything
     * (and is a {@code Warning} only for the two codes whose record still applies), then a skip in
     * {@code ADBSKIP}, then the record's own type - the file header and the bundle trailers carry no
     * business data to accept or reject. Whatever is left through all three passed.
     */
    private static String category(ReportRow reportRow, FeedFile feed, SkipLog skipped) {
        Optional<ErrorCodeMeaning> meaning = meaning(reportRow);
        if (meaning.isPresent()) {
            return meaning.get().severity().label();
        }
        if (reportRow.hasError()) {
            return ErrorCodeCatalog.Severity.ERROR.label();
        }
        if (skipped.reasonAt(reportRow.position()).isPresent()) {
            return "Skip";
        }
        String type = recordType(reportRow, feed);
        if (type.equals("A") || type.equals("Z")) {
            return "Structural";
        }
        return type.isEmpty() ? "" : "Good";
    }

    /**
     * What actually happened to the record, in the catalog's own phrasing for the same outcome -
     * {@code Rejected - C0707; ...}, {@code Skipped to ADB SKIP file - reason 002 (...)},
     * {@code Processed with WARNING C0715 (...); row still written}.
     *
     * <p>An accepted record gets a bare {@code Accepted}. The catalog says more - "Accepted -
     * appointment inserted", "bundle opened, awaiting BD firm record" - but that is knowledge of
     * what the record was for, and this file only knows that nothing rejected it.
     */
    private static String actualResult(ReportRow reportRow, FeedFile feed, SkipLog skipped) {
        if (reportRow.hasError()) {
            String code = reportRow.error().errorCode();
            Optional<ErrorCodeMeaning> meaning = meaning(reportRow);
            if (meaning.filter(found -> found.severity() == ErrorCodeCatalog.Severity.WARNING)
                    .isPresent()) {
                return "Processed with WARNING " + code + " (" + meaning.get().meaningOrBlank()
                        + "); row still written";
            }
            return "Rejected - " + code
                    + meaning.map(found -> "; " + found.disposition()).orElse("");
        }
        Optional<String> reason = skipped.reasonAt(reportRow.position());
        if (reason.isPresent()) {
            return "Skipped to ADB SKIP file - reason " + reason.get()
                    + " (" + SkipLog.describe(reason.get()) + ")";
        }
        String type = recordType(reportRow, feed);
        if (type.equals("A")) {
            return "File header - validated against the run parameters";
        }
        if (type.equals("Z")) {
            return "Bundle trailer";
        }
        return type.isEmpty() ? "" : "Accepted";
    }

    /**
     * The sheet's rows: one per error, and - when a feed is available and
     * {@code include-records-without-errors} is on - one for every record in the feed that did not
     * fail.
     *
     * <p>{@code LNAERROR} holds errors, so on its own the sheet has no row for the file header, for
     * a contra header that opened cleanly, or for any record that passed. Listing every record makes
     * the sheet line up with the test-records catalog, which has a row per record either way.
     *
     * <p>A record that failed more than once keeps <em>one row per error</em> rather than being
     * collapsed: the codes point at different fields and different byte ranges, and merging them
     * would lose exactly what the two field columns are for. Record 564 of the Day 1 corpus fails
     * {@code B0100}, {@code B0200} and {@code B0300} at once, and each is worth its own row.
     */
    private List<ReportRow> reportRows(ParsedLnaErrorFile parsed, FeedFile feed) {
        if (feed == null || !properties.includeRecordsWithoutErrors()) {
            return parsed.records().stream()
                    .map(record -> new ReportRow(recordPosition(record), record))
                    .toList();
        }

        Map<Long, List<LnaErrorRecord>> byPosition = new LinkedHashMap<>();
        for (LnaErrorRecord record : parsed.records()) {
            byPosition.computeIfAbsent(recordPosition(record), position -> new ArrayList<>())
                    .add(record);
        }

        List<ReportRow> rows = new ArrayList<>(feed.recordCount() + parsed.recordCount());
        for (long position = 1; position <= feed.recordCount(); position++) {
            List<LnaErrorRecord> errors = byPosition.remove(position);
            if (errors == null) {
                rows.add(new ReportRow(position, null));
                continue;
            }
            for (LnaErrorRecord error : errors) {
                rows.add(new ReportRow(position, error));
            }
        }
        // An error whose position is past the end of the feed still gets its row - dropping it would
        // hide the one thing that says the two files disagree about how long the feed is.
        byPosition.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entry.getValue()
                        .forEach(error -> rows.add(new ReportRow(entry.getKey(), error))));
        return rows;
    }

    /**
     * One row of the sheet: a feed record position, and the error reported against it -
     * {@code null} for a record that did not fail.
     */
    private record ReportRow(long position, LnaErrorRecord error) {

        boolean hasError() {
            return error != null;
        }
    }

    /** The report's zero-padded record position as a number, or 0 when it is not one. */
    private static long recordPosition(LnaErrorRecord record) {
        try {
            return Long.parseLong(record.recordPosition());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A feed lookup for this row's position - empty when there is no feed, or nothing there. */
    private static Optional<String> feedLookup(
            FeedFile feed, ReportRow reportRow, BiFunction<FeedFile, Long, Optional<String>> lookup) {
        if (feed == null) {
            return Optional.empty();
        }
        return lookup.apply(feed, reportRow.position()).filter(value -> !value.isEmpty());
    }

    /**
     * The catalog's {@code Key (ID / SSN)}: the Allstate id that identifies a firm record, the SSN
     * that identifies a producer record.
     *
     * <h2>Why this reads the feed rather than the report</h2>
     *
     * {@code LNAERROR} carries the failing record's own identity on some paths and not others - on
     * the Day 1 corpus, 44 rows of 257. Every {@code B}, {@code D01} and {@code E0100} row leaves
     * both the Allstate id and the tax id blank, so a key taken from the report alone is missing
     * four times out of five and the column cannot be compared against anything.
     *
     * <p>With a feed in hand the key is read from the record itself, keyed on the record's type so
     * it follows the same convention the catalog does: the BD id for a contra header (54-63), the
     * firm's own id for a firm record (88-97), the SSN for either producer record (4-12). Without a
     * feed it falls back to whatever the report happened to carry.
     *
     * <p>SSN keys stay masked - a key column is no place to leak what masking hides elsewhere,
     * whichever file the digits came from.
     */
    private String key(ReportRow reportRow, FeedFile feed) {
        Optional<String> fromFeed = feedLookup(feed, reportRow, FeedFile::keyAt)
                .map(value -> feed.keyIsATaxIdAt(reportRow.position()) ? taxId(value) : value);
        if (fromFeed.isPresent()) {
            return fromFeed.get();
        }
        if (!reportRow.hasError()) {
            return "";
        }
        LnaErrorRecord record = reportRow.error();
        return record.allstateId().isEmpty() ? taxId(record.ssnOrTin()) : record.allstateId();
    }

    /**
     * {@code Bundle ALSTBD0001} when the row carries its bundle's BD id, and the catalog's own two
     * wordings when it does not.
     *
     * <p>A blank BD id means two different things. Usually it means there was no bundle -
     * {@code LNAERROR} leaves the bundle-context columns blank for a standalone record. But on a
     * {@code B0100}/{@code B0200}/{@code B0300} row the missing id <em>is</em> the error: the
     * bundle exists and its header is blank. The code's own scope is what tells them apart; reading
     * the id alone would file every rejected-at-header bundle under "no bundle".
     */
    private static String bundleOrBlock(ReportRow reportRow, FeedFile feed) {
        if (feed != null) {
            // The file header opens no bundle and belongs to none - it is the file's own record.
            if (feed.typeAt(reportRow.position()).filter("A"::equals).isPresent()) {
                return "File header";
            }
            Optional<String> bundle = feed.bundleIdAt(reportRow.position());
            if (bundle.isPresent()) {
                return bundle.get().isEmpty() ? "Bundle (no id)" : "Bundle " + bundle.get();
            }
            if (feed.recordAt(reportRow.position()).isPresent()) {
                return "Update block (bundle-less)";
            }
        }
        if (!reportRow.hasError()) {
            return "";
        }
        LnaErrorRecord record = reportRow.error();
        if (!record.contraHeaderBdAllstateId().isEmpty()) {
            return "Bundle " + record.contraHeaderBdAllstateId();
        }
        boolean insideABundle = meaning(reportRow)
                .map(found -> found.scope() == ErrorCodeCatalog.Scope.BUNDLE)
                .orElse(false)
                || !record.contraHeaderFirmName().isEmpty()
                || !record.contraHeaderDistributionChannel().isEmpty();
        return insideABundle ? "Bundle (no id)" : "Update block (bundle-less)";
    }

    private static Optional<ErrorCodeMeaning> meaning(ReportRow reportRow) {
        return reportRow.hasError()
                ? ErrorCodeCatalog.find(reportRow.error().errorCode())
                : Optional.empty();
    }

    private static Optional<ErrorCodeTarget> target(ReportRow reportRow) {
        return reportRow.hasError()
                ? ErrorCodeFieldIndex.find(reportRow.error().errorCode())
                : Optional.empty();
    }

    /**
     * Masks to the last four digits, and blanks an all-zero value - see the class Javadoc. Masking
     * counts the digits it hides so the original width stays visible; a nine-digit id and a
     * truncated one should not look alike.
     */
    private String taxId(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.isEmpty() || digits.chars().allMatch(character -> character == '0')) {
            return "";
        }
        if (!properties.maskTaxId()) {
            return digits;
        }
        if (digits.length() <= 4) {
            return "*".repeat(digits.length());
        }
        return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }

    private static void headerCell(Row row, int column, String value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(styles.header);
    }

    /**
     * A {@code Category} cell in the catalog's own colours - the fill and font of its
     * {@code Good}/{@code Error}/{@code Warning}/{@code Skip}/{@code Structural} cells, read out of
     * the catalog itself rather than approximated. A value the catalog has no colour for is written
     * plainly rather than given an invented one.
     */
    private static void categoryCell(Row row, int column, String value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(styles.category.getOrDefault(value, styles.text));
    }

    private static void textCell(Row row, int column, String value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(styles.text);
    }

    private static void numberCell(Row row, int column, long value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(styles.number);
    }

    /**
     * The colours the test-records catalog gives its {@code Category} cells, and its header row -
     * read from the file itself, not matched by eye. They are Excel's own Good/Neutral/Bad palette:
     * fill first, then the bold font colour that goes with it.
     */
    private static final Map<String, String[]> CATEGORY_COLOURS = Map.of(
            "Good", new String[] {"C6EFCE", "006100"},
            "Error", new String[] {"FFC7CE", "9C0006"},
            "Warning", new String[] {"FFEB9C", "9C6500"},
            "Skip", new String[] {"D9D9D9", "595959"},
            "Structural", new String[] {"DDEBF7", "1F4E79"});

    /** The catalog's header fill. */
    private static final String HEADER_FILL = "1F3864";

    /**
     * The workbook's cell styles, created once. POI caps a workbook at 64k styles and creating one
     * per cell reaches that on a file this size, so these are shared across every cell that uses
     * them.
     */
    private static final class Styles {

        private final CellStyle header;
        private final CellStyle text;
        private final CellStyle number;
        private final Map<String, CellStyle> category = new HashMap<>();

        private Styles(XSSFWorkbook workbook) {
            XSSFFont headerFont = workbook.createFont();
            headerFont.setBold(true);
            // RGB white rather than the indexed one, so the header matches the catalog's byte for
            // byte rather than merely looking the same.
            headerFont.setColor(colour("FFFFFF"));

            XSSFCellStyle headerStyle = workbook.createCellStyle();
            header = headerStyle;
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(colour(HEADER_FILL));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.LEFT);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            CATEGORY_COLOURS.forEach((value, colours) -> {
                XSSFFont font = workbook.createFont();
                font.setBold(true);
                font.setColor(colour(colours[1]));
                XSSFCellStyle style = workbook.createCellStyle();
                style.setFont(font);
                style.setFillForegroundColor(colour(colours[0]));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                style.setVerticalAlignment(VerticalAlignment.TOP);
                category.put(value, style);
            });

            text = workbook.createCellStyle();
            text.setVerticalAlignment(VerticalAlignment.TOP);

            number = workbook.createCellStyle();
            number.setVerticalAlignment(VerticalAlignment.TOP);
            number.setAlignment(HorizontalAlignment.RIGHT);
            // Plain integer: these are ordinals and counts, not quantities to group with commas.
            number.setDataFormat(workbook.createDataFormat().getFormat("0"));
        }

        /** {@code "C6EFCE"} as the colour POI writes into the sheet. */
        private static XSSFColor colour(String rgb) {
            return new XSSFColor(new byte[] {
                    (byte) Integer.parseInt(rgb.substring(0, 2), 16),
                    (byte) Integer.parseInt(rgb.substring(2, 4), 16),
                    (byte) Integer.parseInt(rgb.substring(4, 6), 16)}, null);
        }
    }
}
