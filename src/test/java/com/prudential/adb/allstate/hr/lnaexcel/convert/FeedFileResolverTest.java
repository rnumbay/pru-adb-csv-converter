package com.prudential.adb.allstate.hr.lnaexcel.convert;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.prudential.adb.allstate.hr.lnaexcel.config.LnaErrorExcelProperties;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedField;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedFile;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedLayout;
import com.prudential.adb.allstate.hr.lnaexcel.parse.LnaErrorField;
import com.prudential.adb.allstate.hr.lnaexcel.parse.LnaErrorFileParser;
import com.prudential.adb.allstate.hr.lnaexcel.parse.ParsedLnaErrorFile;
import com.prudential.adb.allstate.hr.lnaexcel.testsupport.LnaErrorRecordFixture;

class FeedFileResolverTest {

    @TempDir
    Path reports;

    @TempDir
    Path feeds;

    /** Two firm-record errors at positions 1 and 2 - what a candidate feed has to line up with. */
    private ParsedLnaErrorFile report() throws IOException {
        Path file = reports.resolve("lnaerror-20260815.dat");
        Files.write(file, List.of(
                        LnaErrorRecordFixture.populated().with(LnaErrorField.RECORD_POSITION, "1")
                                .with(LnaErrorField.ERROR_CODE, "C0707").render(),
                        LnaErrorRecordFixture.populated().with(LnaErrorField.RECORD_POSITION, "2")
                                .with(LnaErrorField.ERROR_CODE, "C0701").render()),
                StandardCharsets.UTF_8);
        return new LnaErrorFileParser(properties()).parse(file);
    }

    @Test
    void findsTheFeedInTheConfiguredDirectoryWithoutBeingToldWhichFileItIs() throws IOException {
        writeFeed("ALLSTATE.LNA.D20260815", "C", "C");

        FeedFile resolved = new FeedFileResolver(properties()).resolve(report(), null);

        assertThat(resolved).isNotNull();
        assertThat(resolved.source().getFileName()).hasToString("ALLSTATE.LNA.D20260815");
    }

    /** A feed from another cycle lines up with nothing - it must not be used for its neighbour. */
    @Test
    void passesOverACandidateWhoseRecordsDoNotLineUpWithTheReport() throws IOException {
        writeFeed("ALLSTATE.LNA.D20260814", "D01", "D01");
        writeFeed("ALLSTATE.LNA.D20260815", "C", "C");

        FeedFile resolved = new FeedFileResolver(properties()).resolve(report(), null);

        assertThat(resolved.source().getFileName()).hasToString("ALLSTATE.LNA.D20260815");
    }

    @Test
    void takesNoFeedAtAllWhenNothingInTheDirectoryMatches() throws IOException {
        writeFeed("ALLSTATE.LNA.D20260814", "D01", "D01");

        assertThat(new FeedFileResolver(properties()).resolve(report(), null)).isNull();
    }

    @Test
    void ignoresFilesThatDoNotMatchTheFeedPattern() throws IOException {
        writeFeed("NOTES.TXT", "C", "C");

        assertThat(new FeedFileResolver(properties()).resolve(report(), null)).isNull();
    }

    @Test
    void anEmptyOrMissingFeedDirectoryIsNotAFailure() throws IOException {
        assertThat(new FeedFileResolver(properties()).resolve(report(), null)).isNull();
        assertThat(new FeedFileResolver(properties(feeds.resolve("absent")))
                .resolve(report(), null)).isNull();
    }

    @Test
    void anExplicitFeedIsUsedWithoutSearchingTheDirectory() throws IOException {
        writeFeed("ALLSTATE.LNA.D20260815", "C", "C");
        Path explicit = writeFeed("HANDED-IN", "C", "C");

        FeedFile resolved = new FeedFileResolver(properties())
                .resolve(report(), FeedFile.load(explicit, StandardCharsets.UTF_8));

        assertThat(resolved.source().getFileName()).hasToString("HANDED-IN");
    }

    /** An explicitly named feed still has to be the right one - naming it is not evidence. */
    @Test
    void anExplicitFeedThatDoesNotMatchIsRejectedRatherThanTrusted() throws IOException {
        Path wrong = writeFeed("HANDED-IN", "D01", "D01");

        FeedFile resolved = new FeedFileResolver(properties())
                .resolve(report(), FeedFile.load(wrong, StandardCharsets.UTF_8));

        assertThat(resolved).isNull();
    }

    private Path writeFeed(String fileName, String... recordTypes) throws IOException {
        Path file = feeds.resolve(fileName);
        Files.write(file,
                java.util.Arrays.stream(recordTypes).map(FeedFileResolverTest::record).toList(),
                StandardCharsets.UTF_8);
        return file;
    }

    private static String record(String type) {
        StringBuilder record = new StringBuilder(" ".repeat(FeedLayout.RECORD_LENGTH));
        record.replace(0, type.length(), type);
        FeedField name = type.equals("C") ? FeedLayout.FIRM_NAME : FeedLayout.PRODUCER_FIRST_NAME;
        record.replace(name.startByte() - 1, name.startByte() - 1 + "FIXTURE".length(), "FIXTURE");
        return record.toString();
    }

    private LnaErrorExcelProperties properties() {
        return properties(feeds);
    }

    private LnaErrorExcelProperties properties(Path feedDirectory) {
        return new LnaErrorExcelProperties(
                Path.of("in"), Path.of("out"), "lnaerror-*.dat", feedDirectory, "ALLSTATE*", null,
                StandardCharsets.UTF_8, true, false, true, "Records", true);
    }
}
