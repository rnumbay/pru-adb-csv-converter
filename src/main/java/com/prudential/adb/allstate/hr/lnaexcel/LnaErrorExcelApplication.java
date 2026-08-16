package com.prudential.adb.allstate.hr.lnaexcel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Converts HR1's {@code LNAERROR} artifact - the tilde-delimited, fixed-325-byte LNA validation
 * error log written by {@code hr-producer-sync}'s {@code FileIngestionOutputWriter} - into a
 * formatted {@code .xlsx} workbook.
 *
 * <p>This is a standalone deployable rather than a seventh output writer inside hr-producer-sync,
 * because it has a different consumer and a different lifecycle: the {@code .dat} files are the
 * artifacts operations reconciles a run against and must stay byte-exact at their legacy layout,
 * while the workbook is a convenience view for whoever has to actually read the errors. Keeping the
 * conversion out of the ingestion run also means it can be re-run against any past cycle's file
 * without touching the ingestion job.
 *
 * <p>It runs as a batch/CLI process - no web server, no database. See
 * {@code cli.ConversionRunner} for the arguments.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LnaErrorExcelApplication {

    /**
     * Exits through {@link SpringApplication#exit} so the runner's {@code ExitCodeGenerator} is
     * honoured - a conversion failure has to leave a non-zero exit code behind for whatever
     * schedules this.
     */
    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(LnaErrorExcelApplication.class, args)));
    }
}
