package com.prudential.adb.allstate.hr.lnaexcel.feed;

/**
 * What an {@code ERRORCD} code points at in the feed: a record type, and - for the codes that
 * reject one field - that field's byte range.
 *
 * @param errorCode  the five-character {@code ERRORCD} code
 * @param recordKind the feed record the rule runs against, {@code null} for the {@code ADB}
 *                   processing-error codes, which are not feed validations at all
 * @param field      the field the rule rejects, {@code null} when the rule is not about one field
 * @param note       what a reader needs to know beyond the range - why a code has no field, or
 *                   what makes its field range less obvious than it looks. {@code null} when there
 *                   is nothing to add
 */
public record ErrorCodeTarget(
        String errorCode, FeedRecordKind recordKind, FeedField field, String note) {

    /** Whether this code names one field with a byte range. */
    public boolean hasField() {
        return field != null;
    }
}
