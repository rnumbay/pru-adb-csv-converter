package com.prudential.adb.allstate.hr.lnaexcel.feed;

import java.util.List;

/**
 * The inbound Allstate LNA feed record layouts, field by field, at their byte positions.
 *
 * <h2>Where these numbers come from</h2>
 *
 * Every range below is transcribed from the code that actually decodes the feed in
 * {@code pru-adb-poc} - {@code FeedRecordMapper.toFirmProfile} / {@code toProducerProfile},
 * {@code Bundle.ContraHeader.from}, and the {@code BundleStructureValidator} constants - which in
 * turn follow hr-modernization-spec/05-data-dictionary/file-layouts-hr1.md. They are not inferred
 * from field names or reconstructed from the error text.
 *
 * <p>Names here are the operator-facing ones, which are not always the decoder's Java names. The
 * clearest case is the firm record's first address block at 115-306: the mapper calls it
 * {@code commissionAddress}, every {@code ERRORCD} description calls it
 * <strong>BUSINESS ADDRESS</strong>, and the same block is a commission address for an LLE and a
 * business address for a BD or HA. The error text is what someone reads off the report, so the
 * error text wins, with the alternative name recorded beside it.
 *
 * <h2>Address blocks</h2>
 *
 * An address is entered as a block <em>and</em> as its individual lines. No error code rejects "the
 * city" - {@code C0707} rejects the whole business address - so a code points at the block, while
 * the lines are what someone needs once they are looking at the record.
 */
public final class FeedLayout {

    /** {@code LRECL} of the inbound LNA feed file - {@code FeedRecord.RECORD_LENGTH}. */
    public static final int RECORD_LENGTH = 600;

    // ---- File header (A) - HeaderRecord ---------------------------------------------------
    /** {@code WS-COMPANY-NAME}, the interface control header's own company. */
    public static final FeedField HEADER_COMPANY_NAME =
            FeedField.of(FeedRecordKind.FILE_HEADER, "Company name", 2, 101);
    public static final FeedField HEADER_TRANSMISSION_DATE =
            FeedField.of(FeedRecordKind.FILE_HEADER, "Transmission date", 102, 109);
    public static final FeedField HEADER_RECORD_COUNT =
            FeedField.of(FeedRecordKind.FILE_HEADER, "Record count", 110, 121);

    // ---- Contra header (B) - Bundle.ContraHeader.from ------------------------------------
    public static final FeedField CH_FIRM_NAME =
            FeedField.of(FeedRecordKind.CONTRA_HEADER, "Allstate firm name", 2, 51);
    public static final FeedField CH_DISTRIBUTION_CHANNEL =
            FeedField.of(FeedRecordKind.CONTRA_HEADER, "Distribution channel code", 52, 53);
    public static final FeedField CH_BD_ALLSTATE_ID =
            FeedField.of(FeedRecordKind.CONTRA_HEADER, "Allstate BD id", 54, 63);

    // ---- Firm entity (C) - FeedRecordMapper.toFirmProfile --------------------------------
    private static final String BUSINESS_ADDRESS = "Business address";
    private static final String CORRESPONDENCE_ADDRESS = "Correspondence address";

