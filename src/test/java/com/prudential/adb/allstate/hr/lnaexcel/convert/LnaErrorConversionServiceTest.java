package com.prudential.adb.allstate.hr.lnaexcel.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.prudential.adb.allstate.hr.lnaexcel.config.LnaErrorExcelProperties;
import com.prudential.adb.allstate.hr.lnaexcel.excel.LnaErrorExcelWriter;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedField;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedFile;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedLayout;
import com.prudential.adb.allstate.hr.lnaexcel.parse.LnaErrorField;
import com.prudential.adb.allstate.hr.lnaexcel.parse.LnaErrorFileParser;
import com.prudential.adb.allstate.hr.lnaexcel.testsupport.LnaErrorRecordFixture;

/**
 * End to end over the real parser and the real POI writer - a {@code .dat} in, an openable
 * {@code .xlsx} out.
 */
class LnaErrorConversionServiceTest {

    @TempDir
    Path input;

    @TempDir
    Path output;

    @Test
    void convertsADatFileToAWorkbookNamedAfterIt() throws IOException {
        Path source = feed("lnaerror-20260815.dat", "C0701", "D0101");

        ConversionResult result = service(properties()).convertFile(source, output);

        assertThat(result.workbook()).isEqualTo(output.resolve("lnaerror-20260815.xlsx"));
        assertThat(result.workbook()).exists();
        assertThat(result.recordCount()).isEqualTo(2);
        assertThat(result.hasWarnings()).isFalse();

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(result.workbook()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = workbook.getSheet("Records");
            assertThat(sheet.getLastRowNum()).isEqualTo(2); // header + two records
            assertThat(sheet.getRow(1).getCell(columnOf(sheet, "Error Code"))
                    .getStringCellValue()).isEqualTo("C0701");
            // ...and the byte range that sends someone to the right place in the feed record.
            assertThat(sheet.getRow(1).getCell(columnOf(sheet, "Field Position (bytes)"))
                    .getStringCellValue()).isEqualTo("2-10");
        }
    }

    @Test
    void createsTheOutputDirectoryWhenItIsNotThereYet() throws IOException {
        Path source = feed("lnaerror-20260815.dat", "C0701");
        Path missing = output.resolve("not").resolve("created").resolve("yet");

        ConversionResult result = service(properties()).convertFile(source, missing);

        assertThat(result.workbook()).exists();
    }

    @Test
    void writesToTheExactPathWhenOneIsNamed() throws IOException {
        Path source = feed("lnaerror-20260815.dat", "C0701");
        Path target = output.resolve("day1-errors.xlsx");

        ConversionResult result = service(properties()).convertTo(source, target);

        assertThat(result.workbook()).isEqualTo(target);
        assertThat(target).exists();
    }

    @Test
    void findsEveryMatchingFileInADirectoryInNameOrder() throws IOException {
        feed("lnaerror-20260814.dat", "C0701");
        feed("lnaerror-20260815.dat", "D0101");
        feed("adbskip-20260815.dat", "C0701");

        List<Path> sources = service(properties()).findSources(input);

        assertThat(sources).extracting(path -> path.getFileName().toString())
                .containsExactly("lnaerror-20260814.dat", "lnaerror-20260815.dat");
    }

    @Test
    void aDirectoryWithNothingToConvertIsNotAFailure() {
        assertThat(service(properties()).findSources(input)).isEmpty();
    }

    @Test
    void aNamedFileThatIsNotThereIs() {
        assertThatThrownBy(() -> service(properties()).findSources(input.resolve("absent.dat")))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("Input does not exist");
    }

    @Test
    void carriesLayoutWarningsOntoTheResultButStillWritesTheWorkbook() throws IOException {
        Path source = input.resolve("lnaerror-20260815.dat");
        Files.write(source,
                List.of(LnaErrorRecordFixture.populated().render().substring(0, 180)),
                StandardCharsets.UTF_8);

        ConversionResult result = service(properties()).convertFile(source, output);

        assertThat(result.warnings()).hasSize(1);
        assertThat(result.workbook()).exists();
    }

    @Test
    void failOnLayoutWarningStopsTheFileInsteadOfConvertingIt() throws IOException {
        Path source = input.resolve("lnaerror-20260815.dat");
        Files.write(source,
                List.of(LnaErrorRecordFixture.populated().render().substring(0, 180)),
                StandardCharsets.UTF_8);
        LnaErrorConversionService service = service(properties(true, true));

        assertThatThrownBy(() -> service.convertFile(source, output))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("does not match the LNAERROR layout");
        assertThat(output.resolve("lnaerror-20260815.xlsx")).doesNotExist();
    }

