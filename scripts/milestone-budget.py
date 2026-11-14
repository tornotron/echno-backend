#!/usr/bin/env python3
"""Check that the next milestone PR from auth-workos to development stays under the limits.

Usage:
    scripts/milestone-budget.py <base> <head>

<base> is development and <head> the pull request's head: everything between them is what the
next milestone PR would carry. GitHub rebase-merges at most 100 commits, and CodeRabbit reviews at
most 100 files on this repository's plan, counted after its path filters. So the check:

    warns   from WARN_AT commits or files
    fails   above LIMIT commits or files

Files are counted after the path filters in .coderabbit.yaml, read from that file, so this count
and CodeRabbit's cannot drift apart. Only the plain list form is read:

    path_filters:
      - "!docs/openapi.json"

The result goes to stdout, and to the job summary when GITHUB_STEP_SUMMARY is set.
"""

import fnmatch
import os
import re
import subprocess
import sys

WARN_AT = 80
LIMIT = 100
CODERABBIT_CONFIG = ".coderabbit.yaml"


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def path_filters(config_path):
    """The exclusions under `path_filters:`, without their leading `!`."""
    if not os.path.exists(config_path):
        return []
    excluded = []
    in_filters = False
    filters_indent = 0
    for line in open(config_path, encoding="utf-8"):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if stripped == "path_filters:":
            in_filters, filters_indent = True, indent
            continue
        if in_filters:
            item = re.match(r"-\s*[\"']?(!?)([^\"']+)[\"']?\s*$", stripped)
            if indent > filters_indent and item:
                if item.group(1) == "!":
                    excluded.append(item.group(2))
                continue
            in_filters = False
    return excluded


def counts(base, head, excluded):
    commits = int(git("rev-list", "--count", f"{base}..{head}").strip())
    changed = [path for path in git("diff", "--name-only", f"{base}...{head}").splitlines() if path]
    reviewed = [path for path in changed
                if not any(fnmatch.fnmatch(path, pattern) for pattern in excluded)]
    return commits, len(changed), len(reviewed)


def verdict(value):
    if value > LIMIT:
        return "over the limit"
    if value >= WARN_AT:
        return "close to the limit"
    return "within budget"


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    base, head = argv[1], argv[2]
    excluded = path_filters(CODERABBIT_CONFIG)
    commits, changed, reviewed = counts(base, head, excluded)

    lines = [
        "### Milestone budget",
        "",
        "What the next milestone PR from `auth-workos` to `development` would carry with this change.",
        "",
        "| | Count | Limit | |",
        "| --- | --- | --- | --- |",
        f"| Commits | {commits} | {LIMIT} | {verdict(commits)} |",
        f"| Files CodeRabbit reviews | {reviewed} | {LIMIT} | {verdict(reviewed)} |",
        "",
        f"{changed} files changed in all; {changed - reviewed} left out by the path filters in "
        f"`{CODERABBIT_CONFIG}` ({', '.join(excluded) or 'none'}).",
    ]
    report = "\n".join(lines)
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(report + "\n")

    worst = max(commits, reviewed)
    if worst > LIMIT:
        print(f"::error::The milestone would carry {commits} commits and {reviewed} reviewed files; "
              f"the limit is {LIMIT} of each. Close the milestone (open its PR to development) "
              f"before merging more into auth-workos, or split this change.")
        return 1
    if worst >= WARN_AT:
        print(f"::warning::The milestone carries {commits} commits and {reviewed} reviewed files, "
              f"close to the limit of {LIMIT}. Plan to close it soon.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
