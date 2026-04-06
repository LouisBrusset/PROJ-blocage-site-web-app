#!/bin/bash
# Lance le backend FastAPI (depuis le dossier backend/)
cd "$(dirname "$0")"
uv run --with fastapi --with uvicorn[standard] --with sqlmodel uvicorn main:app --host 0.0.0.0 --port 8001 --reload
