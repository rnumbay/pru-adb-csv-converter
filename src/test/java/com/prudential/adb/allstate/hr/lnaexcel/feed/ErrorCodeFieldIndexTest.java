package com.prudential.adb.allstate.hr.lnaexcel.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ErrorCodeFieldIndexTest {

    /**
     * The whole {@code ERRORCD} catalog seeded by {@code V7__error_code_seed.sql}. Every code that
     * can reach an {@code LNAERROR} row is in this list, so the index must answer for all of them -
     * a blank "Feed Field" column should mean "this code is not about one field", never "nobody
     * filled this in".
     */
    private static final List<String> CATALOG = List.of(
            "B0100", "B0200", "B0300", "B0400", "B0500", "B0601", "B0602", "B0604", "B0605",
            "B0606", "B0607", "B0608", "B0609", "B0610", "B0611", "B0612", "B0613", "B0700",
            "O2711", "C0701", "C0702", "C0703", "C0704", "C0705", "C0706", "C0707", "C0708",
            "C0709", "C0710", "C0711", "C0712", "C0713", "C0714", "C0715", "C0716", "C0717",
            "D0101", "D0102", "D0103", "D0104", "D0105", "D0106", "D0107", "E0100", "F0101",
            "F0102", "F0103", "F0104", "F0105", "F0106", "F0107", "F0108", "F0109", "F0110",
            "F0111", "F0112", "F0113", "M2701", "P2721", "D2731");

    @Test
    void knowsEveryCodeInTheErrorcdCatalog() {
        assertThat(CATALOG).allSatisfy(code ->
                assertThat(ErrorCodeFieldIndex.find(code))
                        .as("index knows %s", code)
                        .isPresent());
        assertThat(ErrorCodeFieldIndex.all()).hasSameSizeAs(CATALOG);
    }

    /**
     * The case that prompted all of this: the report says {@code "BUSINESS ADDRESS" ... IN THE
     * "FIRM ENTITY" RECORD} and gives no position. The answer is bytes 115-306 of the firm record.
     */
    @Test
    void resolvesTheBusinessAddressCodesToTheFirmRecordsFirstAddressBlock() {
        assertThat(ErrorCodeFieldIndex.find("C0707")).get().satisfies(target -> {
            assertThat(target.recordKind()).isEqualTo(FeedRecordKind.FIRM_ENTITY);
            assertThat(target.field().byteRange()).isEqualTo("115-306");
            assertThat(target.field().length()).isEqualTo(192);
        });

        // B0607 is the same rule on the bundle's first firm record - same field, same bytes.
        assertThat(ErrorCodeFieldIndex.find("B0607")).get()
                .satisfies(target -> assertThat(target.field())
                        .isEqualTo(ErrorCodeFieldIndex.find("C0707").orElseThrow().field()));
    }

    @Test
    void separatesTheFirmsOwnAllstateIdFromItsParentReference() {
        assertThat(ErrorCodeFieldIndex.find("C0704").orElseThrow().field().byteRange())
                .isEqualTo("88-97");
        assertThat(ErrorCodeFieldIndex.find("C0712").orElseThrow().field().byteRange())
                .isEqualTo("526-535");
        assertThat(ErrorCodeFieldIndex.find("C0713").orElseThrow().field().byteRange())
                .isEqualTo("516-525");
    }

    @Test
    void separatesTheProducerProfilesFirmAndBdReferences() {
        assertThat(ErrorCodeFieldIndex.find("F0107").orElseThrow().field().byteRange())
                .isEqualTo("241-250");
        assertThat(ErrorCodeFieldIndex.find("F0108").orElseThrow().field().byteRange())
                .isEqualTo("251-260");
    }

    @Test
    void pointsProducerCodesAtTheProducerRecordsOwnLayout() {
        assertThat(ErrorCodeFieldIndex.find("D0101").orElseThrow().field().byteRange())
                .isEqualTo("4-12");
        assertThat(ErrorCodeFieldIndex.find("D0106").orElseThrow().field().byteRange())
                .isEqualTo("97-328");
        assertThat(ErrorCodeFieldIndex.find("F0104").orElseThrow().field().byteRange())
                .isEqualTo("44-235");
    }

    /** A code that rejects a record or a bundle gets a note, and deliberately no byte range. */
    @Test
    void givesNoByteRangeToACodeThatIsNotAboutOneField() {
        assertThat(List.of("B0400", "C0714", "C0715", "C0717", "E0100", "F0109", "F0113"))
                .allSatisfy(code -> assertThat(ErrorCodeFieldIndex.find(code)).get()
                        .satisfies(target -> {
                            assertThat(target.hasField()).as("%s has no field", code).isFalse();
                            assertThat(target.note()).as("%s explains why", code).isNotBlank();
                        }));
    }

    @Test
    void treatsTheAdbProcessingCodesAsNotFeedValidationsAtAll() {
        assertThat(List.of("M2701", "O2711", "P2721", "D2731"))
                .allSatisfy(code -> assertThat(ErrorCodeFieldIndex.find(code)).get()
                        .satisfies(target -> {
                            assertThat(target.hasField()).as("%s names no field", code).isFalse();
                            assertThat(target.note()).contains("ADB processing error");
                        }));
    }

    /**
     * Profile creation only ever runs from a producer profile record, and the Day 1 corpus bears
     * that out - all 33 of its {@code P2721} rows sit on records the test catalog types {@code D02}.
     * The driver-level codes can attach to any record, and {@code O2711} is unexercised, so those
     * three assert nothing.
     */
    @Test
    void typesTheProfileCreationFailureAsTheProducerProfileRecord() {
        assertThat(ErrorCodeFieldIndex.find("P2721").orElseThrow().recordKind())
                .isEqualTo(FeedRecordKind.PRODUCER_PROFILE);
        assertThat(List.of("M2701", "O2711", "D2731")).allSatisfy(code ->
                assertThat(ErrorCodeFieldIndex.find(code).orElseThrow().recordKind())
                        .as("%s is not tied to one record type", code).isNull());
    }

    /**
     * {@code B0400} is detected when the bundle closes, so its row sits on whatever record closed
     * it - the next contra header, or the file trailer. Naming a record type here would type those
     * rows wrongly; the Day 1 corpus has three on a following {@code B} and one on a {@code Z}.
     */
    @Test
    void tiesTheMissingBdFirmCodeToNoRecordTypeAtAll() {
        assertThat(ErrorCodeFieldIndex.find("B0400")).get().satisfies(target -> {
            assertThat(target.recordKind()).isNull();
            assertThat(target.hasField()).isFalse();
            assertThat(target.note()).contains("Reported at the record that closed the bundle");
        });
    }

    @Test
    void everyMappedFieldIsOneTheLayoutDeclares() {
        assertThat(ErrorCodeFieldIndex.all())
                .filteredOn(ErrorCodeTarget::hasField)
                .allSatisfy(target -> assertThat(FeedLayout.allFields()).contains(target.field()));
    }

    @Test
    void reportsBothCodesThatRejectASharedField() {
        assertThat(ErrorCodeFieldIndex.codesFor(FeedLayout.FIRM_BUSINESS_ADDRESS))
                .containsExactly("B0607", "C0707");
    }

    @Test
    void hasNoAnswerForACodeItDoesNotKnow() {
        assertThat(ErrorCodeFieldIndex.find("Z9999")).isEmpty();
        assertThat(ErrorCodeFieldIndex.find("")).isEmpty();
        assertThat(ErrorCodeFieldIndex.find(null)).isEmpty();
    }
}
