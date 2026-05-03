#!/usr/bin/env python3
"""
End-to-end smoke driver for the lsp4logo server.

Spawns the fat-jar, walks it through one full LSP session against
samples/square.logo, and pretty-prints every response and notification the
server emits. Useful as a fast sanity check that all features still respond,
and as a "what does this LSP actually do" demonstration that doesn't require
an editor.

Usage:
    ./gradlew shadowJar
    python3 scripts/demo.py [path/to/sample.logo]

The script terminates the server cleanly via the standard `shutdown` + `exit`
JSON-RPC sequence.
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
JAR = REPO / "build" / "libs" / "lsp4logo-0.1.0.jar"
DEFAULT_SAMPLE = REPO / "samples" / "square.logo"


def frame(body: str) -> bytes:
    payload = body.encode("utf-8")
    return f"Content-Length: {len(payload)}\r\n\r\n".encode("ascii") + payload


def msg(method: str, params, id: int | None = None) -> bytes:
    m: dict = {"jsonrpc": "2.0", "method": method, "params": params}
    if id is not None:
        m["id"] = id
    return frame(json.dumps(m))


def parse_responses(data: bytes):
    i = 0
    while i < len(data):
        end = data.find(b"\r\n\r\n", i)
        if end == -1:
            break
        header = data[i:end].decode("ascii")
        length = next(
            int(line.split(":", 1)[1].strip())
            for line in header.split("\r\n")
            if line.lower().startswith("content-length:")
        )
        body_start = end + 4
        body = data[body_start : body_start + length]
        yield json.loads(body.decode("utf-8"))
        i = body_start + length


def section(title: str) -> None:
    print()
    print("=" * 70)
    print(title)
    print("=" * 70)


def main() -> int:
    if not JAR.exists():
        print(f"jar not found at {JAR}", file=sys.stderr)
        print("run `./gradlew shadowJar` first.", file=sys.stderr)
        return 1

    sample_path = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else DEFAULT_SAMPLE
    if not sample_path.exists():
        print(f"sample not found at {sample_path}", file=sys.stderr)
        return 1

    source = sample_path.read_text(encoding="utf-8")
    uri = sample_path.as_uri()

    # Build the scripted session.
    session = b"".join(
        [
            msg("initialize", {"processId": None, "rootUri": None, "capabilities": {}}, id=1),
            msg("initialized", {}),
            msg(
                "textDocument/didOpen",
                {
                    "textDocument": {
                        "uri": uri,
                        "languageId": "logo",
                        "version": 1,
                        "text": source,
                    }
                },
            ),
            # Pick a position cheaply: the start of every line is at character 0.
            # Fire definition at every line start; the meaningful one is the
            # call site of SQUARE in square.logo (line index 9 if it's the
            # default sample). Adjust as needed.
            msg(
                "textDocument/definition",
                {
                    "textDocument": {"uri": uri},
                    "position": {"line": 9, "character": 0},
                },
                id=2,
            ),
            msg(
                "textDocument/semanticTokens/full",
                {"textDocument": {"uri": uri}},
                id=3,
            ),
            msg(
                "textDocument/inlayHint",
                {
                    "textDocument": {"uri": uri},
                    "range": {
                        "start": {"line": 0, "character": 0},
                        "end": {"line": 999, "character": 0},
                    },
                },
                id=4,
            ),
            msg("shutdown", None, id=5),
            msg("exit", None),
        ]
    )

    proc = subprocess.Popen(
        ["java", "-jar", str(JAR)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    out, err = proc.communicate(input=session, timeout=15)

    section(f"sample: {sample_path.relative_to(REPO)}")
    print(source.rstrip())

    for response in parse_responses(out):
        if "method" in response:
            section(f"server notification: {response['method']}")
        else:
            section(f"server response to id={response.get('id')}")
        print(json.dumps(response, indent=2, ensure_ascii=False))

    if err:
        section("server stderr (should be empty)")
        print(err.decode("utf-8", errors="replace"))

    return 0


if __name__ == "__main__":
    sys.exit(main())
