package com.prudential.adb.allstate.hr.lnaexcel.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.prudential.adb.allstate.hr.lnaexcel.config.LnaErrorExcelProperties;
import com.prudential.adb.allstate.hr.lnaexcel.testsupport.LnaErrorRecordFixture;

class LnaErrorFileParserTest {

    @TempDir
    Path directory;

    private final LnaErrorFileParser parser = new LnaErrorFileParser(properties());

    @Test
    void parsesEveryFieldAtItsDeclaredOffset() throws IOException {
        Path file = write(LnaErrorRecordFixture.populated().render());

        LnaErrorRecord record = parser.parse(file).records().get(0);

        assertThat(record.recordType()).isEqualTo("FIRM ENTITY");
        assertThat(record.recordPosition()).isEqualTo("000000000103");
        assertThat(record.contraHeaderFirmName()).isEqualTo("FIXTURE FIRM ONE");
        assertThat(record.contraHeaderBdAllstateId()).isEqualTo("TESTBD0001");
        assertThat(record.contraHeaderDistributionChannel()).isEqualTo("10");
        assertThat(record.adbOrgCode()).isEqualTo("0A1");
        assertThat(record.ssnOrTin()).isEqualTo("123456789");
        assertThat(record.personFirmIndicator()).isEqualTo("F");
        assertThat(record.entityType()).isEqualTo("LLE");
        assertThat(record.allstateId()).isEqualTo("0012345678");
        assertThat(record.errorCode()).isEqualTo("C0701");
        assertThat(record.errorDescription())
                .isEqualTo("\"BUSINESS ADDRESS\" NOT VALUED - RECORD REJECTED");
        assertThat(record.lineNumber()).isEqualTo(1);
    }

    @Test
    void trimsThePaddingButKeepsAnEmptyFieldEmpty() throws IOException {
        Path file = write(LnaErrorRecordFixture.empty()
                .with(LnaErrorField.ERROR_CODE, "B0100")
                .render());

        LnaErrorRecord record = parser.parse(file).records().get(0);

        assertThat(record.errorCode()).isEqualTo("B0100");
        assertThat(record.contraHeaderFirmName()).isEmpty();
        // A zoned-numeric field is zero-padded by the writer, not blanked - the parser keeps that
        // as it found it and leaves "nine zeros means absent" to the reader.
        assertThat(record.ssnOrTin()).isEqualTo("000000000");
    }

    /**
     * A tilde inside the 125-character description is exactly the case a {@code split("~")} parser
     * would desynchronise on - every field after it would shift one place left.
     */
    @Test
    void aDelimiterInsideAFieldDoesNotShiftTheFieldsAfterIt() throws IOException {
        Path file = write(LnaErrorRecordFixture.populated()
                .with(LnaErrorField.CONTRA_HEADER_FIRM_NAME, "FIXTURE~FIRM")
                .render());

        ParsedLnaErrorFile parsed = parser.parse(file);

        LnaErrorRecord record = parsed.records().get(0);
        assertThat(record.contraHeaderFirmName()).isEqualTo("FIXTURE~FIRM");
        assertThat(record.errorCode()).isEqualTo("C0701");
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    void skipsBlankLinesAndNumbersRecordsByTheirLineInTheFile() throws IOException {
        Path file = write(
                LnaErrorRecordFixture.populated().with(LnaErrorField.ERROR_CODE, "D0101").render(),
                "",
                LnaErrorRecordFixture.populated().with(LnaErrorField.ERROR_CODE, "D0103").render());

        ParsedLnaErrorFile parsed = parser.parse(file);

        assertThat(parsed.records()).extracting(LnaErrorRecord::errorCode)
                .containsExactly("D0101", "D0103");
        assertThat(parsed.records()).extracting(LnaErrorRecord::lineNumber).containsExactly(1, 3);
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    void readsASeparatorlessFixedBlockFileAsFixedLengthRecords() throws IOException {
        String block = LnaErrorRecordFixture.populated().with(LnaErrorField.ERROR_CODE, "E0100").render()
                + LnaErrorRecordFixture.populated().with(LnaErrorField.ERROR_CODE, "F0104").render();
        Path file = directory.resolve("lnaerror-block.dat");
        Files.writeString(file, block, StandardCharsets.UTF_8);

        ParsedLnaErrorFile parsed = parser.parse(file);

        assertThat(parsed.recordSeparated()).isFalse();
        assertThat(parsed.records()).extracting(LnaErrorRecord::errorCode)
                .containsExactly("E0100", "F0104");
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    void warnsAboutAShortRecordAndReadsItsMissingFieldsAsBlank() throws IOException {
        String truncated = LnaErrorRecordFixture.populated().render().substring(0, 180);
        Path file = write(truncated);

        ParsedLnaErrorFile parsed = parser.parse(file);

        assertThat(parsed.warnings()).singleElement()
                .satisfies(warning -> {
                    assertThat(warning.lineNumber()).isEqualTo(1);
                    assertThat(warning.detail()).contains("180 characters, expected 325");
                });
        LnaErrorRecord record = parsed.records().get(0);
        assertThat(record.recordType()).isEqualTo("FIRM ENTITY");
        assertThat(record.errorDescription()).isEmpty();
    }

    @Test
    void warnsAboutTheFirstMisplacedDelimiterAndNamesTheFieldItFollows() throws IOException {
        String record = LnaErrorRecordFixture.populated().render();
        // Overwrite the tilde after ADB Org Code (offset 157) with a space, as a record written to
        // a different layout would.
        String damaged = record.substring(0, 157) + " " + record.substring(158);
        Path file = write(damaged);

        ParsedLnaErrorFile parsed = parser.parse(file);

        assertThat(parsed.warnings()).singleElement()
                .satisfies(warning -> assertThat(warning.detail())
                        .contains("offset 157")
                        .contains(LnaErrorField.ADB_ORG_CODE.columnHeading())
                        .contains("a space"));
        // Still parsed, still readable - a mis-delimited record is reported, not dropped.
        assertThat(parsed.records()).hasSize(1);
    }

    @Test
    void anEmptyFileParsesToNoRecords() throws IOException {
        Path file = write("");

        ParsedLnaErrorFile parsed = parser.parse(file);

        assertThat(parsed.records()).isEmpty();
        assertThat(parsed.warnings()).isEmpty();
    }

    private Path write(String... records) throws IOException {
        Path file = directory.resolve("lnaerror-20260815.dat");
        Files.write(file, List.of(records), StandardCharsets.UTF_8);
        return file;
    }

    private static LnaErrorExcelProperties properties() {
        return new LnaErrorExcelProperties(
                Path.of("in"), Path.of("out"), "lnaerror-*.dat", null, "ALLSTATE*", null,
                StandardCharsets.UTF_8, true, false, true, "Records", true);
    }
}