    public static final FeedField FIRM_TAX_ID =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "Tax id", 2, 10);
    public static final FeedField FIRM_NAME =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "Firm name", 11, 60);
    public static final FeedField FIRM_ABBREVIATED_NAME =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "Abbreviated firm name", 61, 87);
    public static final FeedField FIRM_OWN_ALLSTATE_ID =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "Allstate id (the firm's own)", 88, 97);
    public static final FeedField FIRM_ENTITY_TYPE =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "Entity type", 98, 100);
    public static final FeedField FIRM_TYPE_CODE =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "Firm type code", 101, 102);
    public static final FeedField FIRM_CRD_NUMBER =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "CRD number", 103, 112);
    public static final FeedField FIRM_RESIDENT_STATE =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "Resident state", 113, 114);

    /**
     * The block {@code B0607}/{@code C0707} reject. Decoded as {@code commissionAddress} - see the
     * class Javadoc on why this carries the error text's name instead.
     */
    public static final FeedField FIRM_BUSINESS_ADDRESS = FeedField.of(
            FeedRecordKind.FIRM_ENTITY,
            BUSINESS_ADDRESS + " (LLE: commission address)", 115, 306);
    public static final FeedField FIRM_CORRESPONDENCE_ADDRESS =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, CORRESPONDENCE_ADDRESS, 307, 498);

    public static final FeedField FIRM_PROFILE_STATUS =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "Profile status", 499, 499);
    public static final FeedField FIRM_PROFILE_EFFECTIVE_DATE =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "Profile effective date", 500, 507);
    public static final FeedField FIRM_PROFILE_TERMINATION_DATE =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "Profile termination date", 508, 515);
    public static final FeedField FIRM_PARENT_BD_ALLSTATE_ID =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "Parent BD Allstate id", 516, 525);
    public static final FeedField FIRM_PARENT_FIRM_ALLSTATE_ID =
            FeedField.of(FeedRecordKind.FIRM_ENTITY, "Parent firm Allstate id", 526, 535);

    // ---- Producer entity (D01) - FeedRecordMapper.toProducerProfile ----------------------
    private static final String HOME_ADDRESS = "Home address";

    public static final FeedField PRODUCER_SSN =
            FeedField.of(FeedRecordKind.PRODUCER_ENTITY, "SSN", 4, 12);
    public static final FeedField PRODUCER_FIRST_NAME =
            FeedField.of(FeedRecordKind.PRODUCER_ENTITY, "First name", 13, 32);
    public static final FeedField PRODUCER_MIDDLE_NAME =
            FeedField.of(FeedRecordKind.PRODUCER_ENTITY, "Middle name", 33, 57);
    public static final FeedField PRODUCER_LAST_NAME =
            FeedField.of(FeedRecordKind.PRODUCER_ENTITY, "Last name", 58, 87);
    public static final FeedField PRODUCER_DATE_OF_BIRTH =
            FeedField.of(FeedRecordKind.PRODUCER_ENTITY, "Date of birth", 88, 95);
    public static final FeedField PRODUCER_SEX_CODE =
            FeedField.of(FeedRecordKind.PRODUCER_ENTITY, "Sex code", 96, 96);
    public static final FeedField PRODUCER_HOME_ADDRESS =
            FeedField.of(FeedRecordKind.PRODUCER_ENTITY, HOME_ADDRESS, 97, 328);
    public static final FeedField PRODUCER_CRD_NUMBER =
            FeedField.of(FeedRecordKind.PRODUCER_ENTITY, "CRD number", 329, 338);
    /** {@code WS-DESIGNATION1..7}, CHAR(5) each - seven slots, one field to the error report. */
    public static final FeedField PRODUCER_DESIGNATIONS =
            FeedField.of(FeedRecordKind.PRODUCER_ENTITY, "Designations (7 x 5)", 339, 373);

    // ---- Producer profile (D02) - FeedRecordMapper.toProducerProfile ---------------------
    public static final FeedField PROFILE_SSN =
            FeedField.of(FeedRecordKind.PRODUCER_PROFILE, "SSN", 4, 12);
    public static final FeedField PROFILE_TYPE =
            FeedField.of(FeedRecordKind.PRODUCER_PROFILE, "Profile type", 13, 13);
    public static final FeedField PROFILE_ALLSTATE_ID =
            FeedField.of(FeedRecordKind.PRODUCER_PROFILE, "Allstate id", 14, 23);
    public static final FeedField PROFILE_EXTERNAL_ID =
            FeedField.of(FeedRecordKind.PRODUCER_PROFILE, "External id", 24, 43);
    public static final FeedField PROFILE_BUSINESS_ADDRESS =
            FeedField.of(FeedRecordKind.PRODUCER_PROFILE, BUSINESS_ADDRESS, 44, 235);
    public static final FeedField PROFILE_RESIDENT_STATE =
            FeedField.of(FeedRecordKind.PRODUCER_PROFILE, "Resident state", 236, 237);
    public static final FeedField PROFILE_PRODUCER_ROLE =
            FeedField.of(FeedRecordKind.PRODUCER_PROFILE, "Producer role", 238, 240);
    public static final FeedField PROFILE_PARENT_FIRM_ALLSTATE_ID =
            FeedField.of(FeedRecordKind.PRODUCER_PROFILE, "Allstate id of the firm", 241, 250);
    public static final FeedField PROFILE_PARENT_BD_ALLSTATE_ID =
            FeedField.of(FeedRecordKind.PRODUCER_PROFILE, "Allstate id of the BD", 251, 260);
    public static final FeedField PROFILE_RELATIONSHIP_STATUS =
            FeedField.of(FeedRecordKind.PRODUCER_PROFILE, "Relationship status", 261, 261);
    public static final FeedField PROFILE_RELATIONSHIP_START_DATE =
            FeedField.of(FeedRecordKind.PRODUCER_PROFILE, "Relationship start date", 262, 269);
    public static final FeedField PROFILE_RELATIONSHIP_END_DATE =
            FeedField.of(FeedRecordKind.PRODUCER_PROFILE, "Relationship end date", 270, 277);

    /** Every field of every record type, in record order - what the layout sheet lists. */
    private static final List<FeedField> ALL_FIELDS = List.of(
            // File header (A)
            HEADER_COMPANY_NAME, HEADER_TRANSMISSION_DATE, HEADER_RECORD_COUNT,

            // Contra header (B)
            CH_FIRM_NAME, CH_DISTRIBUTION_CHANNEL, CH_BD_ALLSTATE_ID,

            // Firm entity (C)
            FIRM_TAX_ID, FIRM_NAME, FIRM_ABBREVIATED_NAME, FIRM_OWN_ALLSTATE_ID, FIRM_ENTITY_TYPE,
            FIRM_TYPE_CODE, FIRM_CRD_NUMBER, FIRM_RESIDENT_STATE,
            FIRM_BUSINESS_ADDRESS,
            addressLine(FeedRecordKind.FIRM_ENTITY, "Address line 1", BUSINESS_ADDRESS, 115, 154),
            addressLine(FeedRecordKind.FIRM_ENTITY, "Address line 2", BUSINESS_ADDRESS, 155, 194),
            addressLine(FeedRecordKind.FIRM_ENTITY, "City", BUSINESS_ADDRESS, 195, 214),
            addressLine(FeedRecordKind.FIRM_ENTITY, "State", BUSINESS_ADDRESS, 215, 216),
            addressLine(FeedRecordKind.FIRM_ENTITY, "ZIP code", BUSINESS_ADDRESS, 217, 226),
            addressLine(FeedRecordKind.FIRM_ENTITY, "Phone number", BUSINESS_ADDRESS, 227, 236),
            addressLine(FeedRecordKind.FIRM_ENTITY, "Fax number", BUSINESS_ADDRESS, 237, 246),
            addressLine(FeedRecordKind.FIRM_ENTITY, "Email id", BUSINESS_ADDRESS, 247, 306),
            FIRM_CORRESPONDENCE_ADDRESS,
            addressLine(FeedRecordKind.FIRM_ENTITY, "Address line 1", CORRESPONDENCE_ADDRESS, 307, 346),
            addressLine(FeedRecordKind.FIRM_ENTITY, "Address line 2", CORRESPONDENCE_ADDRESS, 347, 386),
            addressLine(FeedRecordKind.FIRM_ENTITY, "City", CORRESPONDENCE_ADDRESS, 387, 406),
            addressLine(FeedRecordKind.FIRM_ENTITY, "State", CORRESPONDENCE_ADDRESS, 407, 408),
            addressLine(FeedRecordKind.FIRM_ENTITY, "ZIP code", CORRESPONDENCE_ADDRESS, 409, 418),
            addressLine(FeedRecordKind.FIRM_ENTITY, "Phone number", CORRESPONDENCE_ADDRESS, 419, 428),
            addressLine(FeedRecordKind.FIRM_ENTITY, "Fax number", CORRESPONDENCE_ADDRESS, 429, 438),
            addressLine(FeedRecordKind.FIRM_ENTITY, "Email id", CORRESPONDENCE_ADDRESS, 439, 498),
            FIRM_PROFILE_STATUS, FIRM_PROFILE_EFFECTIVE_DATE, FIRM_PROFILE_TERMINATION_DATE,
            FIRM_PARENT_BD_ALLSTATE_ID, FIRM_PARENT_FIRM_ALLSTATE_ID,

            // Producer entity (D01)
            PRODUCER_SSN, PRODUCER_FIRST_NAME, PRODUCER_MIDDLE_NAME, PRODUCER_LAST_NAME,
            PRODUCER_DATE_OF_BIRTH, PRODUCER_SEX_CODE,
            PRODUCER_HOME_ADDRESS,
            addressLine(FeedRecordKind.PRODUCER_ENTITY, "Address line 1", HOME_ADDRESS, 97, 136),
            addressLine(FeedRecordKind.PRODUCER_ENTITY, "Address line 2", HOME_ADDRESS, 137, 176),
            addressLine(FeedRecordKind.PRODUCER_ENTITY, "Address line 3", HOME_ADDRESS, 177, 216),
            addressLine(FeedRecordKind.PRODUCER_ENTITY, "City", HOME_ADDRESS, 217, 236),
            addressLine(FeedRecordKind.PRODUCER_ENTITY, "State", HOME_ADDRESS, 237, 238),
            addressLine(FeedRecordKind.PRODUCER_ENTITY, "ZIP code", HOME_ADDRESS, 239, 248),
            addressLine(FeedRecordKind.PRODUCER_ENTITY, "Phone number", HOME_ADDRESS, 249, 258),
            addressLine(FeedRecordKind.PRODUCER_ENTITY, "Fax number", HOME_ADDRESS, 259, 268),
            addressLine(FeedRecordKind.PRODUCER_ENTITY, "Email id", HOME_ADDRESS, 269, 328),
            PRODUCER_CRD_NUMBER, PRODUCER_DESIGNATIONS,

            // Producer profile (D02)
            PROFILE_SSN, PROFILE_TYPE, PROFILE_ALLSTATE_ID, PROFILE_EXTERNAL_ID,
            PROFILE_BUSINESS_ADDRESS,
            addressLine(FeedRecordKind.PRODUCER_PROFILE, "Address line 1", BUSINESS_ADDRESS, 44, 83),
            addressLine(FeedRecordKind.PRODUCER_PROFILE, "Address line 2", BUSINESS_ADDRESS, 84, 123),
            addressLine(FeedRecordKind.PRODUCER_PROFILE, "City", BUSINESS_ADDRESS, 124, 143),
            addressLine(FeedRecordKind.PRODUCER_PROFILE, "State", BUSINESS_ADDRESS, 144, 145),
            addressLine(FeedRecordKind.PRODUCER_PROFILE, "ZIP code", BUSINESS_ADDRESS, 146, 155),
            addressLine(FeedRecordKind.PRODUCER_PROFILE, "Phone number", BUSINESS_ADDRESS, 156, 165),
            addressLine(FeedRecordKind.PRODUCER_PROFILE, "Fax number", BUSINESS_ADDRESS, 166, 175),
            addressLine(FeedRecordKind.PRODUCER_PROFILE, "Email id", BUSINESS_ADDRESS, 176, 235),
            PROFILE_RESIDENT_STATE, PROFILE_PRODUCER_ROLE, PROFILE_PARENT_FIRM_ALLSTATE_ID,
            PROFILE_PARENT_BD_ALLSTATE_ID, PROFILE_RELATIONSHIP_STATUS,
            PROFILE_RELATIONSHIP_START_DATE, PROFILE_RELATIONSHIP_END_DATE);

    private FeedLayout() {}

    private static FeedField addressLine(
            FeedRecordKind kind, String label, String block, int startByte, int endByte) {
        return FeedField.partOf(kind, label, block, startByte, endByte);
    }

    /** Every field, in record order, for the workbook's layout reference sheet. */
    public static List<FeedField> allFields() {
        return ALL_FIELDS;
    }

    /** The fields that make up {@code block} - empty for a field that is not one. */
    public static List<FeedField> fieldsIn(FeedField block) {
        String blockName = blockNameOf(block);
        return ALL_FIELDS.stream()
                .filter(field -> field.kind() == block.kind() && blockName.equals(field.partOf()))
                .toList();
    }

    /**
     * The {@code partOf} name sub-fields use for {@code block}. The firm record's business-address
     * block carries its LLE alias in its label, which its lines do not repeat.
     */
    private static String blockNameOf(FeedField block) {
        int alias = block.label().indexOf(" (");
        return alias < 0 ? block.label() : block.label().substring(0, alias);
    }
}
