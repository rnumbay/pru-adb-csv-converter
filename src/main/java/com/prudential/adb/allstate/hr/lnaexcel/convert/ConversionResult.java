package com.prudential.adb.allstate.hr.lnaexcel.convert;

import java.nio.file.Path;
import java.util.List;

import com.prudential.adb.allstate.hr.lnaexcel.parse.ParseWarning;

/**
 * One {@code .dat} converted to one workbook.
 *
 * @param source      the {@code LNAERROR} file that was read
 * @param workbook    the {@code .xlsx} that was written
 * @param recordCount records carried into the workbook
 * @param warnings    layout anomalies found while parsing - carried on the result, not just logged,
 *                    so a caller can decide what a partially-conforming file means to it
 */
public record ConversionResult(
        Path source, Path workbook, int recordCount, List<ParseWarning> warnings) {

    public ConversionResult {
        warnings = List.copyOf(warnings);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
