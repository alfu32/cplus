#!/usr/bin/env python3
"""Check that a packaged cpc launcher speaks clean initialize/shutdown LSP frames."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
from queue import Empty, Queue


def frame(message: dict) -> bytes:
    payload = json.dumps(message, separators=(",", ":")).encode("utf-8")
    return b"Content-Length: " + str(len(payload)).encode("ascii") + b"\r\n\r\n" + payload


def read_frame(stream) -> dict:
    headers = bytearray()
    while b"\r\n\r\n" not in headers:
        byte = stream.read(1)
        if not byte:
            raise RuntimeError("language server closed stdout before a complete LSP header")
        headers.extend(byte)
        if len(headers) > 16 * 1024:
            raise RuntimeError("LSP header exceeds the smoke-test limit")
    header, _, remainder = bytes(headers).partition(b"\r\n\r\n")
    content_length = None
    for line in header.decode("ascii").split("\r\n"):
        name, separator, value = line.partition(":")
        if separator and name.lower() == "content-length":
            content_length = int(value.strip())
    if content_length is None:
        raise RuntimeError("LSP response has no Content-Length header")
    body = bytearray(remainder)
    while len(body) < content_length:
        chunk = stream.read(content_length - len(body))
        if not chunk:
            raise RuntimeError("language server closed stdout in an LSP body")
        body.extend(chunk)
    return json.loads(bytes(body[:content_length]))


def read_frame_with_timeout(process: subprocess.Popen[bytes], timeout: float) -> dict:
    """Read one frame without allowing a broken launcher to hang CI forever."""
    assert process.stdout is not None
    result: Queue[tuple[str, object]] = Queue(maxsize=1)

    def reader() -> None:
        try:
            result.put(("ok", read_frame(process.stdout)))
        except BaseException as error:  # propagate parser/pipe failures to caller
            result.put(("error", error))

    threading.Thread(target=reader, name="lsp-frame-reader", daemon=True).start()
    try:
        kind, value = result.get(timeout=timeout)
    except Empty as error:
        command = " ".join(process.args if isinstance(process.args, list) else [str(process.args)])
        raise TimeoutError(
            f"timed out after {timeout:.1f}s waiting for LSP frame from {command}; "
            f"process_returncode={process.poll()}"
        ) from error
    if kind == "error":
        raise value  # type: ignore[misc]
    return value  # type: ignore[return-value]


def command_for_launcher(launcher: Path) -> list[str]:
    if launcher.suffix.lower() == ".cmd":
        return [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", str(launcher), "lsp"]
    if launcher.suffix.lower() in {".sh", ".zsh"}:
        return ["bash", str(launcher), "lsp"]
    return [str(launcher), "lsp"]


def main() -> int:
    parser = argparse.ArgumentParser()
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--launcher", type=Path)
    source.add_argument("--jar", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()

    command = ["java", "-jar", str(args.jar), "lsp"] if args.jar else command_for_launcher(args.launcher)
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert process.stdin is not None and process.stdout is not None
    failure: BaseException | None = None
    stderr = ""
    try:
        process.stdin.write(frame({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}}))
        process.stdin.flush()
        initialize = read_frame_with_timeout(process, args.timeout)
        if initialize.get("id") != 1 or "result" not in initialize:
            raise RuntimeError(f"unexpected initialize response: {initialize}")

        process.stdin.write(frame({"jsonrpc": "2.0", "method": "initialized", "params": {}}))
        process.stdin.write(frame({"jsonrpc": "2.0", "id": 2, "method": "shutdown", "params": None}))
        process.stdin.write(frame({"jsonrpc": "2.0", "method": "exit", "params": None}))
        process.stdin.flush()
        shutdown = read_frame_with_timeout(process, args.timeout)
        if shutdown.get("id") != 2 or shutdown.get("result", object()) is not None:
            raise RuntimeError(f"unexpected shutdown response: {shutdown}")
        process.wait(timeout=args.timeout)
    except BaseException as error:
        failure = error
    finally:
        if process.poll() is None:
            process.kill()
        try:
            stderr = process.communicate(timeout=args.timeout)[1].decode("utf-8", errors="replace").strip()
        except subprocess.TimeoutExpired:
            stderr = ""

    if failure is not None:
        raise RuntimeError(f"LSP smoke failed: {failure}; stderr={stderr!r}") from failure
    if process.returncode not in (0, None):
        raise RuntimeError(f"LSP process exited with code {process.returncode}; stderr={stderr!r}")

    report = f"launcher={' '.join(command)}\ninitialize=ok\nshutdown=ok\nexit_code={process.returncode}\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report, encoding="utf-8")
    print(report, end="")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # noqa: BLE001 - concise CI failure output
        print(f"LSP smoke failed: {error}", file=sys.stderr)
        raise
