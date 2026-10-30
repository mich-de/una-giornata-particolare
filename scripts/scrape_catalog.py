#!/usr/bin/env python3
"""
Costruisce il catalogo dei film/commedie/documentari di Totò, Eduardo De Filippo
e Luciano De Crescenzo disponibili su RaiPlay, Mediaset Infinity, LA7 e sul canale
YouTube "Il Teatro di Eduardo".

Solo libreria standard (yt-dlp opzionale, per YouTube).

Uso:
    python3 scripts/scrape_catalog.py                # scansione normale
    python3 scripts/scrape_catalog.py --deep         # controlla tutto l'indice A-Z di RaiPlay (lento, per la CI)
    python3 scripts/scrape_catalog.py --only raiplay,mediaset

Scrive app/src/main/assets/catalogo.json (l'app lo scarica anche da GitHub).
"""
import argparse
import concurrent.futures as cf
import datetime as dt
import html
import json
import os
import re
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
FILMOGRAPHY = os.path.join(ASSETS, "filmografia.json")
OUT = os.path.join(ASSETS, "catalogo.json")
EXTRA = os.path.join(ROOT, "data", "aggiunte.json")

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
LOG = []


def log(msg):
    print(msg, flush=True)
    LOG.append(msg)


# ---------------------------------------------------------------- HTTP helpers

def http_get(url, headers=None, timeout=20, retries=2):
    h = {"User-Agent": UA, "Accept-Language": "it-IT,it;q=0.9"}
    if headers:
        h.update(headers)
    last = None
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(url, headers=h)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as e:
            if e.code in (404, 410, 403):
                raise
            last = e
        except Exception as e:  # noqa: BLE001
            last = e
        time.sleep(0.8 * (attempt + 1))
    raise last


def get_json(url, **kw):
    return json.loads(http_get(url, **kw))


def try_json(url, **kw):
    try:
        return get_json(url, **kw)
    except Exception:  # noqa: BLE001
        return None


# ---------------------------------------------------------------- matching

def norm(s):
    if not s:
        return ""
    s = unicodedata.normalize("NFKD", str(s))
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    s = s.replace("&", " e ")
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def compact(s):
    return norm(s).replace(" ", "")


def has_words(text_norm, phrase):
    phrase = norm(phrase)
    return bool(phrase) and re.search(r"(?:^| )" + re.escape(phrase) + r"(?: |$)", text_norm) is not None


class Filmography:
    def __init__(self, path):
        data = json.load(open(path, encoding="utf-8"))
        self.persons = data["persons"]
        self.works = data["works"]
        for w in self.works:
            w["_n"] = norm(w["title"])
            w["_c"] = compact(w["title"])
            w["_w"] = w["_n"].split()

    def title_matches(self, title):
        n = norm(title)
        c = n.replace(" ", "")
        words = n.split()
        out = []
        for w in self.works:
            if c == w["_c"]:
                out.append(w)
            elif len(w["_w"]) >= 2 and len(w["_c"]) >= 8 and words[: len(w["_w"])] == w["_w"]:
                out.append(w)  # es. "Natale in casa Cupiello (1977)"
        return out

    @staticmethod
    def strong_mention(person, credits_norm, text_norm):
        if person == "toto":
            return has_words(credits_norm, "toto") or "de curtis" in text_norm or "de curtis" in credits_norm
        if person == "eduardo":
            return "eduardo de filippo" in credits_norm or "eduardo de filippo" in text_norm
        if person == "decrescenzo":
            return "de crescenzo" in credits_norm or "luciano de crescenzo" in text_norm
        return False

    @staticmethod
    def weak_mention(person, text_norm):
        if person == "toto":
            return has_words(text_norm, "toto")
        if person == "eduardo":
            return has_words(text_norm, "eduardo") or "de filippo" in text_norm
        if person == "decrescenzo":
            return "de crescenzo" in text_norm or has_words(text_norm, "bellavista")
        return False

    def classify(self, title, year=None, credits="", text=""):
        """Ritorna (persons, work_principale) oppure ([], None)."""
        credits_n = norm(credits)
        text_n = norm(f"{title} {text}")
        persons, best = [], None
        for w in self.title_matches(title):
            for p in w["persons"]:
                strong = self.strong_mention(p, credits_n, text_n)
                weak = self.weak_mention(p, text_n)
                year_ok = bool(year and w.get("year") and abs(int(year) - int(w["year"])) <= 1)
                distinctive = len(w["_c"]) >= 14 or has_words(w["_n"], "toto") or has_words(w["_n"], "bellavista")
                old_enough = not year or int(year) <= 1975
                ok = strong or year_ok or (weak and (p != "toto" or old_enough)) or (not year and distinctive)
                if ok and p not in persons:
                    persons.append(p)
                    best = best or w
        if not persons:  # titolo non in filmografia: serve una citazione forte
            for p in self.persons:
                if self.strong_mention(p, credits_n, text_n):
                    persons.append(p)
        return persons, best

    def keyword_hit(self, title):
        n = norm(title)
        return any(has_words(n, k) for k in ("toto", "eduardo", "de filippo", "de crescenzo", "bellavista", "de curtis")) \
            or bool(self.title_matches(title))


