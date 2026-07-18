#!/usr/bin/env node
// Mental brand-asset generator — deterministic SVG output, zero dependencies (Node 18+).
// All text is drawn as vector paths (no fonts). Run from repo root.
//
//   node scripts/generate-brand-assets.mjs all                      # heroes, release banners, buttons, divider, social SVG
//   node scripts/generate-brand-assets.mjs header <slug> "TEXT"     # project/assets/headers/<slug>.svg   (54px README section header)
//   node scripts/generate-brand-assets.mjs release-header <slug> "TEXT"  # project/assets/release/headers/<slug>.svg (42px)
//   node scripts/generate-brand-assets.mjs button <slug> "TEXT" [filled] # project/assets/buttons/<slug>.svg (46px)
//
// project/assets/social-preview.png must be re-rasterized after `all` (any SVG->PNG at 1280x640),
// e.g.: npx @resvg/resvg-js-cli project/assets/social-preview.src.svg -o project/assets/social-preview.png
import { writeFileSync, mkdirSync } from 'node:fs';

export const ACC = '#FF4655';                 // Mental ember/red accent (static, both themes)
export const GRAY = '#828994';                // static gray, legible on light + dark GitHub
export const DARK  = { bg:'#0C0E12', border:'#2A2F38', ink:'#EDEAE4', muted:'#8A919C', rule:'#333944', accent:ACC };
export const LIGHT = { bg:'#F8F6F2', border:'#DFDAD1', ink:'#1C1E23', muted:'#6E7580', rule:'#D8D3C9', accent:ACC };

// ——— stroke font (single-stroke engineered glyphs, cap height 100, y=0 top / 100 baseline) ———
// Used for headers, buttons, taglines, labels. Stroke width 15u at render (19u for buttons).
export const F = {
'A':{w:70,l:[[[0,100],[35,0],[70,100]],[[13,68],[57,68]]]},
'B':{w:56,l:[[[0,0],[0,100]],[[0,0],[42,0],[54,10],[54,38],[42,48],[0,48]],[[42,48],[56,58],[56,90],[44,100],[0,100]]]},
'C':{w:64,l:[[[64,13],[51,0],[15,0],[0,14],[0,86],[15,100],[51,100],[64,87]]]},
'D':{w:64,l:[[[0,0],[42,0],[64,18],[64,82],[42,100],[0,100],[0,0]]]},
'E':{w:56,l:[[[56,0],[0,0],[0,100],[56,100]],[[0,48],[44,48]]]},
'F':{w:56,l:[[[56,0],[0,0],[0,100]],[[0,48],[42,48]]]},
'G':{w:66,l:[[[66,13],[53,0],[15,0],[0,14],[0,86],[15,100],[53,100],[66,87],[66,50],[40,50]]]},
'H':{w:62,l:[[[0,0],[0,100]],[[62,0],[62,100]],[[0,48],[62,48]]]},
'I':{w:14,l:[[[7,0],[7,100]]]},
'J':{w:48,l:[[[48,0],[48,84],[34,100],[13,100],[0,88]]]},
'K':{w:60,l:[[[0,0],[0,100]],[[58,0],[2,50]],[[22,36],[60,100]]]},
'L':{w:52,l:[[[0,0],[0,100],[52,100]]]},
'M':{w:84,l:[[[0,100],[0,0],[42,56],[84,0],[84,100]]]},
'N':{w:64,l:[[[0,100],[0,0],[64,100],[64,0]]]},
'O':{w:68,l:[[[20,0],[48,0],[68,16],[68,84],[48,100],[20,100],[0,84],[0,16],[20,0]]]},
'P':{w:58,l:[[[0,100],[0,0],[44,0],[58,12],[58,40],[44,52],[0,52]]]},
'Q':{w:68,l:[[[20,0],[48,0],[68,16],[68,84],[48,100],[20,100],[0,84],[0,16],[20,0]],[[46,72],[70,102]]]},
'R':{w:60,l:[[[0,100],[0,0],[44,0],[58,12],[58,40],[44,52],[0,52]],[[28,52],[60,100]]]},
'S':{w:58,l:[[[58,12],[46,0],[14,0],[2,11],[2,38],[13,48],[45,52],[56,62],[56,89],[44,100],[12,100],[0,88]]]},
'T':{w:60,l:[[[0,0],[60,0]],[[30,0],[30,100]]]},
'U':{w:62,l:[[[0,0],[0,84],[16,100],[46,100],[62,84],[62,0]]]},
'V':{w:66,l:[[[0,0],[33,100],[66,0]]]},
'W':{w:92,l:[[[0,0],[20,100],[46,30],[72,100],[92,0]]]},
'X':{w:62,l:[[[0,0],[62,100]],[[62,0],[0,100]]]},
'Y':{w:62,l:[[[0,0],[31,46],[62,0]],[[31,46],[31,100]]]},
'Z':{w:58,l:[[[0,0],[58,0],[0,100],[58,100]]]},
'0':{w:58,l:[[[16,0],[42,0],[58,14],[58,86],[42,100],[16,100],[0,86],[0,14],[16,0]]]},
'1':{w:24,l:[[[0,16],[20,0],[20,100]]]},
'2':{w:58,l:[[[2,12],[14,0],[46,0],[58,12],[58,36],[0,100],[58,100]]]},
'8':{w:58,l:[[[15,0],[43,0],[54,10],[54,38],[43,47],[15,47],[4,38],[4,10],[15,0]],[[13,47],[45,47],[58,58],[58,89],[46,100],[12,100],[0,89],[0,58],[13,47]]]},
'.':{w:12,l:[[[6,90],[6,100]]]},
',':{w:12,l:[[[7,88],[7,100],[2,110]]]},
'-':{w:40,l:[[[0,50],[40,50]]]},
'&':{w:66,l:[[[62,52],[24,96],[10,96],[2,88],[2,74],[46,32],[46,12],[36,0],[20,0],[10,10],[10,26],[64,96]]]},
};
// NOTE: '8' second loop above must start [13,47]... if a digit/letter is missing, author it in this
// style: straight segments + faceted corners, drawn on a 100-tall box starting at x=0, w = true bbox.

