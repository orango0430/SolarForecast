#!/bin/sh
# 1) processed 데이터가 없으면 raw(공공데이터 CSV + Open-Meteo 캐시)에서 만든다
# 2) 레지스트리에 champion이 없으면 학습해서 등록한다
# 3) 모델 서버 실행
set -e
if [ ! -f "$DATA_DIR/processed/weather_forecast.csv" ]; then
  python -m solarcast.real all
fi
python -m solarcast.train --if-missing
exec uvicorn solarcast.serve:app --host 0.0.0.0 --port 8000
