import urllib.request, re, json, time, sys, html

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
}

def get_page(url):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.read().decode('utf-8', errors='ignore')

def get_json(url):
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode('utf-8', errors='ignore'))

episodes_urls = set()
for page in range(0, 10):
    url = f"https://www.la7.it/una-giornata-particolare/rivedila7?page={page}"
    try:
        content = get_page(url)
    except Exception as e:
        print(f"Page {page} error: {e}")
        break
    
    links = re.findall(r'href=[\"\'](/una-giornata-particolare/rivedila7/[a-zA-Z0-9\-_]+)[\"\']', content)
    unique_links = [l for l in set(links) if not l.endswith('/rivedila7')]
    print(f"Page {page}: found {len(unique_links)} episode links")
    if not unique_links:
        break
    for l in unique_links:
        episodes_urls.add("https://www.la7.it" + l)

print(f"Total unique episode URLs found: {len(episodes_urls)}")

episodes_data = []

for idx, ep_url in enumerate(sorted(episodes_urls)):
    print(f"[{idx+1}/{len(episodes_urls)}] Fetching {ep_url}")
    try:
        page_html = get_page(ep_url)
    except Exception as e:
        print(f"  Error fetching page: {e}")
        continue
    
    # Extract entryId
    entry_m = re.search(r'\"entry_id\":\s*\"([^\"]+)\"', page_html) or re.search(r'entryId=([a-zA-Z0-9_]+)', page_html) or re.search(r'entry_id=([a-zA-Z0-9_]+)', page_html) or re.search(r'kaltura_entry_id\s*=\s*[\'\"]([^\'\"]+)[\'\"]', page_html) or re.search(r'entry-id=[\'\"]([^\'\"]+)[\'\"]', page_html)
    entry_id = entry_m.group(1) if entry_m else None
    
    if not entry_id:
        # Check in script tags
        entry_m2 = re.search(r'0_[a-z0-9]{8}', page_html)
        if entry_m2:
            entry_id = entry_m2.group(0)

    # Title
    title_m = re.search(r'<meta property=\"og:title\" content=\"([^\"]+)\"', page_html) or re.search(r'<title>([^<]+)</title>', page_html)
    title = html.unescape(title_m.group(1).replace(" | LA7", "").strip()) if title_m else ""

    # Description
    desc_m = re.search(r'<meta property=\"og:description\" content=\"([^\"]+)\"', page_html) or re.search(r'<meta name=\"description\" content=\"([^\"]+)\"', page_html)
    desc = html.unescape(desc_m.group(1).strip()) if desc_m else ""

    # Thumbnail
    thumb_m = re.search(r'<meta property=\"og:image\" content=\"([^\"]+)\"', page_html)
    thumb = thumb_m.group(1).strip() if thumb_m else ""

    # Date
    date_m = re.search(r'class=\"[^\"]*date[^\"]*\"[^>]*>(.*?)<', page_html, re.I) or re.search(r'([0-9]{2}/[0-9]{2}/[0-9]{4})', page_html)
    date_str = date_m.group(1).strip() if date_m else ""

    print(f"  Title: {title[:40]} | Date: {date_str} | EntryId: {entry_id}")

    flavors = []
    has_subtitles = False
    subtitles_urls = []
    hls_url = ""
    direct_mp4 = ""
    top_res = "720p"

    if entry_id:
        # Check Kaltura Flavors
        flavor_api = f"https://kdam.iltrovatore.it/api_v3/index.php?service=flavorAsset&action=list&filter:entryIdEqual={entry_id}&format=1"
        try:
            flavor_res = get_json(flavor_api)
            objects = flavor_res.get('objects', [])
            for obj in objects:
                fid = obj.get('id')
                w = obj.get('width', 0)
                h = obj.get('height', 0)
                br = obj.get('bitrate', 0)
                size = obj.get('size', 0)
                status = obj.get('status', 0)
                if status == 2: # READY
                    flavors.append({
                        'flavor_id': fid,
                        'width': w,
                        'height': h,
                        'bitrate': br,
                        'size': size
                    })
            # Sort by width/height
            flavors.sort(key=lambda x: (x['width'], x['bitrate']), reverse=True)
            if flavors:
                top = flavors[0]
                top_res = f"{top['height']}p"
                direct_mp4 = f"https://awsvodpkg.iltrovatore.it/content/entry/data/0/737/{entry_id}_{top['flavor_id']}_1.mp4"
        except Exception as e:
            print(f"  Flavor API error: {e}")

        hls_url = f"https://kdam.iltrovatore.it/p/103/sp/10300/playManifest/entryId/{entry_id}/format/applehttp/protocol/https/a.m3u8"

        # Check Subtitles
        caption_api = f"https://kdam.iltrovatore.it/api_v3/index.php?service=caption_captionAsset&action=list&filter:entryIdEqual={entry_id}&format=1"
        try:
            caption_res = get_json(caption_api)
            cap_objects = caption_res.get('objects', [])
            if cap_objects:
                has_subtitles = True
                for cap in cap_objects:
                    subtitles_urls.append(cap)
                print(f"  FOUND SUBTITLES: {len(cap_objects)}")
        except Exception as e:
            pass

    episodes_data.append({
        'title': title,
        'date': date_str,
        'description': desc,
        'thumb': thumb,
        'page_url': ep_url,
        'entry_id': entry_id,
        'resolution': top_res,
        'hls_url': hls_url,
        'direct_mp4': direct_mp4,
        'has_subtitles': has_subtitles,
        'subtitles': subtitles_urls,
        'flavors': flavors
    })
    time.sleep(0.3)

out_file = '/home/michde/Documenti/unagiornataparticolare/puntate_ugp.json'
with open(out_file, 'w', encoding='utf-8') as f:
    json.dump(episodes_data, f, ensure_ascii=False, indent=2)

print(f"\nDone! Saved {len(episodes_data)} episodes to {out_file}")
