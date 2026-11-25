// Incremental syntax highlighter.
// Architecture follows CodeMirror's classic design: a stateful line tokenizer
// whose state is cheap to copy, cached per line, so an edit only re-tokenizes
// from the changed line to the end of the visible window.

export type Lang = 'js' | 'html' | 'css' | 'py' | 'json' | 'md' | 'txt';

export interface Tok { t: string; c: string }

export interface HLState { block: boolean; str: string }

const JS_KW = new Set(('const let var function return if else for while do break continue new class extends import from export default ' +
    'try catch finally throw typeof instanceof in of async await switch case this null undefined true false yield static get set super ' +
    'delete void debugger with arguments').split(' '));
const PY_KW = new Set(('def return if elif else for while import from as class try except finally raise with lambda pass break continue ' +
    'in is not and or None True False global nonlocal assert del yield print len range str int float bool list dict set tuple input type ' +
    'super self cls open enumerate zip map filter sum min max abs sorted reversed').split(' '));
const JSON_KW = new Set(['true', 'false', 'null']);

const WORD_RE = /[A-Za-z_$؀-ۿ][\w$؀-ۿ]*|0x[0-9a-fA-F]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|\s+|./g;

function classifyPlain(chunk: string, kw: Set<string>, out: Tok[]) {
    WORD_RE.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = WORD_RE.exec(chunk))) {
        const w = m[0];
        let c = '';
        if (/^[\d]/.test(w) || /^0x/.test(w)) c = 'tok-num';
        else if (kw.has(w)) c = 'tok-kw';
        out.push({ t: w, c });
        if (m.index >= chunk.length) break;
    }
}

interface CodeCfg {
    kw: Set<string>;
    lineComment: string;
    blockOpen: string;
    blockClose: string;
    strings: string[];
    triples: string[];
}

const CFG: Record<string, CodeCfg> = {
    js: { kw: JS_KW, lineComment: '//', blockOpen: '/*', blockClose: '*/', strings: ["'", '"', '`'], triples: [] },
    py: { kw: PY_KW, lineComment: '#', blockOpen: '', blockClose: '', strings: ["'", '"'], triples: ["'''", '"""'] },
    json: { kw: JSON_KW, lineComment: '', blockOpen: '', blockClose: '', strings: ['"'], triples: [] },
};

function scanCode(line: string, st: HLState, cfg: CodeCfg): Tok[] {
    const toks: Tok[] = [];
    let i = 0;
    let plain = '';
    const flush = () => { if (plain) { classifyPlain(plain, cfg.kw, toks); plain = ''; } };
    while (i < line.length) {
        if (st.block) {
            flush();
            const end = line.indexOf(cfg.blockClose, i);
            if (end === -1) { toks.push({ t: line.slice(i), c: 'tok-com' }); i = line.length; }
            else { toks.push({ t: line.slice(i, end + cfg.blockClose.length), c: 'tok-com' }); i = end + cfg.blockClose.length; st.block = false; }
            continue;
        }
        if (st.str) {
            flush();
            let j = i;
            let end = -1;
            while (j < line.length) {
                const k = line.indexOf(st.str, j);
                if (k === -1) break;
                if (k > 0 && line[k - 1] === '\\') { j = k + st.str.length; continue; }
                end = k; break;
            }
            if (end === -1) { toks.push({ t: line.slice(i), c: 'tok-str' }); i = line.length; }
            else { toks.push({ t: line.slice(i, end + st.str.length), c: 'tok-str' }); i = end + st.str.length; st.str = ''; }
            continue;
        }
        const ch = line[i];
        if (cfg.lineComment && line.startsWith(cfg.lineComment, i)) {
            flush(); toks.push({ t: line.slice(i), c: 'tok-com' }); break;
        }
        if (cfg.blockOpen && line.startsWith(cfg.blockOpen, i)) {
            flush();
            const end = line.indexOf(cfg.blockClose, i + 2);
            if (end === -1) { toks.push({ t: line.slice(i), c: 'tok-com' }); st.block = true; i = line.length; }
            else { toks.push({ t: line.slice(i, end + 2), c: 'tok-com' }); i = end + 2; }
            continue;
        }
        let triple = '';
        for (const tr of cfg.triples) { if (line.startsWith(tr, i)) { triple = tr; break; } }
        if (triple) {
            flush();
            const end = line.indexOf(triple, i + 3);
            if (end === -1) { toks.push({ t: line.slice(i), c: 'tok-str' }); st.str = triple; i = line.length; }
            else { toks.push({ t: line.slice(i, end + 3), c: 'tok-str' }); i = end + 3; }
            continue;
        }
        if (cfg.strings.indexOf(ch) !== -1) {
            flush();
            let j = i + 1;
            let closed = false;
            while (j < line.length) {
                if (line[j] === '\\') { j += 2; continue; }
                if (line[j] === ch) { closed = true; break; }
                j++;
            }
            const stop = closed ? j + 1 : line.length;
            toks.push({ t: line.slice(i, stop), c: 'tok-str' });
            if (!closed && ch === '`') st.str = ch;
            i = stop;
            continue;
        }
        plain += ch; i++;
    }
    flush();
    return toks;
}