// ——— wordmark glyphs (FILLED Valorant-style letterforms; bbox-exact: x spans 0..w) ———
// M: traced from the owner's reference, 25% narrowed, stroke t=19.3, parametric junctions.
export const VG = {
'M':{w:109,p:[[[0,100],[44.7,0],[62,0],[62,69],[89.7,0],[109,0],[109,100],[89.7,100],[89.7,48.1],[68.9,100],[42.7,100],[42.7,47.7],[19.3,100]]]},
'E':{w:54,p:[[[0,0],[54,0],[48,20],[21,20],[21,40],[46,40],[40,60],[21,60],[21,80],[54,80],[54,100],[0,100]]]},
'N':{w:66,p:[[[0,100],[0,0],[21,0],[45,56],[45,0],[66,0],[66,100],[45,100],[21,44],[21,100]]]},
'T':{w:58,p:[[[0,0],[58,0],[52,20],[39,20],[39,100],[19,100],[19,20],[0,20]]]},
'A':{w:66,p:[[[0,100],[24,0],[42,0],[66,100],[45,100],[33,44],[21,100]]]},
'L':{w:50,p:[[[0,0],[21,0],[21,80],[50,80],[50,92],[42,100],[0,100]]]},
};
// Optical compensation: A and L sit 10u left of their metric slot (closes the T's open right cutout).
export const SHIFT = { A:-10, L:-10 };
export const WORD_GAP = cap => 0.16*cap;   // uniform bbox-edge-to-edge letter gap

export function measure(t,cap,tr){const s=cap/100;let w=0;for(const ch of t){w+=(ch===' '?44:(F[ch]?F[ch].w:44))*s+tr;}return w-tr;}
export function drawText(t,x,y,cap,color,swU,tr){const s=cap/100;let cx=x,out='';for(const ch of t){if(ch===' '||!F[ch]){cx+=44*s+tr;continue;}for(const pl of F[ch].l){out+=`<polyline points="${pl.map(p=>((cx+p[0]*s).toFixed(1)+','+(y+(p[1]-100)*s).toFixed(1))).join(' ')}" fill="none" stroke="${color}" stroke-width="${(swU*s).toFixed(2)}" stroke-linecap="square" stroke-linejoin="round"/>`;}cx+=F[ch].w*s+tr;}return out;}
export function valText(t,x,y,cap,color){const gap=WORD_GAP(cap),s=cap/100;let cx=x,out='';for(const ch of t){const g=VG[ch];if(!g){cx+=44*s+gap;continue;}const dx=(SHIFT[ch]||0)*s;for(const poly of g.p)out+=`<polygon points="${poly.map(p=>((cx+dx+p[0]*s).toFixed(1)+','+(y+(p[1]-100)*s).toFixed(1))).join(' ')}" fill="${color}"/>`;cx+=g.w*s+gap;}return out;}
export function valWidth(t,cap){const gap=WORD_GAP(cap),s=cap/100;let w=0;for(const ch of t)w+=(VG[ch]?VG[ch].w:44)*s+gap;return w-gap-10*s;}
export function marker(cx,cy,r,color,sw){const a=r*0.40,b=r*0.94;let s='';for(const[dx,dy]of[[1,1],[1,-1],[-1,1],[-1,-1]])s+=`<line x1="${(cx+dx*a).toFixed(1)}" y1="${(cy+dy*a).toFixed(1)}" x2="${(cx+dx*b).toFixed(1)}" y2="${(cy+dy*b).toFixed(1)}" stroke="${color}" stroke-width="${sw}" stroke-linecap="square"/>`;const d=sw*0.78;return s+`<rect x="${(cx-d).toFixed(1)}" y="${(cy-d).toFixed(1)}" width="${(d*2).toFixed(1)}" height="${(d*2).toFixed(1)}" fill="${color}" transform="rotate(45 ${cx} ${cy})"/>`;}
export const SVG=(w,h,body)=>`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${body}</svg>`;
export const cutPanel=(x,y,w,h,c)=>`M${x+c},${y} H${x+w} V${y+h-c} L${x+w-c},${y+h} H${x} V${y+c} Z`;
const ruler=(w,h,n,T,sw)=>{let b='';for(let i=1;i<n;i++){const tx=(w/n)*i;b+=`<line x1="${tx}" y1="1.5" x2="${tx}" y2="7.5" stroke="${T.rule}" stroke-width="${sw}"/><line x1="${tx}" y1="${h-1.5}" x2="${tx}" y2="${h-7.5}" stroke="${T.rule}" stroke-width="${sw}"/>`;}return b;};
const TAG='ASYNC PVP & CLASSIC COMBAT';

