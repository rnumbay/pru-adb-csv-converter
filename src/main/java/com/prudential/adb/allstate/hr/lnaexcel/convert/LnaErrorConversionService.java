package com.prudential.adb.allstate.hr.lnaexcel.convert;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.prudential.adb.allstate.hr.lnaexcel.config.LnaErrorExcelProperties;
import com.prudential.adb.allstate.hr.lnaexcel.excel.LnaErrorExcelWriter;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedFile;
import com.prudential.adb.allstate.hr.lnaexcel.parse.LnaErrorFileParser;
import com.prudential.adb.allstate.hr.lnaexcel.parse.ParsedLnaErrorFile;
import com.prudential.adb.allstate.hr.lnaexcel.parse.SkipLog;

/**
 * Turns {@code LNAERROR} files into workbooks: find, parse, render, write.
 *
 * <h2>The workbook is written temp-then-renamed</h2>
 *
 * Same idiom as hr-producer-sync's {@code AtomicFileWriter}, and for the same reason: an
 * {@code .xlsx} is a zip, and a half-written one is not a slightly-short spreadsheet, it is a file
 * Excel refuses to open. Writing to a sibling temp file and renaming on success means a failed run
 * leaves either the previous workbook or nothing - never a corrupt one. The temp file is created in
 * the target directory, not the system temp directory, so the rename stays on one filesystem and
 * can be atomic.
 */
@Service
public class LnaErrorConversionService {

    private static final Logger log = LoggerFactory.getLogger(LnaErrorConversionService.class);

    private static final String WORKBOOK_EXTENSION = ".xlsx";

    private final LnaErrorFileParser parser;
    private final LnaErrorExcelWriter excelWriter;
    private final FeedFileResolver feedResolver;
    private final LnaErrorExcelProperties properties;

    public LnaErrorConversionService(
            LnaErrorFileParser parser,
            LnaErrorExcelWriter excelWriter,
            FeedFileResolver feedResolver,
            LnaErrorExcelProperties properties) {
        this.parser = parser;
        this.excelWriter = excelWriter;
        this.feedResolver = feedResolver;
        this.properties = properties;
    }

    /**
     * The files {@code input} names: itself if it is a single {@code .dat}, or every file in it
     * matching {@code lnaerror-excel.file-pattern}, in name order.
     *
     * <p>A directory with no matching file gives an empty list rather than an exception - "no feed
     * error log today" is a normal, and good, outcome for a scheduled run. A named file that does
     * not exist is not: someone asked for that file by name.
     *
     * <p>Discovery is separate from conversion so the caller can convert each file independently
     * and let one bad file fail on its own - see {@code cli.ConversionRunner}.
     *
     * @throws ConversionException if {@code input} does not exist or cannot be listed
     */
    public List<Path> findSources(Path input) {
        if (!Files.exists(input)) {
            throw new ConversionException("Input does not exist: " + input.toAbsolutePath());
        }
        if (!Files.isDirectory(input)) {
            return List.of(input);
        }
        List<Path> sources = listMatching(input);
        if (sources.isEmpty()) {
            log.info("No file matching '{}' in {}", properties.filePattern(), input.toAbsolutePath());
        }
        return sources;
    }

    /** Converts one {@code .dat} into {@code outputDirectory}, named after the source. */
    public ConversionResult convertFile(Path source, Path outputDirectory) {
        return convertFile(source, outputDirectory, null);
    }

    /** As {@link #convertFile(Path, Path)}, with the feed the report came from. */
    public ConversionResult convertFile(Path source, Path outputDirectory, FeedFile feed) {
        return convertTo(source, targetFor(source, outputDirectory), feed);
    }

    /**
     * Converts one {@code .dat} to a workbook at exactly {@code target} - what {@code --output}
     * means when it names an {@code .xlsx} rather than a directory.
     */
    public ConversionResult convertTo(Path source, Path target) {
        return convertTo(source, target, null);
    }

