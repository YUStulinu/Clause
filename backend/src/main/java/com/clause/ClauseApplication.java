package com.clause;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Clause: reads a contract, explains it clause by clause, and compares two of its versions. */
@SpringBootApplication
public class ClauseApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClauseApplication.class, args);
    }
}
