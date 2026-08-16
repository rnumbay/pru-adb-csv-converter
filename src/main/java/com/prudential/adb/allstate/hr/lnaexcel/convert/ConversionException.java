package com.prudential.adb.allstate.hr.lnaexcel.convert;

/**
 * A conversion that cannot produce a workbook: the input is missing, the output cannot be written,
 * or the file failed a check the caller asked to be strict about
 * ({@code lnaerror-excel.fail-on-layout-warning}).
 *
 * <p>Deliberately one exception type rather than a hierarchy. The only decision anyone makes on it
 * is "this file did not convert, report it and carry on with the next one" - the reason belongs in
 * the message, which is what an operator reads.
 */
public class ConversionException extends RuntimeException {

    public ConversionException(String message) {
        super(message);
    }

    public ConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