function scanMarkup(line: string, st: HLState): Tok[] {
    const toks: Tok[] = [];
    let i = 0;
    while (i < line.length) {
        if (st.str === '<!--') {
            const end = line.indexOf('-->', i);
            if (end === -1) { toks.push({ t: line.slice(i), c: 'tok-com' }); i = line.length; }
            else { toks.push({ t: line.slice(i, end + 3), c: 'tok-com' }); i = end + 3; st.str = ''; }
            continue;
        }
        if (line.startsWith('<!--', i)) {
            const end = line.indexOf('-->', i + 4);
            if (end === -1) { toks.push({ t: line.slice(i), c: 'tok-com' }); st.str = '<!--'; i = line.length; }
            else { toks.push({ t: line.slice(i, end + 3), c: 'tok-com' }); i = end + 3; }
            continue;
        }
        if (line[i] === '<') {
            const close = line.indexOf('>', i);
            const stop = close === -1 ? line.length : close + 1;
            const tag = line.slice(i, stop);
            const inner = /^<\/?[A-Za-z][\w-]*/.exec(tag);
            if (inner) {
                toks.push({ t: tag.slice(0, inner[0].indexOf(/[A-Za-z]/.exec(inner[0])![0])), c: 'tok-punc' });
                toks.push({ t: inner[0].slice(inner[0].indexOf(/[A-Za-z]/.exec(inner[0])![0])), c: 'tok-kw' });
                const rest = tag.slice(inner[0].length);
                const re = /([\w-]+)(=)("([^"]*)"|'([^']*)')?/g;
                let m: RegExpExecArray | null;
                let last = 0;
                while ((m = re.exec(rest))) {
                    if (m.index > last) toks.push({ t: rest.slice(last, m.index), c: 'tok-punc' });
                    toks.push({ t: m[1], c: 'tok-attr' });
                    if (m[2]) { toks.push({ t: m[2], c: 'tok-punc' }); toks.push({ t: m[3], c: 'tok-str' }); }
                    last = m.index + m[0].length;
                }
                if (last < rest.length) toks.push({ t: rest.slice(last), c: 'tok-punc' });
            } else {
                toks.push({ t: tag, c: 'tok-punc' });
            }
            i = stop;
            continue;
        }
        const next = line.indexOf('<', i);
        const stop = next === -1 ? line.length : next;
        toks.push({ t: line.slice(i, stop), c: '' });
        i = stop;
    }
    return toks;
}

