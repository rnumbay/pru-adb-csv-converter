package com.prudential.adb.allstate.hr.lnaexcel.convert;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.prudential.adb.allstate.hr.lnaexcel.config.LnaErrorExcelProperties;
import com.prudential.adb.allstate.hr.lnaexcel.feed.ErrorCodeFieldIndex;
import com.prudential.adb.allstate.hr.lnaexcel.feed.ErrorCodeTarget;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedFile;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedRecordKind;
import com.prudential.adb.allstate.hr.lnaexcel.parse.LnaErrorRecord;
import com.prudential.adb.allstate.hr.lnaexcel.parse.ParsedLnaErrorFile;

/**
 * Finds the inbound feed a report was produced from, so nobody has to name it.
 *
 * <h2>Matched, not guessed</h2>
 *
 * Neither file says which other file it belongs to. Rather than infer it from names - which works
 * for a production feed called {@code ALLSTATE.LNA.D20260807} and not at all for
 * {@code ALLSTATE_LNA_TEST_INPUT_DAY1.txt} - each candidate is <em>checked</em>: for every row whose
 * error code implies a feed record type, the record at that position in the candidate should be of
 * that type. A feed from the wrong cycle disagrees almost everywhere, and the first candidate that
 * agrees is the one used.
 *
 * <p>Names still matter, but only for ordering: a file whose name carries the report's cycle date is
 * tried first, so the common case costs one read rather than several.
 *
 * <p>The check is what makes searching a directory safe at all. Getting this wrong is silent and
 * convincing - every row would still carry a name, a real one, from a real record, just not the
 * record that failed. So a candidate that disagrees on more than a quarter of what can be checked is
 * rejected, and when nothing matches the report converts without the feed-derived columns rather
 * than with plausible ones.
 */
@Component
public class FeedFileResolver {

    private static final Logger log = LoggerFactory.getLogger(FeedFileResolver.class);

    /** Above this share of disagreeing rows, a candidate is not this report's feed. */
    private static final double MAXIMUM_DISAGREEMENT = 0.25;

    private final LnaErrorExcelProperties properties;

    /** Feeds already read this run - a batch of cycles should not re-read one file per report. */
    private final Map<Path, FeedFile> loaded = new HashMap<>();

    public FeedFileResolver(LnaErrorExcelProperties properties) {
        this.properties = properties;
    }

    /**
     * The feed for {@code parsed}: {@code explicit} when one was named, otherwise whichever file in
     * the configured feed directory matches. Returns {@code null} when there is none - the report
     * converts fine without it, minus the columns that need it.
     */
    public FeedFile resolve(ParsedLnaErrorFile parsed, FeedFile explicit) {
        if (explicit != null) {
            if (matches(parsed, explicit)) {
                return explicit;
            }
            log.error("{} does not look like the feed behind {} - the feed-derived columns are "
                            + "left blank", explicit.source().getFileName(), parsed.source().getFileName());
            return null;
        }
        return discover(parsed);
    }

    private FeedFile discover(ParsedLnaErrorFile parsed) {
        Path directory = properties.feedDirectory();
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        List<Path> candidates = candidatesIn(directory, cycleOf(parsed.source()));
        if (candidates.isEmpty()) {
            log.info("No file matching '{}' in {}", properties.feedFilePattern(),
                    directory.toAbsolutePath());
            return null;
        }
        for (Path candidate : candidates) {
            FeedFile feed = read(candidate);
            if (feed != null && matches(parsed, feed)) {
                log.info("Matched feed {} ({} record(s)) to {}",
                        candidate.toAbsolutePath(), feed.recordCount(), parsed.source().getFileName());
                return feed;
            }
        }
        log.warn("None of the {} file(s) in {} matches {} - the feed-derived columns are left blank",
                candidates.size(), directory.toAbsolutePath(), parsed.source().getFileName());
        return null;
    }

    /**
     * Candidates in the directory, those whose name carries {@code cycle} first. Ordering is a
     * shortcut, not a decision - every candidate still has to pass {@link #matches}.
     */
    private List<Path> candidatesIn(Path directory, String cycle) {
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(directory, properties.feedFilePattern())) {
            for (Path candidate : stream) {
                if (Files.isRegularFile(candidate)) {
                    candidates.add(candidate);
                }
            }
        } catch (IOException e) {
            log.warn("Could not list feed directory {}: {}", directory.toAbsolutePath(), e.getMessage());
            return List.of();
        }
        candidates.sort(Comparator
                .comparing((Path path) -> !carriesCycle(path, cycle))
                .thenComparing(path -> path.getFileName().toString()));
        return candidates;
    }

    private static boolean carriesCycle(Path candidate, String cycle) {
        return cycle != null && candidate.getFileName().toString().contains(cycle);
    }

    /** {@code lnaerror-20260815.dat} -&gt; {@code 20260815}, or null when the name has no date. */
    private static String cycleOf(Path report) {
        String name = report.getFileName().toString();
        int digits = 0;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i))) {
                digits++;
                if (digits == 8) {
                    return name.substring(i - 7, i + 1);
                }
            } else {
                digits = 0;
            }
        }
        return null;
    }

    private FeedFile read(Path candidate) {
        FeedFile cached = loaded.get(candidate);
        if (cached != null) {
            return cached;
        }
        try {
            FeedFile feed = FeedFile.load(candidate, properties.charset());
            loaded.put(candidate, feed);
            return feed;
        } catch (IOException e) {
            log.warn("Could not read candidate feed {}: {}", candidate.toAbsolutePath(), e.getMessage());
            return null;
        }
    }

    /**
     * Whether {@code feed} is the file {@code parsed} was produced from - see the class Javadoc for
     * why this is a check rather than a naming convention.
     *
     * <p>A report with nothing checkable in it (every row an ADB processing error, say) matches
     * anything, so it takes the first candidate. That is the best available answer: there is no
     * evidence either way, and the alternative is dropping the columns for a report that may well
     * belong to the only feed present.
     */
    private static boolean matches(ParsedLnaErrorFile parsed, FeedFile feed) {
        int checkable = 0;
        int disagreed = 0;
        for (LnaErrorRecord record : parsed.records()) {
            Optional<String> expected = ErrorCodeFieldIndex.find(record.errorCode())
                    .map(ErrorCodeTarget::recordKind)
                    .map(FeedRecordKind::typeCode);
            if (expected.isEmpty()) {
                continue;
            }
            checkable++;
            if (feed.typeAt(recordPosition(record)).filter(expected.get()::equals).isEmpty()) {
                disagreed++;
            }
        }
        if (checkable == 0) {
            return true;
        }
        if (disagreed > checkable * MAXIMUM_DISAGREEMENT) {
            log.debug("{} disagrees with {} on {} of {} checked rows", feed.source().getFileName(),
                    parsed.source().getFileName(), disagreed, checkable);
            return false;
        }
        if (disagreed > 0) {
            log.warn("{} rows of {} point at a feed record of an unexpected type in {}",
                    disagreed, checkable, feed.source().getFileName());
        }
        return true;
    }

    private static long recordPosition(LnaErrorRecord record) {
        try {
            return Long.parseLong(record.recordPosition());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
