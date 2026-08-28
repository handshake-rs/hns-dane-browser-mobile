#!/usr/bin/env python3
"""Checkpointed bulk TRANSFER/FINALIZE runner for an HSD wallet HTTP server."""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import decimal
import getpass
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


MAX_NAMES = 10_000
MAX_NAME_FILE_BYTES = 1_048_576
MAX_BASE_UNITS = 18_446_744_073_709_551_615
NAME_RE = re.compile(r"[a-z0-9](?:[a-z0-9_-]{0,61}[a-z0-9])?\Z")
TXID_RE = re.compile(r"[0-9a-f]{64}\Z")
SCHEMA_VERSION = 1


class ToolError(Exception):
    pass


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def parse_hns(value: str) -> int:
    try:
        amount = decimal.Decimal(value)
    except decimal.InvalidOperation as error:
        raise ToolError(f"invalid HNS amount: {value}") from error
    if not amount.is_finite() or amount <= 0:
        raise ToolError("fee values must be positive finite HNS amounts")
    base = amount * decimal.Decimal(1_000_000)
    if base != base.to_integral_value() or base > MAX_BASE_UNITS:
        raise ToolError("fee values support at most 6 decimal places and must fit u64")
    return int(base)


def read_names(path: Path) -> list[str]:
    try:
        raw = path.read_bytes()
    except OSError as error:
        raise ToolError(f"cannot read names file: {error}") from error
    if not raw or len(raw) > MAX_NAME_FILE_BYTES:
        raise ToolError("names file must contain 1 byte through 1 MiB")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ToolError("names file is not exact UTF-8") from error
    try:
        if text.startswith("["):
            parsed = json.loads(text)
            if not isinstance(parsed, list) or not all(isinstance(item, str) for item in parsed):
                raise ToolError("JSON input must be an array containing only strings")
            names = parsed
        else:
            names = text.splitlines()
    except json.JSONDecodeError as error:
        raise ToolError(f"invalid JSON names file: {error}") from error
    if not 1 <= len(names) <= MAX_NAMES:
        raise ToolError(f"names file must contain 1 through {MAX_NAMES:,} names")
    if len(set(names)) != len(names):
        raise ToolError("names file contains duplicates")
    for index, name in enumerate(names, start=1):
        if not NAME_RE.fullmatch(name):
            raise ToolError(f"name #{index} is not exact canonical ASCII: {name!r}")
    return names


