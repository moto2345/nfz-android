#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
하코 NFZ 조회 — 항공고시보(NOTAM) 수집 스크립트 (GitHub Actions에서 30분마다 실행)

1) 국토교통부 항공정보통합관리(xNOTAM, aim.koca.go.kr)에서 국내 NOTAM을 가져온다.
   브라우저에서는 CORS 때문에 직접 부를 수 없어서, 서버(GitHub Actions)에서 받아 notam.json으로 저장한다.
2) 그중 드론·초경량비행장치에 해당하는 것만 추린다.
   - 본문에 드론/무인기/초경량/UA/UAS/UAV 등이 나오는 것
   - Q코드가 무인항공기 관련(QWU…)인 것
   - 임시 비행제한구역(QRT…) 중 지면(또는 500ft 이하)부터 시작하는 것
3) 좌표·반경을 읽어 지도에 그릴 수 있는 영역(GeoJSON 다각형)으로 만든다.

참고: GitHub Actions 서버에서 xNOTAM 호출이 가끔 막히는 경우가 있어 재시도하고,
최근에 성공한 데이터가 있으면 이번 회차는 조용히 건너뛴다.
"""
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
UTC = timezone.utc
OUT = os.environ.get("NOTAM_OUT", "notam.json")
BASE = os.environ.get("NOTAM_BASE", "https://aim.koca.go.kr/xNotam/searchAllNotam.do")
STALE_HOURS = 6        # 이 시간 넘게 갱신 못 하면 실패로 알림 (GitHub 예약 실행이 불규칙해서 여유 있게)
REFRESH_HOURS = 3      # 내용이 같아도 이 간격마다는 '확인 시각'을 갱신
MAX_PAGES = 40
AHEAD_DAYS = 14        # 앞으로 14일 안에 시작하는 것까지 포함

KW = re.compile(r"DRONE|UAV\b|UAS\b|\bUA\b|UNMANNED|\bRPAS?\b|ULTRA[- ]?LIGHT|\bULV\b|MODEL\s+AIRCRAFT|드론|초경량|무인\s*비행|무인기", re.I)
QLINE = re.compile(r"Q\)\s*(\w{4})/(Q\w{4})/(\w*)/(\w*)/(\w*)/(\d{3})/(\d{3})/(\d{2})(\d{2})([NS])(\d{3})(\d{2})([EW])(\d{3})")
DMS = re.compile(r"(\d{2})(\d{2})(\d{2}(?:\.\d+)?)\s*([NS])\s*[,/ ]?\s*(\d{3})(\d{2})(\d{2}(?:\.\d+)?)\s*([EW])")
DM = re.compile(r"(?<!\d)(\d{2})(\d{2})([NS])\s*[,/ ]?\s*(\d{3})(\d{2})([EW])")
RADIUS = re.compile(r"(?:RADIUS|RDS|반경)\s*(?:OF\s*)?([\d.]+)\s*(NM|KM|M)\b", re.I)
RADIUS_PRE = re.compile(r"([\d.]+)\s*(NM|KM|M)\s*(?:RADIUS|RDS|반경)", re.I)
HEAD = re.compile(r"([A-Z]\d{4}/\d{2})\s+NOTAM([NRC])\b\s*([A-Z]\d{4}/\d{2})?")
KIND = {"QRP": ("P", "임시비행금지구역"), "QRR": ("R", "임시비행제한구역"), "QRT": ("R", "임시비행제한구역"),
        "QRD": ("D", "임시위험구역"), "QWU": ("U", "드론 활동 구역")}


def log(*a):
    print(*a, file=sys.stderr)


# ───────── 가져오기 ─────────
def build_url(page):
    now = datetime.now(KST)
    qs = {
        "sch_snow_series": "", "sch_select": "", "sch_inorout": "D",
        "sch_from_date": (now - timedelta(days=30)).strftime("%Y-%m-%d"), "sch_from_time": "0000",
        "sch_to_date": (now + timedelta(days=30)).strftime("%Y-%m-%d"), "sch_to_time": "2359",
        "sch_series": "", "sch_notam_no": "", "sch_elevation_min": "", "sch_elevation_max": "",
        "sch_airport": "", "sch_qcode": "", "sch_fir": "", "sch_full_text": "", "iborderby": "",
    }
    return f"{BASE}?ibpage={page}&" + urllib.parse.urlencode(qs)


def fetch_json(url):
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "ko-KR,ko;q=0.9,en;q=0.8",
        "Referer": "https://aim.koca.go.kr/xNotam/?language=ko_KR",
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode("utf-8"))


def fetch_page_retry(page):
    last = None
    for i, wait in enumerate([0, 5, 15]):
        if wait:
            time.sleep(wait)
        try:
            data = fetch_json(build_url(page))
            recs = data.get("DATA")
            if not isinstance(recs, list):
                raise ValueError(f"DATA 배열 없음. keys={list(data.keys())}")
            return recs
        except Exception as e:
            last = e
            log(f"page {page} 시도 {i + 1} 실패: {type(e).__name__}: {e}")
    raise last


def fetch_all():
    seen, out = set(), []
    for page in range(1, MAX_PAGES + 1):
        recs = fetch_page_retry(page)
        new = [r for r in recs if str(r.get("SEQ") or r.get("NOTAM_NO")) not in seen]
        if not new:
            break
        for r in new:
            seen.add(str(r.get("SEQ") or r.get("NOTAM_NO")))
        out.extend(new)
    else:
        print(f"::warning::{MAX_PAGES}쪽까지 받아도 끝나지 않았습니다. 일부 NOTAM이 빠졌을 수 있습니다.")
    return out


# ───────── 해석 ─────────
def parse_time(s):
    """'YYMMDDHHMM'(UTC) → datetime. PERM → None(무기한). EST 등 꼬리는 무시."""
    if not s:
        return None
    s = str(s).strip().upper()
    if s.startswith("PERM"):
        return None
    m = re.match(r"(\d{10})", s)
    if not m:
        return None
    d = m.group(1)
    return datetime(2000 + int(d[0:2]), int(d[2:4]), int(d[4:6]), int(d[6:8]), int(d[8:10]), tzinfo=UTC)


def dms(deg, mi, sec, hemi):
    v = int(deg) + int(mi) / 60 + float(sec) / 3600
    return -v if hemi in "SW" else v


def circle(lat, lon, r_m, n=48):
    pts = []
    for i in range(n + 1):
        a = 2 * math.pi * i / n
        dlat = (r_m * math.cos(a)) / 110574
        dlon = (r_m * math.sin(a)) / (111320 * math.cos(math.radians(lat)))
        pts.append([round(lon + dlon, 6), round(lat + dlat, 6)])
    return {"type": "Polygon", "coordinates": [pts]}


def field(full, letter, nxt):
    # 항목 기호는 줄 처음이나 공백 뒤에만 옴. D)는 E) 앞부분에서만 찾음 (E 본문 속 "(D)" 등과 구분)
    src = full
    if letter in ("A", "B", "C", "D"):
        e = re.search(r"(?:^|\s)E\)", full)
        src = full[:e.start()] if e else full
    m = re.search(rf"(?:^|\s){letter}\)\s*(.*?)\s*(?=(?:^|\s)(?:{nxt})\)|$)", src, re.S)
    return re.sub(r"\s+", " ", m.group(1)).strip().rstrip(")") if m else ""


def parse(rec):
    full = rec.get("FULL_TEXT") or ""
    etext = (rec.get("ECODE") or field(full, "E", "[FG]") or "").strip()
    h = HEAD.search(full[:120])
    if h and h.group(2) == "C":
        return None  # 취소 공지
    if re.search(r"NOTAM\s+CNL|NEW NOTAM TO FLW", etext, re.I) or (rec.get("QCODE") or "").upper()[-2:] in ("CN", "XX"):
        return None  # 취소·대체 안내문(영역 없음)
    q = QLINE.search(full)
    qcode = (rec.get("QCODE") or (q.group(2) if q else "") or "").upper()
    lower = int(q.group(6)) if q else 0
    upper = int(q.group(7)) if q else 999

    # 드론·초경량 관련만
    kw = bool(KW.search(etext) or KW.search(full))
    if kw or qcode.startswith("QWU"):
        category = "drone"
    elif qcode.startswith("QRT") and lower <= 5:
        category = "temp"
    else:
        return None

    start, end = parse_time(rec.get("EFFECTIVESTART")), parse_time(rec.get("EFFECTIVEEND"))
    now = datetime.now(UTC)
    if end and end < now:
        return None
    if start and start > now + timedelta(days=AHEAD_DAYS):
        return None

    # 영역: 본문 좌표(+반경) 우선, 없으면 Q줄의 중심·반경
    geom, center, radius_m = None, None, None
    pts = [(dms(*m.groups()[0:3], m.group(4)), dms(*m.groups()[4:7], m.group(8))) for m in DMS.finditer(etext)]
    if not pts:
        pts = [(dms(m.group(1), m.group(2), 0, m.group(3)), dms(m.group(4), m.group(5), 0, m.group(6))) for m in DM.finditer(etext)]
    # 반경 표현마다 가장 가까운 좌표를 중심으로 원을 만듦 (여러 원이면 모두)
    coord_pos = [(m.start(), m.end()) for m in DMS.finditer(etext)] or [(m.start(), m.end()) for m in DM.finditer(etext)]
    rads = [(m.start(), m.end(), m.group(1), m.group(2)) for m in RADIUS.finditer(etext)] + [(m.start(), m.end(), m.group(1), m.group(2)) for m in RADIUS_PRE.finditer(etext)]
    circles = []
    if rads and pts and len(coord_pos) == len(pts):
        used = set()
        for rpos, rend, v, u in sorted(rads):
            free = [k for k in range(len(pts)) if k not in used]
            if not free:
                break
            # 반경 글자와 좌표 사이 간격이 가장 짧은 좌표를 짝으로 ("RADIUS 1KM CENTERED ON <좌표>" / "<좌표> RADIUS 1NM" 모두)
            def gap(k):
                a0, a1 = coord_pos[k]
                return a0 - rend if a0 >= rend else rpos - a1 if a1 <= rpos else 0
            i = min(free, key=lambda k: (gap(k), 0 if coord_pos[k][0] >= rend else 1))
            used.add(i)
            circles.append((pts[i], float(v) * (1852 if u.upper() == "NM" else 1000 if u.upper() == "KM" else 1)))
    if circles:
        center, radius_m = circles[0]
        parts = [circle(c[0], c[1], r)["coordinates"] for c, r in circles]
        # 원 말고 남은 좌표가 3개 이상이면 그것도 다각형 구역으로 함께
        rest = [p for p in pts if all(p is not c for c, _ in circles)]
        if len(rest) >= 3:
            ring = [[round(lo, 6), round(la, 6)] for la, lo in rest]
            if ring[0] != ring[-1]:
                ring.append(ring[0])
            parts.append([ring])
        if len(parts) == 1:
            geom = {"type": "Polygon", "coordinates": parts[0]}
        else:
            geom = {"type": "MultiPolygon", "coordinates": parts}
            radius_m = max(r for _, r in circles)
    elif len(pts) >= 3:
        ring = [[round(lo, 6), round(la, 6)] for la, lo in pts]
        if ring[0] != ring[-1]:
            ring.append(ring[0])
        geom = {"type": "Polygon", "coordinates": [ring]}
        center = (sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))
    elif q:
        r_nm = int(q.group(14))
        center = (dms(q.group(8), q.group(9), 0, q.group(10)), dms(q.group(11), q.group(12), 0, q.group(13)))
        if 0 < r_nm < 999:
            radius_m = r_nm * 1852
            geom = circle(center[0], center[1], radius_m)

    kind, kname = KIND.get(qcode[:3], ("U" if category == "drone" else "R", "드론 관련 구역" if category == "drone" else "임시비행제한구역"))
    return {
        "no": rec.get("NOTAM_NO") or "",
        "kind": kind,
        "kindName": kname,
        "series": rec.get("SERIES") or "",
        "location": rec.get("LOCATION") or "",
        "qcode": qcode,
        "qmean": rec.get("QCODE_MEAN") or "",
        "category": category,
        "start": start.isoformat() if start else None,
        "end": end.isoformat() if end else None,
        "endEst": "EST" in str(rec.get("EFFECTIVEEND") or "").upper(),
        "schedule": field(full, "D", "E"),
        "lower": lower,
        "upper": upper,
        "fromTxt": field(full, "F", "G"),
        "toTxt": field(full, "G", r"\s*$"),
        "text": etext[:1200],
        "center": [round(center[0], 6), round(center[1], 6)] if center else None,
        "radiusM": round(radius_m) if radius_m else None,
        "geometry": geom,
    }


# ───────── 저장 ─────────
def load_prev():
    try:
        with open(OUT, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def main():
    prev = load_prev()
    try:
        records = fetch_all()
    except Exception as e:
        if prev and prev.get("fetchedAtUTC"):
            age = (datetime.now(UTC) - datetime.fromisoformat(prev["fetchedAtUTC"])).total_seconds() / 3600
            if age < STALE_HOURS:
                print(f"::warning::xNOTAM 호출 실패, 마지막 성공이 {age:.1f}시간 전이라 이번 회차는 건너뜁니다: {e}")
                return 0
        print(f"::error::항공고시보를 {STALE_HOURS}시간 넘게 가져오지 못했습니다: {e}")
        return 1

    # 서버가 빈 목록이나 평소보다 훨씬 적은 목록을 주면(점검·차단 등) 기존 자료를 지키고 실패로 알림
    prev_total = (prev or {}).get("totalFetched") or 0
    if not records or (prev_total >= 200 and len(records) < prev_total * 0.3):
        age = (datetime.now(UTC) - datetime.fromisoformat(prev["fetchedAtUTC"])).total_seconds() / 3600 if prev and prev.get("fetchedAtUTC") else 99
        msg = f"xNOTAM이 {len(records)}건만 돌려줬습니다(이전 {prev_total}건)"
        if age < STALE_HOURS:
            print(f"::warning::{msg} — 기존 자료를 유지합니다.")
            return 0
        if not records:
            print(f"::error::{msg} — {STALE_HOURS}시간 넘게 정상 자료를 받지 못했습니다.")
            return 1
        print(f"::warning::{msg} — {STALE_HOURS}시간 넘게 계속 적게 와서 실제로 줄어든 것으로 보고 새 자료로 바꿉니다.")

    # NOTAMR(대체)·NOTAMC(취소)가 가리키는 이전 NOTAM은 빼기
    gone = set()
    for r in records:
        h = HEAD.search((r.get("FULL_TEXT") or "")[:120])
        if h and h.group(2) in "RC" and h.group(3):
            gone.add(h.group(3))
    items, seen = [], set()
    for x in (parse(r) for r in records):
        if not x or x["no"] in gone:
            continue
        key = (x["text"], x["start"], x["end"], tuple(x["center"] or ()))
        if key in seen:
            continue  # 같은 내용 중복 공지
        seen.add(key)
        items.append(x)
    items.sort(key=lambda x: (x["start"] or ""))
    now = datetime.now(UTC)
    if prev and prev.get("items") == items and prev.get("fetchedAtUTC"):
        age = (now - datetime.fromisoformat(prev["fetchedAtUTC"])).total_seconds() / 3600
        if age < REFRESH_HOURS:
            print(f"변경 없음 ({len(items)}건) — 저장 생략")
            return 0
    out = {
        "fetchedAt": now.astimezone(KST).isoformat(timespec="seconds"),
        "fetchedAtUTC": now.isoformat(timespec="seconds"),
        "source": "국토교통부 항공정보통합관리 xNOTAM (aim.koca.go.kr)",
        "totalFetched": len(records),
        "count": len(items),
        "items": items,
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"OK — 전체 {len(records)}건 중 드론 관련 {len(items)}건 저장")
    return 0


if __name__ == "__main__":
    sys.exit(main())
