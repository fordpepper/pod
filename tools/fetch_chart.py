#!/usr/bin/env python3
"""Writes docs/top.json: Apple Music's most-played songs with their free 30-second previews.

The chart comes from Apple's RSS feed generator and the preview links from the iTunes lookup
API. Neither needs a key. Run daily by .github/workflows/chart.yml.
"""
import json
import os
import sys
import urllib.request

COUNTRY = os.environ.get("CHART_COUNTRY", "us")
COUNT = 100
FEED = f"https://rss.applemarketingtools.com/api/v2/{COUNTRY}/music/most-played/{COUNT}/songs.json"
LOOKUP = "https://itunes.apple.com/lookup?country={c}&entity=song&id={ids}"
OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "top.json")


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "pod-chart/1.0"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def main():
    chart = get(FEED)["feed"]["results"]
    ids = [x["id"] for x in chart]
    found = {}
    for i in range(0, len(ids), 50):
        batch = ",".join(ids[i:i + 50])
        for t in get(LOOKUP.format(c=COUNTRY, ids=batch))["results"]:
            if t.get("wrapperType") == "track" and t.get("previewUrl"):
                found[str(t["trackId"])] = t
    songs = []
    for rank, x in enumerate(chart, 1):
        t = found.get(x["id"])
        if not t:
            continue
        art = (t.get("artworkUrl100") or x.get("artworkUrl100") or "").replace("100x100bb", "600x600bb")
        songs.append({
            "rank": rank,
            "id": x["id"],
            "title": t.get("trackName") or x["name"],
            "artist": t.get("artistName") or x["artistName"],
            "album": t.get("collectionName") or "",
            "track": t.get("trackNumber") or 0,
            "art": art,
            "preview": t["previewUrl"],
            "url": x.get("url") or t.get("trackViewUrl"),
        })
    if len(songs) < 10:
        sys.exit(f"only {len(songs)} songs with previews; keeping the old file")
    data = {"country": COUNTRY, "updated": get(FEED)["feed"].get("updated"), "songs": songs}
    with open(OUT, "w") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    print(f"wrote {len(songs)} songs to {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
