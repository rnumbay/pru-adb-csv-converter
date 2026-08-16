package com.prudential.adb.allstate.hr.lnaexcel.testsupport;

import java.util.EnumMap;
import java.util.Map;

import com.prudential.adb.allstate.hr.lnaexcel.parse.LnaErrorField;

/**
 * Builds {@code LNAERROR} records the way {@code hr-producer-sync}'s
 * {@code FileIngestionOutputWriter} writes them: each field padded to its declared width,
 * tilde-delimited, one trailing empty field, the whole record padded to the 325-byte {@code LRECL}.
 *
 * <p>Rendering the record here rather than checking in a copy of a real {@code lnaerror-*.dat}
 * keeps every test's input synthetic - the real artifact carries firm names and tax identifiers,
 * and a fixture is not the place for either. Values below are visibly invented for that reason.
 */
public final class LnaErrorRecordFixture {

    private final Map<LnaErrorField, String> values = new EnumMap<>(LnaErrorField.class);

    private LnaErrorRecordFixture() {}

    /** A record with every field populated - the "nothing unusual here" baseline. */
    public static LnaErrorRecordFixture populated() {
        return new LnaErrorRecordFixture()
                .with(LnaErrorField.RECORD_TYPE, "FIRM ENTITY")
                .with(LnaErrorField.RECORD_POSITION, "103")
                .with(LnaErrorField.CONTRA_HEADER_FIRM_NAME, "FIXTURE FIRM ONE")
                .with(LnaErrorField.CONTRA_HEADER_BD_ALLSTATE_ID, "TESTBD0001")
                .with(LnaErrorField.CONTRA_HEADER_DISTRIBUTION_CHANNEL, "10")
                .with(LnaErrorField.ADB_ORG_CODE, "0A1")
                .with(LnaErrorField.SSN_OR_TIN, "123456789")
                .with(LnaErrorField.PERSON_FIRM_INDICATOR, "F")
                .with(LnaErrorField.ENTITY_TYPE, "LLE")
                .with(LnaErrorField.ALLSTATE_ID, "0012345678")
                .with(LnaErrorField.ERROR_CODE, "C0701")
                .with(LnaErrorField.ERROR_DESCRIPTION, "\"BUSINESS ADDRESS\" NOT VALUED - RECORD REJECTED");
    }

    /** A record with every field empty - what a standalone record with no bundle context looks like. */
    public static LnaErrorRecordFixture empty() {
        return new LnaErrorRecordFixture();
    }

    public LnaErrorRecordFixture with(LnaErrorField field, String value) {
        values.put(field, value);
        return this;
    }

    /** Renders the 325-character record. */
    public String render() {
        StringBuilder record = new StringBuilder();
        for (LnaErrorField field : LnaErrorField.values()) {
            record.append(format(field)).append(LnaErrorField.DELIMITER);
        }
        return pad(record.toString(), LnaErrorField.RECORD_LENGTH);
    }

    private String format(LnaErrorField field) {
        String value = values.getOrDefault(field, "");
        return switch (field.kind()) {
            // Zoned numeric: right-justified, zero-padded - FixedWidthFields.numeric's rule.
            case NUMBER, TAX_ID -> {
                String digits = value.replaceAll("\\D", "");
                yield digits.length() >= field.width()
                        ? digits.substring(digits.length() - field.width())
                        : "0".repeat(field.width() - digits.length()) + digits;
            }
            case TEXT -> pad(value, field.width());
        };
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value.substring(0, width)
                : value + " ".repeat(width - value.length());
    }
}
