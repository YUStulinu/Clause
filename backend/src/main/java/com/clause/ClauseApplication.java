package com.clause;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Clause: reads a contract, explains it clause by clause, and compares two of its versions. */
@SpringBootApplication
public class ClauseApplication {

    public static void main(String[] args) {
        if (Cli.handles(args)) {
            // UTF-8 whatever the system's code page (Windows defaults to cp1252, which mangles ă, ș, ț).
            var out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            var err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
            System.exit(Cli.run(args, out, err));
        }
        SpringApplication.run(ClauseApplication.class, args);
    }
}
