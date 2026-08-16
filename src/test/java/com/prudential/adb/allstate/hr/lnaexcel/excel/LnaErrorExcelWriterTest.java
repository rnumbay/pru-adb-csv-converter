package com.prudential.adb.allstate.hr.lnaexcel.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import com.prudential.adb.allstate.hr.lnaexcel.config.LnaErrorExcelProperties;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedFile;
import com.prudential.adb.allstate.hr.lnaexcel.parse.LnaErrorRecord;
import com.prudential.adb.allstate.hr.lnaexcel.parse.ParseWarning;
import com.prudential.adb.allstate.hr.lnaexcel.parse.ParsedLnaErrorFile;
import com.prudential.adb.allstate.hr.lnaexcel.parse.SkipLog;

class LnaErrorExcelWriterTest {

    /** One sheet, whatever the run found - there is nothing else for a reader to go looking in. */
    @Test
    void writesExactlyOneSheet() throws IOException {
        Workbook workbook = workbook(properties(true), parsed(record(1, "C0707")));

        assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
        assertThat(workbook.getSheetName(0)).isEqualTo("Records");
    }

    /** A file the parser had complaints about is still one sheet - the warnings go to the log. */
    @Test
    void staysOneSheetForAFileWithLayoutWarnings() throws IOException {
        ParsedLnaErrorFile withWarning = new ParsedLnaErrorFile(
                Path.of("lnaerror-20260815.dat"),
                List.of(record(1, "C0701")),
                List.of(new ParseWarning(4, "is 180 characters, expected 325")),
                true);

        assertThat(workbook(properties(true), withWarning).getNumberOfSheets()).isEqualTo(1);
    }

