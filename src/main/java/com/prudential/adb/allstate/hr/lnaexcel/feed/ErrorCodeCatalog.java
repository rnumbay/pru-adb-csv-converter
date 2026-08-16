package com.prudential.adb.allstate.hr.lnaexcel.feed;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The short meaning and the disposition of each {@code ERRORCD} code - what the rule is about in a
 * handful of words, and what happens to the record and its bundle when it fires.
 *
 * <h2>Provenance</h2>
 *
 * Transcribed from the <b>Error Codes</b> sheet of {@code ADB Allstate LNA Test Records Catalog.xlsx},
 * the test-records catalog the parallel-run team writes its expectations against. That is
 * deliberately a different source from {@link ErrorCodeFieldIndex} (read out of the validator code)
 * and from the {@code error_code} table (the {@code ERRORCD} extract, whose full 125-character
 * descriptions {@code LNAERROR} already carries).
 *
 * <p>Keeping all three does not duplicate one fact three ways - they answer different questions.
 * The description says what failed in the report's own words, the meaning says it in five, and the
 * disposition says what it cost: "Firm record rejected - bundle continues" and "First firm record
 * fails - whole bundle rejected" are the same field failing on two different records, and the
 * difference between them is 96 other records.
 *
 * <h2>Codes the catalog does not list</h2>
 *
 * The catalog covers the 55 codes its own test corpus exercises. {@code C0703} (no HR1 rule
 * produces it) and the four {@code ADB} processing codes are not among them; they are carried here
 * with a disposition and no borrowed meaning, rather than left to look like an oversight.
 */
public final class ErrorCodeCatalog {

    /** What a code costs when it fires. */
    public enum Severity {
        /** The record - or its whole bundle - is rejected. */
        ERROR("Error"),
        /** Reported, but the record still applies. Only {@code C0715} and {@code F0109}. */
        WARNING("Warning");

        private final String label;

        Severity(String label) {
            this.label = label;
        }

        /** The catalog's own vocabulary for this severity, so the two reports read alike. */
        public String label() {
            return label;
        }
    }

    /**
     * Whether a code is raised about a bundle or about one record.
     *
     * <p>Load-bearing beyond documentation: a {@link #BUNDLE} code proves the row came from inside
     * a bundle even when every bundle-context column on it is blank - which is exactly what
     * {@code B0100}/{@code B0200}/{@code B0300} mean (the contra header's own name, channel and id
     * are the things that are missing). Without it, a bundle whose header is entirely blank is
     * indistinguishable from a standalone record that never had a bundle.
     */
    public enum Scope {
        /** The rule is about the bundle - the {@code B01xx}-{@code B07xx} family. */
        BUNDLE,
        /** The rule is about this one record. */
        RECORD,
        /**
         * The failure is in the run, not in the record - the four {@code ADB} processing codes. The
         * record that carries one is often perfectly well formed; a subprogram failed while applying
         * it. Worth its own scope because a description of such a record should describe the record,
         * not call it malformed.
         */
        RUN
    }

    private static final String BUNDLE_AT_HEADER = "Bundle rejected at contra header";
    private static final String BUNDLE_AT_FIRST_FIRM = "First firm record fails - whole bundle rejected";
    private static final String FIRM_RECORD = "Firm record rejected - bundle continues";
    private static final String PRODUCER = "Producer rejected - its D02 skipped (reason 002)";
    private static final String APPOINTMENT = "Producer profile (appointment) rejected";
    private static final String WARNING_PROCESSED = "Warning - record still processed";
    private static final String PROCESSING = "ADB processing failure - not a record validation";

    private static final Map<String, ErrorCodeMeaning> MEANINGS = build();

    private ErrorCodeCatalog() {}

    /** The catalog entry for {@code errorCode}, or empty for a code it does not list. */
    public static Optional<ErrorCodeMeaning> find(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(MEANINGS.get(errorCode.trim().toUpperCase()));
    }