def make_entry(fm, *, platform, ref, title, persons, work, year=None, page_url="", thumb="", description="",
               duration=0, kind=None, drm=None, director="", cast="", source=""):
    return {
        "id": f"{platform}:{ref}",
        "title": title.strip(),
        "year": int(year) if year else (work.get("year") if work else None),
        "persons": persons,
        "kind": kind or (work["kind"] if work else "film"),
        "platform": platform,
        "ref": ref,
        "page_url": page_url,
        "thumb": thumb,
        "description": (description or "").strip(),
        "duration": int(duration or 0),
        "drm": drm,
        "director": director or "",
        "cast": cast or "",
        "source": source or "",
        "matched": work["title"] if work else "",
    }


def parse_year(v):
    if v is None:
        return None
    m = re.search(r"(19|20)\d{2}", str(v))
    return int(m.group(0)) if m else None


def parse_duration(v):
    if not v:
        return 0
    if isinstance(v, (int, float)):
        return int(v)
    parts = [int(p) for p in re.findall(r"\d+", str(v))]
    if len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    return parts[0] if parts else 0


# ---------------------------------------------------------------- RaiPlay

RAI = "https://www.raiplay.it"


def rai_abs(path):
    if not path:
        return ""
    if path.startswith("//"):
        return "https:" + path
    if path.startswith("http"):
        return path
    if path.startswith("/raiplay/"):
        path = path[len("/raiplay"):]
    return RAI + path


def rai_program_json(path):
    p = (path or "").strip().replace("?json", "")
    if p.startswith("http"):
        p = urllib.parse.urlparse(p).path
    if p.startswith("/raiplay/"):
        p = p[len("/raiplay"):]
    p = p.rstrip("/")
    if p.endswith("/index.json"):
        p = p[: -len("/index.json")]
    for ext in (".html", ".json"):
        if p.endswith(ext):
            p = p[: -len(ext)]
    return RAI + p + ".json" if p else ""


def rai_img(images):
    if not isinstance(images, dict):
        return ""
    for k in ("landscape", "landscape_logo", "landscape43", "portrait_logo", "portrait", "portrait43", "square"):
        v = images.get(k)
        if v:
            return rai_abs(v.replace("[RESOLUTION]", "640x-"))
    return ""


def rai_video_ref(path):
    p = (path or "").strip()
    if p.startswith("http"):
        p = urllib.parse.urlparse(p).path
    for ext in (".json", ".html"):
        if p.endswith(ext):
            p = p[: -len(ext)]
    return p


