#!/usr/bin/env python3
"""전국 교통 CCTV 위치 목록(국가교통정보센터 ITS)을 받아 cctv.json으로 저장.

- 월 호출 한도(기본 100건)를 아끼려고 일주일에 한 번만 실행 (고속도로 1건 + 국도 1건 = 2건)
- 영상 주소는 금방 만료되므로 저장하지 않음 → 앱에서 영상을 볼 때만 1건씩 새로 받음
- 이번 달에 이 작업이 쓴 호출 수를 apiUse에 기록 → 앱이 '남은 조회 횟수(추정)'를 계산
- 인증키는 저장소 Settings → Secrets → Actions 의 ITS_KEY 에 넣어 둠
"""
import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

OUT = 'cctv.json'
KEY = os.environ.get('ITS_KEY', '').strip()
LIMIT = int(os.environ.get('ITS_LIMIT', '100') or 100)
KST = timezone(timedelta(hours=9))
# 자동 작업이 생기기 전, 앱 개발 테스트로 이미 쓴 횟수 (그 달에만 더함)
ALREADY_USED = {'2026-09': 4}
BOX = {'minX': '124.0', 'maxX': '132.0', 'minY': '33.0', 'maxY': '39.0'}  # 대한민국 전체


def load_prev():
    try:
        with open(OUT, encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return {}


def fetch(kind):
    q = dict(BOX, apiKey=KEY, type=kind, cctvType='4', getType='json')
    url = 'https://openapi.its.go.kr:9443/cctvInfo?' + urllib.parse.urlencode(q)
    req = urllib.request.Request(url, headers={'User-Agent': 'hako-nfz/1.0'})
    with urllib.request.urlopen(req, timeout=90) as r:
        body = json.loads(r.read().decode('utf-8', 'replace'))
    data = (body.get('response') or {}).get('data')
    if not isinstance(data, list):
        raise RuntimeError('응답 형식이 예상과 다름: ' + json.dumps(body, ensure_ascii=False)[:300])
    out = []
    for x in data:
        try:
            lat, lon = float(x.get('coordy')), float(x.get('coordx'))
        except (TypeError, ValueError):
            continue
        if not (33 <= lat <= 39 and 124 <= lon <= 132):
            continue
        out.append({'n': str(x.get('cctvname') or 'CCTV').strip(), 'lat': round(lat, 6), 'lon': round(lon, 6), 't': kind})
    return out


def main():
    if not KEY:
        print('ITS_KEY(Secrets)가 없어 건너뜀')
        return 0
    prev = load_prev()
    month = datetime.now(KST).strftime('%Y-%m')
    use = prev.get('apiUse') or {}
    n = use.get('n', 0) if use.get('month') == month else ALREADY_USED.get(month, 0)

    items, failed = [], []
    for kind in ('ex', 'its'):
        n += 1  # 실패해도 호출 1건은 쓴 것으로 셈
        try:
            got = fetch(kind)
            print(f'{kind}: {len(got)}개')
            if not got:
                raise RuntimeError('0개')
            items += got
        except Exception as e:  # 한쪽이 실패하면 지난 목록의 그 종류를 그대로 유지
            print(f'{kind} 실패: {e}')
            failed.append(kind)
            items += [x for x in prev.get('items', []) if x.get('t') == kind]

    seen, uniq = set(), []
    for x in sorted(items, key=lambda x: (x['t'], x['n'], x['lat'], x['lon'])):
        k = (x['n'], x['lat'], x['lon'])
        if k not in seen:
            seen.add(k)
            uniq.append(x)

    out = {
        'updatedAtUTC': prev.get('updatedAtUTC') if len(failed) == 2 else datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
        'source': '국가교통정보센터(ITS)',
        'apiUse': {'month': month, 'n': n, 'limit': LIMIT},
        'items': uniq,
    }
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, separators=(',', ':'))
    print(f'저장: {len(uniq)}개 · 이번 달 자동 작업 호출 {n}/{LIMIT}')
    return 1 if len(failed) == 2 and not uniq else 0


if __name__ == '__main__':
    sys.exit(main())
