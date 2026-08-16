package com.prudential.adb.allstate.hr.lnaexcel.parse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.prudential.adb.allstate.hr.lnaexcel.config.LnaErrorExcelProperties;

/**
 * Reads an {@code LNAERROR} file into {@link LnaErrorRecord}s, at the offsets {@link LnaErrorField}
 * declares.
 *
 * <h2>Fields are cut at their offsets, not split on the delimiter</h2>
 *
 * The record is tilde-delimited <em>and</em> fixed-width, and only one of those two can be trusted:
 * the delimiters are byte gaps between fields that are already padded to width, so a tilde
 * appearing inside a 125-character error description or a firm name would desynchronise a
 * {@code split("~")} for the rest of the record. Cutting at declared offsets cannot desynchronise.
 * The delimiter positions are then <em>checked</em> rather than used - a tilde missing from where
 * the layout says it should be is the clearest available signal that the file is not the layout
 * this app was built for, and it becomes a {@link ParseWarning}.
 *
 * <h2>Both record framings are accepted</h2>
 *
 * {@code hr-producer-sync} writes these files newline-separated, but the same artifact arriving
 * from z/OS is a fixed-block file of {@link LnaErrorField#RECORD_LENGTH}-byte records with no
 * separator at all. A file with no line separator in it is therefore chunked by record length
 * instead of being read as one enormous line.
 *
 * <h2>A malformed record is a warning, not a failure</h2>
 *
 * Short, long and mis-delimited records are parsed as best they can be and reported through
 * {@link ParsedLnaErrorFile#warnings()}. This file is itself the error log for a feed that has
 * already gone wrong; refusing to convert it because one record is malformed would withhold the
 * other 256 from the person trying to find out what happened. Escalating warnings to a failure is
 * the caller's call - see {@code lnaerror-excel.fail-on-layout-warning}.
 */
@Component
public class LnaErrorFileParser {

    private static final Logger log = LoggerFactory.getLogger(LnaErrorFileParser.class);

    private final LnaErrorExcelProperties properties;

    public LnaErrorFileParser(LnaErrorExcelProperties properties) {
        this.properties = properties;
    }

    /**
     * Parses {@code file}.
     *
     * @throws IOException if the file cannot be read - unlike a malformed record, there is nothing
     *                     partial to salvage here
     */
    public ParsedLnaErrorFile parse(Path file) throws IOException {
        String content = decode(Files.readAllBytes(file));
        boolean recordSeparated = content.indexOf('\n') >= 0 || content.indexOf('\r') >= 0;
        List<String> rawRecords = recordSeparated ? splitOnSeparator(content) : splitOnLength(content);

        List<LnaErrorRecord> records = new ArrayList<>(rawRecords.size());
        List<ParseWarning> warnings = new ArrayList<>();
        for (int i = 0; i < rawRecords.size(); i++) {
            String raw = rawRecords.get(i);
            if (raw.isBlank()) {
                continue;
            }
            int lineNumber = i + 1;
            records.add(parseRecord(raw, lineNumber, warnings));
        }

        log.debug("Parsed {} record(s) with {} warning(s) from {}",
                records.size(), warnings.size(), file);
        return new ParsedLnaErrorFile(file, records, warnings, recordSeparated);
    }

    private LnaErrorRecord parseRecord(String raw, int lineNumber, List<ParseWarning> warnings) {
        String record = raw;
        boolean padded = false;
        if (record.length() < LnaErrorField.RECORD_LENGTH) {
            warnings.add(new ParseWarning(lineNumber,
                    "is " + record.length() + " characters, expected " + LnaErrorField.RECORD_LENGTH
                            + " - missing fields read as blank"));
            record = record + " ".repeat(LnaErrorField.RECORD_LENGTH - record.length());
            padded = true;
        } else if (record.length() > LnaErrorField.RECORD_LENGTH) {
            warnings.add(new ParseWarning(lineNumber,
                    "is " + record.length() + " characters, expected " + LnaErrorField.RECORD_LENGTH
                            + " - trailing characters ignored"));
        }

        // A padded record is missing the delimiters that ran off its end, and reporting those as
        // misplaced would be reporting this parser's own padding back to the reader. The
        // short-record warning above already says the tail is blank.
        if (!padded) {
            checkDelimiters(record, lineNumber, warnings);
        }

        return new LnaErrorRecord(
                lineNumber,
                LnaErrorField.RECORD_TYPE.extract(record),
                LnaErrorField.RECORD_POSITION.extract(record),
                LnaErrorField.CONTRA_HEADER_FIRM_NAME.extract(record),
                LnaErrorField.CONTRA_HEADER_BD_ALLSTATE_ID.extract(record),
                LnaErrorField.CONTRA_HEADER_DISTRIBUTION_CHANNEL.extract(record),
                LnaErrorField.ADB_ORG_CODE.extract(record),
                LnaErrorField.SSN_OR_TIN.extract(record),
                LnaErrorField.PERSON_FIRM_INDICATOR.extract(record),
                LnaErrorField.ENTITY_TYPE.extract(record),
                LnaErrorField.ALLSTATE_ID.extract(record),
                LnaErrorField.ERROR_CODE.extract(record),
                LnaErrorField.ERROR_DESCRIPTION.extract(record));
    }

    /**
     * Reports the <em>first</em> field whose terminating delimiter is missing, not all twelve. Once
     * one is out of place the rest almost always are too, and one warning per record keeps the
     * report readable; the field named is the point where the record stops matching the layout.
     */
    private void checkDelimiters(String record, int lineNumber, List<ParseWarning> warnings) {
        for (LnaErrorField field : LnaErrorField.values()) {
            char actual = record.charAt(field.delimiterOffset());
            if (actual != LnaErrorField.DELIMITER) {
                warnings.add(new ParseWarning(lineNumber,
                        "expected '" + LnaErrorField.DELIMITER + "' at offset "
                                + field.delimiterOffset() + " after " + field.columnHeading()
                                + ", found " + describe(actual)
                                + " - fields may be misaligned from here on"));
                return;
            }
        }
    }

    private static String describe(char actual) {
        return actual == ' ' ? "a space" : "'" + actual + "'";
    }

    private static List<String> splitOnSeparator(String content) {
        return List.of(content.split("\r\n|\n|\r", -1));
    }

    private static List<String> splitOnLength(String content) {
        List<String> records = new ArrayList<>(content.length() / LnaErrorField.RECORD_LENGTH + 1);
        for (int start = 0; start < content.length(); start += LnaErrorField.RECORD_LENGTH) {
            records.add(content.substring(
                    start, Math.min(start + LnaErrorField.RECORD_LENGTH, content.length())));
        }
        return records;
    }

    /**
     * Decodes with {@link CodingErrorAction#REPLACE} rather than
     * {@link Files#readString(Path)}'s strict decoding: an undecodable byte somewhere in a firm
     * name is not a reason to refuse to convert the file, and the replacement character keeps every
     * subsequent field on its offset. Charset is configurable because the same artifact read
     * straight off z/OS may not be UTF-8 - see {@code lnaerror-excel.charset}.
     */
    private String decode(byte[] bytes) throws CharacterCodingException {
        CharsetDecoder decoder = properties.charset()
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
        return decoded.toString();
    }
}
