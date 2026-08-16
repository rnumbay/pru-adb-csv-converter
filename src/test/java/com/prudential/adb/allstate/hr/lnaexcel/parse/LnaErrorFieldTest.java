package com.prudential.adb.allstate.hr.lnaexcel.parse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class LnaErrorFieldTest {

    /**
     * The delimiter offsets of a real generated {@code lnaerror-20260815.dat} - every one of its
     * 257 records carries its tildes at exactly these positions. This is the check that ties the
     * layout enum to the file it exists to read: if a field width here ever drifts from what
     * {@code FileIngestionOutputWriter} emits, this list stops matching.
     */
    private static final List<Integer> DELIMITER_OFFSETS_IN_A_REAL_FILE =
            List.of(25, 38, 89, 100, 151, 157, 167, 169, 173, 184, 190, 316);

    @Test
    void delimitersLandWhereTheGeneratedFilePutsThem() {
        List<Integer> declared = Arrays.stream(LnaErrorField.values())
                .map(LnaErrorField::delimiterOffset)
                .toList();

        assertThat(declared).isEqualTo(DELIMITER_OFFSETS_IN_A_REAL_FILE);
    }

    @Test
    void fieldsAndDelimitersFitInsideTheRecordLength() {
        LnaErrorField last = LnaErrorField.values()[LnaErrorField.values().length - 1];

        // ...the last delimiter, plus the trailing empty 13th field, plus filler to the LRECL.
        assertThat(last.delimiterOffset()).isLessThan(LnaErrorField.RECORD_LENGTH);
    }

    @Test
    void everyFieldHasAColumnHeading() {
        assertThat(Arrays.stream(LnaErrorField.values()).map(LnaErrorField::columnHeading))
                .doesNotContainNull()
                .allSatisfy(heading -> assertThat(heading).isNotBlank());
    }
}
