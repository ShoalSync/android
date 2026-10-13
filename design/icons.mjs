// Reads the icons in icons.json (Lucide's, by name) and icons/ (Tidefell's own), as path data each
// platform can draw. Lucide draws 24 by 24 with 2px round strokes; an icon of our own says its
// viewBox and is filled.
import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';

const n = (v) => Number(v);

/** An SVG shape as path data, so every platform needs to draw paths only. */
function toPath([tag, a]) {
  switch (tag) {
    case 'path':
      return a.d;
    case 'line':
      return `M${a.x1} ${a.y1}L${a.x2} ${a.y2}`;
    case 'polyline':
    case 'polygon': {
      const [first, ...rest] = a.points.trim().split(/\s+/);
      return `M${first}${rest.map((p) => `L${p}`).join('')}${tag === 'polygon' ? 'Z' : ''}`;
    }
    case 'circle':
      return ellipse(n(a.cx), n(a.cy), n(a.r), n(a.r));
    case 'ellipse':
      return ellipse(n(a.cx), n(a.cy), n(a.rx), n(a.ry));
    case 'rect': {
      const [x, y, w, h] = [n(a.x ?? 0), n(a.y ?? 0), n(a.width), n(a.height)];
      const rx = Math.min(n(a.rx ?? a.ry ?? 0), w / 2);
      const ry = Math.min(n(a.ry ?? a.rx ?? 0), h / 2);
      if (!rx && !ry) return `M${x} ${y}h${w}v${h}h${-w}Z`;
      return (
        `M${x + rx} ${y}h${w - 2 * rx}a${rx} ${ry} 0 0 1 ${rx} ${ry}v${h - 2 * ry}` +
        `a${rx} ${ry} 0 0 1 ${-rx} ${ry}h${-(w - 2 * rx)}a${rx} ${ry} 0 0 1 ${-rx} ${-ry}` +
        `v${-(h - 2 * ry)}a${rx} ${ry} 0 0 1 ${rx} ${-ry}Z`
      );
    }
    default:
      throw new Error(`An icon has a <${tag}>, which icons.mjs cannot draw`);
  }
}

const ellipse = (cx, cy, rx, ry) =>
  `M${cx - rx} ${cy}a${rx} ${ry} 0 1 0 ${2 * rx} 0a${rx} ${ry} 0 1 0 ${-2 * rx} 0Z`;

/**
 * Every icon: its name, viewport, whether it is stroked or filled, its paths, and its SVG for the
 * platforms that take SVG.
 */
export async function loadIcons(here) {
  const map = JSON.parse(await readFile(join(here, 'icons.json'), 'utf8')).lucide;
  const lucide = join(here, 'node_modules/lucide-static');
  const nodes = JSON.parse(await readFile(join(lucide, 'icon-nodes.json'), 'utf8'));
  const icons = [];
  for (const [name, source] of Object.entries(map)) {
    if (!nodes[source]) throw new Error(`Lucide has no icon ${source} (for ${name})`);
    const svg = await readFile(join(lucide, 'icons', `${source}.svg`), 'utf8');
    icons.push({ name, source: `Lucide ${source}`, viewport: 24, stroked: true, paths: nodes[source].map(toPath), svg });
  }
  for (const file of (await readdir(join(here, 'icons'))).filter((f) => f.endsWith('.svg')).sort()) {
    const svg = await readFile(join(here, 'icons', file), 'utf8');
    const viewBox = svg.match(/viewBox="0 0 (\d+) (\d+)"/);
    if (!viewBox || viewBox[1] !== viewBox[2]) throw new Error(`icons/${file} needs a square viewBox from 0 0`);
    if (/<(circle|rect|line|polyline|polygon|ellipse)\b/.test(svg)) throw new Error(`icons/${file}: draw it with <path> only`);
    const paths = [...svg.matchAll(/<path[^>]*\sd="([^"]+)"/g)].map((m) => m[1]);
    icons.push({ name: file.slice(0, -4), source: `icons/${file}`, viewport: n(viewBox[1]), stroked: false, paths, svg });
  }
  const names = icons.map((i) => i.name);
  const twice = names.find((x, i) => names.indexOf(x) !== i);
  if (twice) throw new Error(`Two icons are named ${twice}`);
  return icons;
}
