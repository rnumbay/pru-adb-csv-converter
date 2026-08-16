package com.prudential.adb.allstate.hr.lnaexcel.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Internal consistency of the transcribed layout. These cannot prove the ranges match
 * {@code FeedRecordMapper} - only reading it can, and that is what the transcription did - but they
 * do catch the mistakes a transcription actually makes: a digit dropped, a block whose stated span
 * no longer covers its lines, a field left off the end of the record.
 */
class FeedLayoutTest {

    @Test
    void everyFieldFitsInsideTheFeedRecord() {
        assertThat(FeedLayout.allFields()).allSatisfy(field -> {
            assertThat(field.startByte()).isGreaterThanOrEqualTo(1);
            assertThat(field.endByte()).isLessThanOrEqualTo(FeedLayout.RECORD_LENGTH);
            assertThat(field.length()).isEqualTo(field.endByte() - field.startByte() + 1);
        });
    }

    /** A block's stated span must be exactly the span of the lines inside it - no more, no less. */
    @Test
    void everyAddressBlockCoversExactlyItsOwnLines() {
        assertBlockCoversItsLines(FeedLayout.FIRM_BUSINESS_ADDRESS);
        assertBlockCoversItsLines(FeedLayout.FIRM_CORRESPONDENCE_ADDRESS);
        assertBlockCoversItsLines(FeedLayout.PRODUCER_HOME_ADDRESS);
        assertBlockCoversItsLines(FeedLayout.PROFILE_BUSINESS_ADDRESS);
    }

    /** Within one record type, two fields may not claim the same byte unless one is a block. */
    @Test
    void noTwoFieldsOfOneRecordTypeOverlap() {
        for (FeedRecordKind kind : FeedRecordKind.values()) {
            List<FeedField> fields = FeedLayout.allFields().stream()
                    .filter(field -> field.kind() == kind)
                    .filter(field -> field.partOf() == null)
                    .filter(field -> FeedLayout.fieldsIn(field).isEmpty())
                    .sorted((a, b) -> Integer.compare(a.startByte(), b.startByte()))
                    .toList();
            for (int i = 1; i < fields.size(); i++) {
                assertThat(fields.get(i).startByte())
                        .as("%s starts after %s ends", fields.get(i).label(), fields.get(i - 1).label())
                        .isGreaterThan(fields.get(i - 1).endByte());
            }
        }
    }

    @Test
    void addressLinesSitInsideTheBlockTheyBelongTo() {
        List<FeedField> lines = FeedLayout.fieldsIn(FeedLayout.PRODUCER_HOME_ADDRESS);

        assertThat(lines).isNotEmpty().allSatisfy(line -> {
            assertThat(line.startByte())
                    .isGreaterThanOrEqualTo(FeedLayout.PRODUCER_HOME_ADDRESS.startByte());
            assertThat(line.endByte())
                    .isLessThanOrEqualTo(FeedLayout.PRODUCER_HOME_ADDRESS.endByte());
        });
    }

    private static void assertBlockCoversItsLines(FeedField block) {
        List<FeedField> lines = FeedLayout.fieldsIn(block);

        assertThat(lines).as("%s has lines", block.label()).isNotEmpty();
        assertThat(lines.get(0).startByte()).isEqualTo(block.startByte());
        assertThat(lines.get(lines.size() - 1).endByte()).isEqualTo(block.endByte());
        // ...and the lines are contiguous, so the block has no byte belonging to nothing.
        for (int i = 1; i < lines.size(); i++) {
            assertThat(lines.get(i).startByte()).isEqualTo(lines.get(i - 1).endByte() + 1);
        }
    }
}
