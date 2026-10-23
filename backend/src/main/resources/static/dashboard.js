const $ = (id) => document.getElementById(id);
const css = (v) => getComputedStyle(document.documentElement).getPropertyValue(v).trim();
const SKY = { 1: '☀', 3: '⛅', 4: '☁' };
const LABEL = {
  solar: { unit: 'kWh', metric: 'NMAE', key: 'nmae', color: '--accent', soft: '--accent-soft' },
  demand: { unit: 'MWh', metric: 'MAPE', key: 'mape', color: '--demand', soft: '--demand-soft' },
};

let target = 'solar';
let date = null;
let follow = true; // 리플레이를 따라 최신 재생일을 계속 보여줄지
let dayChart, trendChart;

const fmt = (n, d = 1) => (n == null || isNaN(n) ? '-' : Number(n).toLocaleString('ko-KR', { maximumFractionDigits: d }));
const get = (url) => fetch(url).then((r) => (r.ok ? r.json() : []));
const iso = (d) => d.toISOString().slice(0, 10);
const addDays = (s, n) => { const d = new Date(s + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + n); return iso(d); };
const hourOf = (t) => { const h = new Date(t).getHours(); return h === 0 ? 24 : h; };

async function latestDate() {
  const r = await get('/api/replay/status');
  if (r.cursor && r.cursor !== 'null') {
    const last = addDays(r.cursor, -1);
    return last >= r.start ? last : r.start;
  }
  const m = await get(`/api/metrics/daily?target=${target}`);
  return m.length ? m[m.length - 1].date : iso(new Date());
}

async function load() {
  if (!date) date = await latestDate();
  $('date').value = date;
  const L = LABEL[target];
  const [fc, act, wx, metrics, models, alerts, replay] = await Promise.all([
    get(`/api/forecasts?target=${target}&date=${date}`),
    get(`/api/actuals?target=${target}&date=${date}`),
    target === 'solar' ? get(`/api/weather?date=${date}`) : Promise.resolve([]),
    get(`/api/metrics/daily?target=${target}`),
    get('/api/models'),
    get('/api/alerts'),
    get('/api/replay/status'),
  ]);

  // KPI
  const champion = (models[target] || []).find((m) => m.stage === 'champion');
  const threshold = champion && champion.validationScore ? champion.validationScore * 1.5 : null;
  const today = metrics.find((m) => m.date === date);
  const upto = metrics.filter((m) => m.date <= date).slice(-7);
  const avg7 = upto.length ? upto.reduce((s, m) => s + (m[L.key] ?? 0), 0) / upto.length : null;
  $('k1t').textContent = `일 오차 (${L.metric})`;
  $('k1').textContent = today ? fmt(today[L.key], 2) + '%' : '-';
  $('k1s').textContent = today ? (today.belowP10Hours >= 3 ? `⚠ P10 하회 ${today.belowP10Hours}시간 연속` : `모델 v${today.modelVersion}`) : (fc.length ? '실측 없음 (공개 후 비교)' : '예측 없음');
  $('k2').textContent = avg7 == null ? '-' : fmt(avg7, 2) + '%';
  $('k2').className = 'value ' + (avg7 != null && threshold ? (avg7 > threshold ? 'bad' : 'good') : '');
  $('k2s').textContent = threshold ? `경고 기준 ${fmt(threshold, 2)}% (${upto.length}일)` : '';
  const sumP = fc.reduce((s, f) => s + f.p50, 0), sumA = act.reduce((s, a) => s + a.value, 0);
  $('k3').textContent = fc.length ? `${fmt(sumP, 0)} / ${act.length ? fmt(sumA, 0) : '-'}` : '-';
  $('k3s').textContent = L.unit + (fc.some((f) => f.fallback) ? ' · 14시 예보 대체' : '');
  $('k4').textContent = champion ? `v${champion.version}` : '-';
  $('k4s').textContent = champion && champion.validationScore ? `검증 ${L.metric} ${fmt(champion.validationScore, 2)}%` : '';

  const r = replay;
  $('replay').textContent = r.cursor && r.cursor !== 'null'
    ? `리플레이 ${r.start} ~ ${r.end} · 다음 ${r.cursor}${r.running ? (r.auto ? ' · 자동 진행 중' : ' · 대기') : ' · 정지'}`
    : '리플레이 미시작 (관리 화면에서 시작)';

  drawDay(fc, act, L);
  drawWeather(wx);
  drawTrend(metrics, threshold, L);
  drawAlerts(alerts);

  clearTimeout(load.timer);
  if (follow && r.running && r.auto) {
    load.timer = setTimeout(async () => { date = await latestDate(); load(); }, 3000);
  }
}

