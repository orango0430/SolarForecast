"""위경도 -> 기상청 단기예보 격자(nx, ny) 변환 (기상청 LCC DFS 공식)."""
import math

RE = 6371.00877
GRID = 5.0
SLAT1, SLAT2 = 30.0, 60.0
OLON, OLAT = 126.0, 38.0
XO, YO = 43, 136


def latlon_to_grid(lat: float, lon: float) -> tuple[int, int]:
    d = math.pi / 180.0
    re = RE / GRID
    slat1, slat2 = SLAT1 * d, SLAT2 * d
    olon, olat = OLON * d, OLAT * d

    sn = math.tan(math.pi * 0.25 + slat2 * 0.5) / math.tan(math.pi * 0.25 + slat1 * 0.5)
    sn = math.log(math.cos(slat1) / math.cos(slat2)) / math.log(sn)
    sf = math.tan(math.pi * 0.25 + slat1 * 0.5)
    sf = math.pow(sf, sn) * math.cos(slat1) / sn
    ro = math.tan(math.pi * 0.25 + olat * 0.5)
    ro = re * sf / math.pow(ro, sn)

    ra = math.tan(math.pi * 0.25 + lat * d * 0.5)
    ra = re * sf / math.pow(ra, sn)
    theta = lon * d - olon
    if theta > math.pi:
        theta -= 2.0 * math.pi
    if theta < -math.pi:
        theta += 2.0 * math.pi
    theta *= sn

    nx = int(math.floor(ra * math.sin(theta) + XO + 0.5))
    ny = int(math.floor(ro - ra * math.cos(theta) + YO + 0.5))
    return nx, ny
