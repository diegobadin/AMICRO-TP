"""Entrypoint: start the stdlib http.server on PORT (default 8087)."""

from __future__ import annotations

import os
from http.server import HTTPServer

from app import SERVICE, make_handler

DEFAULT_PORT = 8087


def main() -> None:
    port = int(os.environ.get("PORT", DEFAULT_PORT))
    server = HTTPServer(("0.0.0.0", port), make_handler())  # noqa: S104
    print(f'{{"level":"info","service":"{SERVICE}","action":"listen","port":{port}}}', flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