export function hero(T){
  const w=860,h=240,cap=78,mR=30,mGap=32;
  const wordW=valWidth('MENTAL',cap),total=mR*2+mGap+wordW,x0=(w-total)/2,base=h*0.585;
  let b=`<path d="${cutPanel(1.5,1.5,w-3,h-3,16)}" fill="${T.bg}" stroke="${T.border}" stroke-width="2"/>`+ruler(w,h,12,T,1.5);
  b+=marker(x0+mR,base-cap*0.5,mR,T.accent,7)+valText('MENTAL',x0+mR*2+mGap,base,cap,T.ink);
  const tcap=13,ttr=6,tw=measure(TAG,tcap,ttr),ty=base+45,rl=48,rg=20,tx0=(w-tw)/2;
  b+=`<line x1="${tx0-rg-rl}" y1="${ty-tcap*0.42}" x2="${tx0-rg}" y2="${ty-tcap*0.42}" stroke="${T.rule}" stroke-width="1.5"/>`;
  b+=`<line x1="${tx0+tw+rg}" y1="${ty-tcap*0.42}" x2="${tx0+tw+rg+rl}" y2="${ty-tcap*0.42}" stroke="${T.rule}" stroke-width="1.5"/>`;
  b+=drawText(TAG,tx0,ty,tcap,T.muted,14,ttr);
  return SVG(w,h,b);
}
export function banner(T){
  const w=860,h=120,cap=44,mR=17,base=(h+cap)/2;
  let b=`<path d="${cutPanel(1.5,1.5,w-3,h-3,12)}" fill="${T.bg}" stroke="${T.border}" stroke-width="2"/>`+ruler(w,h,12,T,1.5);
  b+=marker(42+mR,base-cap*0.5,mR,T.accent,4.5)+valText('MENTAL',42+mR*2+20,base,cap,T.ink);
  const label='RELEASE',lcap=15,ltr=6.5,lw=measure(label,lcap,ltr),lx=w-42-lw,lbase=(h+lcap)/2;
  b+=`<line x1="${lx-24-52}" y1="${lbase-lcap*0.42}" x2="${lx-24}" y2="${lbase-lcap*0.42}" stroke="${T.rule}" stroke-width="1.5"/>`;
  b+=drawText(label,lx,lbase,lcap,T.accent,15,ltr);
  return SVG(w,h,b);
}
export function social(){
  const T=DARK,w=1280,h=640,cap=128,mR=52,mGap=54;
  let b=`<rect width="${w}" height="${h}" fill="${T.bg}"/><rect x="24" y="24" width="${w-48}" height="${h-48}" fill="none" stroke="${T.border}" stroke-width="2"/>`;
  for(let i=1;i<16;i++){const tx=24+((w-48)/16)*i;b+=`<line x1="${tx}" y1="24" x2="${tx}" y2="38" stroke="${T.rule}" stroke-width="2"/><line x1="${tx}" y1="${h-24}" x2="${tx}" y2="${h-38}" stroke="${T.rule}" stroke-width="2"/>`;}
  const wordW=valWidth('MENTAL',cap),total=mR*2+mGap+wordW,x0=(w-total)/2,base=372;
  b+=marker(x0+mR,base-cap*0.5,mR,T.accent,12)+valText('MENTAL',x0+mR*2+mGap,base,cap,T.ink);
  const tcap=22,ttr=10,tw=measure(TAG,tcap,ttr),tx0=(w-tw)/2,ty=base+80,rl=84,rg=34;
  b+=`<line x1="${tx0-rg-rl}" y1="${ty-tcap*0.42}" x2="${tx0-rg}" y2="${ty-tcap*0.42}" stroke="${T.rule}" stroke-width="2"/>`;
  b+=`<line x1="${tx0+tw+rg}" y1="${ty-tcap*0.42}" x2="${tx0+tw+rg+rl}" y2="${ty-tcap*0.42}" stroke="${T.rule}" stroke-width="2"/>`;
  b+=drawText(TAG,tx0,ty,tcap,T.muted,14,ttr);
  return SVG(w,h,b);
}
export function header(text){        // 54px README section header
  const cap=20,tr=6.2,mR=10,gap=17,h=54;
  const tw=measure(text,cap,tr),w=Math.ceil(mR*2+gap+tw+4),base=(h+cap)/2;
  return SVG(w,h,marker(mR+1,base-cap*0.5,mR,ACC,3)+drawText(text,mR*2+gap,base,cap,ACC,15,tr));
}
export function releaseHeader(text){ // 42px release-notes header
  const cap=16,tr=5,mR=8,gap=14,h=42;
  const tw=measure(text,cap,tr),w=Math.ceil(mR*2+gap+tw+4),base=(h+cap)/2;
  return SVG(w,h,marker(mR+1,base-cap*0.5,mR,ACC,2.5)+drawText(text,mR*2+gap,base,cap,ACC,15,tr));
}
export function button(label,filled){ // 46px download/releases-style button
  const cap=13.5,tr=4,h=46,pad=26,tw=measure(label,cap,tr);
  const iconW=filled?12+15:0,w=Math.ceil(pad+iconW+tw+pad),base=(h+cap)/2;
  const shape=`M0,0 H${w} V${h-9} L${w-9},${h} H0 Z`;let b='';
  if(filled){
    b+=`<path d="${shape}" fill="${ACC}"/>`;const icx=pad+6,c='#F7F2EC';
    b+=`<line x1="${icx}" y1="15" x2="${icx}" y2="26" stroke="${c}" stroke-width="2.4" stroke-linecap="square"/>`;
    b+=`<polyline points="${icx-4.5},22 ${icx},27.5 ${icx+4.5},22" fill="none" stroke="${c}" stroke-width="2.4" stroke-linecap="square"/>`;
    b+=`<line x1="${icx-6}" y1="31.5" x2="${icx+6}" y2="31.5" stroke="${c}" stroke-width="2.4" stroke-linecap="square"/>`;
    b+=drawText(label,pad+iconW,base,cap,'#F7F2EC',19,tr);
  } else {
    b+=`<path d="${shape}" fill="none" stroke="${GRAY}" stroke-width="2.5"/>`+drawText(label,pad,base,cap,GRAY,19,tr);
  }
  return SVG(w,h,b);
}
export function divider(){
  const w=260,h=22,cy=11;
  return SVG(w,h,`<line x1="0" y1="${cy}" x2="102" y2="${cy}" stroke="${GRAY}" stroke-width="1.6"/>`+marker(130,cy,9,ACC,2.8)+`<line x1="158" y1="${cy}" x2="260" y2="${cy}" stroke="${GRAY}" stroke-width="1.6"/>`);
}

