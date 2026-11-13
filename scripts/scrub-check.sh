#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Fails when the working tree contains material that must never be published.
#
# Generic rules, always on: issue ids, calendar dates, money amounts, IP addresses, e-mail addresses,
# secrets, UUIDs, untranslated comments in code, committed environment files.
#
# Project-specific rules (names that must not appear in this repository) are deliberately not written
# here: they live in a private file outside the repository, named by the SCRUB_PRIVATE_PATTERNS
# environment variable. One extended regular expression per line, matched case-insensitively; empty
# lines and lines starting with # are ignored. See CONTRIBUTING.md.
#
#   SCRUB_PRIVATE_PATTERNS unset        -> warning on stderr, generic rules only
#   SCRUB_PRIVATE_PATTERNS set, no file -> exit 2
#
# Scans every tracked file plus untracked files that are not ignored.
# Exit code 0 = clean, 1 = findings, 2 = configuration error.
# Requires bash, git and perl (present on macOS and on GitHub runners).
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

private="${SCRUB_PRIVATE_PATTERNS:-}"
if [ -z "$private" ]; then
    echo "scrub-check: SCRUB_PRIVATE_PATTERNS is not set: running the generic rules only" >&2
elif [ ! -f "$private" ]; then
    echo "scrub-check: SCRUB_PRIVATE_PATTERNS names a file that does not exist" >&2
    exit 2
fi

git ls-files -z --cached --others --exclude-standard | SCRUB_PRIVATE_FILE="$private" perl -0ne '
    BEGIN {
        binmode STDOUT, ":encoding(UTF-8)";
        # [rule name, regex, path regex exempt from the rule]
        # This script is exempt from the generic rules because their own sources would match them.
        my $self = qr{^scripts/scrub-check\.sh$};
        my $nobody = qr{^$};
        @rules = (
            ["issue id",            qr{\b(?:B|D|E|M|S|V)[0-9]{2,4}\b},                         $self],
            ["issue id",            qr{\b(?:it|T|P|PR)-[0-9]{2,4}\b},                          $self],
            ["money amount",        qr{\x{20AC}|\bEUR\b},                                      $self],
            ["calendar date",       qr{\b20[0-9]{2}-[01][0-9]-[0-3][0-9]\b},                   $self],
            ["ip address",          qr{\b(?!127\.0\.0\.1\b)(?!0\.0\.0\.0\b)(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b}, $self],
            ["e-mail address",      qr{[A-Za-z0-9._%+-]+@(?!example\.(?:com|org|net)\b)[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}}, $self],
            ["secret",              qr{\bsk-[A-Za-z0-9_-]{20,}|\bAKIA[0-9A-Z]{16}\b|-----BEGIN [A-Z ]*PRIVATE KEY-----|\bgh[pousr]_[A-Za-z0-9]{30,}|\bxox[abprs]-[A-Za-z0-9-]{10,}}, $self],
            ["uuid",                qr{\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b}i, $self],
        );
        # Private rules apply to every file, this one included: it must not name what they protect.
        my $file = $ENV{SCRUB_PRIVATE_FILE};
        if (defined $file && $file ne "") {
            open(my $pf, "<:encoding(UTF-8)", $file) or do {
                print STDERR "scrub-check: cannot read the private pattern file\n";
                exit 2;
            };
            my $count = 0;
            local $/ = "\n";
            while (my $pattern = <$pf>) {
                chomp $pattern;
                $pattern =~ s/\r$//;
                next if $pattern =~ /^\s*$/ || $pattern =~ /^\s*#/;
                my $re = eval { qr{$pattern}i };
                if (!defined $re) {
                    print STDERR "scrub-check: invalid regular expression on line $. of the private pattern file\n";
                    exit 2;
                }
                push @rules, ["private pattern (line $.)", $re, $nobody];
                $count++;
            }
            close($pf);
            print STDERR "scrub-check: $count private pattern(s) loaded\n";
        }
        # Untranslated comments: Italian words in code and build files.
        $code_files = qr{\.(?:java|xml|ya?ml|sh|properties)$};
        $italian = qr{\b(?:perch\x{e9}|quindi|della|delle|nella|questo|questa|tappa|catena|fallimento|chiamata)\b}i;
        $found = 0;
    }
    chomp;
    my $path = $_;
    next if $path eq "" || !-f $path;
    if ($path =~ m{(^|/)\.env(\..*)?$}) {
        print "$path: env file: environment files must never be committed\n";
        $found++;
        next;
    }
    next if -B $path;
    local $/ = "\n";
    open(my $fh, "<:encoding(UTF-8)", $path) or die "cannot read $path: $!";
    while (my $line = <$fh>) {
        for my $rule (@rules) {
            my ($name, $re, $exempt) = @$rule;
            next if $path =~ $exempt;
            if ($line =~ $re) {
                my $hit = $&;
                print "$path:$.: $name: $hit\n";
                $found++;
            }
        }
        if ($path =~ $code_files && $path !~ m{^scripts/scrub-check\.sh$} && $line =~ $italian) {
            print "$path:$.: italian text in code: $&\n";
            $found++;
        }
    }
    close($fh);
    END {
        if ($?) {
            # a configuration error already set the exit code
        } elsif ($found) {
            print STDERR "scrub-check: $found finding(s). Remove them before committing.\n";
            exit 1;
        } else {
            print STDERR "scrub-check: clean\n";
        }
    }
'
