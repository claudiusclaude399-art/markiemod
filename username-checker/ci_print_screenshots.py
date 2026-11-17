"""CI helper: print the self-test screenshots into the log as base64 JPEGs."""
import base64
import io
import os

from PIL import Image

for name in ("screenshot", "screenshot_search"):
    if os.path.exists(name + ".png"):
        buf = io.BytesIO()
        Image.open(name + ".png").convert("RGB").save(buf, "JPEG", quality=70)
        print(f"{name}_B64:" + base64.b64encode(buf.getvalue()).decode())
