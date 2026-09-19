#!/usr/bin/env python3
"""Generate (or remove) a backend module skeleton from the templates beside this script.

Usage:
    scripts/scaffold-module.py <id> [--name "Human Name"] [--dry-run]
    scripts/scaffold-module.py <id> --delete

Normally invoked through Gradle:
    ./gradlew scaffoldModule -Pid=toolbox-talks -Pname="Toolbox Talks"

The id must match the manifest pattern [a-z][a-z0-9-]* and must not already have a
package under modules/ or a changelog directory. Templates live under
scripts/scaffold/templates/backend/ and mirror the repository layout; both file paths
and file contents carry the placeholders below, which are replaced verbatim (no template
engine):

    __MODULE_ID__       toolbox-talks     module id, URL segment, changelog directory
    __MODULE_PKG__      toolboxtalks      Java package segment (id without hyphens)
    __MODULE_PASCAL__   ToolboxTalks      class-name prefix
    __MODULE_SNAKE__    toolbox_talks     table-name prefix
    __MODULE_UPPER__    TOOLBOX_TALKS     constant suffix
    __FEATURE_KEY__     MODULE_TOOLBOX_TALKS
    __MODULE_NAME__     Toolbox Talks     human label

--delete removes exactly the files the same invocation would create, then any directories
left empty by that, so a throwaway module (CI's ci-probe) leaves no trace.
"""

import argparse
import os
import re
import sys
from pathlib import Path

ID_PATTERN = re.compile(r"^[a-z][a-z0-9-]*$")
ROOT = Path(__file__).resolve().parent.parent
TEMPLATES = ROOT / "scripts" / "scaffold" / "templates" / "backend"
MODULES_PKG = ROOT / "src" / "main" / "java" / "org" / "tornotron" / "echno_backend" / "modules"
CHANGELOG = ROOT / "src" / "main" / "resources" / "db" / "changelog" / "modules"


def placeholders(module_id: str, name: str) -> dict:
    words = module_id.split("-")
    pascal = "".join(w[:1].upper() + w[1:] for w in words)
    upper = "_".join(w.upper() for w in words)
    return {
        "__MODULE_ID__": module_id,
        "__MODULE_PKG__": module_id.replace("-", ""),
        "__MODULE_PASCAL__": pascal,
        "__MODULE_SNAKE__": module_id.replace("-", "_"),
        "__MODULE_UPPER__": upper,
        "__FEATURE_KEY__": "MODULE_" + upper,
        "__MODULE_NAME__": name,
    }


def substitute(text: str, mapping: dict) -> str:
    for key, value in mapping.items():
        text = text.replace(key, value)
    return text


def planned_files(mapping: dict) -> list:
    """(template path, target path) for every file under the template tree."""
    plan = []
    for template in sorted(p for p in TEMPLATES.rglob("*") if p.is_file()):
        relative = template.relative_to(TEMPLATES)
        target = ROOT / substitute(str(relative), mapping)
        plan.append((template, target))
    return plan


def fail(message: str) -> None:
    print(f"scaffold-module: {message}", file=sys.stderr)
    sys.exit(2)


def check_id(module_id: str) -> None:
    if not ID_PATTERN.match(module_id):
        fail(f"id '{module_id}' must match {ID_PATTERN.pattern}")
    if not TEMPLATES.is_dir():
        fail(f"template directory missing: {TEMPLATES}")


def generate(module_id: str, name: str, dry_run: bool) -> None:
    check_id(module_id)
    mapping = placeholders(module_id, name)
    package_dir = MODULES_PKG / mapping["__MODULE_PKG__"]
    changelog_dir = CHANGELOG / module_id
    for existing in (package_dir, changelog_dir):
        if existing.exists():
            fail(f"'{module_id}' already exists: {existing.relative_to(ROOT)}")
    plan = planned_files(mapping)
    clashes = [t for _, t in plan if t.exists()]
    if clashes:
        fail("would overwrite: " + ", ".join(str(t.relative_to(ROOT)) for t in clashes))
    for template, target in plan:
        print(f"{'would write' if dry_run else 'write'}  {target.relative_to(ROOT)}")
        if dry_run:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(substitute(template.read_text(encoding="utf-8"), mapping), encoding="utf-8")
    if not dry_run:
        print(f"\nModule '{module_id}' ({name}) generated: {len(plan)} files, feature key "
              f"{mapping['__FEATURE_KEY__']}, kill switch echno.modules.{module_id}.enabled.")
        print("Next: ./gradlew compileJava, then the tests under src/test/java/.../modules/"
              f"{mapping['__MODULE_PKG__']}/, then ./gradlew openApiSnapshot -PupdateOpenApiSnapshot.")


def delete(module_id: str) -> None:
    check_id(module_id)
    mapping = placeholders(module_id, module_id)
    removed = 0
    for _, target in planned_files(mapping):
        if target.exists():
            target.unlink()
            removed += 1
            print(f"delete  {target.relative_to(ROOT)}")
            parent = target.parent
            while parent != ROOT and parent.is_dir() and not any(parent.iterdir()):
                parent.rmdir()
                parent = parent.parent
    print(f"\nRemoved {removed} generated files for '{module_id}'.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("id", help="module id, for example toolbox-talks")
    parser.add_argument("--name", help="human label; defaults to the id in title case")
    parser.add_argument("--dry-run", action="store_true", help="list the files without writing them")
    parser.add_argument("--delete", action="store_true", help="remove the files this scaffold generated")
    args = parser.parse_args()
    if args.delete:
        delete(args.id)
        return
    name = args.name or " ".join(w.capitalize() for w in args.id.split("-"))
    generate(args.id, name, args.dry_run)


if __name__ == "__main__":
    main()
