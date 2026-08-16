package com.prudential.adb.allstate.hr.lnaexcel.parse;

import java.nio.file.Path;
import java.util.List;

/**
 * The result of parsing one {@code LNAERROR} file: the records, plus every layout anomaly noticed
 * on the way through.
 *
 * <p>Anomalies are collected rather than thrown. A record whose delimiters sit in the wrong place
 * is still worth putting in front of an operator - it is the error log for a failed feed, and
 * dropping the whole file because record 200 is short would hide the other 256. Fatal problems
 * (the file cannot be read at all) stay exceptions; see {@link LnaErrorFileParser}.
 *
 * @param source      the file these records came from
 * @param records     the parsed records, in file order
 * @param warnings    layout anomalies, in file order
 * @param recordSeparated whether the source used line separators, or was a separator-less fixed
 *                        block of {@link LnaErrorField#RECORD_LENGTH}-byte records
 */
public record ParsedLnaErrorFile(
        Path source, List<LnaErrorRecord> records, List<ParseWarning> warnings, boolean recordSeparated) {

    public ParsedLnaErrorFile {
        records = List.copyOf(records);
        warnings = List.copyOf(warnings);
    }

    public int recordCount() {
        return records.size();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
