package com.conveyal.r5.speed_test;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

class CommandLineOpts {
    private static final boolean OPTION_UNKNOWN_THEN_FAIL = false;
    protected CommandLine cmd;
    private static final String ROOT_DIR_OPT = "d";
    private static final String MULTI_CRITERIA_RR_OPT = "c";
    private static final String HELP_OPT = "h";
    private static final String DOWNLOAD_URL = "u";

    CommandLineOpts(String[] args) {
        Options options = speedTestOptions();

        CommandLineParser cmdParser = new DefaultParser();


        try {
            cmd = cmdParser.parse(options, args, OPTION_UNKNOWN_THEN_FAIL);

            if(printHelpOptSet()) {
                printHelp(options);
                System.exit(0);
            }
            if(!cmd.getArgList().isEmpty()) {
                System.err.println("Unexpected argument(s): " + cmd.getArgList());
                printHelp(options);
                System.exit(-2);
            }

        } catch (ParseException e) {
            System.err.println(e.getMessage());
            printHelp(options);
            System.exit(-1);
        }
    }

    Options speedTestOptions() {
        Options options = new Options();
        options.addOption(ROOT_DIR_OPT, "rootDir", true, "Root directory where network and input files are located. (Optional)");
        options.addOption(MULTI_CRITERIA_RR_OPT, "multiCriteria", false, "Use multi criteria version of range raptor. (Optional)");
        options.addOption(HELP_OPT, "help", false, "Print all command line options, then exit. (Optional)");
        options.addOption(DOWNLOAD_URL, "downloadUrl", true, "Download network file from url. (Optional)");
        return options;
    }

    File rootDir() {
        File rootDir = new File(cmd.getOptionValue(ROOT_DIR_OPT, "."));
        if(!rootDir.exists()) {
            throw new IllegalArgumentException("Unable to find root directory: " + rootDir.getAbsolutePath());
        }
        return rootDir;
    }

    URL downloadUrl() {
        URL downloadUrl;
        try {
            downloadUrl = new URL(cmd.getOptionValue(DOWNLOAD_URL, null));
        } catch (MalformedURLException e) {
            downloadUrl = null;
        }
        return downloadUrl;
    }

    boolean useMultiCriteriaSearch() {
        return cmd.hasOption(MULTI_CRITERIA_RR_OPT);
    }

    private boolean printHelpOptSet() {
        return cmd.hasOption(HELP_OPT);
    }

    private void printHelp(Options options) {
        HelpFormatter formater = new HelpFormatter();
        formater.printHelp("[options]", options);
    }
}
