package com.prudential.adb.allstate.hr.lnaexcel.parse;

/**
 * A record that did not match the declared {@code LNAERROR} layout but was parsed anyway.
 *
 * @param lineNumber 1-based position of the offending record in its source file
 * @param detail     what did not match, phrased for whoever has to look at the {@code .dat}
 */
public record ParseWarning(int lineNumber, String detail) {

    @Override
    public String toString() {
        return "record " + lineNumber + ": " + detail;
    }
}
