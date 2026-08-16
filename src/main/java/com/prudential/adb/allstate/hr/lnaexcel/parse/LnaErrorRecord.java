package com.prudential.adb.allstate.hr.lnaexcel.parse;

/**
 * One parsed {@code LNAERROR} record - the mirror image of {@code hr-producer-sync}'s
 * {@code LnaValidationErrorEntry}, which renders these same twelve fields on the way out.
 *
 * <p>Every value is trimmed of the space padding the fixed-width record carries, so an absent
 * character field arrives here as {@code ""} rather than a run of blanks. Nothing else is
 * interpreted: codes stay codes, and the zero-padded numerics keep their padding, because deciding
 * what an absent tax id or an unknown entity type <em>means</em> belongs to whoever reads the
 * workbook, not to a format converter.
 *
 * @param lineNumber 1-based position of the record in its source file, kept so a warning or a
 *                   surprising row can be traced back to the {@code .dat}
 */
public record LnaErrorRecord(
        int lineNumber,
        String recordType,
        String recordPosition,
        String contraHeaderFirmName,
        String contraHeaderBdAllstateId,
        String contraHeaderDistributionChannel,
        String adbOrgCode,
        String ssnOrTin,
        String personFirmIndicator,
        String entityType,
        String allstateId,
        String errorCode,
        String errorDescription) {

    /** The value of {@code field}, for callers that walk the layout rather than name fields. */
    public String value(LnaErrorField field) {
        return switch (field) {
            case RECORD_TYPE -> recordType;
            case RECORD_POSITION -> recordPosition;
            case CONTRA_HEADER_FIRM_NAME -> contraHeaderFirmName;
            case CONTRA_HEADER_BD_ALLSTATE_ID -> contraHeaderBdAllstateId;
            case CONTRA_HEADER_DISTRIBUTION_CHANNEL -> contraHeaderDistributionChannel;
            case ADB_ORG_CODE -> adbOrgCode;
            case SSN_OR_TIN -> ssnOrTin;
            case PERSON_FIRM_INDICATOR -> personFirmIndicator;
            case ENTITY_TYPE -> entityType;
            case ALLSTATE_ID -> allstateId;
            case ERROR_CODE -> errorCode;
            case ERROR_DESCRIPTION -> errorDescription;
        };
    }
}
