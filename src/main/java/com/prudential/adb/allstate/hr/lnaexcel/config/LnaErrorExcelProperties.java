package com.prudential.adb.allstate.hr.lnaexcel.config;

import java.nio.charset.Charset;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything this converter can be pointed at or told to do, bound from the
 * {@code lnaerror-excel.*} block of {@code application.yml}. Each property is documented at its
 * declaration here <em>and</em> in the YAML, as in hr-producer-sync.
 *
 * @param inputDirectory      where {@code lnaerror-*.dat} files are looked for when no
 *                            {@code --input} argument is given. Defaults to hr-producer-sync's own
 *                            {@code ingestion.output-directory} as a sibling checkout, which is
 *                            where the files this app exists to read are actually written
 * @param outputDirectory     where generated workbooks are written
 * @param filePattern         glob matched against file names when the input is a directory
 * @param feedDirectory       where to look for the inbound LNA feed a report was produced from.
 *                            Defaults to hr-producer-sync's own feed-landing directory. The feed
 *                            supplies the failing record's own name, which the report cannot carry -
 *                            it names only the bundle's BD - and is found by matching rather than by
 *                            name, so nobody has to say which file goes with which cycle
 * @param feedFilePattern     glob for candidate feeds in {@code feedDirectory}. Deliberately not
 *                            {@code *}: the directory is a landing area, and a candidate that is
 *                            obviously not a feed is not worth reading to find that out
 * @param feedFile            one specific feed, overriding the search. Normally left unset and
 *                            given as {@code --feed} for a one-off
 * @param charset             character set of the {@code .dat}. UTF-8 is what hr-producer-sync's
 *                            {@code AtomicFileWriter} writes; a file taken straight off z/OS may
 *                            need {@code IBM037} or similar, hence the knob. Undecodable bytes are
 *                            replaced rather than fatal - see {@code LnaErrorFileParser}
 * @param maskTaxId           whether the SSN/TIN column is masked to its last four digits.
 *                            <strong>Defaults to true.</strong> The workbook is a convenience copy
 *                            that gets mailed around and left in shared drives, unlike the
 *                            {@code .dat} it comes from; a full nine-digit SSN is not something to
 *                            spread by default. Set to false deliberately, for a run whose output
 *                            stays inside the same controls as the source artifact
 * @param failOnLayoutWarning whether layout warnings abort the conversion instead of being logged
 *                            and carried on the {@code ConversionResult}. False by default: this is
 *                            the error log for a feed that already failed, and one malformed record
 *                            is a poor reason to withhold the rest
 * @param includeRecordsWithoutErrors whether the sheet carries a row for every record in the feed,
 *                            not only the ones that failed. {@code LNAERROR} holds errors, so on its
 *                            own the sheet has no row at all for the file header, a clean contra
 *                            header or any record that passed - which is what makes it awkward to
 *                            line up against the test-records catalog, where every record has a row.
 *                            Needs a feed: without one there is nothing to list. A record that failed
 *                            more than once keeps one row per error
 * @param dataSheetName       name of the workbook's one sheet: one row per record, the eight columns
 *                            of the test-records catalog, then the error code and the feed field and
 *                            byte position it was raised against
 * @param overwriteExisting   whether an existing workbook for the same cycle is replaced. False
 *                            makes a re-run fail loudly instead of silently rewriting a file
 *                            someone may already be working from
 */
@ConfigurationProperties(prefix = "lnaerror-excel")
public record LnaErrorExcelProperties(
        @DefaultValue("../pru-adb-poc/local-data/ingestion-output") Path inputDirectory,
        @DefaultValue("./local-data/excel") Path outputDirectory,
        @DefaultValue("lnaerror-*.dat") String filePattern,
        @DefaultValue("../pru-adb-poc/local-data/feed-landing") Path feedDirectory,
        @DefaultValue("ALLSTATE*") String feedFilePattern,
        Path feedFile,
        @DefaultValue("UTF-8") Charset charset,
        @DefaultValue("true") boolean maskTaxId,
        @DefaultValue("false") boolean failOnLayoutWarning,
        @DefaultValue("true") boolean includeRecordsWithoutErrors,
        @DefaultValue("Records") String dataSheetName,
        @DefaultValue("true") boolean overwriteExisting) {
}
