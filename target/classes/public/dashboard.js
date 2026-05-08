(() => {
    let MAX = 28800; const STORAGE_KEY = 'telemetry_layout';
    let windowSec = 300, paused = false, msgThisSec = 0;

    // O(1) circular buffer — no Array.shift() copies
    class RingBuffer {
        constructor(capacity) {
            this.buf = new Array(capacity);
            this.cap = capacity;
            this.head = 0;   // index of oldest element
            this.size = 0;
        }
        push(item) {
            const idx = (this.head + this.size) % this.cap;
            this.buf[idx] = item;
            if (this.size < this.cap) this.size++;
            else this.head = (this.head + 1) % this.cap;
        }
        get(i) { return this.buf[(this.head + i) % this.cap]; }
        // Return the last `n` items as a plain array (for ECharts)
        tail(n) {
            const count = Math.min(n, this.size);
            const start = this.size - count;
            const out = new Array(count);
            for (let i = 0; i < count; i++) out[i] = this.buf[(this.head + start + i) % this.cap];
            return out;
        }
        toArray() { return this.tail(this.size); }
    }

    const buffers = new Map();        // metricId -> RingBuffer
    const mstats = new Map();         // metricId -> {min,max,sum,sumSq,count,prevVal,lastVal}
    const panels = new Map();         // panelId -> {chart,el,seriesIds:[],colors:{},..}
    const metricToPanel = new Map();  // metricId -> panelId
    const allMetricIds = [];
    const statsConfig = new Map();     // metricName -> Set('min','max','avg')  — absent = show all
    const unitsConfig = new Map();     // metricId -> unit string

    const $ = id => document.getElementById(id);
    const dashEl = $('dashboard'), backdropEl = $('backdrop');

    // ── Layout persistence ──
    function loadLayout() { try { return JSON.parse(localStorage.getItem(STORAGE_KEY)) || {}; } catch { return {}; } }
    function saveLayout(p) { localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...loadLayout(), ...p })); }
    function savePanelOrder() {
        saveLayout({ panelOrder: [...dashEl.children].map(p => p.dataset.panelId).filter(Boolean) });
    }
    function saveMerges() {
        const g = {};
        panels.forEach((pd, pid) => { if (pd.seriesIds.length > 1) g[pid] = pd.seriesIds.slice(); });
        saveLayout({ mergedGroups: g });
    }
    (function restoreSettings() {
        const L = loadLayout();
        if (L.windowSec) { windowSec = L.windowSec; $('sel-window').value = String(windowSec); }
        if (L.searchFilter) $('search-box').value = L.searchFilter;
    })();

    // ── Search ──
    function applyFilter(t) {
        dashEl.querySelectorAll('.panel').forEach(p => {
            p.style.display = p.querySelector('.p-title').textContent.toLowerCase().includes(t) ? '' : 'none';
        });
    }
    $('search-box').addEventListener('input', e => { const t = e.target.value.toLowerCase(); applyFilter(t); saveLayout({ searchFilter: t }); });

    // ── Drag & Drop ──
    let draggedPanel = null;
    window.dragStart = (_, p) => { draggedPanel = p; };
    window.drop = (_, target) => {
        if (!draggedPanel || draggedPanel === target) return;
        const all = [...dashEl.children], di = all.indexOf(draggedPanel), ti = all.indexOf(target);
        di < ti ? target.after(draggedPanel) : target.before(draggedPanel);
        panels.forEach(pd => { if (pd.chart) setTimeout(() => pd.chart.resize(), 50); });
        savePanelOrder();
    };

    // ── Pause & Reset ──
    $('btn-pause').onclick = () => {
        paused = !paused;
        $('btn-pause').textContent = paused ? '▶ Paused' : '⏸ Live';
        $('btn-pause').classList.toggle('active', paused);
        $('pause-banner').classList.toggle('show', paused);
    };
    $('btn-reset').onclick = () => {
        localStorage.removeItem(STORAGE_KEY);
        location.reload();
    };
    $('sel-window').onchange = e => { windowSec = +e.target.value; saveLayout({ windowSec }); };

    // ── Colors & Formatting ──
    const palette = ['#73bf69', '#3274d9', '#f2cc0c', '#e02f44', '#8ab8ff', '#ff780a', '#e5a8e2', '#32d1df'];
    let ci = 0;
    function nextColor() { return palette[ci++ % palette.length]; }
    function fmt(v, mid) {
        if (v == null || isNaN(v)) return '–';
        const u = mid ? unitsConfig.get(mid) : null;
        if (u) {
            // Auto-scale when a unit is registered
            const a = Math.abs(v);
            const isBytes = (u === 'Bytes');
            if (a >= 1e9) return (v / 1e9).toFixed(2) + (isBytes ? ' GB' : 'G' + u);
            if (a >= 1e6) return (v / 1e6).toFixed(2) + (isBytes ? ' MB' : 'M' + u);
            if (a >= 1e4) return (v / 1e3).toFixed(1) + (isBytes ? ' KB' : 'K' + u);
            return (Number.isInteger(v) ? v.toLocaleString() : v.toFixed(2)) + (isBytes ? ' B' : u);
        }
        // Raw number — no scaling
        if (Number.isInteger(v)) return v.toLocaleString();
        return v.toFixed(4);
    }
    function label(id) { return id.replace(/^(jvm|custom)_/, '').replace(/_/g, ' '); }

    // ── Stats bar ──
    setInterval(() => {
        $('msg-rate').textContent = msgThisSec; msgThisSec = 0;
    }, 1000);
    function updateUptime(sec) {
        const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
        $('g-uptime').textContent = h ? `${h}h ${m}m ${s}s` : m ? `${m}m ${s}s` : `${s}s`;
    }

    // ── ECharts series builder ──
    function buildSeries(pd) {
        return pd.seriesIds.map((id, i) => ({
            name: label(id), type: 'line', showSymbol: false,
            lineStyle: { width: 1.5, color: pd.colors[id] },
            areaStyle: i === 0 ? {
                color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                    { offset: 0, color: pd.colors[id] + '33' }, { offset: 1, color: pd.colors[id] + '00' }
                ])
            } : null,
            data: []
        }));
    }

    // ── Legend builder ──
    function rebuildLegend(pd) {
        const el = pd.legendEl; el.innerHTML = '';
        pd.seriesIds.forEach(id => {
            const item = document.createElement('span');
            item.className = 'p-legend-item';
            const dot = `<span class="legend-dot" style="background:${pd.colors[id]}"></span>`;
            const valSpan = `<b id="lv-${pd.primaryId}-${id}">–</b>`;
            let remove = '';
            if (id !== pd.primaryId) {
                remove = `<button class="legend-remove" data-remove="${id}" title="Remove overlay">✕</button>`;
            }
            item.innerHTML = `${dot} ${label(id)}: ${valSpan} ${remove}`;
            el.appendChild(item);
        });
        el.querySelectorAll('.legend-remove').forEach(btn => {
            btn.onclick = () => removeOverlay(pd.primaryId, btn.dataset.remove);
        });
    }

    // ── Create Panel ──
    function createPanel(primaryId, title) {
        const color = nextColor();
        const panel = document.createElement('div');
        panel.className = 'panel';
        panel.dataset.panelId = primaryId;
        const sc = statsConfig.get(primaryId);
        const showMin = !sc || sc.has('min');
        const showMax = !sc || sc.has('max');
        const showAvg = !sc || sc.has('avg');
        panel.innerHTML = `
        <div class="p-head" draggable="true" ondragstart="dragStart(event,this.parentElement)" ondragover="event.preventDefault()" ondrop="drop(event,this.parentElement)">
            <div style="display:flex;align-items:center"><div class="p-title">${title}</div><span class="alert-badge" id="badge-${primaryId}"></span></div>
            <div class="p-ctrls" style="position:relative">
                <button class="p-btn" data-act="merge" title="Overlay metric">➕</button>
                <button class="p-btn" data-act="csv" title="Export CSV">📥</button>
                <button class="p-btn" data-act="max" title="Maximize">⛶</button>
            </div>
        </div>
        <div class="p-body">
            <div class="p-stat-row">
                <span class="p-stat-last" id="val-${primaryId}">–</span>
                ${showMin ? `<span class="p-stat-mini">min <b id="min-${primaryId}">–</b></span>` : ''}
                ${showMax ? `<span class="p-stat-mini">max <b id="max-${primaryId}">–</b></span>` : ''}
                ${showAvg ? `<span class="p-stat-mini">avg <b id="avg-${primaryId}">–</b></span>` : ''}
            </div>
            <div class="chart-wrap" id="cw-${primaryId}"></div>
        </div>
        <div class="p-legend" id="legend-${primaryId}"></div>`;

        // Merge button
        panel.querySelector('[data-act="merge"]').onclick = function (e) {
            e.stopPropagation(); showMergeDropdown(primaryId, this);
        };
        // CSV
        panel.querySelector('[data-act="csv"]').onclick = () => {
            const pd = panels.get(primaryId); if (!pd) return;
            const hdr = ['timestamp', ...pd.seriesIds.map(label)].join(',');
            const pBuf = buffers.get(pd.primaryId);
            if (!pBuf) return;
            const arr = pBuf.toArray();
            const rows = arr.map((_, i) => {
                const ts = arr[i][0];
                return [ts, ...pd.seriesIds.map(sid => { const b = buffers.get(sid); if (!b) return ''; const v = b.get(i); return v ? v[1] : ''; })].join(',');
            });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(new Blob([hdr + '\n' + rows.join('\n')], { type: 'text/csv' }));
            a.download = title.replace(/[^a-z0-9]/gi, '_').toLowerCase() + '.csv';
            a.click();
        };
        // Maximize
        panel.querySelector('[data-act="max"]').onclick = () => {
            const isMax = panel.classList.toggle('maximized');
            backdropEl.classList.toggle('active', isMax);
            setTimeout(() => panels.get(primaryId)?.chart?.resize(), 60);
        };

        // Insert at saved position
        const order = (loadLayout().panelOrder || []);
        const si = order.indexOf(primaryId);
        let ins = false;
        if (si >= 0) {
            for (const ch of dashEl.children) {
                if (order.indexOf(ch.dataset.panelId) > si) { dashEl.insertBefore(panel, ch); ins = true; break; }
            }
        }
        if (!ins) dashEl.appendChild(panel);

        // ECharts
        const cw = $(`cw-${primaryId}`);
        const chart = echarts.init(cw, null, { renderer: 'canvas' });
        chart.setOption({
            animation: false, backgroundColor: 'transparent', textStyle: { color: '#9fa3a6' },
            tooltip: {
                trigger: 'axis', backgroundColor: 'rgba(34,37,43,0.95)', borderColor: '#2c3235',
                textStyle: { color: '#d8d9da', fontSize: 12 }, axisPointer: { type: 'cross', label: { backgroundColor: '#22252b' } }
            },
            toolbox: {
                show: true, right: 8, top: 0, itemSize: 12,
                feature: {
                    dataZoom: {
                        yAxisIndex: 'none', title: { zoom: 'Drag to zoom', back: 'Undo zoom' },
                        iconStyle: { borderColor: '#6c7378' }, emphasis: { iconStyle: { borderColor: '#33a2e5' } }
                    },
                    restore: {
                        title: 'Reset zoom', iconStyle: { borderColor: '#6c7378' },
                        emphasis: { iconStyle: { borderColor: '#33a2e5' } }
                    }
                }
            },
            grid: { top: 8, bottom: 30, left: 10, right: 16, containLabel: true },
            dataZoom: [
                { type: 'inside', xAxisIndex: 0 },
                {
                    type: 'slider', xAxisIndex: 0, height: 18, bottom: 4, borderColor: '#2c3235',
                    fillerColor: 'rgba(50,116,217,0.15)', handleStyle: { color: '#3274d9' },
                    textStyle: { color: '#9fa3a6', fontSize: 10 },
                    dataBackground: { lineStyle: { color: '#2c3235' }, areaStyle: { color: '#2c3235' } }
                }
            ],
            xAxis: {
                type: 'time', splitLine: { show: true, lineStyle: { color: '#2c3235', type: 'dashed' } },
                axisLine: { lineStyle: { color: '#3a3f47' } }, axisLabel: { color: '#8e8e8e', fontSize: 10 }
            },
            yAxis: {
                type: 'value', splitLine: { show: true, lineStyle: { color: '#2c3235', type: 'dashed' } },
                axisLine: { show: false }, axisLabel: { color: '#8e8e8e', fontSize: 10, formatter: v => fmt(v, primaryId) }
            },
            series: [{
                name: label(primaryId), type: 'line', showSymbol: false,
                lineStyle: { width: 1.5, color: color },
                areaStyle: {
                    color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
                        { offset: 0, color: color + '55' }, { offset: 1, color: color + '00' }])
                },
                data: []
            }]
        });
        chart.on('restore', () => {
            const pd = panels.get(primaryId);
            if (!pd) return;
            pd.chart.setOption({ series: buildSeries(pd) }, { replaceMerge: ['series'] });
            // Re-apply thresholds by clearing applied set for this panel's metrics
            pd.seriesIds.forEach(mid => appliedThresholds.delete(mid));
        });
        new ResizeObserver(() => chart.resize()).observe(cw);

        const pd = {
            chart, el: panel, primaryId, seriesIds: [primaryId], colors: { [primaryId]: color },
            legendEl: $(`legend-${primaryId}`),
            valEl: $(`val-${primaryId}`), minEl: $(`min-${primaryId}`),
            maxEl: $(`max-${primaryId}`), avgEl: $(`avg-${primaryId}`)
        };
        panels.set(primaryId, pd);
        rebuildLegend(pd);
        return pd;
    }

    // ── Merge Dropdown ──
    let activeDd = null;
    function closeDd() { if (activeDd) { activeDd.remove(); activeDd = null; } }
    document.addEventListener('click', () => closeDd());

    function showMergeDropdown(panelId, btn) {
        closeDd();
        const pd = panels.get(panelId); if (!pd) return;
        const dd = document.createElement('div');
        dd.className = 'merge-dropdown';
        dd.innerHTML = '<div class="merge-dd-title">Overlay metric</div>';
        const available = allMetricIds.filter(id => !pd.seriesIds.includes(id));
        if (!available.length) {
            dd.innerHTML += '<div class="merge-item" style="color:var(--text-muted);cursor:default">No other metrics</div>';
        } else {
            available.forEach(id => {
                const item = document.createElement('div');
                item.className = 'merge-item';
                item.textContent = label(id);
                item.onclick = (e) => { e.stopPropagation(); addOverlay(panelId, id); closeDd(); };
                dd.appendChild(item);
            });
        }
        btn.parentElement.appendChild(dd);
        activeDd = dd;
        dd.onclick = e => e.stopPropagation();
    }

    // ── Overlay Management ──
    function addOverlay(panelId, metricId) {
        const pd = panels.get(panelId);
        if (!pd || pd.seriesIds.includes(metricId)) return;
        const color = nextColor();
        pd.seriesIds.push(metricId);
        pd.colors[metricId] = color;
        metricToPanel.set(metricId, panelId);

        // Hide standalone panel
        const standalone = panels.get(metricId);
        if (standalone && standalone !== pd) standalone.el.style.display = 'none';

        // Rebuild ECharts series
        pd.chart.setOption({ series: buildSeries(pd) }, { replaceMerge: ['series'] });
        rebuildLegend(pd);
        saveMerges();
        schedulePanelRender(panelId);
    }

    function removeOverlay(panelId, metricId) {
        const pd = panels.get(panelId); if (!pd) return;
        pd.seriesIds = pd.seriesIds.filter(id => id !== metricId);
        delete pd.colors[metricId];
        metricToPanel.set(metricId, metricId);

        // Show standalone panel again
        const standalone = panels.get(metricId);
        if (standalone) standalone.el.style.display = '';

        pd.chart.setOption({ series: buildSeries(pd) }, { replaceMerge: ['series'] });
        rebuildLegend(pd);
        saveMerges();
        schedulePanelRender(panelId);
    }

    // ── Metric Init ──
    function ensureMetric(metricId) {
        if (!mstats.has(metricId)) {
            mstats.set(metricId, { min: Infinity, max: -Infinity, sum: 0, count: 0, lastVal: null });
            buffers.set(metricId, new RingBuffer(MAX));
            allMetricIds.push(metricId);
        }
    }

    function initMetric(key, group) {
        const id = `${group}_${key}`;
        ensureMetric(id);

        if (panels.has(id) || metricToPanel.has(id)) return;

        // 1. Manual merge precedence (from localStorage)
        const merges = loadLayout().mergedGroups || {};
        let hostId = null;
        for (const [hId, members] of Object.entries(merges)) {
            if (members.includes(id) && hId !== id && panels.has(hId)) {
                hostId = hId; break;
            }
        }

        // 2. Auto-merge by tag prefix (e.g., "latency/venueA" auto-merges with "latency/venueB")
        const slashIdx = key.indexOf('/');
        if (!hostId && slashIdx > 0) {
            const prefix = `${group}_${key.substring(0, slashIdx)}/`;
            for (const existingId of panels.keys()) {
                if (existingId.startsWith(prefix)) {
                    hostId = existingId; break;
                }
            }
        }

        // Execute merge if host found
        if (hostId) {
            addOverlay(hostId, id);
            return;
        }

        // 3. Create standalone panel
        const displayKey = slashIdx > 0 ? key.substring(0, slashIdx) : key;
        const title = `${group.toUpperCase()} / ${displayKey}`;
        createPanel(id, title);
        metricToPanel.set(id, id);

        // If this new panel should host others that already exist
        if (merges[id]) {
            merges[id].forEach(mid => {
                if (mid !== id && mstats.has(mid)) addOverlay(id, mid);
            });
        }

        const f = $('search-box').value.toLowerCase();
        if (f) applyFilter(f);
    }

    // ── Batched Rendering ──
    let dirty = new Set(), rafId = null;
    function schedulePanelRender(panelId) { dirty.add(panelId); if (!rafId) rafId = requestAnimationFrame(flushRender); }

    function flushRender() {
        rafId = null;
        for (const panelId of dirty) {
            const pd = panels.get(panelId);
            if (!pd || !pd.chart) continue;

            const pts = windowSec === 0 ? MAX : windowSec;
            const seriesData = pd.seriesIds.map(mid => {
                const buf = buffers.get(mid);
                return buf ? buf.tail(pts) : [];
            });
            pd.chart.setOption({ series: seriesData.map(d => ({ data: d })) });

            // Update primary stats
            const ps = mstats.get(pd.primaryId);
            if (ps) {
                pd.valEl.textContent = fmt(ps.lastVal, pd.primaryId);
                if (pd.minEl) pd.minEl.textContent = fmt(ps.min, pd.primaryId);
                if (pd.maxEl) pd.maxEl.textContent = fmt(ps.max, pd.primaryId);
                if (pd.avgEl) pd.avgEl.textContent = ps.count ? fmt(ps.sum / ps.count, pd.primaryId) : '–';
            }

            // Update legend values
            pd.seriesIds.forEach(mid => {
                const ms = mstats.get(mid);
                const el = document.getElementById(`lv-${pd.primaryId}-${mid}`);
                if (el && ms) el.textContent = fmt(ms.lastVal, mid);
            });

            // Check thresholds for alert state
            let alertLevel = 0; // 0=ok, 1=warn, 2=crit
            pd.seriesIds.forEach(mid => {
                const th = thresholdMap.get(mid);
                if (!th) return;
                const ms = mstats.get(mid);
                if (!ms || ms.lastVal == null) return;
                if (th[1] && !isNaN(th[1]) && ms.lastVal >= th[1]) alertLevel = 2;
                else if (th[0] && !isNaN(th[0]) && ms.lastVal >= th[0] && alertLevel < 2) alertLevel = 1;
            });
            pd.el.classList.toggle('alert-warn', alertLevel === 1);
            pd.el.classList.toggle('alert-crit', alertLevel === 2);
            const badge = $(`badge-${pd.primaryId}`);
            if (badge) badge.textContent = alertLevel === 2 ? '🔴 CRIT' : alertLevel === 1 ? '⚠ WARN' : '';
        }
        dirty.clear();
    }

    // ── Ingest ──
    function ingest(key, group, ts, value) {
        const id = `${group}_${key}`;
        if (!mstats.has(id)) initMetric(key, group);

        const ms = mstats.get(id);
        buffers.get(id).push([ts, value]);

        ms.lastVal = value;

        ms.count++;
        ms.sum += value;
        if (value < ms.min) ms.min = value;
        if (value > ms.max) ms.max = value;

        const panelId = metricToPanel.get(id) || id;
        schedulePanelRender(panelId);
    }
    // ── Thresholds ──
    const appliedThresholds = new Set();
    const thresholdMap = new Map(); // metricId -> [warn, crit]
    function applyThresholds(thresholds) {
        for (const [metricName, levels] of Object.entries(thresholds)) {
            // Find the panel that owns this metric (try both groups)
            for (const group of ['jvm', 'custom']) {
                const id = `${group}_${metricName}`;
                const panelId = metricToPanel.get(id) || id;
                const pd = panels.get(panelId);
                if (!pd || appliedThresholds.has(id)) continue;
                appliedThresholds.add(id);
                thresholdMap.set(id, levels);

                const [warn, crit] = levels;
                const markData = [];
                if (warn && !isNaN(warn)) {
                    markData.push({
                        yAxis: warn,
                        lineStyle: { color: '#f2cc0c', width: 1.5, type: 'dashed' },
                        label: {
                            formatter: `warn ${fmt(warn, pd.primaryId)}`, position: 'insideEndTop',
                            color: '#f2cc0c', fontSize: 10
                        }
                    });
                }
                if (crit && !isNaN(crit)) {
                    markData.push({
                        yAxis: crit,
                        lineStyle: { color: '#e02f44', width: 1.5, type: 'dashed' },
                        label: {
                            formatter: `crit ${fmt(crit, pd.primaryId)}`, position: 'insideEndTop',
                            color: '#e02f44', fontSize: 10
                        }
                    });
                }
                if (markData.length) {
                    pd.chart.setOption({
                        series: [{ markLine: { silent: true, symbol: 'none', data: markData } }]
                    });
                }
            }
        }
    }

    // ── WebSocket ──
    function connect() {
        const ws = new WebSocket(`ws://${location.host}/ws/telemetry`);
        ws.onopen = () => { $('ws-dot').classList.add('on'); $('ws-label').textContent = 'Connected'; };
        ws.onclose = () => { $('ws-dot').classList.remove('on'); $('ws-label').textContent = 'Reconnecting…'; setTimeout(connect, 2000); };
        ws.onmessage = (e) => {
            msgThisSec++;
            if (paused) return;
            const data = JSON.parse(e.data), ts = data.timestamp;
            if (data.title && document.title !== data.title + " Dashboard") {
                document.title = data.title + " Dashboard";
                const titleEl = document.getElementById('g-title');
                if (titleEl) titleEl.textContent = data.title;
            }
            if (data.bufferCapacity && data.bufferCapacity > MAX) {
                MAX = data.bufferCapacity;
            }
            if (data.uptimeSec != null) updateUptime(data.uptimeSec);
            if (data.statsConfig) {
                for (const [name, stats] of Object.entries(data.statsConfig)) {
                    const s = new Set(stats);
                    statsConfig.set(`jvm_${name}`, s);
                    statsConfig.set(`custom_${name}`, s);
                }
            }
            if (data.units) {
                for (const [name, unit] of Object.entries(data.units)) {
                    unitsConfig.set(`jvm_${name}`, unit);
                    unitsConfig.set(`custom_${name}`, unit);
                }
            }
            if (data.thresholds) applyThresholds(data.thresholds);
            for (const group of ['jvm', 'custom']) {
                if (!data[group]) continue;
                for (const [key, val] of Object.entries(data[group])) {
                    if (typeof val === 'number') ingest(key, group, ts, val);
                }
            }
        };
    }
    connect();
})();
