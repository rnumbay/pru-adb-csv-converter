package com.prudential.adb.allstate.hr.lnaexcel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.prudential.adb.allstate.hr.lnaexcel.cli.ConversionRunner;
import com.prudential.adb.allstate.hr.lnaexcel.parse.LnaErrorField;
import com.prudential.adb.allstate.hr.lnaexcel.testsupport.LnaErrorRecordFixture;

/**
 * The wiring test: the context starts, and the {@code ApplicationRunner} converts what the
 * configured input directory holds without any argument being passed - which is how the scheduled
 * run of this app works.
 */
@SpringBootTest
class LnaErrorExcelApplicationTest {

    @TempDir
    static Path input;

    @TempDir
    static Path output;

    @Autowired
    private ConversionRunner runner;

    @BeforeAll
    static void writeAFeedErrorLog() throws IOException {
        Files.write(input.resolve("lnaerror-20260815.dat"),
                List.of(LnaErrorRecordFixture.populated()
                                .with(LnaErrorField.ERROR_CODE, "C0701").render(),
                        LnaErrorRecordFixture.populated()
                                .with(LnaErrorField.ERROR_CODE, "D0101").render()),
                StandardCharsets.UTF_8);
    }

    @DynamicPropertySource
    static void directories(DynamicPropertyRegistry registry) {
        registry.add("lnaerror-excel.input-directory", input::toString);
        registry.add("lnaerror-excel.output-directory", output::toString);
    }

    @Test
    void convertsTheConfiguredInputDirectoryOnStartup() {
        assertThat(output.resolve("lnaerror-20260815.xlsx")).exists();
        assertThat(runner.getExitCode()).isZero();
    }
}
