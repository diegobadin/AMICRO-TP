"""The image has to contain the modules the service imports.

P7 shipped `placement.py` into ranking and not into ranking's image: every test passed, because a
test imports from a directory and a container imports from an image. The pod crash-looped on
`ModuleNotFoundError` at startup — found by a deploy, which is a slow place to find it.

The Dockerfile lists its files by name on purpose (a `COPY . .` would carry tests and a venv into
the runtime image), so this is the check that keeps that list honest.
"""

from __future__ import annotations

from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parents[1]


def copied_modules() -> set[str]:
    lines = (SERVICE_ROOT / "Dockerfile").read_text().splitlines()
    copied: set[str] = set()
    for line in lines:
        if line.startswith("COPY ") and ".py" in line:
            copied |= {token for token in line.split() if token.endswith(".py")}
    return copied


def test_every_module_reaches_the_image() -> None:
    on_disk = {path.name for path in SERVICE_ROOT.glob("*.py")}
    missing = sorted(on_disk - copied_modules())
    assert not missing, (
        f"these modules exist but are not COPYed into the image: {missing}. The service will start"
        " and die on ModuleNotFoundError; add them to the Dockerfile."
    )