    private static Map<String, ErrorCodeMeaning> build() {
        Map<String, ErrorCodeMeaning> catalog = new LinkedHashMap<>();

        put(catalog, "B0100", "blank firm name", BUNDLE_AT_HEADER);
        put(catalog, "B0200", "dist channel not in PRU PROFILE A00001 list", BUNDLE_AT_HEADER);
        put(catalog, "B0300", "blank BD Allstate id", BUNDLE_AT_HEADER);
        put(catalog, "B0400", "bundle without BD firm record", BUNDLE_AT_HEADER);
        put(catalog, "B0500", "first C entity is not BD", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0601", "invalid/dummy TIN", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0602", "blank firm name", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0604", "Allstate id <> contra header BD id", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0605", "blank/unknown firm type", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0606", "invalid residence state", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0607", "invalid business/commission address block", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0608", "invalid correspondence address block", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0609", "invalid profile status", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0610", "missing/invalid profile start date", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0611", "invalid profile term date for status", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0612", "blank firm Allstate id", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0613", "BD Allstate id <> contra header / not in DB", BUNDLE_AT_FIRST_FIRM);
        put(catalog, "B0700", "duplicate BD id in UTT_ALL_CNTR", BUNDLE_AT_HEADER);

        put(catalog, "C0701", "invalid/dummy TIN", FIRM_RECORD);
        put(catalog, "C0702", "blank firm name", FIRM_RECORD);
        // C0703 is in the ERRORCD table but not in the catalog's corpus - no HR1 rule produces it.
        put(catalog, "C0703", null, FIRM_RECORD);
        put(catalog, "C0704", "blank Allstate id", FIRM_RECORD);
        put(catalog, "C0705", "blank/unknown firm type", FIRM_RECORD);
        put(catalog, "C0706", "invalid residence state", FIRM_RECORD);
        put(catalog, "C0707", "invalid business/commission address block", FIRM_RECORD);
        put(catalog, "C0708", "invalid correspondence address block (BD)", FIRM_RECORD);
        put(catalog, "C0709", "invalid profile status", FIRM_RECORD);
        put(catalog, "C0710", "missing/invalid profile start date", FIRM_RECORD);
        put(catalog, "C0711", "invalid profile term date for status", FIRM_RECORD);
        put(catalog, "C0712", "blank firm Allstate id", FIRM_RECORD);
        put(catalog, "C0713", "BD Allstate id <> bundle BD / not in DB", FIRM_RECORD);
        put(catalog, "C0714", "duplicate active BD in bundle", FIRM_RECORD);
        warning(catalog, "C0715", "active LLE/HA under non-active BD");
        put(catalog, "C0716", "entity type not BD/LLE/HA", FIRM_RECORD);
        put(catalog, "C0717", "HA firm id has no BD-LLE relationship", FIRM_RECORD);

        put(catalog, "D0101", "invalid/dummy SSN", PRODUCER);
        put(catalog, "D0102", "blank first name", PRODUCER);
        put(catalog, "D0103", "blank last name", PRODUCER);
        put(catalog, "D0104", "invalid DOB or under 16 years", PRODUCER);
        put(catalog, "D0105", "invalid sex code", PRODUCER);
        put(catalog, "D0106", "invalid home address block", PRODUCER);
        put(catalog, "D0107", "designation not in UTT_ALL_DSGN", PRODUCER);
        put(catalog, "E0100", "D01 never followed by its D02",
                "Producer rejected - profile record missing");

        put(catalog, "F0101", "SSN <> preceding D01", APPOINTMENT);
        put(catalog, "F0102", "profile type not C/H", APPOINTMENT);
        put(catalog, "F0103", "blank producer Allstate id", APPOINTMENT);
        put(catalog, "F0104", "invalid business address block", APPOINTMENT);
        put(catalog, "F0105", "invalid residence state", APPOINTMENT);
        put(catalog, "F0106", "producer role populated (must be spaces)", APPOINTMENT);
        put(catalog, "F0107", "blank firm Allstate id", APPOINTMENT);
        put(catalog, "F0108", "BD Allstate id <> bundle BD / not in DB", APPOINTMENT);
        warning(catalog, "F0109", "active appointment under non-active BD");
        put(catalog, "F0110", "invalid relationship status", APPOINTMENT);
        put(catalog, "F0111", "missing/invalid relationship start date", APPOINTMENT);
        put(catalog, "F0112", "invalid relationship end date for status", APPOINTMENT);
        put(catalog, "F0113", "firm id has no BD-LLE relationship in DB", APPOINTMENT);

        put(catalog, "M2701", null, PROCESSING);
        put(catalog, "O2711", null, PROCESSING);
        put(catalog, "P2721", null, PROCESSING);
        put(catalog, "D2731", null, PROCESSING);

        return Collections.unmodifiableMap(catalog);
    }

    /**
     * Scope comes from the disposition rather than being stated twice: the two bundle dispositions
     * are exactly the {@code B} family, and a code cannot be given one of them and then not be
     * about a bundle.
     */
    private static void put(
            Map<String, ErrorCodeMeaning> catalog, String code, String meaning, String disposition) {
        Scope scope = Scope.RECORD;
        if (disposition.equals(BUNDLE_AT_HEADER) || disposition.equals(BUNDLE_AT_FIRST_FIRM)) {
            scope = Scope.BUNDLE;
        } else if (disposition.equals(PROCESSING)) {
            scope = Scope.RUN;
        }
        catalog.put(code, new ErrorCodeMeaning(code, meaning, disposition, Severity.ERROR, scope));
    }

    private static void warning(Map<String, ErrorCodeMeaning> catalog, String code, String meaning) {
        catalog.put(code,
                new ErrorCodeMeaning(code, meaning, WARNING_PROCESSED, Severity.WARNING, Scope.RECORD));
    }

    /**
     * @param errorCode   the five-character {@code ERRORCD} code
     * @param meaning     the rule in a few words, {@code null} for a code the catalog does not gloss
     * @param disposition what happens to the record and its bundle when the code fires
     * @param severity    whether the record is rejected or merely reported
     * @param scope       whether the rule is about the bundle or about one record
     */
    public record ErrorCodeMeaning(
            String errorCode, String meaning, String disposition, Severity severity, Scope scope) {

        /** The meaning, or {@code ""} - callers writing a cell want a string either way. */
        public String meaningOrBlank() {
            return meaning == null ? "" : meaning;
        }
    }
}
