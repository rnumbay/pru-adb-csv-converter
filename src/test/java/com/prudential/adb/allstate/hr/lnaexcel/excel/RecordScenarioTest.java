package com.prudential.adb.allstate.hr.lnaexcel.excel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedField;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedFile;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedLayout;
import com.prudential.adb.allstate.hr.lnaexcel.parse.LnaErrorRecord;
import com.prudential.adb.allstate.hr.lnaexcel.parse.SkipLog;

class RecordScenarioTest {

    @TempDir
    Path directory;

    @Test
    void describesAContraHeaderByTheBundleItOpens() throws IOException {
        FeedFile feed = feed(record("B", Map.of(
                FeedLayout.CH_BD_ALLSTATE_ID, "TESTBD0001",
                FeedLayout.CH_DISTRIBUTION_CHANNEL, "02")));

        assertThat(RecordScenario.describe(1, feed, null, SkipLog.empty()))
                .isEqualTo("Contra header - opens bundle for BD TESTBD0001 (channel 02).");
    }

    @Test
    void describesAFirmRecordByItsEntityStatusAndDates() throws IOException {
        FeedFile feed = feed(
                record("B", Map.of(FeedLayout.CH_BD_ALLSTATE_ID, "TESTBD0001")),
                record("C", Map.of(FeedLayout.FIRM_PROFILE_STATUS, "A",
                        FeedLayout.FIRM_PROFILE_EFFECTIVE_DATE, "20250101",
                        FeedLayout.FIRM_PROFILE_TERMINATION_DATE, "99999999")),
                record("C", Map.of(FeedLayout.FIRM_ENTITY_TYPE, "LLE",
                        FeedLayout.FIRM_OWN_ALLSTATE_ID, "TESTLE0001",
                        FeedLayout.FIRM_PARENT_BD_ALLSTATE_ID, "TESTBD0001",
                        FeedLayout.FIRM_PROFILE_STATUS, "A",
                        FeedLayout.FIRM_PROFILE_EFFECTIVE_DATE, "20250115",
                        FeedLayout.FIRM_PROFILE_TERMINATION_DATE, "99999999")));

        // The bundle's own BD record names itself; a later firm record names the BD it hangs off.
        assertThat(RecordScenario.describe(2, feed, null, SkipLog.empty()))
                .isEqualTo("BD firm itself (first C) - status Active, 20250101-99999999");
        assertThat(RecordScenario.describe(3, feed, null, SkipLog.empty()))
                .isEqualTo("LLE firm TESTLE0001 under BD TESTBD0001 - status Active, "
                        + "20250115-99999999");
    }

    @Test
    void describesAFailedRecordByTheCodeAndWhatItMeans() throws IOException {
        FeedFile feed = feed(record("C", Map.of(FeedLayout.FIRM_ENTITY_TYPE, "LLE")));

        assertThat(RecordScenario.describe(1, feed, error("C0707"), SkipLog.empty()))
                .isEqualTo("Error LLE firm record: C0707 invalid business/commission address block");
    }

    /**
     * A processing failure is the run falling over while applying a record that was itself fine, so
     * the record is still described as what it is.
     */
    @Test
    void describesARecordThatOnlyHitAProcessingFailureAsItself() throws IOException {
        FeedFile feed = feed(record("D02", Map.of(
                FeedLayout.PROFILE_TYPE, "C",
                FeedLayout.PROFILE_ALLSTATE_ID, "TESTIP0001",
                FeedLayout.PROFILE_PARENT_FIRM_ALLSTATE_ID, "TESTBD0001",
                FeedLayout.PROFILE_PARENT_BD_ALLSTATE_ID, "TESTBD0001",
                FeedLayout.PROFILE_RELATIONSHIP_STATUS, "A",
                FeedLayout.PROFILE_RELATIONSHIP_START_DATE, "20250201",
                FeedLayout.PROFILE_RELATIONSHIP_END_DATE, "99999999")));

        assertThat(RecordScenario.describe(1, feed, error("P2721"), SkipLog.empty()))
                .isEqualTo("Appointment (type C) TESTIP0001 under firm TESTBD0001 / BD TESTBD0001 "
                        + "- Active 20250201-99999999");
    }