def rai_video_meta(ref):
    v = try_json(RAI + ref + ".json")
    if not v:
        return {}
    drm = bool(((v.get("rights_management") or {}).get("rights") or {}).get("drm"))
    video = v.get("video") or {}
    return {
        "drm": drm,
        "duration": parse_duration(video.get("duration") or v.get("duration")),
        "description": v.get("description") or "",
        "has_content": bool(video.get("content_url")),
    }


def raiplay_scan(fm, deep=False):
    log("== RaiPlay")
    candidates = {}
    try:
        az = get_json(RAI + "/dl/RaiTV/RaiPlayMobile/Prod/Config/programmiAZ-elenco.json")
        total = 0
        for _, items in (az.items() if isinstance(az, dict) else []):
            for it in items or []:
                total += 1
                name = it.get("name") or it.get("title") or ""
                url = rai_program_json(it.get("PathID") or it.get("path_id") or "")
                if url and (deep or fm.keyword_hit(name)):
                    candidates[url] = name
        log(f"  indice A-Z: {total} programmi, {len(candidates)} da controllare")
    except Exception as e:  # noqa: BLE001
        log(f"  indice A-Z non disponibile: {e}")

    # slug "compattato" (es. /programmi/miseriaenobilta) per ogni titolo in filmografia
    for w in fm.works:
        for slug in {w["_c"], w["_c"] + str(w.get("year") or "")}:
            url = f"{RAI}/programmi/{slug}.json"
            candidates.setdefault(url, w["title"])

    def check(url):
        p = try_json(url)
        if not p:
            return []
        info = p.get("program_info") or p
        name = info.get("name") or p.get("name") or candidates.get(url, "")
        year = parse_year(info.get("year"))
        credits = " ".join(str(info.get(k) or "") for k in ("actors", "direction", "conductor", "authors", "credits"))
        desc = info.get("description") or info.get("vanity") or p.get("description") or ""
        typos = [t.get("nome", "") for t in (info.get("typologies") or []) if isinstance(t, dict)]
        genres = [g.get("nome", "") for g in (info.get("genres") or []) if isinstance(g, dict)]
        kind = "film" if "Film" in typos else ("teatro" if any("teatr" in norm(x) or "prosa" in norm(x) for x in typos + genres)
                                              else ("documentario" if any("document" in norm(x) for x in typos + genres) else "tv"))
        thumb = rai_img(info.get("images") or p.get("images"))
        page = rai_abs((p.get("path_id") or url).replace(".json", ""))
        out = []
        first = p.get("first_item_path")
        if first:
            persons, work = fm.classify(name, year, credits, desc)
            if persons:
                ref = rai_video_ref(first)
                out.append(make_entry(fm, platform="raiplay", ref=ref, title=name, persons=persons, work=work, year=year,
                                      page_url=RAI + ref + ".html", thumb=thumb, description=desc, kind=kind if not work else None,
                                      director=info.get("direction") or "", cast=info.get("actors") or "", source=page))
            return out
        # programma a più contenuti (es. raccolte teatrali): blocchi -> set -> item
        prog_persons, _ = fm.classify(name, year, credits, desc)
        base = url[: -len(".json")]
        for b in p.get("blocks") or []:
            for s in b.get("sets") or []:
                sid = s.get("id")
                if not sid:
                    continue
                cs = try_json(f"{base}/{sid}.json") or {}
                for it in (cs.get("items") or [])[:300]:
                    path = it.get("path_id") or ""
                    if "/video/" not in path:
                        continue
                    t = it.get("name") or it.get("episode_title") or it.get("subtitle") or ""
                    item_desc = it.get("description") or ""
                    persons, work = fm.classify(t, parse_year(it.get("year")) or None, credits,
                                                f"{name} {desc} {item_desc}")
                    if not persons and prog_persons and fm.title_matches(t):
                        persons, work = prog_persons, fm.title_matches(t)[0]
                    if not persons and prog_persons and len(prog_persons) == 1 and fm.keyword_hit(name):
                        persons = prog_persons  # raccolta dedicata (es. "Il teatro di Eduardo")
                    if persons:
                        ref = rai_video_ref(path)
                        out.append(make_entry(fm, platform="raiplay", ref=ref, title=t or name, persons=persons, work=work,
                                              year=parse_year(it.get("year")), page_url=RAI + ref + ".html",
                                              thumb=rai_img(it.get("images")) or thumb, description=item_desc or desc,
                                              duration=parse_duration(it.get("duration")), kind=kind if not work else None,
                                              director=info.get("direction") or "", cast=info.get("actors") or "",
                                              source=f"{name}"))
        return out

    results = []
    with cf.ThreadPoolExecutor(max_workers=16 if deep else 8) as ex:
        for res in ex.map(check, list(candidates)):
            results.extend(res)
    # metadati del video (DRM, durata) per ciascun titolo trovato
    with cf.ThreadPoolExecutor(max_workers=8) as ex:
        metas = list(ex.map(lambda e: rai_video_meta(e["ref"]), results))
    for e, m in zip(results, metas):
        if m:
            e["drm"] = m["drm"]
            e["duration"] = e["duration"] or m["duration"]
            e["description"] = e["description"] or m["description"]
    log(f"  trovati {len(results)} titoli")
    return results


