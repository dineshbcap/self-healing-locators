package com.dinesh.healing;

/**
 * Shared argv guard for the CLI entry points ({@link LocatorPatchCli},
 * {@link HealingReportPublisherCli}, {@link HealingChurnCli}). A flag like
 * {@code --format} expects a following value; without this, {@code args[++i]}
 * throws an unhandled {@code ArrayIndexOutOfBoundsException} (raw stack trace,
 * exit code 1) when the flag is the last argument, instead of the same clean
 * usage-error/exit-2 path every other bad-argv case already takes.
 */
final class CliArgs {

    private CliArgs() {
    }

    /** Returns {@code args[i]}, or prints a usage error and exits (never returns) if there's no such element. */
    static String next(String[] args, int i, String flagName) {
        if (i >= args.length) {
            System.err.println("Missing value for " + flagName);
            System.exit(2);
            throw new IllegalStateException("unreachable");
        }
        return args[i];
    }
}
