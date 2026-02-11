import base64
import re

hash_str = "2LPZitix2YHYsdin2Kog2KfZhNmF2LTYp9mH2K/YqSDYp9mE2YXYqti52K/Yr9ipID0__IGh0dHBzOi8vdy5hZmxhbXkucHJvL2FsYmFwbGF5ZXIvdHdpc3RlZC0yMDI2"

parts = hash_str.split("__")
for i, part in enumerate(parts):
    try:
        # Clean whitespace and try to decode
        clean_part = part.strip()
        decoded = base64.b64decode(clean_part).decode('utf-8', errors='ignore')
        print(f"Part {i} decoded: {decoded}")
        
        # Look for URL in decoded part
        url_match = re.search(r'https?://[^\s<>"\']+', decoded)
        if url_match:
            print(f"Found URL in Part {i}: {url_match.group(0)}")
    except Exception as e:
        print(f"Failed to decode Part {i}: {e}")

# Try decoding the whole thing after replacing __ with nothing
try:
    decoded_full = base64.b64decode(hash_str.replace("__", "")).decode('utf-8', errors='ignore')
    print(f"Full decoded (no __): {decoded_full}")
except Exception as e:
    print(f"Failed to decode full: {e}")
