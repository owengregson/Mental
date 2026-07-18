#!/usr/bin/env node
// Renders Mental's bStats player chart (last 14 days) into project/assets/stats/{players.svg,players-dark.svg}.
// Run from repo root: node scripts/render-bstats-chart.mjs
// The README's <picture> paths never change — this script just refreshes the files.
const PLUGIN_ID = 31788;
const MAX_ELEMENTS = 672; // 14 days of 30-minute datapoints
const DAYS = 14;

function renderChart(points, T) {
  const W=900, H=300, padL=58, padR=26, padT=56, padB=40;
  const iw=W-padL-padR, ih=H-padT-padB;
  const MON=['JAN','FEB','MAR','APR','MAY','JUN','JUL','AUG','SEP','OCT','NOV','DEC'];
  // bucket to ~140 samples (mean)
  const N=140, t0=points[0][0], t1=points[points.length-1][0], span=t1-t0||1;
  const sums=new Array(N).fill(0), cnts=new Array(N).fill(0);
  for (const [ts,v] of points){ const b=Math.min(N-1,Math.floor((ts-t0)/span*N)); sums[b]+=v; cnts[b]++; }
  const vals=[]; for(let i=0;i<N;i++){ if(cnts[i]) vals.push([t0+span*(i+0.5)/N, sums[i]/cnts[i]]); }
  const vmax=Math.max(...vals.map(p=>p[1]),1);
  const step=[1,2,2.5,5,10].map(m=>m*Math.pow(10,Math.floor(Math.log10(vmax/4)))).find(s=>s*4>=vmax)|| Math.ceil(vmax/4);
  const ymax=step*4;
  const X=ts=>padL+(ts-t0)/span*iw, Y=v=>padT+ih-(v/ymax)*ih;
  // smooth path (catmull-rom → cubic bezier)
  const pts=vals.map(([ts,v])=>[X(ts),Y(v)]);
  let d=`M${pts[0][0].toFixed(1)},${pts[0][1].toFixed(1)}`;
  for(let i=0;i<pts.length-1;i++){
    const p0=pts[Math.max(0,i-1)],p1=pts[i],p2=pts[i+1],p3=pts[Math.min(pts.length-1,i+2)];
    const c1=[p1[0]+(p2[0]-p0[0])/6,p1[1]+(p2[1]-p0[1])/6], c2=[p2[0]-(p3[0]-p1[0])/6,p2[1]-(p3[1]-p1[1])/6];
    d+=`C${c1[0].toFixed(1)},${c1[1].toFixed(1)} ${c2[0].toFixed(1)},${c2[1].toFixed(1)} ${p2[0].toFixed(1)},${p2[1].toFixed(1)}`;
  }
  const area=d+`L${pts[pts.length-1][0].toFixed(1)},${(padT+ih).toFixed(1)} L${pts[0][0].toFixed(1)},${(padT+ih).toFixed(1)} Z`;
  const mono='ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
  let g='';
  // panel (cut corners) + brand ruler ticks
  g+=`<path d="M13.5,1.5 H${W-1.5} V${H-13.5} L${W-13.5},${H-1.5} H1.5 V13.5 Z" fill="${T.bg}" stroke="${T.border}" stroke-width="2"/>`;
  for(let i=1;i<12;i++){const tx=(W/12)*i;g+=`<line x1="${tx}" y1="1.5" x2="${tx}" y2="7.5" stroke="${T.rule}" stroke-width="1.5"/><line x1="${tx}" y1="${H-1.5}" x2="${tx}" y2="${H-7.5}" stroke="${T.rule}" stroke-width="1.5"/>`;}
  // header: title
  g+=`<text x="${padL}" y="31" font-family="${mono}" font-size="13" letter-spacing="3" fill="${T.text}">PLAYERS — LAST 14 DAYS</text>`;
  // grid + y labels
  for(let i=0;i<=4;i++){
    const v=step*i, y=Y(v);
    g+=`<line x1="${padL}" y1="${y.toFixed(1)}" x2="${W-padR}" y2="${y.toFixed(1)}" stroke="${T.grid}" stroke-width="1"/>`;
    g+=`<text x="${padL-10}" y="${(y+4).toFixed(1)}" text-anchor="end" font-family="${mono}" font-size="12" fill="${T.text}">${v%1?v.toFixed(1):v}</text>`;
  }
  // x labels: ~every 3.5 days
  for(let i=0;i<=4;i++){
    const ts=t0+span*i/4, dt=new Date(ts);
    const lab=`${MON[dt.getUTCMonth()]} ${dt.getUTCDate()}`;
    const anchor=i===0?'start':(i===4?'end':'middle');
    g+=`<text x="${X(ts).toFixed(1)}" y="${H-padB+24}" text-anchor="${anchor}" font-family="${mono}" font-size="12" letter-spacing="1" fill="${T.text}">${lab}</text>`;
  }
  // dotted texture pattern + gradient area + line
  g=`<defs><pattern id="dots" width="14" height="14" patternUnits="userSpaceOnUse"><circle cx="7" cy="7" r="1.4" fill="${T.accent}" fill-opacity="0.10"/></pattern><linearGradient id="fade" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${T.accent}" stop-opacity="0.28"/><stop offset="1" stop-color="${T.accent}" stop-opacity="0.02"/></linearGradient></defs>`+g;
  g+=`<path d="${area}" fill="url(#fade)"/>`;
  g+=`<path d="${area}" fill="url(#dots)"/>`;
  g+=`<path d="${d}" fill="none" stroke="${T.accent}" stroke-width="2.6" stroke-linejoin="round" stroke-linecap="round"/>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${g}</svg>`;
}

const DARK={"bg":"#0C0E12","border":"#2A2F38","rule":"#333944","grid":"#1D2129","text":"#8A919C","accent":"#FF4655"};
const LIGHT={"bg":"#F8F6F2","border":"#DFDAD1","rule":"#D8D3C9","grid":"#E7E2D9","text":"#6E7580","accent":"#FF4655"};

const api = `https://bstats.org/api/v1/plugins/${PLUGIN_ID}/charts/players/data?maxElements=${MAX_ELEMENTS}`;
const res = await fetch(api, { headers: { accept: 'application/json' } });
if (!res.ok) { console.error('bStats API', res.status); process.exit(1); }
let data = await res.json(); // [[timestampMs, players], ...]
const cutoff = Date.now() - DAYS*86400000;
data = data.filter(p => Array.isArray(p) && p[0] >= cutoff && Number.isFinite(p[1])).sort((a,b)=>a[0]-b[0]);
if (data.length < 2) { console.error('not enough datapoints:', data.length); process.exit(1); }
const { writeFileSync, mkdirSync } = await import('node:fs');
mkdirSync('project/assets/stats', { recursive: true });
writeFileSync('project/assets/stats/players-dark.svg', renderChart(data, DARK));
writeFileSync('project/assets/stats/players.svg', renderChart(data, LIGHT));
console.log(`rendered ${data.length} datapoints, latest=${Math.round(data[data.length-1][1])}`);
