package com.prudential.adb.allstate.hr.lnaexcel.feed;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which feed field each {@code ERRORCD} code rejects - the table that turns "{@code C0707}" into
 * "firm record, business address, bytes 115-306".
 *
 * <h2>Keyed on the code, not on the report's record-type label</h2>
 *
 * The code names one rule on one record type. The {@code LNAERROR} "Record Type" column does not:
 * it labels a broker-dealer's own firm record {@code CONTRA HEADER}, so a {@code B0607} row reads
 * {@code CONTRA HEADER} while its bytes are the firm record's 115-306. See {@link FeedRecordKind}.
 *
 * <h2>Where each mapping comes from</h2>
 *
 * Each entry pairs the code with the field the validator actually raises it against - read from
 * {@code FeedRecordValidator} and {@code BundleStructureValidator} in {@code pru-adb-poc}, not
 * inferred from the {@code ERRORCD} description. The two do not always agree: {@code C0707} says
 * "BUSINESS ADDRESS" and is raised against the field the decoder calls {@code commissionAddress}.
 *
 * <h2>Codes with no field</h2>
 *
 * Some codes reject a <em>record</em> or a <em>bundle</em> rather than a field - a missing
 * {@code D02}, a second active BD, a parent that does not exist in ADB. Those carry a note instead
 * of a range. Inventing a byte range for them would be worse than leaving it blank: someone would
 * go and read those bytes and find nothing wrong with them.
 */
public final class ErrorCodeFieldIndex {

    private static final String NOT_ONE_FIELD = "Not a field rule - ";

    private static final Map<String, ErrorCodeTarget> TARGETS = buildIndex();

    private ErrorCodeFieldIndex() {}

    /** The target for {@code errorCode}, or empty for a code this table does not know. */
    public static Optional<ErrorCodeTarget> find(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(TARGETS.get(errorCode.trim().toUpperCase()));
    }

    /** Every code that rejects {@code field}, for the layout sheet's own cross-reference. */
    public static List<String> codesFor(FeedField field) {
        return TARGETS.values().stream()
                .filter(target -> field.equals(target.field()))
                .map(ErrorCodeTarget::errorCode)
                .sorted()
                .toList();
    }

    /** Every mapping, code order - the whole table, for tests and for callers that want it all. */
    public static List<ErrorCodeTarget> all() {
        return TARGETS.values().stream().toList();
    }

