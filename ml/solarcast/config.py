"""프로젝트 공통 설정. 실데이터로 바꿀 때 여기 값부터 확인한다."""
import os
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = Path(os.getenv("DATA_DIR", ROOT / "data"))
RAW_DIR = DATA_DIR / "raw"
PROCESSED_DIR = DATA_DIR / "processed"

TZ = "Asia/Seoul"


@dataclass(frozen=True)
class Plant:
    id: int
    name: str
    capacity_kw: float
    lat: float
    lon: float
    asos_stn_id: int


# 무릉리 태양광: 위치·설비용량은 데이터 상세에서 확인 후 교체 (1주차 통과 기준)
# 서귀포시 대정읍 무릉리 근방, 가장 가까운 ASOS는 고산(185)
PLANT = Plant(
    id=1,
    name="한국남부발전 무릉리 태양광",
    capacity_kw=float(os.getenv("PLANT_CAPACITY_KW", "1000")),
    lat=33.27,
    lon=126.20,
    asos_stn_id=185,
)

# 수요용 5개 도시 ASOS 지점 (서울, 부산, 대구, 광주, 대전)
DEMAND_STATIONS = {
    108: ("서울", 37.57, 126.97),
    159: ("부산", 35.10, 129.03),
    143: ("대구", 35.88, 128.62),
    156: ("광주", 35.17, 126.89),
    133: ("대전", 36.37, 127.37),
}

CDD_BASE = 24.0
HDD_BASE = 18.0

# 학습에 쓰지 않는 마지막 구간 (리플레이 기간)
REPLAY_DAYS = 90