    /**
     * The sheet is the catalog's own eight columns, in its order - the two files are meant to be
     * compared cell for cell, which a column of ours in the middle would break - then our three.
     */
    @Test
    void writesTheCatalogsEightColumnsThenOurThree() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed(record(1, "C0707"))));

        assertThat(headings(sheet)).containsExactly("Rec #", "Type", "Bundle / Block",
                "Key (ID / SSN)", "Name", "Category", "Scenario", "Actual Result",
                "Error Code", "Feed Field", "Field Position (bytes)");
    }

    /**
     * Ours are appended, not spliced in beside Category: columns A-H have to stay the catalog's own,
     * in its order, or the comparison this sheet exists for stops lining up.
     */
    @Test
    void keepsTheCatalogsEightColumnsAheadOfOurOwn() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed(record(1, "C0707"))));

        assertThat(headings(sheet).indexOf("Error Code")).isEqualTo(8);
        assertThat(sheet.getRow(1).getCell(8).getStringCellValue()).isEqualTo("C0707");
    }

    /**
     * LNAERROR names the failing field in prose and never says where it is. These two columns do -
     * and they come from the code, not from the prose: C0707 says "BUSINESS ADDRESS" and is raised
     * against the block the decoder calls the commission address.
     */
    @Test
    void namesTheFeedFieldAndBytePositionTheCodeWasRaisedAgainst() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed(record(1, "C0707"))));

        assertThat(cell(sheet, "Feed Field").getStringCellValue())
                .isEqualTo("Business address (LLE: commission address)");
        assertThat(cell(sheet, "Field Position (bytes)").getStringCellValue()).isEqualTo("115-306");
    }

    /**
     * A code that rejects a record or a bundle has no byte range, and must not be given one -
     * someone would go and read those bytes and find nothing wrong with them.
     */
    @Test
    void leavesTheFieldColumnsBlankForACodeThatIsNotAboutOneField() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed(record(1, "E0100"))));

        assertThat(cell(sheet, "Feed Field").getStringCellValue()).isEmpty();
        assertThat(cell(sheet, "Field Position (bytes)").getStringCellValue()).isEmpty();
    }

    @Test
    void leavesTheFieldColumnsBlankForACodeTheIndexDoesNotKnow() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed(record(1, "Z9999"))));

        assertThat(cell(sheet, "Feed Field").getStringCellValue()).isEmpty();
        assertThat(cell(sheet, "Field Position (bytes)").getStringCellValue()).isEmpty();
    }

    /** The catalog's own Category colours, read out of the catalog rather than matched by eye. */
    @Test
    void coloursTheCategoryCellTheWayTheCatalogDoes() throws IOException {
        assertThat(categoryFill("C0707")).isEqualTo("FFFFC7CE");   // Error - red
        assertThat(categoryFill("C0715")).isEqualTo("FFFFEB9C");   // Warning - amber
    }

    /**
     * A code this app does not recognise still means the record failed - the row exists in
     * LNAERROR - so it is an Error and is coloured as one. Only the code's meaning is unknown.
     */
    @Test
    void treatsAnUnrecognisedCodeAsAnErrorRatherThanAsNoOutcome() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed(record(1, "Z9999"))));

        assertThat(cell(sheet, "Category").getStringCellValue()).isEqualTo("Error");
        assertThat(categoryFill("Z9999")).isEqualTo("FFFFC7CE");
        assertThat(cell(sheet, "Error Code").getStringCellValue()).isEqualTo("Z9999");
    }

    private static String categoryFill(String errorCode) throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed(record(1, errorCode))));
        org.apache.poi.xssf.usermodel.XSSFCellStyle style =
                (org.apache.poi.xssf.usermodel.XSSFCellStyle) cell(sheet, "Category").getCellStyle();
        return style.getFillForegroundColorColor().getARGBHex();
    }

    @Test
    void phrasesWhatHappenedTheWayTheCatalogPhrasesWhatShould() throws IOException {
        Sheet rejected = sheetOf(workbook(properties(true), parsed(record(1, "C0707"))));
        assertThat(cell(rejected, "Category").getStringCellValue()).isEqualTo("Error");
        assertThat(cell(rejected, "Actual Result").getStringCellValue())
                .isEqualTo("Rejected - C0707; Firm record rejected - bundle continues");

        // The same field on the bundle's first firm record costs the whole bundle, not one record.
        Sheet bundleFatal = sheetOf(workbook(properties(true), parsed(record(1, "B0607"))));
        assertThat(cell(bundleFatal, "Actual Result").getStringCellValue())
                .isEqualTo("Rejected - B0607; First firm record fails - whole bundle rejected");

        // ...and the two warning codes are reported without rejecting anything.
        Sheet warned = sheetOf(workbook(properties(true), parsed(record(1, "C0715"))));
        assertThat(cell(warned, "Category").getStringCellValue()).isEqualTo("Warning");
        assertThat(cell(warned, "Actual Result").getStringCellValue())
                .isEqualTo("Processed with WARNING C0715 (active LLE/HA under non-active BD); "
                        + "row still written");
    }

    /** Without a feed there is no record to describe, and nothing is invented in its place. */
    @Test
    void leavesScenarioBlankWithNoFeedToDescribeTheRecordFrom() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed(record(1, "C0707"))));

        assertThat(cell(sheet, "Scenario").getStringCellValue()).isEmpty();
    }

    /**
     * The columns the test-records catalog carries and LNAERROR does not - a row here has to line
     * up against a catalog row without anyone translating between them.
     */
    @Test
    void derivesTheCatalogsTypeBundleAndKeyColumns() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed(record(1, "C0707"))));

        assertThat(cell(sheet, "Type").getStringCellValue()).isEqualTo("C");
        assertThat(cell(sheet, "Bundle / Block").getStringCellValue())
                .isEqualTo("Bundle TESTBD0001");
        assertThat(cell(sheet, "Key (ID / SSN)").getStringCellValue()).isEqualTo("0012345678");
    }

    /**
     * A B06xx row is labelled CONTRA HEADER by the report, but the record is a firm (C) record -
     * typing it off the label rather than the code would put a C row under B, and would send the
     * field columns to the contra header's bytes instead of the firm record's.
     */
    @Test
    void typesABdsOwnFirmRecordAsCEvenThoughTheReportLabelsItContraHeader() throws IOException {
        LnaErrorRecord bdRecord = new LnaErrorRecord(1, "CONTRA HEADER", "000000000004",
                "FIXTURE FIRM", "TESTBD0001", "10", "0A1", "123456789", "F", "", "0012345678",
                "B0607", "SOME ERROR");

        Sheet sheet = sheetOf(workbook(properties(true), parsed(bdRecord)));

        assertThat(cell(sheet, "Type").getStringCellValue()).isEqualTo("C");
        assertThat(cell(sheet, "Feed Field").getStringCellValue())
                .isEqualTo("Business address (LLE: commission address)");
        assertThat(cell(sheet, "Field Position (bytes)").getStringCellValue()).isEqualTo("115-306");
    }

    @Test
    void callsARecordWithNoBundleContextAnUpdateBlock() throws IOException {
        LnaErrorRecord standalone = new LnaErrorRecord(1, "FIRM ENTITY", "000000000004", "", "",
                "", "0A1", "123456789", "F", "LLE", "0012345678", "C0707", "SOME ERROR");

        Sheet sheet = sheetOf(workbook(properties(true), parsed(standalone)));

        assertThat(cell(sheet, "Bundle / Block").getStringCellValue())
                .isEqualTo("Update block (bundle-less)");
    }

    /** A producer row carries no Allstate id, so its key is the tax id - and stays masked. */
    @Test
    void fallsBackToTheMaskedTaxIdWhenTheRowHasNoAllstateId() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed(producer("123456789"))));

        assertThat(cell(sheet, "Key (ID / SSN)").getStringCellValue()).isEqualTo("*****6789");
        assertThat(cell(sheet, "Type").getStringCellValue()).isEqualTo("D01");
    }

    @Test
    void writesTheTaxIdInFullOnlyWhenMaskingIsTurnedOff() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(false), parsed(producer("123456789"))));

        assertThat(cell(sheet, "Key (ID / SSN)").getStringCellValue()).isEqualTo("123456789");
    }

    /** The writer that produces the .dat zero-pads its numerics, so nine zeros means "absent". */
    @Test
    void blanksAnAllZeroTaxId() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(false), parsed(producer("000000000"))));

        assertThat(cell(sheet, "Key (ID / SSN)").getStringCellValue()).isEmpty();
    }

    @Test
    void keepsLeadingZerosOnIdentifiersByWritingThemAsText() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed(record(1, "C0701"))));

        assertThat(cell(sheet, "Key (ID / SSN)").getCellType()).isEqualTo(CellType.STRING);
        assertThat(cell(sheet, "Key (ID / SSN)").getStringCellValue()).isEqualTo("0012345678");
    }

    @Test
    void writesTheRecordPositionAsANumberSoItSortsNumerically() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed(record(1, "C0701"))));

        assertThat(cell(sheet, "Rec #").getCellType()).isEqualTo(CellType.NUMERIC);
        assertThat(cell(sheet, "Rec #").getNumericCellValue()).isEqualTo(103d);
    }

    @Test
    void writesAHeaderOnlySheetForAFileWithNoRecords() throws IOException {
        Sheet sheet = sheetOf(workbook(properties(true), parsed()));

        assertThat(sheet.getLastRowNum()).isZero();
        assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Rec #");
    }

    private static Workbook workbook(LnaErrorExcelProperties properties, ParsedLnaErrorFile parsed)
            throws IOException {
        return workbook(properties, parsed, null);
    }

    private static Workbook workbook(
            LnaErrorExcelProperties properties, ParsedLnaErrorFile parsed, FeedFile feed)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new LnaErrorExcelWriter(properties).write(parsed, feed, SkipLog.empty(), out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }

    private static Sheet sheetOf(Workbook workbook) {
        return workbook.getSheet("Records");
    }

    /** The first data row's cell under {@code heading}, found by heading rather than by position. */
    private static org.apache.poi.ss.usermodel.Cell cell(Sheet sheet, String heading) {
        int column = headings(sheet).indexOf(heading);
        if (column < 0) {
            throw new AssertionError("No column headed " + heading);
        }
        return sheet.getRow(1).getCell(column);
    }

    private static List<String> headings(Sheet sheet) {
        List<String> headings = new ArrayList<>();
        for (org.apache.poi.ss.usermodel.Cell cell : sheet.getRow(0)) {
            headings.add(cell.getStringCellValue());
        }
        return headings;
    }

    private static ParsedLnaErrorFile parsed(LnaErrorRecord... records) {
        return new ParsedLnaErrorFile(
                Path.of("lnaerror-20260815.dat"), List.of(records), List.of(), true);
    }

    private static LnaErrorRecord record(int lineNumber, String errorCode) {
        return new LnaErrorRecord(lineNumber, "FIRM ENTITY", "000000000103", "FIXTURE FIRM",
                "TESTBD0001", "10", "0A1", "123456789", "F", "LLE", "0012345678", errorCode,
                "SOME ERROR DESCRIPTION");
    }

    /** A D01 row: no Allstate id of its own, so the key column falls back to the tax id. */
    private static LnaErrorRecord producer(String ssn) {
        return new LnaErrorRecord(1, "PRODUCER ENTITY", "000000000023", "FIXTURE FIRM",
                "TESTBD0001", "10", "0A1", ssn, "P", "IP", "", "D0101", "SSN INVALID");
    }

    private static LnaErrorExcelProperties properties(boolean maskTaxId) {
        return new LnaErrorExcelProperties(
                Path.of("in"), Path.of("out"), "lnaerror-*.dat", null, "ALLSTATE*", null,
                StandardCharsets.UTF_8, maskTaxId, false, true, "Records", true);
    }
}
