"""Fetches official RNode firmware releases (`markqvist/RNode_Firmware`
on GitHub) and drives a full flash operation — the network/zip-handling
half of the RNode flasher. `esp32_flasher.py` owns the actual esptool
wire protocol; kept as two separate modules for the same reason
`rnode_interface.py` is already separate from `orchestrator.py` — each
owns one real, independently-testable concern.

Uses plain `urllib.request` (stdlib), matching this module's own
established convention for network calls from the Python side —
`orchestrator.py`'s TCP-hub-directory fetch already does the same,
rather than adding an HTTP client dependency to the Kotlin side for what
is, here, a small, infrequent, non-latency-sensitive fetch.

**Deliberately excludes every other firmware source Columba's own real
flasher offers** (`microReticulum`, RNode Community Edition, an
arbitrary custom `.bin` upload) — per explicit direction, this app's
flasher only ever installs the official firmware from this one real
GitHub repo.
"""

import io
import json
import urllib.request
import zipfile

RELEASES_API_URL = "https://api.github.com/repos/markqvist/RNode_Firmware/releases"
DOWNLOAD_BASE_URL = "https://github.com/markqvist/RNode_Firmware/releases/download"
ASSET_PREFIX = "rnode_firmware_"
ASSET_SUFFIX = ".zip"


def list_releases_json(limit: int = 5) -> str:
    """Real GitHub Releases API listing. Returns a JSON array,
    `[{"tag": str, "published_at": str, "boards": [str, ...]}, ...]`,
    newest first (GitHub's own default order) — or `{"error": str}` on
    any fetch failure (network down, rate-limited, etc.), so the caller
    can distinguish "no releases" from "couldn't check." Each board name
    is derived by stripping the real, fixed `rnode_firmware_`/`.zip`
    file-name convention off each release asset. Boards this flasher
    doesn't actually know how to flash (see `esp32_flasher.BOARDS` — the
    nRF52-based Heltec T114/RAK4631 boards, notably) are still listed
    rather than silently hidden — the caller decides how to present an
    unsupported board (see that module's own doc comment for why they're
    excluded from `BOARDS`), this function's only job is to report what
    the release actually contains."""
    try:
        req = urllib.request.Request(
            RELEASES_API_URL, headers={"Accept": "application/vnd.github+json"},
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            releases = json.loads(resp.read().decode("utf-8"))
    except Exception as exc:
        return json.dumps({"error": str(exc)})

    result = []
    for rel in releases[:limit]:
        boards = []
        for asset in rel.get("assets", []):
            name = asset.get("name", "")
            if name.startswith(ASSET_PREFIX) and name.endswith(ASSET_SUFFIX):
                boards.append(name[len(ASSET_PREFIX):-len(ASSET_SUFFIX)])
        if boards:
            result.append({
                "tag": rel.get("tag_name", ""),
                "published_at": rel.get("published_at", ""),
                "boards": sorted(boards),
            })
    return json.dumps(result)


def _download_asset(tag: str, board_key: str) -> bytes:
    url = f"{DOWNLOAD_BASE_URL}/{tag}/{ASSET_PREFIX}{board_key}{ASSET_SUFFIX}"
    with urllib.request.urlopen(url, timeout=60) as resp:
        return resp.read()


def _extract(zip_bytes: bytes, board_key: str) -> dict:
    """Real internal zip layout (flat, board-prefixed file names) —
    confirmed directly against this project's own pinned `rns==1.3.9`
    dependency's `RNS.Utilities.rnodeconf` (its `zip.extractall()` call
    plus the exact paths its own `esptool write_flash` argument lists
    reference), not assumed."""
    result = {}
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        names = {n.rsplit("/", 1)[-1]: n for n in zf.namelist()}
        suffix_map = {
            "bootloader": f"{ASSET_PREFIX}{board_key}.bootloader",
            "partitions": f"{ASSET_PREFIX}{board_key}.partitions",
            "boot_app0": f"{ASSET_PREFIX}{board_key}.boot_app0",
            "app": f"{ASSET_PREFIX}{board_key}.bin",
        }
        for key, fname in suffix_map.items():
            if fname in names:
                result[key] = zf.read(names[fname])
    return result


def download_and_flash(bridge, tag: str, board_key: str, on_progress=None) -> str:
    """The one call the Kotlin flasher screen actually makes end to end:
    downloads the real official-firmware release asset for `board_key`
    at `tag`, extracts it, and flashes it onto whatever's connected via
    `bridge` (a live `Esp32FlashBridge` Kotlin object — `write`/
    `read_byte`/`set_dtr`/`set_rts`; a distinct, exclusive-use USB
    session from RNode's own operational `RnodeUsbBridge` — the caller
    is responsible for making sure RNode's own interface isn't attached
    to the same device first). `on_progress`, if given, is a Kotlin
    object exposing `onProgress(current: int, total: int)` — see
    `esp32_flasher.flash_board`'s own doc comment; this function doesn't
    report download progress separately, only the flash itself (a
    firmware zip is small enough — a few hundred KB to a couple MB —
    that the download itself completing is the "step 1 of 2" signal, not
    something worth its own progress bar).

    Returns the same JSON-string `{"success": bool, "message": str}`
    shape `esp32_flasher.flash_board` already returns."""
    # nomadportal_core.-qualified — see orchestrator.py's matching
    # comment on its own rnode_interface import for why a bare
    # `from esp32_flasher import ...` doesn't resolve here (both files
    # live inside the nomadportal_core package, not at their Chaquopy
    # srcDir's own root).
    from nomadportal_core.esp32_flasher import flash_board, BOARDS

    if board_key not in BOARDS:
        return json.dumps({"success": False, "message": f"Unsupported board: {board_key}"})

    try:
        zip_bytes = _download_asset(tag, board_key)
    except Exception as exc:
        return json.dumps({"success": False, "message": f"Could not download firmware: {exc}"})

    files = _extract(zip_bytes, board_key)
    missing = [k for k in ("bootloader", "partitions", "boot_app0", "app") if k not in files]
    if missing:
        return json.dumps({
            "success": False,
            "message": f"Firmware archive is missing: {', '.join(missing)}",
        })

    return flash_board(bridge, board_key, files, on_progress)