    @Test
    void refusesToReplaceAnExistingWorkbookWhenOverwriteIsOff() throws IOException {
        Path source = feed("lnaerror-20260815.dat", "C0701");
        Path existing = Files.writeString(output.resolve("lnaerror-20260815.xlsx"), "not a workbook");
        LnaErrorConversionService service = service(properties(true, false, false));

        assertThatThrownBy(() -> service.convertFile(source, output))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("overwrite-existing is false");
        assertThat(existing).hasContent("not a workbook");
    }

    /** A failed write must not leave the temp file behind for the next run to trip over. */
    @Test
    void leavesNoTemporaryFileBehindWhenTheWriteFails() throws IOException {
        Path source = feed("lnaerror-20260815.dat", "C0701");
        // A non-empty directory standing where the workbook should go: the write succeeds, the
        // rename onto it cannot - which is the path that has a temp file to clean up.
        Path blocked = Files.createDirectory(output.resolve("lnaerror-20260815.xlsx"));
        Files.writeString(blocked.resolve("occupied.txt"), "in the way");

        assertThatThrownBy(() -> service(properties()).convertFile(source, output))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("Could not write");

        try (var entries = Files.list(output)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    /**
     * The whole point of passing a feed: LNAERROR gives a position, the feed says whose record that
     * was. The report itself carries only the bundle's BD name.
     */
    @Test
    void namesTheFailingRecordWhenTheFeedIsSupplied() throws IOException {
        Path source = input.resolve("lnaerror-20260815.dat");
        Files.write(source, List.of(LnaErrorRecordFixture.populated()
                .with(LnaErrorField.RECORD_POSITION, "2")
                .with(LnaErrorField.ERROR_CODE, "C0707")
                .render()), StandardCharsets.UTF_8);
        FeedFile feed = feedWith("APEX GAP NOL1 LLC");

        ConversionResult result = service(properties()).convertFile(source, output, feed);

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(result.workbook()))) {
            Sheet sheet = workbook.getSheet("Records");
            Row failing = rowForPosition(sheet, 2);
            assertThat(failing.getCell(columnOf(sheet, "Name")).getStringCellValue())
                    .isEqualTo("APEX GAP NOL1 LLC");
            // ...and where in that record to look: C0707 is the 115-306 address block.
            assertThat(failing.getCell(columnOf(sheet, "Field Position (bytes)"))
                    .getStringCellValue()).isEqualTo("115-306");
        }
    }