    /**
     * As {@link #convertTo(Path, Path)}, with {@code feed} - the inbound file this report was
     * produced from, when the caller already has one in hand. Passing {@code null} is the normal
     * case: {@link FeedFileResolver} then finds it in the configured feed directory. Either way, a
     * feed that does not belong to this report is dropped rather than used.
     */
    public ConversionResult convertTo(Path source, Path target, FeedFile feed) {
        ParsedLnaErrorFile parsed;
        try {
            parsed = parser.parse(source);
        } catch (IOException e) {
            throw new ConversionException("Could not read " + source.toAbsolutePath(), e);
        }

        if (parsed.hasWarnings()) {
            if (properties.failOnLayoutWarning()) {
                throw new ConversionException(source.getFileName() + " does not match the LNAERROR "
                        + "layout (" + parsed.warnings().size() + " warning(s)), first: "
                        + parsed.warnings().get(0));
            }
            log.warn("{} produced {} layout warning(s); first: {}",
                    source.getFileName(), parsed.warnings().size(), parsed.warnings().get(0));
        }

        writeWorkbook(parsed, feedResolver.resolve(parsed, feed), skipLogBeside(source), target);

        log.info("Converted {} ({} record(s)) -> {}",
                source.getFileName(), parsed.recordCount(), target.toAbsolutePath());
        return new ConversionResult(source, target, parsed.recordCount(), parsed.warnings());
    }

    /** {@code lnaerror-20260815.dat} in, {@code lnaerror-20260815.xlsx} out. */
    private Path targetFor(Path source, Path outputDirectory) {
        String name = source.getFileName().toString();
        int extension = name.lastIndexOf('.');
        String base = extension > 0 ? name.substring(0, extension) : name;
        return outputDirectory.resolve(base + WORKBOOK_EXTENSION);
    }

    /**
     * The {@code ADBSKIP} artifact from the same run, which sits beside the error log and is named
     * the same way - {@code lnaerror-20260815.dat} has {@code adbskip-20260815.dat} next to it.
     *
     * <p>Optional: an absent or unreadable skip log costs the {@code Category} column its
     * {@code Skip} markings and nothing else, which is not worth failing a conversion over.
     */
    private SkipLog skipLogBeside(Path source) {
        String name = source.getFileName().toString();
        if (!name.startsWith("lnaerror")) {
            return SkipLog.empty();
        }
        Path skipLog = source.resolveSibling(name.replaceFirst("lnaerror", "adbskip"));
        if (!Files.isRegularFile(skipLog)) {
            return SkipLog.empty();
        }
        try {
            SkipLog loaded = SkipLog.load(skipLog, properties.charset());
            log.debug("Read {} skipped record(s) from {}", loaded.size(), skipLog.getFileName());
            return loaded;
        } catch (IOException e) {
            log.warn("Could not read {}: {}", skipLog.getFileName(), e.getMessage());
            return SkipLog.empty();
        }
    }

    private void writeWorkbook(
            ParsedLnaErrorFile parsed, FeedFile feed, SkipLog skipped, Path target) {
        Path directory = target.toAbsolutePath().getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            if (!properties.overwriteExisting() && Files.exists(target)) {
                throw new ConversionException("Workbook already exists and overwrite-existing is "
                        + "false: " + target.toAbsolutePath());
            }

            temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                excelWriter.write(parsed, feed, skipped, out);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
        } catch (AccessDeniedException e) {
            // Windows refuses to replace a file that is open, and the file this writes is one
            // someone opens in Excel - so this is the ordinary re-run-while-reading case, not a
            // permissions problem. Saying so beats making someone read a stack trace to find out.
            throw new ConversionException(target.toAbsolutePath()
                    + " could not be replaced - it is most likely open in Excel. Close it and run "
                    + "again; the existing workbook has been left untouched.", e);
        } catch (IOException e) {
            throw new ConversionException("Could not write " + target.toAbsolutePath(), e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    /**
     * Removes the temp file left behind by a failed write. Failing to delete it must not replace
     * the real error - the conversion failure is what the caller needs to hear about.
     */
    private static void deleteQuietly(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException e) {
            log.warn("Could not remove temporary file {}", temporary, e);
        }
    }

    private List<Path> listMatching(Path directory) {
        List<Path> sources = new ArrayList<>();
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(directory, properties.filePattern())) {
            for (Path candidate : stream) {
                if (Files.isRegularFile(candidate)) {
                    sources.add(candidate);
                }
            }
        } catch (IOException e) {
            throw new ConversionException("Could not list " + directory.toAbsolutePath(), e);
        }
        sources.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return sources;
    }
}