// ——— CLI ———
const [,,cmd,a,b,c] = process.argv;
const put=(p,s)=>{mkdirSync(p.replace(/\/[^/]+$/,''),{recursive:true});writeFileSync(p,s);console.log('wrote',p);};
if (cmd==='all'){
  put('project/assets/hero-dark.svg',hero(DARK)); put('project/assets/hero.svg',hero(LIGHT));
  put('project/assets/release/banner-dark.svg',banner(DARK)); put('project/assets/release/banner.svg',banner(LIGHT));
  put('project/assets/buttons/download.svg',button('DOWNLOAD LATEST',true)); put('project/assets/buttons/releases.svg',button('ALL RELEASES',false));
  put('project/assets/divider.svg',divider()); put('project/assets/social-preview.src.svg',social());
  console.log('NOTE: rasterize project/assets/social-preview.src.svg -> project/assets/social-preview.png (1280x640)');
} else if (cmd==='header' && a && b) put(`project/assets/headers/${a}.svg`, header(b.toUpperCase()));
else if (cmd==='release-header' && a && b) put(`project/assets/release/headers/${a}.svg`, releaseHeader(b.toUpperCase()));
else if (cmd==='button' && a && b) put(`project/assets/buttons/${a}.svg`, button(b.toUpperCase(), c==='filled'));
else { console.log('usage: all | header <slug> "TEXT" | release-header <slug> "TEXT" | button <slug> "TEXT" [filled]'); process.exit(1); }
