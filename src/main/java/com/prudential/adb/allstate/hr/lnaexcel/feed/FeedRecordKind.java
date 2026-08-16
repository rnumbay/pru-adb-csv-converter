package com.prudential.adb.allstate.hr.lnaexcel.feed;

/**
 * The four inbound LNA feed record types an {@code LNAERROR} row can be reported against, with the
 * record-type character the feed carries in byte 1.
 *
 * <h2>This is not the {@code LNAERROR} "Record Type" column</h2>
 *
 * That column is a <em>label</em>, and it does not name the record's own type: a broker-dealer's
 * own firm ({@code C}) record is labelled {@code CONTRA HEADER}, because legacy treats a BD's
 * identity as belonging to the header that introduces it, and both {@code D01} and {@code D02}
 * records are labelled {@code PRODUCER ENTITY}
 * ({@code AllstateFeedIngestionServiceImpl#recordTypeLabel}). A {@code B0607} row therefore says
 * {@code CONTRA HEADER} while its bytes are the <em>firm</em> record's.
 *
 * <p>That is why {@link ErrorCodeFieldIndex} keys on the error code rather than on the label - the
 * code names one rule on one record type, and the label does not.
 */
public enum FeedRecordKind {

    /**
     * {@code A} - the file's own interface control header, {@code HeaderRecord}. No error code
     * names it: a header that fails validation aborts the run before any record is processed, so
     * nothing about it ever reaches {@code LNAERROR}. It is here because the feed has one, and a
     * sheet that lists every record has a row for it.
     */
    FILE_HEADER("File header (A)", "A"),

    /** {@code B} - contra/BD header, {@code WS-CONTRA-HEADER}. Opens a bundle. */
    CONTRA_HEADER("Contra header (B)", "B"),

    /** {@code C} - firm entity, {@code WS-FIRM-REC}. BD, LLE and HA records all use this layout. */
    FIRM_ENTITY("Firm entity (C)", "C"),

    /** {@code D01} - producer entity, {@code WS-PROD-REC}. The person. */
    PRODUCER_ENTITY("Producer entity (D01)", "D01"),

    /** {@code D02} - producer profile, {@code WS-PROFILE-REC}. The appointment and its linkage. */
    PRODUCER_PROFILE("Producer profile (D02)", "D02");

    private final String label;
    private final String typeCode;

    FeedRecordKind(String label, String typeCode) {
        this.label = label;
        this.typeCode = typeCode;
    }

    /** How this record type is named in the workbook. */
    public String label() {
        return label;
    }

    /**
     * The bare type code the feed carries and the test-records catalog's "Type" column uses -
     * {@code B}, {@code C}, {@code D01}, {@code D02}. Kept separate from {@link #label()} so a row
     * here can be lined up against a catalog row without anyone having to translate.
     */
    public String typeCode() {
        return typeCode;
    }
}