    private static Map<String, ErrorCodeTarget> buildIndex() {
        Map<String, ErrorCodeTarget> index = new LinkedHashMap<>();

        // ---- Contra header (B) ------------------------------------------------------------
        put(index, "B0100", FeedLayout.CH_FIRM_NAME, null);
        put(index, "B0200", FeedLayout.CH_DISTRIBUTION_CHANNEL, null);
        put(index, "B0300", FeedLayout.CH_BD_ALLSTATE_ID, null);
        // No record type either: the bundle's failure is only detectable once the bundle CLOSES, so
        // the row's record position is whatever record closed it - the next contra header, or the
        // file trailer - and not the bundle's own header. The Day 1 corpus has it both ways (three
        // rows on a following B header, one on the Z trailer). The row's bundle-context columns
        // still name the bundle at fault, which is the bundle BEFORE the record at that position.
        structural(index, "B0400", null,
                NOT_ONE_FIELD + "the bundle carried no firm record for its BD at all. Reported at "
                        + "the record that closed the bundle, so this row's position is not the "
                        + "bundle's own header - its bundle columns are");
        put(index, "B0500", FeedLayout.FIRM_ENTITY_TYPE,
                "Read off the bundle's FIRST firm record, which is the one this rule is about");
        put(index, "B0700", FeedLayout.CH_BD_ALLSTATE_ID,
                "The id itself is well formed - it already exists in ADB as a BD");

        // ---- Firm entity (C), BD's own record: B06xx --------------------------------------
        // Same bytes as the C07xx codes below: one layout, two code ranges. Which one is written
        // depends on whether the record is the bundle's first firm record, not on its entity type.
        put(index, "B0601", FeedLayout.FIRM_TAX_ID, null);
        put(index, "B0602", FeedLayout.FIRM_NAME, null);
        put(index, "B0604", FeedLayout.FIRM_OWN_ALLSTATE_ID,
                "Blank, or disagreeing with the contra header's BD id (54-63)");
        put(index, "B0605", FeedLayout.FIRM_TYPE_CODE, null);
        put(index, "B0606", FeedLayout.FIRM_RESIDENT_STATE, null);
        put(index, "B0607", FeedLayout.FIRM_BUSINESS_ADDRESS, null);
        put(index, "B0608", FeedLayout.FIRM_CORRESPONDENCE_ADDRESS, null);
        put(index, "B0609", FeedLayout.FIRM_PROFILE_STATUS, null);
        put(index, "B0610", FeedLayout.FIRM_PROFILE_EFFECTIVE_DATE, null);
        put(index, "B0611", FeedLayout.FIRM_PROFILE_TERMINATION_DATE, null);
        put(index, "B0612", FeedLayout.FIRM_PARENT_FIRM_ALLSTATE_ID,
                "On a BD record this one code covers the firm's own id (88-97) as well; "
                        + "LLE/HA records split the two into C0704 and C0712");
        put(index, "B0613", FeedLayout.FIRM_PARENT_BD_ALLSTATE_ID, null);

        // ---- Firm entity (C), LLE/HA record: C07xx ----------------------------------------
        put(index, "C0701", FeedLayout.FIRM_TAX_ID, null);
        put(index, "C0702", FeedLayout.FIRM_NAME, null);
        put(index, "C0703", FeedLayout.FIRM_ABBREVIATED_NAME,
                "In the ERRORCD catalog, but no HR1 rule produces it today");
        put(index, "C0704", FeedLayout.FIRM_OWN_ALLSTATE_ID,
                "The firm's OWN id - the parent firm reference is C0712, at 526-535");
        put(index, "C0705", FeedLayout.FIRM_TYPE_CODE, null);
        put(index, "C0706", FeedLayout.FIRM_RESIDENT_STATE, null);
        put(index, "C0707", FeedLayout.FIRM_BUSINESS_ADDRESS, null);
        put(index, "C0708", FeedLayout.FIRM_CORRESPONDENCE_ADDRESS, null);
        put(index, "C0709", FeedLayout.FIRM_PROFILE_STATUS, null);
        put(index, "C0710", FeedLayout.FIRM_PROFILE_EFFECTIVE_DATE, null);
        put(index, "C0711", FeedLayout.FIRM_PROFILE_TERMINATION_DATE, null);
        put(index, "C0712", FeedLayout.FIRM_PARENT_FIRM_ALLSTATE_ID,
                "The PARENT firm reference - the record's own id is C0704, at 88-97");
        put(index, "C0713", FeedLayout.FIRM_PARENT_BD_ALLSTATE_ID, null);
        structural(index, "C0714", FeedRecordKind.FIRM_ENTITY,
                NOT_ONE_FIELD + "a second active BD in one bundle; entity type (98-100) and "
                        + "profile status (499) are read across records, not judged on this one");
        structural(index, "C0715", FeedRecordKind.FIRM_ENTITY,
                NOT_ONE_FIELD + "an active LLE/HA under a terminated BD. A warning: the record is "
                        + "still processed");
        put(index, "C0716", FeedLayout.FIRM_ENTITY_TYPE, null);
        structural(index, "C0717", FeedRecordKind.FIRM_ENTITY,
                NOT_ONE_FIELD + "the functional manager (BD or LLE) was not found in ADB. A lookup "
                        + "failure, not a malformed field");

        // ---- Producer entity (D01) --------------------------------------------------------
        put(index, "D0101", FeedLayout.PRODUCER_SSN, null);
        put(index, "D0102", FeedLayout.PRODUCER_FIRST_NAME, null);
        put(index, "D0103", FeedLayout.PRODUCER_LAST_NAME, null);
        put(index, "D0104", FeedLayout.PRODUCER_DATE_OF_BIRTH,
                "Unparseable, or a producer under 16 at the cycle date");
        put(index, "D0105", FeedLayout.PRODUCER_SEX_CODE, null);
        put(index, "D0106", FeedLayout.PRODUCER_HOME_ADDRESS, null);
        put(index, "D0107", FeedLayout.PRODUCER_DESIGNATIONS,
                "Seven CHAR(5) slots; any one of them can be the unknown code");
        structural(index, "E0100", FeedRecordKind.PRODUCER_ENTITY,
                NOT_ONE_FIELD + "no producer profile (D02) record followed this D01");

        // ---- Producer profile (D02) -------------------------------------------------------
        put(index, "F0101", FeedLayout.PROFILE_SSN,
                "Compared against the D01's own SSN, also at 4-12 of that record");
        put(index, "F0102", FeedLayout.PROFILE_TYPE, null);
        put(index, "F0103", FeedLayout.PROFILE_ALLSTATE_ID, null);
        put(index, "F0104", FeedLayout.PROFILE_BUSINESS_ADDRESS, null);
        put(index, "F0105", FeedLayout.PROFILE_RESIDENT_STATE, null);
        put(index, "F0106", FeedLayout.PROFILE_PRODUCER_ROLE, null);
        put(index, "F0107", FeedLayout.PROFILE_PARENT_FIRM_ALLSTATE_ID,
                "The firm the producer is appointed under - the BD it hangs off is F0108, at 251-260");
        put(index, "F0108", FeedLayout.PROFILE_PARENT_BD_ALLSTATE_ID,
                "Blank, or disagreeing with the bundle's BD");
        structural(index, "F0109", FeedRecordKind.PRODUCER_PROFILE,
                NOT_ONE_FIELD + "an active appointment under a terminated BD. A warning: the record "
                        + "is still processed");
        put(index, "F0110", FeedLayout.PROFILE_RELATIONSHIP_STATUS, null);
        put(index, "F0111", FeedLayout.PROFILE_RELATIONSHIP_START_DATE, null);
        put(index, "F0112", FeedLayout.PROFILE_RELATIONSHIP_END_DATE, null);
        structural(index, "F0113", FeedRecordKind.PRODUCER_PROFILE,
                NOT_ONE_FIELD + "the functional manager (BD or LLE) was not found in ADB. A lookup "
                        + "failure, not a malformed field");

        // ---- ADB processing errors, per calling subprogram --------------------------------
        // None of these is a feed validation, so none names a field. One of the four does sit on a
        // knowable record type, because the failing subprogram only runs from one kind of record:
        // profile creation runs from the producer profile, and the Day 1 corpus agrees - all 33 of
        // its P2721 rows land on records the test catalog types D02.
        //
        // O2711 (org-code creation) is presumably the firm record by the same reasoning, but no row
        // in the corpus exercises it, so it is left unasserted rather than guessed. The two
        // driver-level codes can attach to any record at all.
        processing(index, "M2701", null, "UTP27001, the Allstate LNA driver");
        processing(index, "O2711", null, "UTP27101, org-code creation");
        processing(index, "P2721", FeedRecordKind.PRODUCER_PROFILE, "UTP27201, profile creation");
        processing(index, "D2731", null, "UTP27301, the daily driver");

        // Unmodifiable rather than Map.copyOf: copyOf gives no iteration order, and all() is worth
        // more in code order than in whatever order hashing happens to produce.
        return Collections.unmodifiableMap(index);
    }

    private static void put(
            Map<String, ErrorCodeTarget> index, String code, FeedField field, String note) {
        index.put(code, new ErrorCodeTarget(code, field.kind(), field, note));
    }

    private static void structural(
            Map<String, ErrorCodeTarget> index, String code, FeedRecordKind kind, String note) {
        index.put(code, new ErrorCodeTarget(code, kind, null, note));
    }

    private static void processing(
            Map<String, ErrorCodeTarget> index, String code, FeedRecordKind kind, String program) {
        index.put(code, new ErrorCodeTarget(code, kind, null,
                "ADB processing error in " + program + " - not a feed field"));
    }
}
