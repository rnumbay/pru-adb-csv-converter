package com.prudential.adb.allstate.hr.lnaexcel.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedFileTest {

    @TempDir
    Path directory;

    @Test
    void readsAFirmsOwnNameOutOfTheRecordTheReportOnlyGivesAPositionFor() throws IOException {
        FeedFile feed = write(
                header(),
                contraHeader("FIXTURE BD INC", "TESTBD0001"),
                firm("APEX GAP NOL1 LLC", "ALSTLE0101"));

        assertThat(feed.nameAt(3)).contains("APEX GAP NOL1 LLC");
        assertThat(feed.typeAt(3)).contains("C");
        assertThat(feed.field(3, FeedLayout.FIRM_OWN_ALLSTATE_ID)).contains("ALSTLE0101");
    }

    @Test
    void readsAContraHeadersFirmName() throws IOException {
        FeedFile feed = write(header(), contraHeader("FIXTURE BD INC", "TESTBD0001"));

        assertThat(feed.typeAt(2)).contains("B");
        assertThat(feed.nameAt(2)).contains("FIXTURE BD INC");
    }

    @Test
    void joinsAProducersFirstAndLastName() throws IOException {
        FeedFile feed = write(header(), producer("ALEX", "FIXTURE"));

        assertThat(feed.typeAt(2)).contains("D01");
        assertThat(feed.nameAt(2)).contains("ALEX FIXTURE");
    }

    /** A D02 is the appointment, not the person - its name is the D01's, as in the catalog. */
    @Test
    void takesAProfileRecordsNameFromTheProducerRecordItFollows() throws IOException {
        FeedFile feed = write(header(), producer("ALEX", "FIXTURE"), profile());

        assertThat(feed.typeAt(3)).contains("D02");
        assertThat(feed.nameAt(3)).contains("ALEX FIXTURE");
    }

    @Test
    void doesNotBorrowANameAcrossABundleBoundary() throws IOException {
        FeedFile feed = write(
                header(),
                producer("ALEX", "FIXTURE"),
                contraHeader("ANOTHER BD INC", "TESTBD0002"),
                profile());

        assertThat(feed.nameAt(4)).isEmpty();
    }

    /** One producer, several appointments - every profile in the run belongs to the D01 above it. */
    @Test
    void namesEveryProfileInARunOfThemAfterOneProducerRecord() throws IOException {
        FeedFile feed = write(
                header(),
                producerWithSsn("ALEX", "FIXTURE", "111223333"),
                profileWithSsn("111223333"),
                profileWithSsn("111223333"),
                profileWithSsn("111223333"));

        assertThat(feed.nameAt(3)).contains("ALEX FIXTURE");
        assertThat(feed.nameAt(4)).contains("ALEX FIXTURE");
        assertThat(feed.nameAt(5)).contains("ALEX FIXTURE");
    }

    @Test
    void pairsAProfileWithTheProducerCarryingTheSameSsnNotMerelyTheNearestOne() throws IOException {
        FeedFile feed = write(
                header(),
                producerWithSsn("ALEX", "FIXTURE", "111223333"),
                producerWithSsn("BLAKE", "SAMPLE", "444556666"),
                profileWithSsn("111223333"));

        assertThat(feed.nameAt(4)).contains("ALEX FIXTURE");
    }

    /**
     * {@code F0101} is the code for a profile whose SSN does not match its producer record, so the
     * SSN match cannot succeed - the record it follows is still the one it was meant to belong to.
     */
    @Test
    void fallsBackToTheProducerItFollowsWhenNoSsnMatches() throws IOException {
        FeedFile feed = write(
                header(),
                producerWithSsn("ALEX", "FIXTURE", "111223333"),
                profileWithSsn("999887777"));

        assertThat(feed.nameAt(3)).contains("ALEX FIXTURE");
    }

    @Test
    void hasNothingToSayAboutAPositionPastTheEndOfTheFile() throws IOException {
        FeedFile feed = write(header(), firm("APEX GAP NOL1 LLC", "ALSTLE0101"));

        assertThat(feed.recordAt(99)).isEmpty();
        assertThat(feed.typeAt(99)).isEmpty();
        assertThat(feed.nameAt(99)).isEmpty();
        assertThat(feed.field(99, FeedLayout.FIRM_NAME)).isEmpty();
    }

    /** The feed's own header record is legitimately shorter than the 600-byte LRECL. */
    @Test
    void toleratesARecordShorterThanTheRecordLength() throws IOException {
        FeedFile feed = write(header());

        assertThat(feed.recordCount()).isEqualTo(1);
        assertThat(feed.typeAt(1)).contains("A");
        assertThat(feed.field(1, FeedLayout.FIRM_NAME)).contains("");
    }

    @Test
    void readsASeparatorlessFixedBlockFeedToo() throws IOException {
        Path file = directory.resolve("ALLSTATE.LNA.D20260807");
        Files.writeString(file,
                pad(contraHeader("FIXTURE BD INC", "TESTBD0001")) + pad(firm("APEX GAP NOL1 LLC", "ALSTLE0101")),
                StandardCharsets.UTF_8);

        FeedFile feed = FeedFile.load(file, StandardCharsets.UTF_8);

        assertThat(feed.recordCount()).isEqualTo(2);
        assertThat(feed.nameAt(2)).contains("APEX GAP NOL1 LLC");
    }

    private FeedFile write(String... records) throws IOException {
        Path file = directory.resolve("ALLSTATE.LNA.D20260807");
        Files.write(file, List.of(records), StandardCharsets.UTF_8);
        return FeedFile.load(file, StandardCharsets.UTF_8);
    }

    private static String header() {
        return "A" + "ALLSTATE".repeat(1) + " ".repeat(50);
    }

    private static String contraHeader(String firmName, String bdId) {
        return place("B", FeedLayout.CH_FIRM_NAME, firmName, FeedLayout.CH_BD_ALLSTATE_ID, bdId);
    }

    private static String firm(String firmName, String allstateId) {
        return place("C", FeedLayout.FIRM_NAME, firmName, FeedLayout.FIRM_OWN_ALLSTATE_ID, allstateId);
    }

    private static String producer(String first, String last) {
        return place("D01", FeedLayout.PRODUCER_FIRST_NAME, first, FeedLayout.PRODUCER_LAST_NAME, last);
    }

    private static String profile() {
        return place("D02", FeedLayout.PROFILE_ALLSTATE_ID, "0012345678", FeedLayout.PROFILE_TYPE, "C");
    }

    private static String producerWithSsn(String first, String last, String ssn) {
        StringBuilder record = new StringBuilder(producer(first, last));
        put(record, FeedLayout.PRODUCER_SSN, ssn);
        return record.toString();
    }

    private static String profileWithSsn(String ssn) {
        StringBuilder record = new StringBuilder(profile());
        put(record, FeedLayout.PROFILE_SSN, ssn);
        return record.toString();
    }

    /** Builds a record with a type in byte 1 and two fields at their declared offsets. */
    private static String place(
            String type, FeedField first, String firstValue, FeedField second, String secondValue) {
        StringBuilder record = new StringBuilder(" ".repeat(FeedLayout.RECORD_LENGTH));
        record.replace(0, type.length(), type);
        put(record, first, firstValue);
        put(record, second, secondValue);
        return record.toString();
    }

    private static void put(StringBuilder record, FeedField field, String value) {
        record.replace(field.startByte() - 1, field.startByte() - 1 + value.length(), value);
    }

    private static String pad(String record) {
        return record.length() >= FeedLayout.RECORD_LENGTH
                ? record.substring(0, FeedLayout.RECORD_LENGTH)
                : record + " ".repeat(FeedLayout.RECORD_LENGTH - record.length());
    }
}
