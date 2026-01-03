package org.example;

import org.example.command.ParseCommand;
import picocli.CommandLine;

public class App {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new ParseCommand()).execute(args);
        System.exit(exitCode);
    }
}

