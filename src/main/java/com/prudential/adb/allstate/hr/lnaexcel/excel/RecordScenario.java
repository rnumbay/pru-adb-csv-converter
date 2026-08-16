package com.prudential.adb.allstate.hr.lnaexcel.excel;

import java.util.Optional;

import com.prudential.adb.allstate.hr.lnaexcel.feed.ErrorCodeCatalog;
import com.prudential.adb.allstate.hr.lnaexcel.feed.ErrorCodeCatalog.ErrorCodeMeaning;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedField;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedFile;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedLayout;
import com.prudential.adb.allstate.hr.lnaexcel.parse.LnaErrorRecord;
import com.prudential.adb.allstate.hr.lnaexcel.parse.SkipLog;

/**
 * The catalog's {@code Scenario} column: what this record <em>is</em>, in a line.
 *
 * <h2>It is a description, not an intention</h2>
 *
 * The catalog's scenarios read like test design - {@code Error LLE firm record (insert): C0707
 * invalid business/commission address block} - but almost every part of that sentence is a fact
 * about the record: its type, its entity type, the code it raised, and what that code means. Only
 * the {@code (insert)} qualifier is genuinely unavailable, because whether the firm already exists
 * is a question about ADB rather than about the feed, so it is left off.
 *
 * <p>What is generated here therefore matches the catalog in substance and not always to the word.
 * A cell-by-cell diff of this column will show differences of phrasing; a reader comparing the two
 * will see the same record described.
 *
 * <h2>Where each part comes from</h2>
 *
 * The feed record supplies the identifiers, statuses and dates, {@link ErrorCodeCatalog} supplies
 * the short meaning of a code, and {@link SkipLog} supplies the reason a record never got as far as
 * being validated. Nothing is invented: a record whose feed is missing gets no scenario at all.
 */
final class RecordScenario {

    /** Reported at the record that closed the bundle - see {@link #describe}. */
    private static final String BUNDLE_WITHOUT_BD_FIRM = "B0400";

    private RecordScenario() {}

    /** Describes the record at {@code position}, or {@code ""} without a feed to read it from. */
    static String describe(long position, FeedFile feed, LnaErrorRecord error, SkipLog skipped) {
        String errorCode = error == null ? null : error.errorCode();
        if (feed == null) {
            return "";
        }
        Optional<String> type = feed.typeAt(position);
        if (type.isEmpty()) {
            return "";
        }
        Optional<ErrorCodeMeaning> meaning = ErrorCodeCatalog.find(errorCode);
        boolean isWarning = meaning
                .filter(found -> found.severity() == ErrorCodeCatalog.Severity.WARNING)
                .isPresent();
        // A processing failure says nothing about the record - the subprogram fell over while
        // applying a record that was usually fine - so such a record is still described as itself,
        // and what happened to it is the Category and Actual Result columns' business.
        boolean malformed = meaning
                .filter(found -> found.scope() == ErrorCodeCatalog.Scope.RUN)
                .isEmpty();
        boolean failed = errorCode != null && !errorCode.isBlank() && !isWarning && malformed;

        // B0400 is reported on the record that CLOSED the failing bundle, not on a record of it -
        // usually the next bundle's contra header, which is itself perfectly good. Describing that
        // record as an error would blame the wrong one; the catalog notes the trigger instead.
        if (BUNDLE_WITHOUT_BD_FIRM.equals(errorCode) && type.get().equals("B")) {
            return contraHeader(position, feed) + " Arrival triggers B0400 for pending bundle "
                    + error.contraHeaderBdAllstateId() + ".";
        }

        return switch (type.get()) {
            case "A" -> header(position, feed);
            case "B" -> failed
                    ? "Error contra header: " + code(errorCode, meaning)
                    : contraHeader(position, feed);
            case "C" -> failed
                    ? "Error " + entityType(position, feed) + " firm record: " + code(errorCode, meaning)
                    : firm(position, feed) + (isWarning ? " while BD is not active" : "");
            case "D01" -> failed
                    ? "Error producer entity (D01): " + code(errorCode, meaning)
                    : producer(position, feed);
            case "D02" -> profile(position, feed, errorCode, meaning, failed, skipped);
            case "D03" -> "Unknown D sub-type '"
                    + feed.recordAt(position).map(record -> record.substring(1, 3)).orElse("") + "'";
            case "Z" -> trailer(position, feed, failed);
            default -> "Unknown record type '" + type.get() + "'";
        };
    }

    private static String header(long position, FeedFile feed) {
        return "Submitting header: company=" + value(feed, position, FeedLayout.HEADER_COMPANY_NAME)
                + ", trans date=" + value(feed, position, FeedLayout.HEADER_TRANSMISSION_DATE)
                + ", record count=" + value(feed, position, FeedLayout.HEADER_RECORD_COUNT);
    }

