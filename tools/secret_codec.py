#!/usr/bin/env python3
"""Encrypt or decrypt MorseLightCrypt messages on a computer.

Same format as the app (see SecretCodec.kt): salt(4) | AES-256-CTR | HMAC-SHA256
cut to 4 bytes, PBKDF2-HMAC-SHA256 600 000 rounds, 6-bit packed text, base 32
in groups of five. Needs the `cryptography` package.

    python secret_codec.py encrypt "meet at nine" -p secret
    python secret_codec.py decrypt "ABCDE FGHIJ ..." -p secret

Pipe it into the lamp:

    python hue_morse.py ... "$(python secret_codec.py encrypt "hi" -p secret)"
"""

import argparse
import hashlib
import hmac
import os
import sys
import unicodedata

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

from hue_morse import normalize

DOMAIN = b"morselightcrypt/v1"
SALT_BYTES = 4
TAG_BYTES = 4
ITERATIONS = 600_000
BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
PACKED = "\0 ABCDEFGHIJKLMNÑOPQRSTUVWXYZ0123456789.,?'!/()&:;=+-_\"$@"


def keys(password, salt):
    # NFC, as the app does: "ñ" typed as one character or as n + tilde is the same key.
    secret = unicodedata.normalize("NFC", password).encode("utf-8")
    out = hashlib.pbkdf2_hmac("sha256", secret, DOMAIN + salt, ITERATIONS, 64)
    return out[:32], out[32:]


def ctr(key, data):
    encryptor = Cipher(algorithms.AES(key), modes.CTR(bytes(16))).encryptor()
    return encryptor.update(data) + encryptor.finalize()


def tag(key, salt, ciphertext):
    return hmac.new(key, salt + ciphertext, hashlib.sha256).digest()[:TAG_BYTES]


def pack(text):
    bits = "".join(format(PACKED.index(c), "06b") for c in text)
    bits += "0" * (-len(bits) % 8)
    return bytes(int(bits[i:i + 8], 2) for i in range(0, len(bits), 8))


def unpack(data):
    bits = "".join(format(b, "08b") for b in data)
    values = [int(bits[i:i + 6], 2) for i in range(0, len(bits) - 5, 6)]
    if any(v >= len(PACKED) for v in values):
        raise ValueError("bad plaintext")
    return "".join(PACKED[v] for v in values if v)


def to_base32(data):
    bits = "".join(format(b, "08b") for b in data)
    bits += "0" * (-len(bits) % 5)
    return "".join(BASE32[int(bits[i:i + 5], 2)] for i in range(0, len(bits), 5))


def from_base32(text):
    bits = "".join(format(BASE32.index(c), "05b") for c in text)
    return bytes(int(bits[i:i + 8], 2) for i in range(0, len(bits) - 7, 8))


def encrypt(message, password):
    return encrypt_with_salt(message, password, os.urandom(SALT_BYTES))


def encrypt_with_salt(message, password, salt):
    text = normalize(message)
    if not text:
        raise ValueError("empty message")
    enc, mac = keys(password, salt)
    ciphertext = ctr(enc, pack(text))
    digits = to_base32(salt + ciphertext + tag(mac, salt, ciphertext))
    return " ".join(digits[i:i + 5] for i in range(0, len(digits), 5))


def decrypt(received, password):
    digits = "".join(c for c in received.upper() if not c.isspace() and c != "/")
    data = from_base32(digits)
    if len(data) <= SALT_BYTES + TAG_BYTES:
        raise ValueError("too short")
    salt, ciphertext, got = data[:SALT_BYTES], data[SALT_BYTES:-TAG_BYTES], data[-TAG_BYTES:]
    enc, mac = keys(password, salt)
    if not hmac.compare_digest(got, tag(mac, salt, ciphertext)):
        raise ValueError("wrong password or misread letter")
    return unpack(ctr(enc, ciphertext))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", choices=["encrypt", "decrypt"])
    parser.add_argument("text")
    parser.add_argument("-p", "--password", required=True)
    args = parser.parse_args()
    try:
        print(encrypt(args.text, args.password) if args.action == "encrypt" else decrypt(args.text, args.password))
    except ValueError as e:
        sys.exit(f"error: {e}")


if __name__ == "__main__":
    main()
