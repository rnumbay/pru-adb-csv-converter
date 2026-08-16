package com.prudential.adb.allstate.hr.lnaexcel.parse;

/**
 * The {@code LNAERROR} record layout - the single source of truth this app parses <em>and</em>
 * builds its spreadsheet columns from, so the two can never drift apart.
 *
 * <p>Offsets and widths are taken from the writer that produces the file
 * ({@code hr-producer-sync}'s {@code FileIngestionOutputWriter.writeLnaValidationErrorLog}, whose
 * own source is hr-modernization-spec/05-data-dictionary/file-layouts-hr1.md) and were confirmed
 * against a real generated {@code lnaerror-*.dat}: its tildes sit at exactly the offsets the
 * {@link #delimiterOffset()} of each field predicts.
 *
 * <pre>
 *   field                       offset  width      ends
 *   record type                      0     25        25  ~
 *   record position                 26     12        38  ~   zoned numeric, zero-padded
 *   contra header firm name         39     50        89  ~
 *   contra header BD Allstate id    90     10       100  ~
 *   distribution channel           101     50       151  ~
 *   ADB org code                   152      5       157  ~
 *   SSN / TIN                      158      9       167  ~   zoned numeric, zero-padded
 *   person/firm indicator          168      1       169  ~
 *   entity type                    170      3       173  ~
 *   Allstate id                    174     10       184  ~
 *   error code                     185      5       190  ~
 *   error description              191    125       316  ~   trailing blank 13th field follows
 *                                                              then filler to LRECL 325
 * </pre>
 */
public enum LnaErrorField {

    RECORD_TYPE("Record Type", 0, 25, Kind.TEXT),
    RECORD_POSITION("Record Position", 26, 12, Kind.NUMBER),
    CONTRA_HEADER_FIRM_NAME("Contra Header Firm Name", 39, 50, Kind.TEXT),
    CONTRA_HEADER_BD_ALLSTATE_ID("Contra Header BD Allstate ID", 90, 10, Kind.TEXT),
    CONTRA_HEADER_DISTRIBUTION_CHANNEL("Distribution Channel", 101, 50, Kind.TEXT),
    ADB_ORG_CODE("ADB Org Code", 152, 5, Kind.TEXT),
    SSN_OR_TIN("SSN / TIN", 158, 9, Kind.TAX_ID),
    PERSON_FIRM_INDICATOR("Person/Firm", 168, 1, Kind.TEXT),
    ENTITY_TYPE("Entity Type", 170, 3, Kind.TEXT),
    ALLSTATE_ID("Allstate ID", 174, 10, Kind.TEXT),
    ERROR_CODE("Error Code", 185, 5, Kind.TEXT),
    ERROR_DESCRIPTION("Error Description", 191, 125, Kind.TEXT);

    /** How a field's value is rendered in the workbook. */
    public enum Kind {
        /** A character field - written as a string cell. */
        TEXT,
        /** A zoned-numeric field - written as a number cell when it parses, text when it does not. */
        NUMBER,
        /**
         * A tax identifier - {@link Kind#TEXT}, but subject to masking and to the all-zeros rule.
         * Legacy renders an absent tax id as nine zeros rather than blanks (the writer's
         * {@code numeric()} zero-pads), so an all-zero value means "not present", not "id 0".
         */
        TAX_ID
    }

    /** Delimiter between every pair of fields, and after the last one. */
    public static final char DELIMITER = '~';

    /** {@code LRECL} of an {@code LNAERROR} record - fields, delimiters and trailing filler. */
    public static final int RECORD_LENGTH = 325;

    private final String columnHeading;
    private final int offset;
    private final int width;
    private final Kind kind;

    LnaErrorField(String columnHeading, int offset, int width, Kind kind) {
        this.columnHeading = columnHeading;
        this.offset = offset;
        this.width = width;
        this.kind = kind;
    }

    /**
     * Fails the class at load time if the declared offsets stop being contiguous - each field must
     * start one byte (the delimiter) after the previous one ends, and the last delimiter must leave
     * room inside the {@code LRECL}. The offsets above are written out literally rather than
     * derived, so they read as the layout table they came from; this is what keeps that literal
     * form honest.
     */
    static {
        LnaErrorField[] fields = values();
        if (fields[0].offset != 0) {
            throw new IllegalStateException("First LNAERROR field must start at offset 0");
        }
        for (int i = 1; i < fields.length; i++) {
            int expected = fields[i - 1].offset + fields[i - 1].width + 1;
            if (fields[i].offset != expected) {
                throw new IllegalStateException(
                        "LNAERROR field " + fields[i] + " declares offset " + fields[i].offset
                                + " but follows " + fields[i - 1] + " which ends at " + expected);
            }
        }
        LnaErrorField last = fields[fields.length - 1];
        if (last.delimiterOffset() >= RECORD_LENGTH) {
            throw new IllegalStateException(
                    "LNAERROR fields overrun the " + RECORD_LENGTH + "-byte LRECL");
        }
    }

    /** Column heading for this field in the generated workbook. */
    public String columnHeading() {
        return columnHeading;
    }

    /** 0-based offset of the field's first character within the record. */
    public int offset() {
        return offset;
    }

    /** Declared character width, excluding the delimiter that follows it. */
    public int width() {
        return width;
    }

    public Kind kind() {
        return kind;
    }

    /** 0-based offset of the {@code ~} that terminates this field. */
    public int delimiterOffset() {
        return offset + width;
    }

    /**
     * Extracts and trims this field from {@code record}, which is assumed to be at least
     * {@link #delimiterOffset()} characters long - {@link LnaErrorFileParser} pads short records
     * before calling this rather than making every field handle a truncated line.
     */
    String extract(String record) {
        return record.substring(offset, offset + width).trim();
    }
}
