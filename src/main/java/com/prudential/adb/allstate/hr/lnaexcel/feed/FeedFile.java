package com.prudential.adb.allstate.hr.lnaexcel.feed;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The inbound LNA feed file, indexed by record position, so a row of {@code LNAERROR} can be looked
 * back up in the record it came from.
 *
 * <h2>Why this exists</h2>
 *
 * {@code LNAERROR} does not carry the failing record's own name. Its twelve fields include the
 * <em>contra header's</em> firm name - the bundle's BD - and nothing that says which LLE, HA or
 * producer inside that bundle actually failed. For a bundle of ninety records that is the
 * difference between "something in APEX SECURITIES GROUP INC failed" and "APEX GAP NOL1 LLC
 * failed". The name is in the feed, at bytes 11-60 of the firm record, so the only faithful way to
 * put it in the report is to read the feed record back.
 *
 * <p>The same lookup answers the other question a byte range raises - what was actually <em>in</em>
 * those bytes.
 *
 * <h2>Positions are the report's own</h2>
 *
 * {@code E-RECORD-POSITION} is 1-based over the whole file, header record included, which is
 * exactly this index's key. A record position past the end of the file returns empty rather than
 * throwing: it means the feed and the report do not belong to each other, and
 * {@code LnaErrorConversionService} checks for that before trusting any of this.
 */
public final class FeedFile {

    /** {@code D01} and {@code D02} are distinguished by a three-character type, not one. */
    private static final int TYPE_LENGTH = 3;

    private final Path source;
    private final List<String> records;

    private FeedFile(Path source, List<String> records) {
        this.source = source;
        this.records = records;
    }

    /**
     * Reads {@code file}, accepting the same two framings as the error log itself: line-separated
     * records, or a separator-less fixed block of {@link FeedLayout#RECORD_LENGTH}-byte records.
     *
     * <p>Short records are kept as they are rather than padded - {@link #field} clamps, the way
     * {@code FeedRecord.field} does, so a truncated record yields a short value instead of an
     * exception. The feed's own header record is legitimately short.
     */
    public static FeedFile load(Path file, Charset charset) throws IOException {
        String content = decode(Files.readAllBytes(file), charset);
        List<String> records = content.indexOf('\n') >= 0 || content.indexOf('\r') >= 0
                ? new ArrayList<>(List.of(content.split("\r\n|\n|\r", -1)))
                : fixedBlock(content);
        // A trailing newline leaves an empty final element that is not a record.
        if (!records.isEmpty() && records.get(records.size() - 1).isEmpty()) {
            records.remove(records.size() - 1);
        }
        return new FeedFile(file, List.copyOf(records));
    }

    public Path source() {
        return source;
    }

    public int recordCount() {
        return records.size();
    }

    /** The whole record at {@code position}, 1-based - empty if the file has no such record. */
    public Optional<String> recordAt(long position) {
        if (position < 1 || position > records.size()) {
            return Optional.empty();
        }
        return Optional.of(records.get((int) (position - 1)));
    }

    /**
     * The record's type as the feed writes it - {@code A}, {@code B}, {@code C}, {@code D01},
     * {@code D02}, {@code D03}, {@code X}, {@code Z}. A {@code D} record carries a two-digit
     * sub-type, everything else is one character.
     */
    public Optional<String> typeAt(long position) {
        return recordAt(position).map(record -> {
            if (record.isEmpty()) {
                return "";
            }
            if (record.charAt(0) == 'D' && record.length() >= TYPE_LENGTH) {
                return record.substring(0, TYPE_LENGTH);
            }
            return record.substring(0, 1);
        }).filter(type -> !type.isEmpty());
    }

    /**
     * The name the record is about: the firm's for a contra header or firm record, the producer's
     * for a producer record.
     *
     * <p>A {@code D02} carries no name of its own - it is the appointment, not the person - so it
     * takes the name of the {@code D01} it follows, which is the same thing the test-records
     * catalog does. The search walks back rather than assuming the immediately preceding record,
     * because a rejected {@code D01} can leave other records between the two.
     */
    public Optional<String> nameAt(long position) {
        Optional<String> type = typeAt(position);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        return switch (type.get()) {
            // The file header's "name" is the submitting company - what the catalog puts there too.
            case "A" -> field(position, FeedLayout.HEADER_COMPANY_NAME);
            case "B" -> field(position, FeedLayout.CH_FIRM_NAME);
            case "C" -> field(position, FeedLayout.FIRM_NAME);
            case "D01" -> producerName(position);
            case "D02" -> producerRecordFor(position).flatMap(this::producerName);
            default -> Optional.empty();
        };
    }