    private static String contraHeader(long position, FeedFile feed) {
        return "Contra header - opens bundle for BD "
                + value(feed, position, FeedLayout.CH_BD_ALLSTATE_ID)
                + " (channel " + value(feed, position, FeedLayout.CH_DISTRIBUTION_CHANNEL) + ").";
    }

    /**
     * The bundle's own broker-dealer record names itself; every later firm record names the BD it
     * hangs off. That is the same distinction {@code B06xx} versus {@code C07xx} turns on.
     */
    private static String firm(long position, FeedFile feed) {
        String status = "status " + profileStatus(value(feed, position, FeedLayout.FIRM_PROFILE_STATUS))
                + ", " + value(feed, position, FeedLayout.FIRM_PROFILE_EFFECTIVE_DATE)
                + "-" + value(feed, position, FeedLayout.FIRM_PROFILE_TERMINATION_DATE);
        if (feed.isFirstFirmRecordInBundle(position)) {
            return "BD firm itself (first C) - " + status;
        }
        return entityType(position, feed) + " firm "
                + value(feed, position, FeedLayout.FIRM_OWN_ALLSTATE_ID)
                + " under BD " + value(feed, position, FeedLayout.FIRM_PARENT_BD_ALLSTATE_ID)
                + " - " + status;
    }

    private static String producer(long position, FeedFile feed) {
        return "Producer entity " + feed.nameAt(position).orElse("");
    }

    private static String profile(
            long position, FeedFile feed, String errorCode, Optional<ErrorCodeMeaning> meaning,
            boolean failed, SkipLog skipped) {
        // An error outranks a skip, the same way the Category column ranks them. A record that
        // failed is usually in ADBSKIP as well - so is every record behind it in the same bundle -
        // and reading the skip log first would describe a rejected record as merely skipped.
        if (failed) {
            return "Error producer profile (D02): " + code(errorCode, meaning);
        }
        // Only reason 002 is read here. Reason 001 means "unrecognized record type", and this
        // record's type is D02 - the feed says so - so on a D02 that reason contradicts the record
        // it is about, and the record wins. Worth knowing why the contradiction exists: HR1 writes
        // every rejected record to ADBSKIP under reason 001, 247 of Day 1's 253 of them validly
        // typed records that were rejected and already reported in LNAERROR.
        if (skipped.reasonAt(position).filter("002"::equals).isPresent()) {
            return "Producer profile whose D01 was rejected";
        }
        return "Appointment (type " + value(feed, position, FeedLayout.PROFILE_TYPE) + ") "
                + value(feed, position, FeedLayout.PROFILE_ALLSTATE_ID)
                + " under firm " + value(feed, position, FeedLayout.PROFILE_PARENT_FIRM_ALLSTATE_ID)
                + " / BD " + value(feed, position, FeedLayout.PROFILE_PARENT_BD_ALLSTATE_ID)
                + " - " + relationshipStatus(value(feed, position, FeedLayout.PROFILE_RELATIONSHIP_STATUS))
                + " " + value(feed, position, FeedLayout.PROFILE_RELATIONSHIP_START_DATE)
                + "-" + value(feed, position, FeedLayout.PROFILE_RELATIONSHIP_END_DATE);
    }

    /**
     * A trailer closes the bundle it names. When it carries {@code B0400} it is also the record the
     * failure was detected on, which is the one thing about a trailer worth saying twice.
     */
    private static String trailer(long position, FeedFile feed, boolean failed) {
        String bundle = feed.bundleIdAt(position).filter(id -> !id.isEmpty()).orElse("(no id)");
        return failed
                ? "Bundle terminator while bundle " + bundle + " still awaits its BD firm record"
                : "Bundle terminator for " + bundle;
    }

    private static String code(String errorCode, Optional<ErrorCodeMeaning> meaning) {
        return errorCode + meaning.map(found -> " " + found.meaningOrBlank())
                .filter(gloss -> !gloss.isBlank())
                .orElse("");
    }

    private static String entityType(long position, FeedFile feed) {
        String entityType = value(feed, position, FeedLayout.FIRM_ENTITY_TYPE);
        return entityType.isEmpty() ? "firm" : entityType;
    }

    /** {@code WS-PROFILE-STATUS} - the catalog spells these out rather than using the code. */
    private static String profileStatus(String raw) {
        return switch (raw) {
            case "A" -> "Active";
            case "I" -> "Inactive";
            case "T" -> "Terminated";
            default -> raw.isEmpty() ? "(blank)" : raw;
        };
    }

    private static String relationshipStatus(String raw) {
        return switch (raw) {
            case "A" -> "Active";
            case "I" -> "Inactive";
            case "T" -> "Terminated";
            default -> raw.isEmpty() ? "(blank)" : raw;
        };
    }

    private static String value(FeedFile feed, long position, FeedField field) {
        return feed.field(position, field).orElse("");
    }
}
