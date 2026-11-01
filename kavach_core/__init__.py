"""KAVACH reference implementation (Python).

This package is the executable specification of the on-device logic. The
Android apps (android/core) port the same algorithms to Kotlin and are tested
against golden vectors produced from this package, so behaviour measured here
is the behaviour shipped on the phone.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "assets"
MODELS = ASSETS / "models"

__all__ = ["ROOT", "ASSETS", "MODELS"]