function drawDay(fc, act, L) {
  const labels = Array.from({ length: 24 }, (_, i) => `${i + 1}시`);
  const byHour = (rows, key) => { const a = Array(24).fill(null); rows.forEach((r) => { a[hourOf(r.targetTime) - 1] = r[key]; }); return a; };
  const data = {
    labels,
    datasets: [
      { label: 'P90', data: byHour(fc, 'p90'), borderWidth: 0, pointRadius: 0, fill: '+1', backgroundColor: css(L.soft) },
      { label: 'P10', data: byHour(fc, 'p10'), borderWidth: 0, pointRadius: 0, fill: false },
      { label: '예측 P50', data: byHour(fc, 'p50'), borderColor: css(L.color), backgroundColor: css(L.color), borderWidth: 2, pointRadius: 0, tension: .3 },
      { label: '실측', data: byHour(act, 'value'), borderColor: css('--actual'), backgroundColor: css('--actual'), borderWidth: 1.5, pointRadius: 2.5, borderDash: [4, 3] },
    ],
  };
  const opts = {
    responsive: true, maintainAspectRatio: false, animation: false,
    interaction: { mode: 'index', intersect: false },
    plugins: {
      legend: { labels: { filter: (i) => !['P90', 'P10'].includes(i.text), color: css('--muted') } },
      tooltip: { callbacks: { label: (c) => `${c.dataset.label}: ${fmt(c.parsed.y, 1)} ${L.unit}` } },
    },
    scales: {
      x: { ticks: { color: css('--muted') }, grid: { display: false } },
      y: { ticks: { color: css('--muted') }, grid: { color: css('--border') }, title: { display: true, text: L.unit, color: css('--muted') }, beginAtZero: target === 'solar' },
    },
  };
  if (dayChart) { dayChart.data = data; dayChart.options = opts; dayChart.update(); }
  else dayChart = new Chart($('day'), { type: 'line', data, options: opts });
}

function drawWeather(wx) {
  const el = $('weather');
  if (!wx.length) { el.innerHTML = ''; return; }
  el.innerHTML = wx.map((w) => {
    const sky = w.pty > 0 ? (w.pty === 3 ? '❄' : '🌧') : (SKY[w.sky] || '·');
    return `<div title="${hourOf(w.time)}시 · 습도 ${fmt(w.reh, 0)}% · 풍속 ${fmt(w.wsd)}m/s"><div class="sky">${sky}</div>${fmt(w.tmp, 0)}°</div>`;
  }).join('');
}

function drawTrend(metrics, threshold, L) {
  const labels = metrics.map((m) => m.date.slice(5));
  const values = metrics.map((m) => m[L.key]);
  const data = {
    labels,
    datasets: [
      { label: `일 ${L.metric} (%)`, data: values, borderColor: css(L.color), backgroundColor: metrics.map((m) => (m.date === date ? css('--actual') : css(L.color))), pointRadius: metrics.map((m) => (m.date === date ? 5 : 2)), borderWidth: 1.5 },
      ...(threshold ? [{ label: '경고 기준', data: values.map(() => threshold), borderColor: css('--bad'), borderDash: [6, 4], pointRadius: 0, borderWidth: 1 }] : []),
    ],
  };
  const opts = {
    responsive: true, maintainAspectRatio: false, animation: false,
    onClick: (_, els) => { if (els.length) { follow = false; date = metrics[els[0].index].date; load(); } },
    plugins: { legend: { labels: { color: css('--muted') } } },
    scales: {
      x: { ticks: { color: css('--muted'), maxTicksLimit: 12 }, grid: { display: false } },
      y: { ticks: { color: css('--muted') }, grid: { color: css('--border') }, beginAtZero: true },
    },
  };
  if (trendChart) { trendChart.data = data; trendChart.options = opts; trendChart.update(); }
  else trendChart = new Chart($('trend'), { type: 'line', data, options: opts });
}

function drawAlerts(alerts) {
  const cls = { PERFORMANCE: 'warn', RETRAIN_RECOMMENDED: 'bad', OUTPUT_ANOMALY: 'bad', PIPELINE_FAILURE: 'bad', MODEL_SWAP: 'good' };
  $('alerts').innerHTML = alerts.slice(0, 30).map((a) =>
    `<li><span class="t ${cls[a.type] || 'muted'}">${a.type}</span><span class="muted">${a.targetDate || ''}</span><br>${a.message}</li>`
  ).join('') || '<li class="muted">경고 없음</li>';
}

document.querySelectorAll('#target button').forEach((b) => b.addEventListener('click', () => {
  document.querySelectorAll('#target button').forEach((x) => x.classList.toggle('on', x === b));
  target = b.dataset.t; load();
}));
$('date').addEventListener('change', (e) => { follow = false; date = e.target.value; load(); });
$('prev').addEventListener('click', () => { follow = false; date = addDays(date, -1); load(); });
$('next').addEventListener('click', () => { follow = false; date = addDays(date, 1); load(); });
$('latest').addEventListener('click', async () => { follow = true; date = await latestDate(); load(); });
load();
