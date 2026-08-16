package com.prudential.adb.allstate.hr.lnaexcel.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.prudential.adb.allstate.hr.lnaexcel.feed.ErrorCodeCatalog.Severity;

class ErrorCodeCatalogTest {

    /**
     * The catalog and the byte-range index are transcribed from different sources - the test-records
     * catalog's Error Codes sheet and the validator code. They must still cover the same codes, or a
     * row would show a byte range with no disposition, or the reverse.
     */
    @Test
    void coversEveryCodeTheByteRangeIndexKnows() {
        assertThat(ErrorCodeFieldIndex.all()).allSatisfy(target ->
                assertThat(ErrorCodeCatalog.find(target.errorCode()))
                        .as("catalog knows %s", target.errorCode())
                        .isPresent());
    }

    @Test
    void separatesTheCostOfTheSameFieldFailingOnDifferentRecords() {
        assertThat(ErrorCodeCatalog.find("C0707")).get().satisfies(meaning -> {
            assertThat(meaning.severity()).isEqualTo(Severity.ERROR);
            assertThat(meaning.disposition()).isEqualTo("Firm record rejected - bundle continues");
        });
        assertThat(ErrorCodeCatalog.find("B0607")).get().satisfies(meaning ->
                assertThat(meaning.disposition())
                        .isEqualTo("First firm record fails - whole bundle rejected"));
        // Same rule, same field, same bytes - two very different outcomes.
        assertThat(ErrorCodeCatalog.find("C0707").orElseThrow().meaning())
                .isEqualTo(ErrorCodeCatalog.find("B0607").orElseThrow().meaning());
    }

    @Test
    void marksOnlyTheTwoWarningCodesAsWarnings() {
        List<String> warnings = ErrorCodeFieldIndex.all().stream()
                .map(ErrorCodeTarget::errorCode)
                .filter(code -> ErrorCodeCatalog.find(code).orElseThrow().severity() == Severity.WARNING)
                .toList();

        assertThat(warnings).containsExactlyInAnyOrder("C0715", "F0109");
    }

    @Test
    void everyCodeCarriesADisposition() {
        assertThat(ErrorCodeFieldIndex.all()).allSatisfy(target ->
                assertThat(ErrorCodeCatalog.find(target.errorCode()).orElseThrow().disposition())
                        .as("%s has a disposition", target.errorCode())
                        .isNotBlank());
    }

    /** The catalog's corpus does not exercise these, so they carry a disposition and no gloss. */
    @Test
    void leavesTheMeaningBlankForCodesTheCatalogDoesNotList() {
        assertThat(List.of("C0703", "M2701", "O2711", "P2721", "D2731"))
                .allSatisfy(code -> assertThat(ErrorCodeCatalog.find(code)).get()
                        .satisfies(meaning -> {
                            assertThat(meaning.meaning()).isNull();
                            assertThat(meaning.meaningOrBlank()).isEmpty();
                            assertThat(meaning.disposition()).isNotBlank();
                        }));
    }

    @Test
    void hasNoAnswerForACodeItDoesNotKnow() {
        assertThat(ErrorCodeCatalog.find("Z9999")).isEmpty();
        assertThat(ErrorCodeCatalog.find(null)).isEmpty();
    }
}