function scanCss(line: string, st: HLState): Tok[] {
    if (st.block) {
        const end = line.indexOf('*/');
        if (end === -1) return [{ t: line, c: 'tok-com' }];
        st.block = false;
        return [{ t: line.slice(0, end + 2), c: 'tok-com' }, ...scanCss(line.slice(end + 2), st)];
    }
    const toks: Tok[] = [];
    const re = /\/\*|@([\w-]+)|("([^"]*)"|'([^']*)')|\b\d+(?:\.\d+)?(?:px|em|rem|%|s|ms|vh|vw|fr|deg)?\b|([\w-]+)(\s*:)|([.#]?[\w-]+(?:[.#][\w-]+)*)(\s*\{)/g;
    let m: RegExpExecArray | null;
    let last = 0;
    while ((m = re.exec(line))) {
        if (m.index > last) toks.push({ t: line.slice(last, m.index), c: '' });
        if (m[0] === '/*') {
            const end = line.indexOf('*/', m.index + 2);
            if (end === -1) { toks.push({ t: line.slice(m.index), c: 'tok-com' }); st.block = true; return toks; }
            toks.push({ t: line.slice(m.index, end + 2), c: 'tok-com' });
            re.lastIndex = end + 2;
        } else if (m[1]) toks.push({ t: m[0], c: 'tok-kw' });
        else if (m[2]) toks.push({ t: m[0], c: 'tok-str' });
        else if (m[5]) { toks.push({ t: m[5], c: 'tok-attr' }); toks.push({ t: m[6], c: '' }); }
        else if (m[7]) { toks.push({ t: m[7], c: 'tok-sel' }); toks.push({ t: m[8], c: '' }); }
        else toks.push({ t: m[0], c: 'tok-num' });
        last = re.lastIndex;
    }
    if (last < line.length) toks.push({ t: line.slice(last), c: '' });
    return toks;
}

function scanMd(line: string): Tok[] {
    const h = /^(#{1,6})\s(.*)$/.exec(line);
    if (h) return [{ t: h[1] + ' ', c: 'tok-kw' }, { t: h[2], c: 'tok-h' }];
    const toks: Tok[] = [];
    const re = /(\*\*[^*]+\*\*)|(`[^`]+`)|(\[[^\]]*\]\([^)]*\))|(\*[^*]+\*)/g;
    let m: RegExpExecArray | null;
    let last = 0;
    while ((m = re.exec(line))) {
        if (m.index > last) toks.push({ t: line.slice(last, m.index), c: '' });
        toks.push({ t: m[0], c: m[2] ? 'tok-str' : m[3] ? 'tok-attr' : 'tok-kw' });
        last = m.index + m[0].length;
    }
    if (last < line.length) toks.push({ t: line.slice(last), c: '' });
    return toks;
}

export function langOf(name: string): Lang {
    const ext = (name.split('.').pop() || '').toLowerCase();
    if (ext === 'js' || ext === 'ts' || ext === 'jsx' || ext === 'tsx' || ext === 'mjs') return 'js';
    if (ext === 'html' || ext === 'htm' || ext === 'xml' || ext === 'svg') return 'html';
    if (ext === 'css' || ext === 'scss') return 'css';
    if (ext === 'py') return 'py';
    if (ext === 'json') return 'json';
    if (ext === 'md' || ext === 'markdown') return 'md';
    return 'txt';
}

export class Highlighter {
    private lines: string[] = [];
    private states: HLState[] = [];
    private lang: Lang = 'txt';

    setText(text: string, lang: Lang) {
        const next = text.split('\n');
        if (lang !== this.lang) { this.lang = lang; this.states = []; }
        let same = 0;
        const limit = Math.min(next.length, this.lines.length, this.states.length);
        while (same < limit && next[same] === this.lines[same]) same++;
        this.lines = next;
        this.states.length = Math.min(same, next.length);
    }

    private ensure(n: number) {
        for (let i = this.states.length; i <= n && i < this.lines.length; i++) {
            const st: HLState = i === 0 ? { block: false, str: '' } : { ...this.states[i - 1] };
            if (this.lang === 'txt' || this.lang === 'md') { this.states.push(st); continue; }
            this.scan(this.lines[i], st);
            this.states.push(st);
        }
    }

    private scan(line: string, st: HLState): Tok[] {
        if (this.lang === 'html') return scanMarkup(line, st);
        if (this.lang === 'css') return scanCss(line, st);
        const cfg = CFG[this.lang];
        if (cfg) return scanCode(line, st, cfg);
        return [{ t: line, c: '' }];
    }

    tokens(from: number, to: number): Tok[][] {
        const a = Math.max(0, from);
        const b = Math.min(this.lines.length - 1, to);
        this.ensure(b);
        const out: Tok[][] = [];
        for (let i = a; i <= b; i++) {
            if (this.lang === 'md') out.push(scanMd(this.lines[i]));
            else if (this.lang === 'txt') out.push([{ t: this.lines[i], c: '' }]);
            else out.push(this.scan(this.lines[i], { ...this.states[i] }));
        }
        return out;
    }

    get lineCount() { return this.lines.length; }
}
