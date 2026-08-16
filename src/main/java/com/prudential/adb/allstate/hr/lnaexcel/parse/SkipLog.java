package com.prudential.adb.allstate.hr.lnaexcel.parse;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * HR1's {@code ADBSKIP} artifact, read for one thing: which records were skipped, and why.
 *
 * <h2>Why a second artifact</h2>
 *
 * A record can leave a run three ways - applied, rejected, or skipped - and {@code LNAERROR} only
 * knows about the second. The skipped ones are in {@code ADBSKIP}: a record whose type is not
 * recognised (reason {@code 001}), and a record whose bundle-mate already failed (reason
 * {@code 002}, the {@code D02} whose {@code D01} was rejected). Without it, those records look
 * like they passed, which is the one thing they did not do.
 *
 * <h2>Layout</h2>
 *
 * {@code LRECL} 615: {@code S-RECORD-POSITION} at bytes 1-12, {@code S-SKIP-REASON} at 13-15, then
 * the skipped record's own 600-byte image at 16-615 - which this class ignores, because the report
 * it feeds already has the record from the feed.
 */
public final class SkipLog {

    private static final int POSITION_END = 12;
    private static final int REASON_END = 15;

    private static final SkipLog EMPTY = new SkipLog(Map.of());

    private final Map<Long, String> reasonByPosition;

    private SkipLog(Map<Long, String> reasonByPosition) {
        this.reasonByPosition = reasonByPosition;
    }

    /** A skip log with nothing in it - what a run with no skipped records leaves behind. */
    public static SkipLog empty() {
        return EMPTY;
    }

    /**
     * Reads {@code file}. Malformed records are skipped rather than fatal: this is a supporting
     * artifact, and a workbook is better off missing one {@code Skip} marking than not being
     * written at all.
     */
    public static SkipLog load(Path file, Charset charset) throws IOException {
        Map<Long, String> reasons = new HashMap<>();
        for (String record : Files.readString(file, charset).split("\r\n|\n|\r")) {
            if (record.length() < REASON_END) {
                continue;
            }
            try {
                long position = Long.parseLong(record.substring(0, POSITION_END).trim());
                reasons.put(position, record.substring(POSITION_END, REASON_END).trim());
            } catch (NumberFormatException e) {
                // Not a record position - nothing to record for it.
            }
        }
        return new SkipLog(Map.copyOf(reasons));
    }

    /** The skip reason for the record at {@code position} - {@code 001} or {@code 002}. */
    public Optional<String> reasonAt(long position) {
        return Optional.ofNullable(reasonByPosition.get(position));
    }

    /** The reason in the words {@code ADBSKIP}'s own catalog uses. */
    public static String describe(String reason) {
        return switch (reason) {
            case "001" -> "unrecognized record";
            case "002" -> "entity record rejected";
            default -> "reason " + reason;
        };
    }

    public int size() {
        return reasonByPosition.size();
    }
}
