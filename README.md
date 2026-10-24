# SolarCast — 태양광 발전량·전력수요 day-ahead 예측 플랫폼

기상 예보로 다음 날 시간별 태양광 발전량(발전소 1곳)과 전국 전력수요를 예측하고,
Spring Boot 플랫폼에서 예측·실측·오차를 매일 자동으로 관리하는 1인 풀스택 AI 프로젝트.

```
기상 예보 ─▶ Spring Boot 배치 ─▶ FastAPI 모델 서버 ─▶ PostgreSQL ─▶ 대시보드 (Thymeleaf + Chart.js)
 (KMA/아카이브)   @Scheduled        champion 모델            예측·실측·오차      예측 구간 띠, 오차 추이, 경고
                    │                    ▲
                    ▼                    │ alias
              감시·경고(Slack)        MLflow 레지스트리 ◀── 학습 파이프라인 (LightGBM 분위수)
                    │
              Actuator ─▶ Prometheus ─▶ Grafana
```

## 결과 (리플레이 구간 2025-10-03 ~ 12-31, 학습에 쓰지 않음, 입력은 운영과 같은 예보 기상)

| 대상 | 지표 | 베이스라인 | Ridge | **LightGBM (서빙)** | 목표 |
|---|---|---|---|---|---|
| 태양광 | NMAE (설비용량 대비, 이용률 10% 이상 시간) | 12.08% | 11.94% | **8.56%** | 10% 이하 ✅ / 도전 8% ❌ |
| 수요 | MAPE | 6.11% | 4.88% | **3.23%** | 3% 이하 ❌ |

태양광 하늘상태별 NMAE: 맑음 8.19% · 구름많음 11.01% · 흐림 10.45% (베이스라인 흐림 19.49%).
P10~P90 구간 적중률은 태양광 87%, 수요 64%라 수요 구간은 너무 좁다 (개선 과제).

**관측 vs 예보 불일치 실험** (기획서 3.3)

| 실험 | 태양광 NMAE |
|---|---|
| 관측 일사(icsr) 포함 — 성능 상한, 운영 불가 | 7.01% |
| 예보 가능 피처 + 청천일사량, 관측으로 학습 (서빙) | 8.56% |
| 같은 피처, 과거 예보로 학습 | 8.67% |

운영에서 일사를 모르는 비용이 약 1.5%p. 청천일사량(pvlib)과 하늘상태 변환으로 학습·운영 피처를 맞추면
과거 예보로 직접 학습한 모델과 성능 차이가 거의 없었다.

**학습 곡선** (`python -m solarcast.curves`, MLflow `*-learning-curve` 실험): 태양광은 220회 반복에서 검증 8.41%로 최저,
이후 600회까지 8.56%로 약간 과적합. 수요는 600회까지 계속 내려가 반복 수를 늘릴 여지가 있다.

## 실행

### Docker Compose (전체)
```bash
cp .env.example .env      # KMA_SERVICE_KEY 등 (없어도 리플레이는 동작)
docker compose up --build
```
- 대시보드 http://localhost:8080 · 관리 http://localhost:8080/admin (admin/admin) · Swagger http://localhost:8080/swagger-ui/index.html
- MLflow http://localhost:5000 · Prometheus http://localhost:9090 · Grafana http://localhost:3000

첫 실행 때 model-api 컨테이너가 `data/raw`로 학습 데이터를 만들고 모델을 학습·등록한 뒤 뜬다 (수 분).
> ⚠ compose 파일은 작성만 했고 아직 실제로 띄워 검증하지 않았다. 아래 로컬 실행은 검증했다.

### 로컬 (Docker 없이)
```powershell
# ML
cd ml
py -3.13 -m venv .venv; .venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python -m solarcast.real all      # data/raw -> data/processed (Open-Meteo 캐시 사용)
.venv\Scripts\python -m solarcast.train         # 실험 기록 + 모델 등록 (sqlite MLflow)
$env:MLFLOW_TRACKING_URI="sqlite:///$PWD/mlflow.db"; .venv\Scripts\python -m uvicorn solarcast.serve:app --port 8000

# 백엔드 (H2 파일 DB, 첫 실행 때 data/processed를 시드)
cd backend; .\gradlew bootRun --args='--spring.profiles.active=local'
```
관리 화면에서 **리플레이 시작 → 자동 진행**을 누르면 90일을 하루씩 재생한다 (예측 → 실측 비교 → 감시).

