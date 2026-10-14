#!/usr/bin/env python3
"""Flash a message in Morse with a Philips Hue bulb, to test the receiver.

Talks to the Hue bridge directly (CLIP API v2) over one kept-alive HTTPS
connection, which is faster and steadier than going through a home automation
hub. Standard library only.

    HUE_KEY=<application key> python hue_morse.py --bridge 192.168.1.2 \
        --light <light resource id> --wpm 4 "hello world"

    python hue_morse.py --list        # light ids and names

Bulbs react 50-150 ms late and the bridge accepts about 10 commands a second,
so stay at 3-5 WPM. The receiver measures and removes a constant delay, but
not a jittery one. --dim keeps the bulb powered and swings its brightness
instead of switching it, which some bulbs follow faster.

When done, the light is put back as it was.
"""

import argparse
import http.client
import json
import os
import ssl
import sys
import time
import unicodedata

CODES = {
    "A": ".-", "B": "-...", "C": "-.-.", "D": "-..", "E": ".", "F": "..-.",
    "G": "--.", "H": "....", "I": "..", "J": ".---", "K": "-.-", "L": ".-..",
    "M": "--", "N": "-.", "Ñ": "--.--", "O": "---", "P": ".--.", "Q": "--.-",
    "R": ".-.", "S": "...", "T": "-", "U": "..-", "V": "...-", "W": ".--",
    "X": "-..-", "Y": "-.--", "Z": "--..",
    "0": "-----", "1": ".----", "2": "..---", "3": "...--", "4": "....-",
    "5": ".....", "6": "-....", "7": "--...", "8": "---..", "9": "----.",
    ".": ".-.-.-", ",": "--..--", "?": "..--..", "'": ".----.", "!": "-.-.--",
    "/": "-..-.", "(": "-.--.", ")": "-.--.-", "&": ".-...", ":": "---...",
    ";": "-.-.-.", "=": "-...-", "+": ".-.-.", "-": "-....-", "_": "..--.-",
    '"': ".-..-.", "$": "...-..-", "@": ".--.-.",
}


def normalize(text):
    """Same rules as the app: upper case, no accents except Ñ, single spaces."""
    out = []
    for raw in text.upper():
        if raw.isspace():
            if out and out[-1] != " ":
                out.append(" ")
            continue
        char = raw if raw == "Ñ" else unicodedata.normalize("NFD", raw)[0]
        if char in CODES:
            out.append(char)
    return "".join(out).rstrip()


def schedule(text):
    """(on, units, char, index) pulses, starting and ending with light."""
    pulses = []
    gap = 0
    for index, char in enumerate(normalize(text)):
        if char == " ":
            gap = 7
            continue
        if pulses:
            pulses.append((False, max(gap, 3), char, index))
        for i, symbol in enumerate(CODES[char]):
            if i:
                pulses.append((False, 1, char, index))
            pulses.append((True, 1 if symbol == "." else 3, char, index))
        gap = 0
    return pulses


class Bridge:
    def __init__(self, host, key):
        context = ssl.create_default_context()
        # The bridge uses a self-signed certificate.
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        self.conn = http.client.HTTPSConnection(host, context=context, timeout=5)
        self.headers = {"hue-application-key": key, "Content-Type": "application/json"}

    def request(self, method, path, body=None):
        data = json.dumps(body) if body is not None else None
        self.conn.request(method, "/clip/v2/resource/" + path, body=data, headers=self.headers)
        response = self.conn.getresponse()
        payload = response.read()
        if response.status >= 300:
            raise RuntimeError(f"{method} {path}: {response.status} {payload[:200]!r}")
        return json.loads(payload) if payload else None

    def lights(self):
        return self.request("GET", "light")["data"]

    def state(self, light):
        return self.request("GET", f"light/{light}")["data"][0]

    def put(self, light, body):
        self.request("PUT", f"light/{light}", body)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("message", nargs="?", help="text to flash")
    parser.add_argument("--bridge", default=os.environ.get("HUE_BRIDGE"), help="bridge IP (or HUE_BRIDGE)")
    parser.add_argument("--light", default=os.environ.get("HUE_LIGHT"), help="light resource id (or HUE_LIGHT)")
    parser.add_argument("--wpm", type=float, default=4, help="words per minute (default 4)")
    parser.add_argument("--repeat", type=int, default=1, help="times to send it (default 1)")
    parser.add_argument("--brightness", type=float, default=100, help="brightness of each flash, 1-100%% (default 100)")
    parser.add_argument("--dim", action="store_true", help="swing brightness 1-100%% instead of on/off")
    parser.add_argument("--list", action="store_true", help="list lights and exit")
    args = parser.parse_args()

    key = os.environ.get("HUE_KEY")
    if not key or not args.bridge:
        sys.exit("Set HUE_KEY and --bridge (or HUE_BRIDGE).")
    bridge = Bridge(args.bridge, key)

    if args.list:
        for light in bridge.lights():
            print(light["id"], light["metadata"]["name"], "on" if light["on"]["on"] else "off")
        return
    if not args.light or not args.message:
        sys.exit("Need --light and a message (use --list to find the id).")

    pulses = schedule(args.message)
    if not pulses:
        sys.exit("Nothing in that message can be sent in Morse.")
    unit = 1.2 / args.wpm
    total = sum(p[1] for p in pulses) * unit
    print(f"{normalize(args.message)!r}: {len(pulses)} pulses, unit {unit * 1000:.0f} ms, "
          f"{total:.1f} s per pass")

    saved = bridge.state(args.light)
    instant = {"dynamics": {"duration": 0}}
    if args.dim:
        on_body = {"dimming": {"brightness": args.brightness}, **instant}
        off_body = {"dimming": {"brightness": 1}, **instant}
        bridge.put(args.light, {"on": {"on": True}, **off_body})
    else:
        # The brightness rides along with every switch-on, so there is no
        # set-up flash that the receiver would read as a letter.
        on_body = {"on": {"on": True}, "dimming": {"brightness": args.brightness}, **instant}
        off_body = {"on": {"on": False}, **instant}
        bridge.put(args.light, off_body)

    try:
        # Darkness first, so the receiver sees where the message starts.
        time.sleep(2)
        for n in range(args.repeat):
            if n:
                time.sleep(14 * unit)  # message gap: the app starts a new line
            start = time.perf_counter()
            elapsed = 0.0
            last_index = -1
            for on, units, char, index in pulses:
                bridge.put(args.light, on_body if on else off_body)
                if on and index != last_index:
                    # The space before a new word is shown too.
                    if last_index >= 0 and index > last_index + 1:
                        print(" ", end="")
                    print(char, end="", flush=True)
                    last_index = index
                elapsed += units * unit
                wait = start + elapsed - time.perf_counter()
                if wait > 0:
                    time.sleep(wait)
            bridge.put(args.light, off_body)
            print()
    except KeyboardInterrupt:
        print("\nstopped")
    finally:
        restore = {"on": {"on": saved["on"]["on"]}, **instant}
        # Sending a brightness to a light that was off makes it blink once,
        # which the receiver reads as a stray "T": only restore it if it was on.
        if saved["on"]["on"] and "dimming" in saved:
            restore["dimming"] = {"brightness": saved["dimming"]["brightness"]}
        bridge.put(args.light, restore)


if __name__ == "__main__":
    main()