# ---------------------------------------------------------------- Mediaset Infinity

MS_FEED = "https://feed.entertainment.tv.theplatform.eu/f/PR1GhC/mediaset-prod-all-programs-v2"
MS_SMIL = ("https://link.theplatform.eu/s/PR1GhC/media/guid/2702976343/{guid}?mbr=true&formats=MPEG-DASH,M3U,MPEG4"
           "&assetTypes=geoNo:HD,browser,geoIT|geoNo:HD,geoIT|geoNo:SD,browser,geoIT|geoNo:SD,geoIT|geoNo|HD|SD&format=SMIL")


def slugify(s):
    return re.sub(r"-+", "-", norm(s).replace(" ", "-")).strip("-") or "video"


def ms_thumb(e):
    th = e.get("thumbnails") or {}
    best, score = "", -1
    for k, v in th.items():
        if not isinstance(v, dict) or not v.get("url"):
            continue
        w = int(v.get("width") or 0)
        s = w + (5000 if "keyframe" in k else 0) + (3000 if "poster" not in k and w >= v.get("height", 0) else 0)
        if 400 <= w <= 1920 and s > score:
            best, score = v["url"], s
    return best


def ms_available(e, now_ms):
    media = e.get("media") or []
    if not media:
        return True
    for m in media:
        a, x = m.get("availableDate") or 0, m.get("expirationDate") or 0
        if (not a or a <= now_ms) and (not x or x > now_ms):
            return True
    return False


def ms_drm(guid):
    try:
        body = http_get(MS_SMIL.format(guid=guid), timeout=15, retries=0)
    except Exception:  # noqa: BLE001
        return None
    if "ProtectionScheme" in body or "None of the available releases" in body:
        return True
    if re.search(r'<video[^>]+src="[^"]+(_no_|\.m3u8|\.mp4)', body):
        return False
    return None  # es. geoblocco: si decide sul dispositivo