    /**
     * LNAERROR holds errors, so a record that passed has no row in it at all - which is what makes
     * the sheet awkward to line up against the test-records catalog, where every record has one.
     * With a feed, the records that did not fail are listed too.
     */
    @Test
    void listsTheFeedRecordsThatDidNotFailToo() throws IOException {
        Path source = input.resolve("lnaerror-20260815.dat");
        Files.write(source, List.of(LnaErrorRecordFixture.populated()
                .with(LnaErrorField.RECORD_POSITION, "2")
                .with(LnaErrorField.ERROR_CODE, "C0707")
                .render()), StandardCharsets.UTF_8);

        ConversionResult result = service(properties())
                .convertFile(source, output, feedWith("APEX GAP NOL1 LLC"));

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(result.workbook()))) {
            Sheet sheet = workbook.getSheet("Records");
            // One row per feed record: the clean header at position 1, the failing firm at 2.
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            Row clean = rowForPosition(sheet, 1);
            assertThat(clean.getCell(columnOf(sheet, "Error Code")).getStringCellValue()).isEmpty();
            assertThat(clean.getCell(columnOf(sheet, "Type")).getStringCellValue()).isEqualTo("A");
            // No code, so no field to point at - the two field columns stay blank.
            assertThat(clean.getCell(columnOf(sheet, "Feed Field")).getStringCellValue()).isEmpty();
            assertThat(clean.getCell(columnOf(sheet, "Field Position (bytes)"))
                    .getStringCellValue()).isEmpty();
        }
    }

    private static Row rowForPosition(Sheet sheet, int position) {
        int column = columnOf(sheet, "Rec #");
        for (Row row : sheet) {
            if (row.getRowNum() > 0 && row.getCell(column).getNumericCellValue() == position) {
                return row;
            }
        }
        throw new AssertionError("No row for record position " + position);
    }

    @Test
    void leavesTheFeedDerivedColumnsBlankWhenNoFeedIsSupplied() throws IOException {
        Path source = feed("lnaerror-20260815.dat", "C0707");

        ConversionResult result = service(properties()).convertFile(source, output);

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(result.workbook()))) {
            Sheet sheet = workbook.getSheet("Records");
            assertThat(sheet.getRow(1).getCell(columnOf(sheet, "Name"))
                    .getStringCellValue()).isEmpty();
            // The field columns come from the error code, not the feed, so they are filled anyway.
            assertThat(sheet.getRow(1).getCell(columnOf(sheet, "Field Position (bytes)"))
                    .getStringCellValue()).isEqualTo("115-306");
        }
    }

    /**
     * A feed from the wrong cycle would fill every row with a real name from a real record - just
     * not the record that failed. Blank beats plausible.
     */
    @Test
    void refusesAFeedThatPointsAtRecordsOfTheWrongType() throws IOException {
        Path source = input.resolve("lnaerror-20260815.dat");
        // Firm-record codes, but the feed has a producer record at both positions.
        Files.write(source, List.of(
                        LnaErrorRecordFixture.populated().with(LnaErrorField.RECORD_POSITION, "1")
                                .with(LnaErrorField.ERROR_CODE, "C0707").render(),
                        LnaErrorRecordFixture.populated().with(LnaErrorField.RECORD_POSITION, "2")
                                .with(LnaErrorField.ERROR_CODE, "C0701").render()),
                StandardCharsets.UTF_8);
        Path feedFile = input.resolve("wrong-cycle.txt");
        String producer = record("D01", FeedLayout.PRODUCER_FIRST_NAME, "ALEX");
        Files.write(feedFile, List.of(producer, producer), StandardCharsets.UTF_8);

        ConversionResult result = service(properties())
                .convertFile(source, output, FeedFile.load(feedFile, StandardCharsets.UTF_8));

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(result.workbook()))) {
            Sheet sheet = workbook.getSheet("Records");
            assertThat(sheet.getRow(1).getCell(columnOf(sheet, "Name"))
                    .getStringCellValue()).isEmpty();
        }
    }

    /** A feed whose record 2 is the firm the fixture's error rows point at. */
    private FeedFile feedWith(String firmName) throws IOException {
        Path feedFile = input.resolve("ALLSTATE.LNA.D20260807");
        StringBuilder firm = new StringBuilder(record("C", FeedLayout.FIRM_NAME, firmName));
        firm.replace(FeedLayout.FIRM_BUSINESS_ADDRESS.startByte() - 1,
                FeedLayout.FIRM_BUSINESS_ADDRESS.startByte() - 1 + "1 FIXTURE STREET".length(),
                "1 FIXTURE STREET");
        Files.write(feedFile, List.of(record("A", FeedLayout.FIRM_NAME, ""), firm.toString()),
                StandardCharsets.UTF_8);
        return FeedFile.load(feedFile, StandardCharsets.UTF_8);
    }

    private static String record(String type, FeedField field, String value) {
        StringBuilder record = new StringBuilder(" ".repeat(FeedLayout.RECORD_LENGTH));
        record.replace(0, type.length(), type);
        if (!value.isEmpty()) {
            record.replace(field.startByte() - 1, field.startByte() - 1 + value.length(), value);
        }
        return record.toString();
    }

    private static int columnOf(Sheet sheet, String heading) {
        for (org.apache.poi.ss.usermodel.Cell cell : sheet.getRow(0)) {
            if (heading.equals(cell.getStringCellValue())) {
                return cell.getColumnIndex();
            }
        }
        throw new AssertionError("No column headed " + heading);
    }

    private Path feed(String fileName, String... errorCodes) throws IOException {
        Path file = input.resolve(fileName);
        Files.write(file,
                java.util.Arrays.stream(errorCodes)
                        .map(code -> LnaErrorRecordFixture.populated()
                                .with(LnaErrorField.ERROR_CODE, code)
                                .render())
                        .toList(),
                StandardCharsets.UTF_8);
        return file;
    }

    private static LnaErrorConversionService service(LnaErrorExcelProperties properties) {
        return new LnaErrorConversionService(
                new LnaErrorFileParser(properties),
                new LnaErrorExcelWriter(properties),
                new FeedFileResolver(properties),
                properties);
    }

    private static LnaErrorExcelProperties properties() {
        return properties(true, false, true);
    }

    private static LnaErrorExcelProperties properties(
            boolean maskTaxId, boolean failOnLayoutWarning) {
        return properties(maskTaxId, failOnLayoutWarning, true);
    }

    private static LnaErrorExcelProperties properties(
            boolean maskTaxId, boolean failOnLayoutWarning, boolean overwriteExisting) {
        return new LnaErrorExcelProperties(
                Path.of("in"), Path.of("out"), "lnaerror-*.dat", null, "ALLSTATE*", null,
                StandardCharsets.UTF_8, maskTaxId, failOnLayoutWarning, true, "Records",
                overwriteExisting);
    }
}