    /**
     * {@code B0400} is reported on the record that closed the failing bundle - the next bundle's
     * contra header, which is itself good. Calling that record an error blames the wrong one.
     */
    @Test
    void describesTheRecordThatMerelyTriggeredABundleFailureAsItself() throws IOException {
        FeedFile feed = feed(record("B", Map.of(
                FeedLayout.CH_BD_ALLSTATE_ID, "TESTBD0002",
                FeedLayout.CH_DISTRIBUTION_CHANNEL, "02")));
        LnaErrorRecord b0400 = new LnaErrorRecord(1, "CONTRA HEADER", "000000000001", "",
                "TESTBD0001", "02", "", "000000000", "", "", "", "B0400", "NO FIRM ENTITY RECORD");

        assertThat(RecordScenario.describe(1, feed, b0400, SkipLog.empty()))
                .isEqualTo("Contra header - opens bundle for BD TESTBD0002 (channel 02). "
                        + "Arrival triggers B0400 for pending bundle TESTBD0001.");
    }

    /**
     * HR1 writes every rejected record to the skip log under reason 001, "unrecognized record
     * type" - on Day 1, 247 of 253 of them validly typed. On a record whose type the feed states,
     * that reason contradicts the record, and the record wins.
     */
    @Test
    void doesNotCallAValidlyTypedRecordUnrecognisedOnTheSkipLogsSayS0() throws IOException {
        FeedFile feed = feed(record("D02", Map.of(
                FeedLayout.PROFILE_TYPE, "C",
                FeedLayout.PROFILE_ALLSTATE_ID, "TESTIP0001",
                FeedLayout.PROFILE_PARENT_FIRM_ALLSTATE_ID, "TESTBD0001",
                FeedLayout.PROFILE_PARENT_BD_ALLSTATE_ID, "TESTBD0001",
                FeedLayout.PROFILE_RELATIONSHIP_STATUS, "A",
                FeedLayout.PROFILE_RELATIONSHIP_START_DATE, "20250201",
                FeedLayout.PROFILE_RELATIONSHIP_END_DATE, "99999999")));

        assertThat(RecordScenario.describe(1, feed, null, skipLog(1, "001")))
                .startsWith("Appointment (type C) TESTIP0001");
        // ...but a profile skipped because its own D01 was rejected is exactly what 002 means.
        assertThat(RecordScenario.describe(1, feed, null, skipLog(1, "002")))
                .isEqualTo("Producer profile whose D01 was rejected");
    }

    @Test
    void hasNothingToDescribeWithoutAFeed() {
        assertThat(RecordScenario.describe(1, null, error("C0707"), SkipLog.empty())).isEmpty();
    }

    private FeedFile feed(String... records) throws IOException {
        Path file = directory.resolve("ALLSTATE.LNA.D20260807");
        Files.write(file, List.of(records), StandardCharsets.UTF_8);
        return FeedFile.load(file, StandardCharsets.UTF_8);
    }

    private SkipLog skipLog(int position, String reason) throws IOException {
        Path file = directory.resolve("adbskip-20260815.dat");
        Files.write(file, List.of(String.format("%012d", position) + reason + "D02"),
                StandardCharsets.UTF_8);
        return SkipLog.load(file, StandardCharsets.UTF_8);
    }

    private static LnaErrorRecord error(String code) {
        return new LnaErrorRecord(1, "FIRM ENTITY", "000000000001", "FIXTURE FIRM", "TESTBD0001",
                "02", "", "000000000", "F", "LLE", "", code, "SOME ERROR");
    }

    /** A feed record of {@code type} with the given fields placed at their declared offsets. */
    private static String record(String type, Map<FeedField, String> fields) {
        StringBuilder record = new StringBuilder(" ".repeat(FeedLayout.RECORD_LENGTH));
        record.replace(0, type.length(), type);
        new LinkedHashMap<>(fields).forEach((field, value) ->
                record.replace(field.startByte() - 1, field.startByte() - 1 + value.length(), value));
        return record.toString();
    }
}