def names_digest(names: list[str]) -> str:
    encoded = json.dumps(names, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    return hashlib.sha256(encoded).hexdigest()


def atomic_checkpoint(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(descriptor, stat.S_IRUSR | stat.S_IWUSR)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    except BaseException:
        try:
            os.close(descriptor)
        except OSError:
            pass
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


def load_checkpoint(path: Path) -> dict[str, Any] | None:
    if not path.exists():
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ToolError(f"checkpoint is unreadable: {error}") from error
    if not isinstance(value, dict) or value.get("schemaVersion") != SCHEMA_VERSION:
        raise ToolError("checkpoint schema is unsupported")
    return value


def request_action(
    base_url: str,
    wallet_id: str,
    api_key: str,
    action: str,
    body: dict[str, Any],
    timeout: float,
) -> dict[str, Any]:
    endpoint = (
        base_url.rstrip("/")
        + "/wallet/"
        + urllib.parse.quote(wallet_id, safe="")
        + "/"
        + action
    )
    encoded = json.dumps(body, separators=(",", ":")).encode("utf-8")
    authorization = base64.b64encode(("x:" + api_key).encode("utf-8")).decode("ascii")
    request = urllib.request.Request(
        endpoint,
        data=encoded,
        method="POST",
        headers={
            "Authorization": "Basic " + authorization,
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response_body = response.read(1_048_577)
            if len(response_body) > 1_048_576:
                raise ToolError("HSD returned an oversized response")
    except urllib.error.HTTPError as error:
        detail = error.read(4096).decode("utf-8", errors="replace")
        raise ToolError(f"HSD HTTP {error.code}: {detail}") from error
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise ToolError(f"HSD request failed: {error}") from error
    try:
        result = json.loads(response_body)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ToolError("HSD returned malformed JSON") from error
    if not isinstance(result, dict):
        raise ToolError("HSD returned a non-object transaction")
    txid = result.get("hash")
    if not isinstance(txid, str) or not TXID_RE.fullmatch(txid):
        raise ToolError("HSD response did not contain a canonical transaction hash")
    return result


def checkpoint_identity(args: argparse.Namespace, names: list[str], max_fee: int, total: int | None) -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "action": args.action,
        "walletId": args.wallet_id,
        "account": args.account,
        "recipient": args.recipient,
        "namesDigest": names_digest(names),
        "nameCount": len(names),
        "maximumFeeBaseUnits": str(max_fee),
        "hardFeeBaseUnits": str(args.hard_fee_base_units) if args.hard_fee_base_units else None,
        "maximumTotalFeeBaseUnits": str(total) if total is not None else None,
    }


def reconcile_checkpoint(
    checkpoint: dict[str, Any],
    identity: dict[str, Any],
    path: Path,
    args: argparse.Namespace,
) -> None:
    for key, expected in identity.items():
        if checkpoint.get(key) != expected:
            raise ToolError(f"checkpoint does not match current {key}")
    inflight = checkpoint.get("inflight")
    if inflight is None:
        if args.resolve_txid or args.retry_inflight:
            raise ToolError("checkpoint has no ambiguous in-flight request to resolve")
        return
    if not isinstance(inflight, dict):
        raise ToolError("checkpoint in-flight record is malformed")
    if args.resolve_txid:
        txid = args.resolve_txid.lower()
        if not TXID_RE.fullmatch(txid):
            raise ToolError("--resolve-txid must be exactly 64 lowercase hex characters")
        completed = checkpoint.setdefault("completed", [])
        completed.append({
            "index": inflight["index"],
            "name": inflight["name"],
            "txid": txid,
            "resolvedAt": utc_now(),
        })
        checkpoint["nextIndex"] = inflight["index"] + 1
        checkpoint["inflight"] = None
        checkpoint["updatedAt"] = utc_now()
        atomic_checkpoint(path, checkpoint)
        return
    if args.retry_inflight:
        checkpoint["inflight"] = None
        checkpoint["updatedAt"] = utc_now()
        atomic_checkpoint(path, checkpoint)
        print("WARNING: explicitly retrying the previously ambiguous name", file=sys.stderr)
        return
    raise ToolError(
        "checkpoint contains an ambiguous in-flight request; inspect HSD wallet history, then "
        "use --resolve-txid TXID if it broadcast or --retry-inflight if it definitely did not"
    )


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser(description=__doc__)
    value.add_argument("action", choices=("transfer", "finalize"))
    value.add_argument("--names", required=True, type=Path)
    value.add_argument("--checkpoint", required=True, type=Path)
    value.add_argument("--wallet-id", default="recovered2")
    value.add_argument("--account", default="default")
    value.add_argument("--recipient", help="required for TRANSFER and forbidden for FINALIZE")
    value.add_argument("--url", default="http://127.0.0.1:12039")
    value.add_argument("--api-key-env", default="HSD_API_KEY")
    value.add_argument("--passphrase-env", help="optional environment variable for an encrypted wallet")
    value.add_argument("--max-fee-hns", required=True)
    value.add_argument("--hard-fee-hns", help="optional exact fee, which must not exceed max fee")
    value.add_argument("--max-total-fee-hns", help="optional worst-case cap across the entire list")
    value.add_argument("--delay-seconds", type=float, default=1.0)
    value.add_argument("--timeout-seconds", type=float, default=30.0)
    value.add_argument("--execute", action="store_true", help="broadcast; omission is a plan-only dry run")
    resolution = value.add_mutually_exclusive_group()
    resolution.add_argument("--resolve-txid")
    resolution.add_argument("--retry-inflight", action="store_true")
    return value


def run(arguments: list[str] | None = None) -> int:
    args = parser().parse_args(arguments)
    if (args.action == "transfer") != bool(args.recipient):
        raise ToolError("TRANSFER requires --recipient; FINALIZE forbids it")
    if not 0 <= args.delay_seconds <= 3600 or not 1 <= args.timeout_seconds <= 600:
        raise ToolError("delay must be 0..3600 seconds and timeout must be 1..600 seconds")
    names = read_names(args.names)
    max_fee = parse_hns(args.max_fee_hns)
    args.hard_fee_base_units = parse_hns(args.hard_fee_hns) if args.hard_fee_hns else None
    if args.hard_fee_base_units and args.hard_fee_base_units > max_fee:
        raise ToolError("hard fee cannot exceed maximum fee")
    max_total = parse_hns(args.max_total_fee_hns) if args.max_total_fee_hns else None
    if max_total is not None and max_fee * len(names) > max_total:
        raise ToolError("name count times per-transaction max fee exceeds maximum total fee")

    identity = checkpoint_identity(args, names, max_fee, max_total)
    checkpoint = load_checkpoint(args.checkpoint)
    if checkpoint is None:
        checkpoint = dict(identity)
        checkpoint.update({
            "createdAt": utc_now(),
            "updatedAt": utc_now(),
            "nextIndex": 0,
            "completed": [],
            "inflight": None,
            "status": "ready",
        })
        atomic_checkpoint(args.checkpoint, checkpoint)
    reconcile_checkpoint(checkpoint, identity, args.checkpoint, args)

    next_index = checkpoint.get("nextIndex")
    if not isinstance(next_index, int) or not 0 <= next_index <= len(names):
        raise ToolError("checkpoint next index is malformed")
    print(
        f"{args.action.upper()} {len(names):,} names from index {next_index:,}; "
        f"max fee {max_fee} base units each; checkpoint {args.checkpoint}"
    )
    if not args.execute:
        print("Plan only: add --execute to broadcast. The checkpoint has been initialized/validated.")
        return 0

    api_key = os.environ.get(args.api_key_env)
    if api_key is None:
        api_key = getpass.getpass(f"{args.api_key_env} (input hidden): ")
    if not api_key:
        raise ToolError("HSD API key is empty")
    passphrase = None
    if args.passphrase_env:
        passphrase = os.environ.get(args.passphrase_env)
        if passphrase is None:
            passphrase = getpass.getpass(f"{args.passphrase_env} (input hidden): ")

    endpoint_action = "transfer" if args.action == "transfer" else "finalize"
    for index in range(next_index, len(names)):
        name = names[index]
        checkpoint["inflight"] = {"index": index, "name": name, "startedAt": utc_now()}
        checkpoint["status"] = "inflight"
        checkpoint["updatedAt"] = utc_now()
        atomic_checkpoint(args.checkpoint, checkpoint)
        body: dict[str, Any] = {
            "name": name,
            "account": args.account,
            "broadcast": True,
            "sign": True,
            "maxFee": max_fee,
        }
        if args.recipient:
            body["address"] = args.recipient
        if args.hard_fee_base_units:
            body["hardFee"] = args.hard_fee_base_units
        if passphrase is not None:
            body["passphrase"] = passphrase
        try:
            transaction = request_action(
                args.url, args.wallet_id, api_key, endpoint_action, body, args.timeout_seconds
            )
        except ToolError as error:
            checkpoint["status"] = "ambiguous"
            checkpoint["lastError"] = str(error)
            checkpoint["updatedAt"] = utc_now()
            atomic_checkpoint(args.checkpoint, checkpoint)
            raise ToolError(
                f"stopped at {name!r}; the request is retained as ambiguous to prevent an "
                "automatic duplicate: {error}"
            ) from error
        txid = transaction["hash"]
        checkpoint["completed"].append({
            "index": index,
            "name": name,
            "txid": txid,
            "broadcastAt": utc_now(),
        })
        checkpoint["nextIndex"] = index + 1
        checkpoint["inflight"] = None
        checkpoint["status"] = "running" if index + 1 < len(names) else "complete"
        checkpoint.pop("lastError", None)
        checkpoint["updatedAt"] = utc_now()
        atomic_checkpoint(args.checkpoint, checkpoint)
        print(f"[{index + 1}/{len(names)}] {name}: {txid}", flush=True)
        if index + 1 < len(names) and args.delay_seconds:
            time.sleep(args.delay_seconds)
    return 0


def main() -> int:
    try:
        return run()
    except (ToolError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