def mediaset_scan(fm):
    log("== Mediaset Infinity")
    queries = ["Totò", "Antonio de Curtis", "Eduardo De Filippo", "Luciano De Crescenzo", "Bellavista"]
    queries += sorted({w["title"] for w in fm.works})
    seen, results = set(), []
    now_ms = int(time.time() * 1000)
    errors = 0

    def search(q):
        url = f"{MS_FEED}?{urllib.parse.urlencode({'q': q, 'range': '1-60'})}"
        try:
            return get_json(url, timeout=20).get("entries") or []
        except Exception:  # noqa: BLE001
            return None

    with cf.ThreadPoolExecutor(max_workers=6) as ex:
        for q, entries in zip(queries, ex.map(search, queries)):
            if entries is None:
                errors += 1
                continue
            for e in entries:
                guid = e.get("guid")
                if not guid or guid in seen:
                    continue
                seen.add(guid)
                ptype = e.get("programType") or ""
                if ptype not in ("movie", "episode", "", None):
                    continue
                if not ms_available(e, now_ms):
                    continue
                title = e.get("title") or ""
                year = parse_year(e.get("year"))
                credits = json.dumps(e.get("credits") or "", ensure_ascii=False)
                for k, v in e.items():
                    if any(t in k.lower() for t in ("cast", "director", "regia", "actor", "people")):
                        credits += " " + json.dumps(v, ensure_ascii=False)
                desc = e.get("description") or e.get("longDescription") or ""
                brand = e.get("mediasetprogram$brandTitle") or ""
                persons, work = fm.classify(title, year, credits, f"{desc} {brand}")
                if not persons:
                    continue
                page = e.get("mediasetprogram$videoPageUrl") or e.get("mediasetprogram$pageUrl") or ""
                if page.startswith("//"):
                    page = "https:" + page
                if not page:
                    page = (f"https://mediasetinfinity.mediaset.it/movie/{slugify(title)}/{slugify(title)}_{guid}"
                            if ptype == "movie" else
                            f"https://mediasetinfinity.mediaset.it/video/{slugify(brand or title).replace('-', '')}/{slugify(title)}_{guid}")
                dur = 0
                for m in e.get("media") or []:
                    dur = max(dur, int(float(m.get("duration") or 0) / (1000 if (m.get("duration") or 0) > 100000 else 1)))
                kind = None if work else ("film" if ptype == "movie" else ("documentario" if "racconto" in norm(brand) else "tv"))
                results.append(make_entry(fm, platform="mediaset", ref=guid, title=title, persons=persons, work=work,
                                          year=year, page_url=page, thumb=ms_thumb(e), description=desc, duration=dur,
                                          kind=kind, source=brand))
    with cf.ThreadPoolExecutor(max_workers=6) as ex:
        for e, d in zip(results, ex.map(lambda x: ms_drm(x["ref"]), results)):
            e["drm"] = d
    log(f"  {len(queries)} ricerche ({errors} fallite), trovati {len(results)} titoli")
    return results


# ---------------------------------------------------------------- LA7 (best effort)

def la7_scan(fm):
    log("== LA7")
    queries = ["Totò", "Eduardo De Filippo", "Luciano De Crescenzo"]
    pages = set()
    for q in queries:
        for tmpl in ("https://www.la7.it/ricerca?query={q}", "https://www.la7.it/search?query={q}"):
            try:
                body = http_get(tmpl.format(q=urllib.parse.quote(q)), timeout=15, retries=0)
            except Exception:  # noqa: BLE001
                continue
            for m in re.findall(r'href="(/[a-z0-9\-]+/(?:rivedila7|video)/[a-z0-9\-_]+)"', body):
                pages.add("https://www.la7.it" + m)
            break
    results = []
    for url in sorted(pages)[:80]:
        try:
            body = http_get(url, timeout=15, retries=0)
        except Exception:  # noqa: BLE001
            continue
        eid = re.search(r"0_[a-z0-9]{8}", body)
        t = re.search(r'<meta property="og:title" content="([^"]+)"', body)
        d = re.search(r'<meta property="og:description" content="([^"]+)"', body)
        img = re.search(r'<meta property="og:image" content="([^"]+)"', body)
        if not (eid and t):
            continue
        title = html.unescape(t.group(1).replace(" | LA7", "").strip())
        desc = html.unescape(d.group(1)) if d else ""
        persons, work = fm.classify(title, None, "", desc)
        if persons and (work or fm.strong_mention(persons[0], "", norm(title + " " + desc))):
            results.append(make_entry(fm, platform="la7", ref=eid.group(0), title=title, persons=persons, work=work,
                                      page_url=url, thumb=img.group(1) if img else "", description=desc,
                                      kind=None if work else "documentario", drm=False, source="LA7"))
    log(f"  {len(pages)} pagine viste, trovati {len(results)} titoli")
    return results


