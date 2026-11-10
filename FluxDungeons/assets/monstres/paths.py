"""Puts the shared preview tools (png_util, apercu, apercu_anim, kept in scripts/influcard of the server, also used by
the pets) on the import path. Imported for its effect by the monster scripts; set INFLUCARD to point elsewhere."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
INFLUCARD = os.environ.get("INFLUCARD", os.path.normpath(os.path.join(HERE, *[".."] * 5, "scripts", "influcard")))
if INFLUCARD not in sys.path:
    sys.path.insert(0, INFLUCARD)
