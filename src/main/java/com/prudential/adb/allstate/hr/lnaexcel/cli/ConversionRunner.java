package com.prudential.adb.allstate.hr.lnaexcel.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import com.prudential.adb.allstate.hr.lnaexcel.config.LnaErrorExcelProperties;
import com.prudential.adb.allstate.hr.lnaexcel.convert.ConversionException;
import com.prudential.adb.allstate.hr.lnaexcel.convert.ConversionResult;
import com.prudential.adb.allstate.hr.lnaexcel.convert.LnaErrorConversionService;
import com.prudential.adb.allstate.hr.lnaexcel.feed.FeedFile;

/**
 * The command line: what runs when the jar runs.
 *
 * <pre>
 *   java -jar lnaerror-excel-converter.jar                          # every lnaerror-*.dat in the configured input directory
 *   java -jar ... --input=lnaerror-20260815.dat                     # one file, workbook named after it
 *   java -jar ... --input=&lt;dir&gt; --output=&lt;dir&gt;                      # explicit directories
 *   java -jar ... --input=lnaerror-20260815.dat --output=day1.xlsx  # one file, exact workbook name
 *   java -jar ... --feed=ALLSTATE.LNA.D20260807                     # override the feed-directory search
 *   java -jar ... --lnaerror-excel.mask-tax-id=false                # any property, same as in YAML
 * </pre>
 *
 * <h2>One bad file does not stop the batch</h2>
 *
 * Each file is converted independently and a failure is logged and counted, so a directory of five
 * cycles yields four workbooks and one reported failure rather than an aborted run. The exit code
 * carries that outcome: {@code 0} when everything asked for converted, {@code 1} when anything did
 * not - including when an explicitly named {@code --input} file does not exist. An input
 * <em>directory</em> holding nothing to convert is a success; a scheduled run on a day with no
 * error log has nothing to report.
 */
@Component
public class ConversionRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ConversionRunner.class);

    private static final String INPUT_ARGUMENT = "input";
    private static final String OUTPUT_ARGUMENT = "output";
    private static final String FEED_ARGUMENT = "feed";
    private static final String WORKBOOK_EXTENSION = ".xlsx";

    private final LnaErrorConversionService conversionService;
    private final LnaErrorExcelProperties properties;

    private int exitCode;

    public ConversionRunner(
            LnaErrorConversionService conversionService, LnaErrorExcelProperties properties) {
        this.conversionService = conversionService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path input = pathArgument(args, INPUT_ARGUMENT, properties.inputDirectory());
        Path output = pathArgument(args, OUTPUT_ARGUMENT, properties.outputDirectory());

        log.info("Converting {} -> {}", input.toAbsolutePath(), output.toAbsolutePath());
        List<Path> sources;
        try {
            sources = conversionService.findSources(input);
        } catch (ConversionException e) {
            log.error("Conversion failed: {}", e.getMessage(), e);
            exitCode = 1;
            return;
        }

        // An exact --output names one workbook, so it can only take one source; otherwise each file
        // in the batch would overwrite the last and the run would quietly produce one workbook.
        if (namesAWorkbook(output) && sources.size() > 1) {
            log.error("--output={} names a single workbook but {} files matched --input={}; "
                            + "give an output directory instead",
                    output, sources.size(), input);
            exitCode = 1;
            return;
        }

        FeedFile feed;
        try {
            feed = loadFeed(args);
        } catch (IOException e) {
            log.error("Could not read the feed file: {}", e.getMessage(), e);
            exitCode = 1;
            return;
        }

        List<ConversionResult> results = new ArrayList<>(sources.size());
        for (Path source : sources) {
            try {
                results.add(namesAWorkbook(output)
                        ? conversionService.convertTo(source, output, feed)
                        : conversionService.convertFile(source, output, feed));
            } catch (ConversionException e) {
                log.error("Could not convert {}: {}", source.getFileName(), e.getMessage(), e);
                exitCode = 1;
            }
        }
        report(results);
    }

    private void report(List<ConversionResult> results) {
        if (results.isEmpty()) {
            return;
        }
        int records = results.stream().mapToInt(ConversionResult::recordCount).sum();
        int warnings = results.stream().mapToInt(result -> result.warnings().size()).sum();
        log.info("Wrote {} workbook(s), {} record(s), {} layout warning(s)",
                results.size(), records, warnings);
        if (properties.maskTaxId()) {
            log.info("SSN/TIN values are masked to their last 4 digits "
                    + "(lnaerror-excel.mask-tax-id=true)");
        }
    }

    /**
     * Loads the inbound feed, when one was named. It is optional because the report converts fine
     * without it - it only adds what the report itself does not carry, the failing record's own
     * name and the value that was in the offending bytes.
     *
     * <p>A named feed that cannot be read fails the run rather than being skipped: someone who
     * passes {@code --feed} is asking for those columns, and quietly producing a workbook without
     * them would be the wrong kind of forgiving.
     */
    private FeedFile loadFeed(ApplicationArguments args) throws IOException {
        Path path = pathArgument(args, FEED_ARGUMENT, properties.feedFile());
        if (path == null) {
            return null;
        }
        FeedFile feed = FeedFile.load(path, properties.charset());
        log.info("Read feed {} ({} record(s))", path.toAbsolutePath(), feed.recordCount());
        return feed;
    }

    /**
     * Treats an {@code --output} ending in {@code .xlsx} as an exact file name rather than a
     * directory, so a one-off conversion can choose what the workbook is called. Anything else is a
     * directory, which is the batch case and the default.
     */
    private static boolean namesAWorkbook(Path output) {
        return output.getFileName() != null
                && output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(WORKBOOK_EXTENSION);
    }

    private static Path pathArgument(ApplicationArguments args, String name, Path fallback) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty() || values.get(0).isBlank()) {
            return fallback;
        }
        return Path.of(values.get(values.size() - 1).trim());
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
