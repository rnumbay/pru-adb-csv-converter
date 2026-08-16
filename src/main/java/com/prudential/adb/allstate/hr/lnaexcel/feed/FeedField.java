package com.prudential.adb.allstate.hr.lnaexcel.feed;

/**
 * One field of an inbound LNA feed record, at the byte range the feed carries it in.
 *
 * <p>Positions are <strong>1-based and inclusive</strong>, the way the layout tables and
 * {@code FeedRecord#field(start, end)} express them - {@code WS-FIRM-NAME | CHAR(50) | 11-60} is
 * {@code startByte 11, endByte 60}. They are positions within the 600-byte record, not within the
 * feed file; see {@link FeedLayout#RECORD_LENGTH}.
 *
 * @param kind      the record type this field belongs to
 * @param label     the field's name, as an operator would say it
 * @param partOf    the enclosing block for a field that is part of one (an address's city is part
 *                  of "Business address"), otherwise {@code null}. Blocks exist because several
 *                  error codes reject a whole address rather than one line of it
 * @param startByte first byte, 1-based inclusive
 * @param endByte   last byte, 1-based inclusive
 */
public record FeedField(
        FeedRecordKind kind, String label, String partOf, int startByte, int endByte) {

    public FeedField {
        if (startByte < 1 || endByte < startByte) {
            throw new IllegalArgumentException(
                    "Invalid byte range for " + label + ": " + startByte + "-" + endByte);
        }
    }

    static FeedField of(FeedRecordKind kind, String label, int startByte, int endByte) {
        return new FeedField(kind, label, null, startByte, endByte);
    }

    static FeedField partOf(
            FeedRecordKind kind, String label, String block, int startByte, int endByte) {
        return new FeedField(kind, label, block, startByte, endByte);
    }

    public int length() {
        return endByte - startByte + 1;
    }

    /** The range as it reads in a layout table: {@code 115-306}. */
    public String byteRange() {
        return startByte + "-" + endByte;
    }
}