    /**
     * The Allstate BD id of the bundle the record at {@code position} belongs to: the nearest
     * preceding contra header's, or its own if it is one.
     *
     * <p>Empty when no contra header precedes it - a standalone record in an update block, which
     * the feed carries outside any bundle. Present but blank when the header exists and its id
     * field does not, which is error {@code B0300} rather than an absent bundle.
     */
    public Optional<String> bundleIdAt(long position) {
        for (long candidate = position; candidate >= 1; candidate--) {
            if (typeAt(candidate).filter("B"::equals).isPresent()) {
                return field(candidate, FeedLayout.CH_BD_ALLSTATE_ID);
            }
        }
        return Optional.empty();
    }

    /**
     * The identifier the test-records catalog keys a record on: the Allstate id for a firm or
     * profile record, the SSN for a producer record.
     *
     * <p>Read from the feed rather than from the error report, which carries the record's own
     * identity on only some of its rows.
     */
    public Optional<String> keyAt(long position) {
        Optional<String> type = typeAt(position);
        if (type.isEmpty()) {
            return Optional.empty();
        }
        return switch (type.get()) {
            case "B" -> field(position, FeedLayout.CH_BD_ALLSTATE_ID);
            case "C" -> field(position, FeedLayout.FIRM_OWN_ALLSTATE_ID);
            case "D01" -> field(position, FeedLayout.PRODUCER_SSN);
            case "D02" -> field(position, FeedLayout.PROFILE_SSN);
            default -> Optional.empty();
        };
    }

    /**
     * Whether the record at {@code position} is the first firm ({@code C}) record of its bundle -
     * the broker-dealer's own record, and the one whose failures take the whole bundle down.
     */
    public boolean isFirstFirmRecordInBundle(long position) {
        if (typeAt(position).filter("C"::equals).isEmpty()) {
            return false;
        }
        for (long candidate = position - 1; candidate >= 1; candidate--) {
            Optional<String> type = typeAt(candidate);
            if (type.filter("C"::equals).isPresent()) {
                return false;
            }
            if (type.filter(found -> found.equals("B") || found.equals("A")).isPresent()) {
                return true;
            }
        }
        return true;
    }

    /** Whether the record at {@code position} keys on a tax identifier rather than an id. */
    public boolean keyIsATaxIdAt(long position) {
        return typeAt(position).filter(type -> type.equals("D01") || type.equals("D02")).isPresent();
    }

    /** {@code field}'s value in the record at {@code position}, trimmed of its padding. */
    public Optional<String> field(long position, FeedField field) {
        return recordAt(position).map(record -> {
            int from = Math.min(field.startByte() - 1, record.length());
            int to = Math.min(field.endByte(), record.length());
            return record.substring(from, to).trim();
        });
    }

    private Optional<String> producerName(long position) {
        String first = field(position, FeedLayout.PRODUCER_FIRST_NAME).orElse("");
        String last = field(position, FeedLayout.PRODUCER_LAST_NAME).orElse("");
        String name = (first + " " + last).trim();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /**
     * The producer record a profile record belongs to: the nearest preceding {@code D01} in the
     * same bundle carrying the same SSN, and failing that the nearest preceding {@code D01} at all.
     *
     * <p>Matching on the SSN rather than on adjacency is what makes a run of profiles work. One
     * producer can carry several appointments, so {@code D01 D02 D02 D02} is ordinary - every one of
     * those profiles belongs to the producer above them, and treating the first {@code D02} as the
     * end of the chain leaves the rest nameless. The Day 1 corpus has 38 rows of exactly that.
     *
     * <p>The positional fallback exists for {@code F0101}, the code for a profile whose SSN does
     * <em>not</em> match its producer record: there the SSN match cannot succeed by definition, and
     * the record it follows is still the record it was meant to belong to - which is how legacy
     * pairs them to detect the mismatch in the first place.
     *
     * <p>The search stops at the bundle boundary. A profile-only update with no producer record in
     * its bundle gets no name rather than the name of whoever preceded it.
     */
    private Optional<Long> producerRecordFor(long position) {
        String ssn = field(position, FeedLayout.PROFILE_SSN).orElse("");
        Long nearest = null;
        for (long candidate = position - 1; candidate >= 1; candidate--) {
            Optional<String> type = typeAt(candidate);
            if (type.filter(found -> found.equals("B") || found.equals("A")).isPresent()) {
                break;
            }
            if (type.filter("D01"::equals).isEmpty()) {
                continue;
            }
            if (nearest == null) {
                nearest = candidate;
            }
            if (!ssn.isEmpty() && ssn.equals(field(candidate, FeedLayout.PRODUCER_SSN).orElse(""))) {
                return Optional.of(candidate);
            }
        }
        return Optional.ofNullable(nearest);
    }

    private static List<String> fixedBlock(String content) {
        List<String> records = new ArrayList<>();
        for (int start = 0; start < content.length(); start += FeedLayout.RECORD_LENGTH) {
            records.add(content.substring(
                    start, Math.min(start + FeedLayout.RECORD_LENGTH, content.length())));
        }
        return records;
    }

    /** Replaces undecodable bytes rather than failing, exactly as the error-log parser does. */
    private static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }
}