# ---------------------------------------------------------------- YouTube: "Il Teatro di Eduardo"

YT_CHANNELS = ["https://www.youtube.com/@eduardodefilippo/videos", "https://www.youtube.com/user/eduardodefilippo/videos"]


def youtube_scan(fm):
    log("== YouTube (Il Teatro di Eduardo)")
    for ch in YT_CHANNELS:
        try:
            out = subprocess.run(["yt-dlp", "--flat-playlist", "-J", "--no-warnings", ch],
                                 capture_output=True, text=True, timeout=600)
        except (FileNotFoundError, subprocess.TimeoutExpired) as e:
            log(f"  yt-dlp non disponibile: {e}")
            return []
        if out.returncode != 0 or not out.stdout.strip():
            log(f"  {ch}: {out.stderr.strip()[:200]}")
            continue
        data = json.loads(out.stdout)
        results = []
        for v in data.get("entries") or []:
            vid, title, dur = v.get("id"), v.get("title") or "", int(v.get("duration") or 0)
            if not vid or (dur and dur < 20 * 60):
                continue  # solo opere intere
            works = fm.title_matches(re.sub(r"\(.*?\)|-\s*Eduardo.*$", "", title, flags=re.I))
            work = works[0] if works else None
            results.append(make_entry(fm, platform="youtube", ref=vid, title=title, persons=["eduardo"], work=work,
                                      page_url=f"https://www.youtube.com/watch?v={vid}",
                                      thumb=f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg",
                                      description=v.get("description") or "", duration=dur,
                                      kind="teatro", drm=False, source=data.get("channel") or "Il Teatro di Eduardo"))
        log(f"  {ch}: trovati {len(results)} titoli")
        return results
    return []


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--deep", action="store_true", help="controlla tutti i programmi dell'indice RaiPlay")
    ap.add_argument("--only", default="raiplay,mediaset,la7,youtube")
    ap.add_argument("--out", default=OUT)
    args = ap.parse_args()

    fm = Filmography(FILMOGRAPHY)
    scanners = {"raiplay": lambda: raiplay_scan(fm, args.deep), "mediaset": lambda: mediaset_scan(fm),
                "la7": lambda: la7_scan(fm), "youtube": lambda: youtube_scan(fm)}
    films, stats = {}, {}
    for name in [s.strip() for s in args.only.split(",") if s.strip()]:
        try:
            res = scanners[name]()
        except Exception as e:  # noqa: BLE001
            log(f"  {name}: errore {e}")
            res = []
        stats[name] = len(res)
        for f in res:
            films.setdefault(f["id"], f)

    if os.path.exists(EXTRA):
        for f in json.load(open(EXTRA, encoding="utf-8")):
            if f.get("id") and f.get("persons"):
                films[f["id"]] = f
                stats["aggiunte"] = stats.get("aggiunte", 0) + 1

    # non cancellare i titoli di una piattaforma se la scansione è fallita del tutto (rete/geo)
    if os.path.exists(args.out):
        try:
            old = json.load(open(args.out, encoding="utf-8"))
            for f in old.get("films", []):
                if stats.get(f.get("platform"), 0) == 0 and f.get("platform") in stats and f["id"] not in films:
                    films[f["id"]] = f
        except Exception:  # noqa: BLE001
            pass

    order = {"toto": 0, "eduardo": 1, "decrescenzo": 2}
    items = sorted(films.values(), key=lambda f: (min(order.get(p, 9) for p in f["persons"]),
                                                  f.get("year") or 9999, norm(f["title"])))
    doc = {"generated": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
           "stats": stats, "films": items}
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=1)
    log(f"Salvati {len(items)} titoli in {args.out}: {stats}")


if __name__ == "__main__":
    sys.exit(main())
