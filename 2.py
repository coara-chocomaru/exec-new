#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
Android 5.1 / API 22
Recursive ODEX -> deoptimized DEX -> SMALI -> DEX

Directory layout:

    ./odex2dex_android51.py
    ./framework/
    ./priv-app/
    ./app/
    ./tools/
        oat2dex.jar
        baksmali.jar
        smali.jar

The script recursively searches:

    framework/**/*.odex
    priv-app/**/*.odex
    app/**/*.odex

and automatically:

    1. Finds boot.oat
    2. Deoptimizes boot.oat with oat2dex
    3. Uses the generated boot class information
    4. Deoptimizes every .odex with oat2dex
    5. Converts the resulting DEX to SMALI with baksmali
    6. Reassembles SMALI into a new DEX with smali
    7. Validates the final DEX
    8. Writes a JSON report

NO RAW BYTE CUTTING IS USED.

The original framework/app/priv-app files are never modified.
"""

from __future__ import print_function

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zlib

from pathlib import Path


# ============================================================================
# GLOBAL CONFIGURATION
# ============================================================================

SCRIPT_DIR = Path(__file__).resolve().parent

DEFAULT_API = 22

DEFAULT_OUTPUT = SCRIPT_DIR / "deodex_out"

DEFAULT_FRAMEWORK = SCRIPT_DIR / "framework"

DEFAULT_TOOLS = SCRIPT_DIR / "tools"

DEFAULT_SEARCH_DIRS = [
    SCRIPT_DIR / "framework",
    SCRIPT_DIR / "priv-app",
    SCRIPT_DIR / "app",
]

JAVA = "java"

JAVA_XMX = "1024m"

COMMAND_TIMEOUT = 1800


# ============================================================================
# LOGGING
# ============================================================================

def log(message):
    print(message, flush=True)


def info(message):
    log("[INFO] " + str(message))


def step(message):
    log("")
    log("[STEP] " + str(message))


def ok(message):
    log("[ OK ] " + str(message))


def warn(message):
    log("[WARN] " + str(message))


def error(message):
    log("[FAIL] " + str(message))


# ============================================================================
# BASIC FILE HELPERS
# ============================================================================

def mkdir(path):
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True)
    return path


def remove_tree(path):
    path = Path(path)

    if path.exists():
        shutil.rmtree(str(path))

    path.mkdir(parents=True, exist_ok=True)


def copy_tree(src, dst):
    src = Path(src)
    dst = Path(dst)

    if dst.exists():
        shutil.rmtree(str(dst))

    shutil.copytree(str(src), str(dst))


def is_file(path):
    return Path(path).is_file()


def is_dir(path):
    return Path(path).is_dir()


def relative_to_root(path, root):
    path = Path(path).resolve()
    root = Path(root).resolve()

    try:
        return path.relative_to(root)
    except ValueError:
        return Path(path.name)


# ============================================================================
# JAVA
# ============================================================================

def java_base(java_bin):
    command = [java_bin]

    if JAVA_XMX:
        command.append("-Xmx" + JAVA_XMX)

    return command


def check_java(java_bin):
    if os.path.sep in java_bin:
        if not Path(java_bin).is_file():
            raise RuntimeError(
                "Java executable not found: " + java_bin
            )
    else:
        if shutil.which(java_bin) is None:
            raise RuntimeError(
                "Java executable not found in PATH: " + java_bin
            )

    result = subprocess.run(
        java_base(java_bin) + ["-version"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

    if result.returncode != 0:
        raise RuntimeError(
            "Java is not working:\n" + result.stderr
        )

    info("Java OK")


# ============================================================================
# COMMAND EXECUTION
# ============================================================================

def run_command(
    command,
    cwd=None,
    timeout=COMMAND_TIMEOUT,
):
    command = [str(x) for x in command]

    info(
        "$ " +
        " ".join(
            "'" + x.replace("'", "'\\''") + "'"
            for x in command
        )
    )

    started = time.time()

    try:
        result = subprocess.run(
            command,
            cwd=str(cwd) if cwd else None,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
        )

    except subprocess.TimeoutExpired as exc:
        elapsed = time.time() - started

        stdout = exc.stdout or ""
        stderr = exc.stderr or ""

        if isinstance(stdout, bytes):
            stdout = stdout.decode(
                "utf-8",
                errors="replace",
            )

        if isinstance(stderr, bytes):
            stderr = stderr.decode(
                "utf-8",
                errors="replace",
            )

        return {
            "command": command,
            "returncode": 124,
            "stdout": stdout,
            "stderr": stderr + "\nTIMEOUT",
            "elapsed": elapsed,
        }

    elapsed = time.time() - started

    if result.stdout:
        print(result.stdout.rstrip())

    if result.stderr:
        print(
            result.stderr.rstrip(),
            file=sys.stderr,
        )

    return {
        "command": command,
        "returncode": result.returncode,
        "stdout": result.stdout or "",
        "stderr": result.stderr or "",
        "elapsed": elapsed,
    }


# ============================================================================
# JAR DISCOVERY
# ============================================================================

def find_jar(tools, exact_names, wildcard):
    tools = Path(tools)

    for name in exact_names:
        candidate = tools / name

        if candidate.is_file():
            return candidate.resolve()

    candidates = list(
        tools.glob(wildcard)
    )

    candidates = [
        x.resolve()
        for x in candidates
        if x.is_file()
    ]

    if not candidates:
        return None

    candidates.sort(
        key=lambda x: (
            x.stat().st_mtime,
            x.stat().st_size,
        ),
        reverse=True,
    )

    return candidates[0]


def find_tools(tools):
    tools = Path(tools)

    if not tools.is_dir():
        raise RuntimeError(
            "tools directory does not exist: " +
            str(tools)
        )

    oat2dex = find_jar(
        tools,
        [
            "oat2dex.jar",
            "oat2Dex.jar",
            "Oat2Dex.jar",
        ],
        "oat2dex*.jar",
    )

    baksmali = find_jar(
        tools,
        [
            "baksmali.jar",
        ],
        "baksmali*.jar",
    )

    smali = find_jar(
        tools,
        [
            "smali.jar",
        ],
        "smali*.jar",
    )

    if oat2dex is None:
        raise RuntimeError(
            "tools/oat2dex.jar not found"
        )

    if baksmali is None:
        raise RuntimeError(
            "tools/baksmali.jar not found"
        )

    if smali is None:
        raise RuntimeError(
            "tools/smali.jar not found"
        )

    info("oat2dex.jar  = " + str(oat2dex))
    info("baksmali.jar = " + str(baksmali))
    info("smali.jar    = " + str(smali))

    return oat2dex, baksmali, smali


# ============================================================================
# RECURSIVE DISCOVERY
# ============================================================================

def find_all_odex(search_dirs, output_dir):
    found = []
    seen = set()

    output_dir = Path(output_dir).resolve()

    for directory in search_dirs:
        directory = Path(directory)

        if not directory.is_dir():
            warn(
                "Directory not found, skipping: " +
                str(directory)
            )
            continue

        info(
            "Recursive search: " +
            str(directory)
        )

        for path in directory.rglob("*.odex"):
            if not path.is_file():
                continue

            resolved = path.resolve()

            try:
                resolved.relative_to(
                    output_dir
                )
                continue
            except ValueError:
                pass

            key = str(resolved)

            if key in seen:
                continue

            seen.add(key)
            found.append(resolved)

    found.sort(
        key=lambda x: str(x)
    )

    return found


def find_all_boot_oat(framework):
    framework = Path(framework)

    if not framework.is_dir():
        return []

    result = []

    for path in framework.rglob("boot.oat"):
        if path.is_file():
            result.append(path.resolve())

    result = sorted(
        set(result),
        key=lambda x: str(x),
    )

    return result


# ============================================================================
# ARCHITECTURE DETECTION
# ============================================================================

def detect_arch(path):
    parts = [
        p.lower()
        for p in Path(path).parts
    ]

    if "arm64" in parts:
        return "arm64"

    if "arm" in parts:
        return "arm"

    if "x86_64" in parts:
        return "x86_64"

    if "x86" in parts:
        return "x86"

    return None


def boot_matches_arch(boot, arch):
    if arch is None:
        return True

    parts = {
        p.lower()
        for p in Path(boot).parts
    }

    return arch in parts


# ============================================================================
# BOOT.OAT SELECTION
# ============================================================================

def select_boot_oat(
    odex,
    framework,
    explicit_boot=None,
):
    if explicit_boot:
        explicit_boot = Path(
            explicit_boot
        ).resolve()

        if not explicit_boot.is_file():
            raise RuntimeError(
                "Specified boot.oat does not exist: " +
                str(explicit_boot)
            )

        return explicit_boot

    boots = find_all_boot_oat(
        framework
    )

    if not boots:
        raise RuntimeError(
            "No boot.oat found under " +
            str(framework)
        )

    if len(boots) == 1:
        return boots[0]

    arch = detect_arch(odex)

    if arch:
        matches = [
            boot
            for boot in boots
            if boot_matches_arch(
                boot,
                arch,
            )
        ]

        if len(matches) == 1:
            return matches[0]

        if matches:
            matches.sort(
                key=lambda x: (
                    len(x.parts),
                    str(x),
                )
            )

            return matches[0]

    # Common Android 5.1 layouts may place ODEX under:
    #
    #   framework/oat/arm/
    #   framework/oat/arm64/
    #
    # or:
    #
    #   framework/arm/
    #   framework/arm64/
    #
    # Try matching any architecture directory appearing in the ODEX path.

    odex_parts = {
        p.lower()
        for p in Path(odex).parts
    }

    scored = []

    for boot in boots:
        boot_parts = {
            p.lower()
            for p in Path(boot).parts
        }

        score = 0

        for candidate in (
            "arm64",
            "arm",
            "x86_64",
            "x86",
        ):
            if candidate in odex_parts:
                if candidate in boot_parts:
                    score += 100

        for part in Path(odex).parts:
            if part in Path(boot).parts:
                score += 1

        scored.append(
            (
                score,
                boot,
            )
        )

    scored.sort(
        key=lambda x: (
            -x[0],
            str(x[1]),
        )
    )

    if scored:
        return scored[0][1]

    raise RuntimeError(
        "Could not determine matching boot.oat"
    )


# ============================================================================
# OAT2DEX HELP
# ============================================================================

def oat2dex_help(
    java,
    oat2dex,
):
    result = run_command(
        java_base(java)
        + [
            "-jar",
            str(oat2dex),
            "--help",
        ],
        timeout=60,
    )

    return result


def has_output_option(help_result):
    text = (
        help_result.get("stdout", "")
        + "\n"
        + help_result.get("stderr", "")
    )

    if re.search(
        r"(?m)(^|\s)-o(\s|,|$)",
        text,
    ):
        return True

    if "--output" in text:
        return True

    if "Output folder" in text:
        return True

    return False


# ============================================================================
# DEX SEARCH
# ============================================================================

def find_dex_files(directory):
    directory = Path(directory)

    if not directory.exists():
        return []

    result = []

    for path in directory.rglob("*.dex"):
        if path.is_file():
            result.append(
                path.resolve()
            )

    result.sort(
        key=lambda x: str(x)
    )

    return result


# ============================================================================
# BOOT DEOPTIMIZATION
# ============================================================================

def prepare_boot(
    java,
    oat2dex,
    boot_oat,
    output,
    api,
    use_output_option,
):
    """
    Correct SmaliEx boot preparation:

        boot.oat
             |
             v
        oat2dex boot
             |
             +--> odex/
             +--> dex/

    The generated boot class output is then passed to the application
    deoptimization step.
    """

    output = Path(output)

    if output.exists():
        shutil.rmtree(
            str(output)
        )

    output.mkdir(
        parents=True,
        exist_ok=True,
    )

    step(
        "BOOT DEOPTIMIZATION"
    )

    if use_output_option:
        command = (
            java_base(java)
            + [
                "-jar",
                str(oat2dex),
                "-a",
                str(api),
                "-o",
                str(output),
                "boot",
                str(boot_oat),
            ]
        )

        result = run_command(
            command
        )

    else:
        command = (
            java_base(java)
            + [
                "-jar",
                str(oat2dex),
                "boot",
                str(boot_oat),
            ]
        )

        result = run_command(
            command,
            cwd=output,
        )

    if result["returncode"] != 0:
        raise RuntimeError(
            "boot.oat deoptimization failed\n" +
            result["stderr"]
        )

    dex_files = find_dex_files(
        output
    )

    if not dex_files:
        raise RuntimeError(
            "oat2dex boot completed but no boot DEX was produced"
        )

    ok(
        "boot.oat -> " +
        str(len(dex_files)) +
        " boot DEX file(s)"
    )

    return result, output


# ============================================================================
# ODEX DEOPTIMIZATION
# ============================================================================

def deoptimize_odex(
    java,
    oat2dex,
    odex,
    boot_output,
    output,
    api,
    use_output_option,
):
    """
    Application/framework ODEX:

        .odex
          |
          | matching boot class folder
          v
        oat2dex
          |
          v
        normal/deoptimized .dex
    """

    output = Path(output)

    if output.exists():
        shutil.rmtree(
            str(output)
        )

    output.mkdir(
        parents=True,
        exist_ok=True,
    )

    step(
        "ODEX DEOPTIMIZATION: " +
        str(odex)
    )

    if use_output_option:
        command = (
            java_base(java)
            + [
                "-jar",
                str(oat2dex),
                "-a",
                str(api),
                "-o",
                str(output),
                str(odex),
                str(boot_output),
            ]
        )

        result = run_command(
            command
        )

    else:
        command = (
            java_base(java)
            + [
                "-jar",
                str(oat2dex),
                str(odex),
                str(boot_output),
            ]
        )

        result = run_command(
            command,
            cwd=output,
        )

    if result["returncode"] != 0:
        raise RuntimeError(
            "ODEX deoptimization failed:\n" +
            result["stderr"]
        )

    dex_files = find_dex_files(
        output
    )

    if not dex_files:
        raise RuntimeError(
            "oat2dex completed but no DEX was produced:\n" +
            str(output)
        )

    ok(
        "ODEX -> " +
        str(len(dex_files)) +
        " deoptimized DEX file(s)"
    )

    return result, dex_files


# ============================================================================
# BAKSMALI
# ============================================================================

def dex_to_smali(
    java,
    baksmali,
    dex,
    output,
    api,
):
    """
    IMPORTANT:

    At this point oat2dex has already deoptimized the ODEX.

    Therefore we disassemble the resulting normal DEX.

        deoptimized.dex
                |
                v
            baksmali
                |
                v
             SMALI
    """

    output = Path(output)

    if output.exists():
        shutil.rmtree(
            str(output)
        )

    output.mkdir(
        parents=True,
        exist_ok=True,
    )

    step(
        "DEX -> SMALI: " +
        str(dex)
    )

    command = (
        java_base(java)
        + [
            "-jar",
            str(baksmali),
            "-a",
            str(api),
            "-o",
            str(output),
            str(dex),
        ]
    )

    result = run_command(
        command
    )

    if result["returncode"] != 0:
        raise RuntimeError(
            "baksmali failed:\n" +
            result["stderr"]
        )

    smali_files = list(
        output.rglob("*.smali")
    )

    if not smali_files:
        raise RuntimeError(
            "baksmali finished but no .smali files were generated"
        )

    ok(
        "DEX -> SMALI: " +
        str(len(smali_files)) +
        " file(s)"
    )

    return result, smali_files


# ============================================================================
# SMALI -> DEX
# ============================================================================

def smali_to_dex(
    java,
    smali,
    smali_directory,
    output_dex,
    api,
):
    """
        SMALI
          |
          v
        smali
          |
          v
        final classes.dex
    """

    smali_directory = Path(
        smali_directory
    )

    output_dex = Path(
        output_dex
    )

    output_dex.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    if output_dex.exists():
        output_dex.unlink()

    step(
        "SMALI -> DEX: " +
        str(output_dex)
    )

    command = (
        java_base(java)
        + [
            "-jar",
            str(smali),
            "-a",
            str(api),
            str(smali_directory),
            "-o",
            str(output_dex),
        ]
    )

    result = run_command(
        command
    )

    if result["returncode"] != 0:
        raise RuntimeError(
            "smali assembly failed:\n" +
            result["stderr"]
        )

    if not output_dex.is_file():
        raise RuntimeError(
            "smali completed but final DEX was not created"
        )

    ok(
        "SMALI -> DEX: " +
        str(output_dex)
    )

    return result


# ============================================================================
# DEX VALIDATION
# ============================================================================

def validate_dex(path):
    """
    Validate the DEX header, file size, Adler32 and SHA-1 signature.

    DEX:

        0x00  magic
        0x08  checksum
        0x0c  SHA-1 signature
        0x20  file_size
        0x24  header_size
        ...
    """

    path = Path(path)

    data = path.read_bytes()

    if len(data) < 112:
        raise RuntimeError(
            "DEX is smaller than a DEX header: " +
            str(path)
        )

    magic = data[0:8]

    if not magic.startswith(
        b"dex\n"
    ):
        raise RuntimeError(
            "Invalid DEX magic: " +
            repr(magic)
        )

    stored_checksum = struct.unpack_from(
        "<I",
        data,
        8,
    )[0]

    stored_sha1 = data[
        12:32
    ]

    file_size = struct.unpack_from(
        "<I",
        data,
        32,
    )[0]

    header_size = struct.unpack_from(
        "<I",
        data,
        36,
    )[0]

    string_ids_size = struct.unpack_from(
        "<I",
        data,
        56,
    )[0]

    type_ids_size = struct.unpack_from(
        "<I",
        data,
        64,
    )[0]

    proto_ids_size = struct.unpack_from(
        "<I",
        data,
        72,
    )[0]

    field_ids_size = struct.unpack_from(
        "<I",
        data,
        80,
    )[0]

    method_ids_size = struct.unpack_from(
        "<I",
        data,
        88,
    )[0]

    class_defs_size = struct.unpack_from(
        "<I",
        data,
        96,
    )[0]

    actual_file_size = len(data)

    computed_sha1 = hashlib.sha1(
        data[32:]
    ).digest()

    computed_checksum = (
        zlib.adler32(
            data[12:]
        )
        & 0xffffffff
    )

    result = {
        "path": str(path),
        "actual_size": actual_file_size,
        "header_file_size": file_size,
        "header_size": header_size,
        "magic": magic.decode(
            "latin1"
        ),
        "version": magic[
            4:7
        ].decode(
            "ascii",
            errors="replace",
        ),
        "checksum_stored": (
            stored_checksum
        ),
        "checksum_computed": (
            computed_checksum
        ),
        "checksum_ok": (
            stored_checksum
            == computed_checksum
        ),
        "sha1_stored": (
            stored_sha1.hex()
        ),
        "sha1_computed": (
            computed_sha1.hex()
        ),
        "sha1_ok": (
            stored_sha1
            == computed_sha1
        ),
        "string_ids_size": (
            string_ids_size
        ),
        "type_ids_size": (
            type_ids_size
        ),
        "proto_ids_size": (
            proto_ids_size
        ),
        "field_ids_size": (
            field_ids_size
        ),
        "method_ids_size": (
            method_ids_size
        ),
        "class_defs_size": (
            class_defs_size
        ),
    }

    if file_size != actual_file_size:
        raise RuntimeError(
            "DEX file_size mismatch: "
            "header=" +
            str(file_size) +
            " actual=" +
            str(actual_file_size)
        )

    return result


# ============================================================================
# FILE HASH
# ============================================================================

def sha256_file(path):
    h = hashlib.sha256()

    with open(
        path,
        "rb",
    ) as fp:
        while True:
            block = fp.read(
                1024 * 1024
            )

            if not block:
                break

            h.update(block)

    return h.hexdigest()


# ============================================================================
# RESULT DIRECTORY
# ============================================================================

def make_output_base(
    odex,
    root,
    output,
):
    relative = relative_to_root(
        odex,
        root,
    )

    """
    Example:

        root/
          priv-app/Foo/Foo.odex

    becomes:

        output/
          priv-app/Foo/
              intermediate/
              smali/
              dex/
    """

    parent = (
        output /
        relative.parent
    )

    if relative.parent == Path("."):
        parent = (
            output /
            relative.stem
        )

    parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    return parent


# ============================================================================
# ONE ODEX
# ============================================================================

def process_odex(
    odex,
    root,
    framework,
    output,
    java,
    oat2dex,
    baksmali,
    smali,
    api,
    boot_cache,
    use_output_option,
):
    odex = Path(odex)

    relative = relative_to_root(
        odex,
        root,
    )

    result = {
        "source": str(odex),
        "relative": str(relative),
        "success": False,
        "architecture": detect_arch(
            odex
        ),
        "boot_oat": None,
        "intermediate_dex": [],
        "smali_directories": [],
        "final_dex": [],
        "validation": [],
        "commands": [],
        "errors": [],
    }

    info("")
    info("=" * 90)
    info(
        "PROCESS " +
        str(relative)
    )
    info("=" * 90)

    try:
        base = make_output_base(
            odex,
            root,
            output,
        )

        work = base / ".work"

        intermediate = (
            base / "intermediate"
        )

        smali_root = (
            base / "smali"
        )

        dex_root = (
            base / "dex"
        )

        work.mkdir(
            parents=True,
            exist_ok=True,
        )

        intermediate.mkdir(
            parents=True,
            exist_ok=True,
        )

        smali_root.mkdir(
            parents=True,
            exist_ok=True,
        )

        dex_root.mkdir(
            parents=True,
            exist_ok=True,
        )

        # ---------------------------------------------------------------
        # Find matching boot.oat
        # ---------------------------------------------------------------

        step(
            "Finding matching boot.oat"
        )

        boot_oat = select_boot_oat(
            odex,
            framework,
        )

        result["boot_oat"] = str(
            boot_oat
        )

        info(
            "Architecture: " +
            str(
                result["architecture"]
            )
        )

        info(
            "boot.oat: " +
            str(boot_oat)
        )

        # ---------------------------------------------------------------
        # Prepare boot class data
        # ---------------------------------------------------------------

        cache_key = str(
            boot_oat
        )

        if cache_key in boot_cache:
            boot_output = boot_cache[
                cache_key
            ]

            info(
                "Reusing boot.oat deoptimization cache"
            )

        else:
            boot_output = (
                output /
                ".boot_cache" /
                hashlib.sha1(
                    cache_key.encode(
                        "utf-8"
                    )
                ).hexdigest()
            )

            boot_result, boot_output = (
                prepare_boot(
                    java=java,
                    oat2dex=oat2dex,
                    boot_oat=boot_oat,
                    output=boot_output,
                    api=api,
                    use_output_option=(
                        use_output_option
                    ),
                )
            )

            result[
                "commands"
            ].append(
                boot_result
            )

            boot_cache[
                cache_key
            ] = boot_output

        # ---------------------------------------------------------------
        # ODEX -> deoptimized DEX
        # ---------------------------------------------------------------

        oat_result, dex_files = (
            deoptimize_odex(
                java=java,
                oat2dex=oat2dex,
                odex=odex,
                boot_output=boot_output,
                output=intermediate,
                api=api,
                use_output_option=(
                    use_output_option
                ),
            )
        )

        result[
            "commands"
        ].append(
            oat_result
        )

        result[
            "intermediate_dex"
        ] = [
            str(x)
            for x in dex_files
        ]

        # ---------------------------------------------------------------
        # Every generated DEX
        # ---------------------------------------------------------------

        for index, dex in enumerate(
            dex_files
        ):
            if len(dex_files) == 1:
                smali_dir = (
                    smali_root /
                    "smali"
                )

                final_name = (
                    odex.stem +
                    ".dex"
                )

            else:
                smali_dir = (
                    smali_root /
                    (
                        "smali_" +
                        str(index + 1)
                    )
                )

                if index == 0:
                    final_name = (
                        odex.stem +
                        ".dex"
                    )
                else:
                    final_name = (
                        odex.stem +
                        "_classes" +
                        str(index + 1) +
                        ".dex"
                    )

            # -----------------------------------------------------------
            # DEX -> SMALI
            # -----------------------------------------------------------

            bak_result, smali_files = (
                dex_to_smali(
                    java=java,
                    baksmali=baksmali,
                    dex=dex,
                    output=smali_dir,
                    api=api,
                )
            )

            result[
                "commands"
            ].append(
                bak_result
            )

            result[
                "smali_directories"
            ].append(
                str(smali_dir)
            )

            # -----------------------------------------------------------
            # SMALI -> DEX
            # -----------------------------------------------------------

            final_dex = (
                dex_root /
                final_name
            )

            smali_result = (
                smali_to_dex(
                    java=java,
                    smali=smali,
                    smali_directory=smali_dir,
                    output_dex=final_dex,
                    api=api,
                )
            )

            result[
                "commands"
            ].append(
                smali_result
            )

            # -----------------------------------------------------------
            # Validate final DEX
            # -----------------------------------------------------------

            validation = (
                validate_dex(
                    final_dex
                )
            )

            validation[
                "sha256"
            ] = sha256_file(
                final_dex
            )

            result[
                "validation"
            ].append(
                validation
            )

            result[
                "final_dex"
            ].append(
                str(final_dex)
            )

            ok(
                "VALID DEX: " +
                str(final_dex)
            )

            info(
                "  size       = " +
                str(
                    validation[
                        "actual_size"
                    ]
                )
            )

            info(
                "  checksum   = " +
                str(
                    validation[
                        "checksum_ok"
                    ]
                )
            )

            info(
                "  sha1       = " +
                str(
                    validation[
                        "sha1_ok"
                    ]
                )
            )

            info(
                "  class_defs = " +
                str(
                    validation[
                        "class_defs_size"
                    ]
                )
            )

        result[
            "success"
        ] = True

        report = (
            base /
            "report.json"
        )

        report.write_text(
            json.dumps(
                result,
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )

        ok(
            "COMPLETED: " +
            str(relative)
        )

    except Exception as exc:
        result[
            "errors"
        ].append(
            str(exc)
        )

        error(
            str(relative) +
            ": " +
            str(exc)
        )

        try:
            base = make_output_base(
                odex,
                root,
                output,
            )

            report = (
                base /
                "report.json"
            )

            report.write_text(
                json.dumps(
                    result,
                    ensure_ascii=False,
                    indent=2,
                ),
                encoding="utf-8",
            )

        except Exception:
            pass

    return result


# ============================================================================
# MAIN
# ============================================================================

def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Android 5.1 recursive ODEX "
            "deodex -> SMALI -> DEX"
        )
    )

    parser.add_argument(
        "--root",
        default=str(
            SCRIPT_DIR
        ),
        help=(
            "Root containing framework/, "
            "priv-app/, app/, tools/"
        ),
    )

    parser.add_argument(
        "--framework",
        default=None,
        help=(
            "framework directory. "
            "Default: <root>/framework"
        ),
    )

    parser.add_argument(
        "--output",
        default=str(
            DEFAULT_OUTPUT
        ),
        help=(
            "Output directory. "
            "Default: ./deodex_out"
        ),
    )

    parser.add_argument(
        "--tools",
        default=str(
            DEFAULT_TOOLS
        ),
        help=(
            "Tools directory. "
            "Default: ./tools"
        ),
    )

    parser.add_argument(
        "--api",
        type=int,
        default=DEFAULT_API,
        help=(
            "Android API level. "
            "Android 5.1 = 22"
        ),
    )

    parser.add_argument(
        "--java",
        default=JAVA,
        help="Java executable",
    )

    parser.add_argument(
        "--boot-oat",
        default=None,
        help=(
            "Optional explicit boot.oat"
        ),
    )

    return parser.parse_args()


def main():
    args = parse_args()

    root = Path(
        args.root
    ).resolve()

    framework = (
        Path(
            args.framework
        ).resolve()
        if args.framework
        else (
            root /
            "framework"
        ).resolve()
    )

    output = Path(
        args.output
    ).resolve()

    tools = Path(
        args.tools
    ).resolve()

    if not root.is_dir():
        error(
            "Root directory does not exist: " +
            str(root)
        )
        return 1

    if not framework.is_dir():
        error(
            "framework directory does not exist: " +
            str(framework)
        )
        return 1

    output.mkdir(
        parents=True,
        exist_ok=True,
    )

    info("=" * 90)
    info(
        "Android 5.1 Recursive ODEX Deodexer"
    )
    info("=" * 90)
    info(
        "Root      : " +
        str(root)
    )
    info(
        "Framework : " +
        str(framework)
    )
    info(
        "Tools     : " +
        str(tools)
    )
    info(
        "Output    : " +
        str(output)
    )
    info(
        "API       : " +
        str(args.api)
    )
    info(
        "Pipeline  : "
        "ODEX -> oat2dex -> DEX -> "
        "baksmali -> SMALI -> smali -> DEX"
    )
    info("=" * 90)

    # ---------------------------------------------------------------
    # Java
    # ---------------------------------------------------------------

    try:
        check_java(
            args.java
        )
    except Exception as exc:
        error(
            str(exc)
        )
        return 1

    # ---------------------------------------------------------------
    # Tools
    # ---------------------------------------------------------------

    try:
        (
            oat2dex,
            baksmali,
            smali,
        ) = find_tools(
            tools
        )
    except Exception as exc:
        error(
            str(exc)
        )
        return 1

    # ---------------------------------------------------------------
    # Find all ODEX
    # ---------------------------------------------------------------

    search_dirs = [
        root / "framework",
        root / "priv-app",
        root / "app",
    ]

    odex_files = find_all_odex(
        search_dirs,
        output,
    )

    info(
        "Total .odex found: " +
        str(len(odex_files))
    )

    if not odex_files:
        warn(
            "No .odex files found."
        )
        return 0

    # ---------------------------------------------------------------
    # Find boot.oat
    # ---------------------------------------------------------------

    boots = find_all_boot_oat(
        framework
    )

    info(
        "Total boot.oat found: " +
        str(len(boots))
    )

    for boot in boots:
        info(
            "  boot.oat: " +
            str(boot)
        )

    if not boots:
        error(
            "No boot.oat found under framework/"
        )
        return 1

    # ---------------------------------------------------------------
    # Determine oat2dex command style
    # ---------------------------------------------------------------

    info(
        "Checking oat2dex command line..."
    )

    help_result = oat2dex_help(
        args.java,
        oat2dex,
    )

    use_output_option = (
        has_output_option(
            help_result
        )
    )

    if use_output_option:
        info(
            "oat2dex: -o output option detected"
        )
    else:
        info(
            "oat2dex: legacy command mode"
        )

    # ---------------------------------------------------------------
    # Boot cache
    # ---------------------------------------------------------------

    boot_cache = {}

    # ---------------------------------------------------------------
    # Process every ODEX
    # ---------------------------------------------------------------

    results = []

    total = len(
        odex_files
    )

    for number, odex in enumerate(
        odex_files,
        1,
    ):
        info("")
        info(
            "#" * 90
        )
        info(
            "FILE " +
            str(number) +
            "/" +
            str(total) +
            ": " +
            str(
                relative_to_root(
                    odex,
                    root,
                )
            )
        )
        info(
            "#" * 90
        )

        result = process_odex(
            odex=odex,
            root=root,
            framework=framework,
            output=output,
            java=args.java,
            oat2dex=oat2dex,
            baksmali=baksmali,
            smali=smali,
            api=args.api,
            boot_cache=boot_cache,
            use_output_option=(
                use_output_option
            ),
        )

        results.append(
            result
        )

    # ---------------------------------------------------------------
    # Summary
    # ---------------------------------------------------------------

    succeeded = [
        x
        for x in results
        if x["success"]
    ]

    failed = [
        x
        for x in results
        if not x["success"]
    ]

    final_dex_count = sum(
        len(
            x["final_dex"]
        )
        for x in results
    )

    summary = {
        "api": args.api,
        "root": str(root),
        "framework": str(framework),
        "tools": str(tools),
        "output": str(output),
        "pipeline": (
            "ODEX -> oat2dex deoptimization -> "
            "DEX -> baksmali -> SMALI -> "
            "smali -> final DEX"
        ),
        "total_odex": len(results),
        "success": len(succeeded),
        "failed": len(failed),
        "final_dex_count": final_dex_count,
        "results": results,
    }

    summary_path = (
        output /
        "summary.json"
    )

    summary_path.write_text(
        json.dumps(
            summary,
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    # ---------------------------------------------------------------
    # Final screen
    # ---------------------------------------------------------------

    info("")
    info("=" * 90)
    info("FINISHED")
    info("=" * 90)

    info(
        "ODEx total : " +
        str(len(results))
    )

    info(
        "Success    : " +
        str(len(succeeded))
    )

    info(
        "Failed     : " +
        str(len(failed))
    )

    info(
        "Final DEX  : " +
        str(final_dex_count)
    )

    info(
        "Summary    : " +
        str(summary_path)
    )

    info("=" * 90)

    if failed:
        error(
            "Some ODEX files failed:"
        )

        for item in failed:
            error(
                "  " +
                item["source"]
            )

            for message in item[
                "errors"
            ]:
                error(
                    "      " +
                    str(message)
                )

        return 1

    ok(
        "ALL ODEX FILES PROCESSED SUCCESSFULLY"
    )

    return 0


if __name__ == "__main__":
    sys.exit(
        main()
    )