### 테스트
```bash
cd ml && python -m pytest tests        # 피처, 누수 점검, 모델 서버 (13개)
cd backend && ./gradlew test           # 지표 계산, 감시 규칙, 보안, KMA 파싱 (13개)
```

## 기상청 API 키 (라이브 모드)

1. 공공데이터포털에서 **기상청_단기예보 조회서비스**, **기상청_지상(종관, ASOS) 시간자료 조회서비스**를 활용신청한다 (같은 키).
2. 프로젝트 루트 `.env`에 `KMA_SERVICE_KEY=발급받은키` (Encoding/Decoding 키 어느 쪽이든 됨). `.env`는 git에 올라가지 않는다.
3. 서버 재시작 후 `POST /api/admin/kma/check` (관리자)로 키가 동작하는지 확인한다.
4. `LIVE_ENABLED=true`면 매일 01:00 전일 관측, 17:30 단기예보 수집, 17:40 내일 예측이 돈다.
   수동 수집은 `POST /api/admin/kma/collect`.

키가 없어도 리플레이 모드는 과거 예보 아카이브(Open-Meteo)로 전부 동작한다.

## 설계 메모

- **시간 규칙**: KST, hour-ending (13시 = 12:00~13:00). D일 예측의 기준 시각은 D-1일 17시.
- **누수 방지**: 수요 라그는 7일 전 같은 시각, 2~8일 전 평균만 쓴다 (D일 18시 이후 실측은 모름). `tests/test_leakage.py`가
  기준 시각 이후 값을 오염시켜도 피처가 바뀌지 않는지 검사한다.
- **forecast.issued_at**: 같은 시각에 대한 예측이 여러 번 생겨도 그때 무엇을 알고 예측했는지 재현.
- **모델 교체**: 재학습 결과는 challenger로 등록. 최근 30일에서 주 지표가 낮고 하늘상태별 오차도 나빠지지 않을 때만
  champion 승격, 롤백은 alias 되돌리기. 모델 서버는 60초마다 alias를 확인해 다시 불러오고, 실패하면 이전 버전 유지.
- **감시**: 일 오차 7일 이동평균 > 검증 성능 × 1.5 → 경고, 3일 연속 → 재학습 권고.
  맑음 예보인데 실측이 P10 아래로 3시간 이상 연속 → 설비 이상 의심. 17시 예보 없으면 14시 발표분으로 대체 예측.
- **범위 축소** (3일 일정): JWT 대신 HTTP Basic, Optuna·LSTM 제외, 자동 재학습은 버튼(API)으로만.

## 데이터와 한계

| 데이터 | 출처 | 비고 |
|---|---|---|
| 태양광 실측 | 공공데이터포털 15095374 한국남부발전 무릉리 태양광발전실적 | 2021-05 ~ 2026-02, 1호기 |
| 전력수요 실측 | 공공데이터포털 15065266 한국전력거래소 시간별 전국 전력수요량 | 2013 ~ 2025 |
| 관측 기상 | Open-Meteo Historical Weather API (ERA5 재분석) | ASOS 대체. 지점 관측이 아님 |
| 예보 기상 | Open-Meteo Previous Runs API, JMA 하루 전 예보 | 기상청 단기예보 과거자료 대체. 발표 시각이 17시와 정확히 같지 않음 |

- **발전소 위치·설비용량은 추정값**이다. 위치는 데이터로 제주 대정(33.27N, 126.20E) 근방으로 추정했고,
  설비용량 1,000kW는 최대 출력 767kW에서 가정했다. NMAE는 설비용량으로 나누므로 값이 바뀌면 지표도 바뀐다. 남부발전 확인 필요.
- 강수확률(POP)은 과거 예보 아카이브에 없어 피처에서 뺐다.
- 발전 실측은 연 단위로 공개되어 라이브 모드에서는 매일 실측 비교가 불가능하다 → 리플레이 모드로 운영 흐름을 검증.
- 데이터 출처 표시: 공공데이터포털(공공누리), Open-Meteo (CC BY 4.0).
