import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Highlighter, Lang, Tok, langOf } from './highlight';
import { CopyText, usePersistentState } from './platform';

// ---------- Types ----------

interface PsFile { id: string; name: string; content: string }
interface Settings { fontSize: number }
interface Project { files: PsFile[]; openId: string; settings: Settings }
interface LogEntry { kind: 'log' | 'warn' | 'error' | 'result' | 'info'; text: string }
type PanelKind = 'console' | 'preview' | null;

const STORE_LIMIT = 60000;

let uidCounter = 0;
function uid(): string {
    uidCounter += 1;
    return 'f' + Date.now().toString(36) + uidCounter.toString(36);
}

function templateFor(name: string): string {
    const lang = langOf(name);
    if (lang === 'html') return '<!DOCTYPE html>\n<html dir="rtl" lang="ar">\n<head>\n  <meta charset="utf-8">\n  <meta name="viewport" content="width=device-width, initial-scale=1">\n  <title>صفحة جديدة</title>\n  <style>\n    body { font-family: sans-serif; padding: 20px; }\n  </style>\n</head>\n<body>\n  <h1>أهلاً!</h1>\n</body>\n</html>\n';
    if (lang === 'css') return 'body {\n  \n}\n';
    if (lang === 'py') return '# بايثون\n\nprint("أهلاً!")\n';
    if (lang === 'json') return '{\n  \n}\n';
    if (lang === 'md') return '# عنوان\n\n';
    if (lang === 'txt') return '';
    return '// جافاسكريبت\n\nconsole.log(\'أهلاً!\');\n';
}

function seedProject(): Project {
    const a: PsFile = {
        id: 'seed-js', name: 'مرحبا.js',
        content: '// أول كود ليك هنا\n// دوس "تشغيل" فوق عشان تشوف النتيجة تحت\n\nfunction تحية(اسم) {\n  return \'أهلاً يا \' + اسم + \'!\';\n}\n\nconsole.log(تحية(\'محمود\'));\n\nfor (let i = 1; i <= 5; i++) {\n  console.log(\'سطر رقم \' + i);\n}\n\nconst أرقام = [1, 2, 3, 4];\nconsole.log(\'المجموع:\', أرقام.reduce((a, b) => a + b, 0));\n',
    };
    const b: PsFile = {
        id: 'seed-html', name: 'صفحة.html',
        content: '<!DOCTYPE html>\n<html dir="rtl" lang="ar">\n<head>\n  <meta charset="utf-8">\n  <style>\n    body { font-family: sans-serif; background: #f4efec; padding: 24px; }\n    .card { background: #fff; border-radius: 16px; padding: 20px; box-shadow: 0 2px 10px rgba(0,0,0,.08); }\n    h1 { color: #0e639c; }\n  </style>\n</head>\n<body>\n  <div class="card">\n    <h1>صفحتي الأولى</h1>\n    <p>عدّل الكود ودوس "معاينة" عشان تشوف النتيجة.</p>\n  </div>\n</body>\n</html>\n',
    };
    return { files: [a, b], openId: a.id, settings: { fontSize: 14 } };
}

function normalizeProject(p: any): Project | null {
    if (!p || !Array.isArray(p.files) || p.files.length === 0) return null;
    const files: PsFile[] = p.files
        .filter((f: any) => f && typeof f.name === 'string' && typeof f.content === 'string')
        .map((f: any) => ({ id: typeof f.id === 'string' ? f.id : uid(), name: f.name, content: f.content }));
    if (files.length === 0) return null;
    const openId = files.some((f) => f.id === p.openId) ? p.openId : files[0].id;
    const fontSize = p.settings && typeof p.settings.fontSize === 'number' ? Math.min(22, Math.max(11, p.settings.fontSize)) : 14;
    return { files, openId, settings: { fontSize } };
}

// ---------- Chunked persistence (4 fixed keys, about 60 KB total) ----------

function useProjectStore() {
    const [c0, setC0, s0] = usePersistentState('pstudio1.a', '');
    const [c1, setC1, s1] = usePersistentState('pstudio1.b', '');
    const [c2, setC2, s2] = usePersistentState('pstudio1.c', '');
    const [c3, setC3, s3] = usePersistentState('pstudio1.d', '');
    const ready = s0.ready && s1.ready && s2.ready && s3.ready;
    const message = s0.message || s1.message || s2.message || s3.message || '';
    const load = useCallback((): string => c0 + c1 + c2 + c3, [c0, c1, c2, c3]);
    const save = useCallback((json: string) => {
        const size = Math.max(1, Math.ceil(json.length / 4));
        setC0(json.slice(0, size));
        setC1(json.slice(size, size * 2));
        setC2(json.slice(size * 2, size * 3));
        setC3(json.slice(size * 3));
    }, [setC0, setC1, setC2, setC3]);
    return { ready, message, load, save };
}

// ---------- Run JavaScript ----------

function fmtValue(v: any): string {
    if (typeof v === 'string') return v;
    if (v === undefined) return 'undefined';
    if (typeof v === 'function') return String(v);
    try {
        const s = JSON.stringify(v, null, 2);
        return s === undefined ? String(v) : s;
    } catch { return String(v); }
}

function runJs(code: string, push: (e: LogEntry) => void, pushLater: (e: LogEntry) => void) {
    const fake = {
        log: (...a: any[]) => push({ kind: 'log', text: a.map(fmtValue).join(' ') }),
        info: (...a: any[]) => push({ kind: 'info', text: a.map(fmtValue).join(' ') }),
        warn: (...a: any[]) => push({ kind: 'warn', text: a.map(fmtValue).join(' ') }),
        error: (...a: any[]) => push({ kind: 'error', text: a.map(fmtValue).join(' ') }),
    };
    const t0 = Date.now();
    try {
        const fn = new Function('console', '"use strict";\n' + code);
        const out = fn(fake);
        if (out && typeof out.then === 'function') {
            push({ kind: 'info', text: '…الكود رجّع Promise، مستني النتيجة' });
            out.then(
                (v: any) => pushLater({ kind: 'result', text: 'Promise ✅ ' + fmtValue(v) }),
                (e: any) => pushLater({ kind: 'error', text: 'Promise ❌ ' + String(e && e.message ? e.message : e) }),
            );
        } else if (out !== undefined) {
            push({ kind: 'result', text: '↩ ' + fmtValue(out) });
        }
        push({ kind: 'info', text: 'اتنفذ في ' + (Date.now() - t0) + 'ms' });
    } catch (e: any) {
        push({ kind: 'error', text: String(e && e.message ? e.message : e) });
    }
}

const SYMBOLS = ['⇥', '(', ')', '{', '}', '[', ']', '<', '>', '=', ';', ':', '.', ',', '"', "'", '`', '/', '\\', '|', '&', '*', '+', '-', '_', '!', '?', '#', '$', '%', '@', '~', '^', '←', '→'];
const SNIPPETS: { label: string; text: string; back: number }[] = [
    { label: 'log', text: 'console.log();', back: 2 },
    { label: 'fn', text: 'function اسم() {\n  \n}', back: 4 },
    { label: '=>', text: '() => ', back: 0 },
    { label: 'if', text: 'if () {\n  \n}', back: 4 },
    { label: 'for', text: 'for (let i = 0; i < ; i++) {\n  \n}', back: 12 },
];

// ---------- Editor (viewport-windowed highlighting: only visible lines are tokenized) ----------

const OVERSCAN = 20;
const PAD_TOP = 8;

interface EditorProps {
    fileId: string;
    content: string;
    lang: Lang;
    fontSize: number;
    taRef: React.RefObject<HTMLTextAreaElement | null>;
    onEdit: (next: string) => void;
    onCursor: (line: number, col: number) => void;
    onKey: (e: React.KeyboardEvent<HTMLTextAreaElement>) => void;
}

function Editor({ fileId, content, lang, fontSize, taRef, onEdit, onCursor, onKey }: EditorProps) {
    const lineHeight = Math.round(fontSize * 1.5);
    const hlRef = useRef<{ id: string; hl: Highlighter } | null>(null);
    if (!hlRef.current || hlRef.current.id !== fileId) {
        hlRef.current = { id: fileId, hl: new Highlighter() };
    }
    const hl = hlRef.current.hl;
    hl.setText(content, lang);

    const [scroll, setScroll] = useState({ top: 0, height: 600 });
    const codeInnerRef = useRef<HTMLDivElement>(null);
    const gutterInnerRef = useRef<HTMLDivElement>(null);
    const rafRef = useRef(0);

    const applyTransform = useCallback((top: number, left: number, startLine: number) => {
        const y = startLine * lineHeight - top;
        if (codeInnerRef.current) codeInnerRef.current.style.transform = 'translate(' + (-left) + 'px,' + y + 'px)';
        if (gutterInnerRef.current) gutterInnerRef.current.style.transform = 'translateY(' + (PAD_TOP + y) + 'px)';
    }, [lineHeight]);

    const start = Math.max(0, Math.floor(scroll.top / lineHeight) - OVERSCAN);
    const end = Math.min(hl.lineCount - 1, Math.ceil((scroll.top + scroll.height) / lineHeight) + OVERSCAN);

    const lines = useMemo(() => hl.tokens(start, end), [hl, content, lang, start, end]);

    const onScroll = () => {
        const ta = taRef.current;
        if (!ta) return;
        const s = Math.max(0, Math.floor(ta.scrollTop / lineHeight) - OVERSCAN);
        applyTransform(ta.scrollTop, ta.scrollLeft, s);
        if (rafRef.current) return;
        rafRef.current = requestAnimationFrame(() => {
            rafRef.current = 0;
            setScroll({ top: ta.scrollTop, height: ta.clientHeight || 600 });
        });
    };

    useEffect(() => {
        const ta = taRef.current;
        if (ta) setScroll({ top: ta.scrollTop, height: ta.clientHeight || 600 });
        applyTransform(ta ? ta.scrollTop : 0, ta ? ta.scrollLeft : 0, start);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [fileId, fontSize, content, start, end, applyTransform]);

    useEffect(() => () => { if (rafRef.current) cancelAnimationFrame(rafRef.current); }, []);

    const gutter: React.ReactNode[] = [];
    for (let i = start; i <= end; i++) {
        gutter.push(<div key={i} className="ps-gutter-line" style={{ height: lineHeight, lineHeight: lineHeight + 'px' }}>{i + 1}</div>);
    }

    let key = 0;
    const codeNodes: React.ReactNode[] = [];
    lines.forEach((toks: Tok[], li: number) => {
        const lineNo = start + li;
        const spans: React.ReactNode[] = [];
        toks.forEach((tok) => {
            key += 1;
            spans.push(<span key={key} className={tok.c || undefined}>{tok.t}</span>);
        });
        codeNodes.push(<div key={lineNo} style={{ height: lineHeight, lineHeight: lineHeight + 'px' }}>{spans.length ? spans : '\u200b'}</div>);
    });

    const reportCursor = () => {
        const ta = taRef.current;
        if (!ta) return;
        const upto = ta.value.slice(0, ta.selectionStart);
        const line = (upto.match(/\n/g) || []).length;
        const lastNl = upto.lastIndexOf('\n');
        onCursor(line, ta.selectionStart - lastNl);
    };

    return (
        <div className="ps-editorwrap" dir="ltr" style={{ fontSize, lineHeight: lineHeight + 'px' }}>
            <div className="ps-gutter" aria-hidden>
                <div className="ps-gutter-inner" ref={gutterInnerRef} style={{ paddingTop: 0 }}>{gutter}</div>
            </div>
            <div className="ps-codearea">
                <pre className="ps-highlight" aria-hidden>
                    <div className="ps-highlight-inner" ref={codeInnerRef}>{codeNodes}</div>
                </pre>
                <textarea
                    ref={taRef as React.RefObject<HTMLTextAreaElement>}
                    className="ps-input"
                    value={content}
                    onChange={(e) => onEdit(e.target.value)}
                    onKeyDown={onKey}
                    onScroll={onScroll}
                    onSelect={reportCursor}
                    onClick={reportCursor}
                    spellCheck={false}
                    autoCapitalize="off"
                    autoCorrect="off"
                    autoComplete="off"
                    aria-label="محرر الكود"
                />
            </div>
        </div>
    );
}

// ---------- HTML preview (shadow DOM keeps page styles from leaking) ----------

function Preview({ html }: { html: string }) {
    const ref = useRef<HTMLDivElement>(null);
    useEffect(() => {
        const el = ref.current;
        if (!el) return;
        try {
            const root = el.shadowRoot || el.attachShadow({ mode: 'open' });
            root.innerHTML = html;
        } catch { /* shadow unsupported */ }
    }, [html]);
    return <div className="ps-preview-host" ref={ref} />;
}

// ---------- App ----------

export function App() {
    const store = useProjectStore();
    const [project, setProject] = useState<Project>(seedProject);
    const loadedRef = useRef(false);
    const [saveState, setSaveState] = useState<'idle' | 'saving' | 'saved' | 'full'>('idle');
    const [drawerOpen, setDrawerOpen] = useState(false);
    const [menuOpen, setMenuOpen] = useState(false);
    const [panel, setPanel] = useState<PanelKind>(null);
    const [logs, setLogs] = useState<LogEntry[]>([]);
    const [toast, setToast] = useState<{ text: string; err: boolean } | null>(null);
    const [renaming, setRenaming] = useState<string | null>(null);
    const [renameText, setRenameText] = useState('');
    const [newName, setNewName] = useState('');
    const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
    const [findOpen, setFindOpen] = useState(false);
    const [findText, setFindText] = useState('');
    const [replaceText, setReplaceText] = useState('');
    const [cursor, setCursor] = useState({ line: 1, col: 1 });

    const taRef = useRef<HTMLTextAreaElement | null>(null);
    const pendingSel = useRef<number | null>(null);
    const saveTimer = useRef(0);
    const toastTimer = useRef(0);
    const panelBodyRef = useRef<HTMLDivElement>(null);

    const file = project.files.find((f) => f.id === project.openId) || project.files[0];
    const lang = langOf(file.name);
    const usedChars = JSON.stringify(project).length;

    // Load once storage is ready
    useEffect(() => {
        if (!store.ready || loadedRef.current) return;
        loadedRef.current = true;
        const joined = store.load();
        if (joined) {
            try {
                const p = normalizeProject(JSON.parse(joined));
                if (p) setProject(p);
            } catch { /* corrupted state: keep seed */ }
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [store.ready]);

    // Debounced autosave
    useEffect(() => {
        if (!loadedRef.current) return;
        setSaveState('saving');
        window.clearTimeout(saveTimer.current);
        saveTimer.current = window.setTimeout(() => {
            const json = JSON.stringify(project);
            if (json.length > STORE_LIMIT) { setSaveState('full'); return; }
            store.save(json);
            setSaveState('saved');
        }, 600);
        return () => window.clearTimeout(saveTimer.current);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [project]);

    // Apply pending cursor after programmatic edits
    useEffect(() => {
        if (pendingSel.current != null && taRef.current) {
            taRef.current.selectionStart = pendingSel.current;
            taRef.current.selectionEnd = pendingSel.current;
            pendingSel.current = null;
        }
    }, [file.content, file.id]);

    useEffect(() => () => { window.clearTimeout(saveTimer.current); window.clearTimeout(toastTimer.current); }, []);

    const showToast = (text: string, err = false) => {
        setToast({ text, err });
        window.clearTimeout(toastTimer.current);
        toastTimer.current = window.setTimeout(() => setToast(null), 2200);
    };

    const updateFile = (content: string) => {
        setProject((p) => ({ ...p, files: p.files.map((f) => (f.id === file.id ? { ...f, content } : f)) }));
    };

    // ----- editing helpers -----

    const insertText = (text: string, back = 0) => {
        const ta = taRef.current;
        if (!ta) return;
        const s = ta.selectionStart;
        const e = ta.selectionEnd;
        const next = file.content.slice(0, s) + text + file.content.slice(e);
        pendingSel.current = s + text.length - back;
        updateFile(next);
        ta.focus();
    };

    const PAIRS: Record<string, string> = { '(': ')', '[': ']', '{': '}', '"': '"', "'": "'", '`': '`' };

    const handleKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
        const ta = taRef.current;
        if (!ta) return;
        const s = ta.selectionStart;
        const en = ta.selectionEnd;
        const v = file.content;
        if (e.key === 'Tab') {
            e.preventDefault();
            insertText('  ');
            return;
        }
        if (e.key === 'Enter') {
            e.preventDefault();
            const lineStart = v.lastIndexOf('\n', s - 1) + 1;
            const indent = (/^\s*/.exec(v.slice(lineStart, s)) || [''])[0];
            const before = v[s - 1];
            const after = v[en];
            const extra = before && PAIRS[before] && after === PAIRS[before] && before !== '"' && before !== "'" && before !== '`';
            const opened = before === '{' || before === '(' || before === '[';
            const ins = '\n' + indent + (opened ? '  ' : '');
            const next = v.slice(0, s) + ins + (extra ? '\n' + indent : '') + v.slice(en);
            pendingSel.current = s + ins.length;
            updateFile(next);
            return;
        }
        if (PAIRS[e.key]) {
            e.preventDefault();
            const closer = PAIRS[e.key];
            if ((e.key === '"' || e.key === "'" || e.key === '`') && v[s] === closer && s === en) {
                pendingSel.current = s + 1;
                updateFile(v);
                return;
            }
            if (s !== en) {
                const next = v.slice(0, s) + e.key + v.slice(s, en) + closer + v.slice(en);
                pendingSel.current = s + 1;
                updateFile(next);
                return;
            }
            const nextCh = v[s];
            if (!nextCh || /[\s)\]},;:]/.test(nextCh)) {
                const next = v.slice(0, s) + e.key + closer + v.slice(s);
                pendingSel.current = s + 1;
                updateFile(next);
                return;
            }
            insertText(e.key);
            return;
        }
        if ((e.key === ')' || e.key === ']' || e.key === '}') && v[s] === e.key && s === en) {
            e.preventDefault();
            pendingSel.current = s + 1;
            updateFile(v);
            return;
        }
        if (e.key === 'Backspace' && s === en && s > 0 && PAIRS[v[s - 1]] === v[s]) {
            e.preventDefault();
            const next = v.slice(0, s - 1) + v.slice(s + 1);
            pendingSel.current = s - 1;
            updateFile(next);
            return;
        }
    };

    const insertSymbol = (sym: string) => {
        if (sym === '⇥') { insertText('  '); return; }
        if (sym === '←' || sym === '→') {
            const ta = taRef.current;
            if (ta) {
                const d = sym === '←' ? -1 : 1;
                const pos = Math.max(0, Math.min(file.content.length, ta.selectionStart + d));
                ta.selectionStart = pos; ta.selectionEnd = pos; ta.focus();
            }
            return;
        }
        if (PAIRS[sym]) {
            const ta = taRef.current;
            if (ta) {
                const s = ta.selectionStart;
                const en = ta.selectionEnd;
                const v = file.content;
                const nextCh = v[s];
                if (s === en && (!nextCh || /[\s)\]},;:]/.test(nextCh))) {
                    const next = v.slice(0, s) + sym + PAIRS[sym] + v.slice(s);
                    pendingSel.current = s + 1;
                    updateFile(next);
                    ta.focus();
                    return;
                }
            }
        }
        insertText(sym);
    };

    // ----- run / preview -----

    const runCurrent = () => {
        if (lang === 'html') { setPanel('preview'); return; }
        if (lang !== 'js') { showToast('التشغيل متاح لملفات ‎.js، والمعاينة لملفات ‎.html'); return; }
        const entries: LogEntry[] = [];
        runJs(file.content, (en) => entries.push(en), (en) => setLogs((prev) => [...prev, en]));
        setLogs(entries);
        setPanel('console');
    };

    useEffect(() => {
        const el = panelBodyRef.current;
        if (el) el.scrollTop = el.scrollHeight;
    }, [logs, panel]);

    // ----- file operations -----

    const openFile = (id: string) => {
        setProject((p) => ({ ...p, openId: id }));
        setDrawerOpen(false);
        setPanel(null);
    };

    const createFile = () => {
        let name = newName.trim();
        if (!name) return;
        if (name.indexOf('/') !== -1) { showToast('الاسم مينفعش فيه /', true); return; }
        if (name.indexOf('.') === -1) name += '.js';
        if (project.files.some((f) => f.name === name)) { showToast('في ملف بالاسم ده', true); return; }
        const f: PsFile = { id: uid(), name, content: templateFor(name) };
        setProject((p) => ({ ...p, files: [...p.files, f], openId: f.id }));
        setNewName('');
        setDrawerOpen(false);
        setPanel(null);
        showToast('اتcreated ' + name);
    };

    const doRename = (id: string) => {
        let name = renameText.trim();
        if (!name) { setRenaming(null); return; }
        if (name.indexOf('/') !== -1) { showToast('الاسم مينفعش فيه /', true); return; }
        if (name.indexOf('.') === -1) name += '.js';
        if (project.files.some((f) => f.name === name && f.id !== id)) { showToast('في ملف بالاسم ده', true); return; }
        setProject((p) => ({ ...p, files: p.files.map((f) => (f.id === id ? { ...f, name } : f)) }));
        setRenaming(null);
    };

    const deleteFile = (id: string) => {
        if (project.files.length <= 1) { showToast('لازم يفضل ملف واحد على الأقل', true); return; }
        if (confirmDelete !== id) {
            setConfirmDelete(id);
            window.setTimeout(() => setConfirmDelete((cur) => (cur === id ? null : cur)), 3000);
            return;
        }
        setConfirmDelete(null);
        setProject((p) => {
            const files = p.files.filter((f) => f.id !== id);
            return { ...p, files, openId: p.openId === id ? files[0].id : p.openId };
        });
        showToast('اتمسح');
    };

    // ----- find & replace -----

    const findNext = () => {
        const ta = taRef.current;
        if (!ta || !findText) return;
        const from = ta.selectionEnd;
        let idx = file.content.indexOf(findText, from);
        if (idx === -1) idx = file.content.indexOf(findText);
        if (idx === -1) { showToast('مش موجود'); return; }
        pendingSel.current = idx;
        updateFile(file.content);
        ta.focus();
        ta.selectionStart = idx;
        ta.selectionEnd = idx + findText.length;
        const lines = file.content.slice(0, idx).split('\n').length;
        ta.scrollTop = Math.max(0, (lines - 4) * Math.round(project.settings.fontSize * 1.5));
    };

    const replaceOne = () => {
        const ta = taRef.current;
        if (!ta || !findText) return;
        const s = ta.selectionStart;
        if (file.content.slice(s, s + findText.length) === findText) {
            const next = file.content.slice(0, s) + replaceText + file.content.slice(s + findText.length);
            pendingSel.current = s + replaceText.length;
            updateFile(next);
        }
        window.setTimeout(findNext, 50);
    };

    const replaceAll = () => {
        if (!findText) return;
        const parts = file.content.split(findText);
        if (parts.length === 1) { showToast('مش موجود'); return; }
        updateFile(parts.join(replaceText));
        showToast('اتبدل ' + (parts.length - 1) + ' مرة');
    };

    // ----- render -----

    const pct = Math.min(100, Math.round((usedChars / STORE_LIMIT) * 100));

    return (
        <div className="ps-app" dir="rtl" lang="ar">
            <div className="ps-topbar">
                <button type="button" className="ps-btn" onClick={() => setDrawerOpen(true)} aria-label="الملفات">☰</button>
                <span className="ps-title">استوديو الجيب</span>
                <button type="button" className="ps-filename" onClick={() => setDrawerOpen(true)} dir="ltr">{file.name}</button>
                <span className="ps-status">{saveState === 'saving' ? 'بيحفظ…' : saveState === 'saved' ? 'اتحفظ ✓' : saveState === 'full' ? 'المساحة مليانة!' : ''}</span>
                <button type="button" className="ps-btn ps-btn-run" onClick={runCurrent}>{lang === 'html' ? 'معاينة' : 'تشغيل ▶'}</button>
                <button type="button" className="ps-btn" onClick={() => setMenuOpen((v) => !v)} aria-label="القايمة">⋮</button>
            </div>

            <Editor
                fileId={file.id}
                content={file.content}
                lang={lang}
                fontSize={project.settings.fontSize}
                taRef={taRef}
                onEdit={updateFile}
                onCursor={(line, col) => setCursor({ line: line + 1, col })}
                onKey={handleKey}
            />

            {findOpen && (
                <div className="ps-findbar">
                    <div className="ps-findrow">
                        <input value={findText} onChange={(e) => setFindText(e.target.value)} placeholder="دوّر على…" />
                        <button type="button" className="ps-btn" onClick={findNext}>التالي</button>
                        <button type="button" className="ps-btn" onClick={() => setFindOpen(false)}>✕</button>
                    </div>
                    <div className="ps-findrow">
                        <input value={replaceText} onChange={(e) => setReplaceText(e.target.value)} placeholder="استبدل بـ…" />
                        <button type="button" className="ps-btn" onClick={replaceOne}>استبدال</button>
                        <button type="button" className="ps-btn" onClick={replaceAll}>الكل</button>
                    </div>
                </div>
            )}

            <div className="ps-symbols">
                {SNIPPETS.map((sn) => (
                    <button key={sn.label} type="button" className="ps-sym ps-snip" onClick={() => insertText(sn.text, sn.back)}>{sn.label}</button>
                ))}
                {SYMBOLS.map((sym) => (
                    <button key={sym} type="button" className="ps-sym" onClick={() => insertSymbol(sym)}>{sym}</button>
                ))}
            </div>

            {panel && (
                <div className="ps-panel">
                    <div className="ps-panel-head">
                        <span>{panel === 'console' ? 'الكونسول' : 'معاينة'}</span>
                        <span style={{ flex: 1 }} />
                        {panel === 'console' && <button type="button" className="ps-iconbtn" onClick={() => setLogs([])}>مسح</button>}
                        <button type="button" className="ps-iconbtn" onClick={() => setPanel(null)}>✕</button>
                    </div>
                    {panel === 'console'
                        ? <div className="ps-panel-body" ref={panelBodyRef}>
                            {logs.length === 0 && <p className="ps-log ps-log-info">مفيش مخرجات لسه</p>}
                            {logs.map((l, i) => (
                                <p key={i} className={'ps-log ' + (l.kind === 'error' ? 'ps-log-err' : l.kind === 'warn' ? 'ps-log-warn' : l.kind === 'result' ? 'ps-log-res' : 'ps-log-info')}>{l.text}</p>
                            ))}
                        </div>
                        : <Preview html={file.content} />}
                </div>
            )}

            {drawerOpen && (
                <>
                    <div className="ps-overlay" onClick={() => setDrawerOpen(false)} />
                    <div className="ps-drawer">
                        <div className="ps-drawer-head">
                            <span>الملفات ({project.files.length})</span>
                            <button type="button" className="ps-iconbtn" onClick={() => setDrawerOpen(false)}>✕</button>
                        </div>
                        <div className="ps-filelist">
                            {project.files.map((f) => (
                                renaming === f.id ? (
                                    <div key={f.id} className="ps-rename" style={{ display: 'flex', gap: 6, padding: '8px 12px' }}>
                                        <input value={renameText} onChange={(e) => setRenameText(e.target.value)} dir="ltr"
                                            onKeyDown={(e) => { if (e.key === 'Enter') doRename(f.id); if (e.key === 'Escape') setRenaming(null); }} autoFocus />
                                        <button type="button" className="ps-iconbtn" onClick={() => doRename(f.id)}>✓</button>
                                    </div>
                                ) : (
                                    <div key={f.id} className={'ps-file' + (f.id === project.openId ? ' ps-open' : '')} onClick={() => openFile(f.id)} role="button" tabIndex={0}>
                                        <span className="ps-file-badge">{langOf(f.name)}</span>
                                        <span className="ps-file-name" dir="ltr">{f.name}</span>
                                        <button type="button" className="ps-iconbtn" aria-label="إعادة تسمية"
                                            onClick={(e) => { e.stopPropagation(); setRenaming(f.id); setRenameText(f.name); }}>✎</button>
                                        <button type="button" className="ps-iconbtn" aria-label="مسح"
                                            onClick={(e) => { e.stopPropagation(); deleteFile(f.id); }}>{confirmDelete === f.id ? 'متأكد؟' : '🗑'}</button>
                                    </div>
                                )
                            ))}
                        </div>
                        <div className="ps-newfile">
                            <input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="اسم الملف، مثال: لعبة.js" dir="ltr"
                                onKeyDown={(e) => { if (e.key === 'Enter') createFile(); }} />
                            <button type="button" className="ps-btn" onClick={createFile}>جديد +</button>
                        </div>
                        <div className="ps-meter">
                            <span>المساحة: {Math.round(usedChars / 1024)} من {Math.round(STORE_LIMIT / 1024)} كيلوبايت</span>
                            <div className="ps-meter-bar"><div className={'ps-meter-fill' + (pct > 90 ? ' ps-full' : '')} style={{ width: pct + '%' }} /></div>
                        </div>
                    </div>
                </>
            )}

            {menuOpen && (
                <>
                    <div className="ps-overlay" onClick={() => setMenuOpen(false)} />
                    <div className="ps-menu">
                        <div className="ps-menu-row">
                            <span>حجم الخط: {project.settings.fontSize}</span>
                            <span>
                                <button type="button" className="ps-btn" onClick={() => setProject((p) => ({ ...p, settings: { fontSize: Math.max(11, p.settings.fontSize - 1) } }))}>−</button>
                                <button type="button" className="ps-btn" onClick={() => setProject((p) => ({ ...p, settings: { fontSize: Math.min(22, p.settings.fontSize + 1) } }))}>+</button>
                            </span>
                        </div>
                        <div className="ps-menu-row">
                            <span>بحث واستبدال</span>
                            <button type="button" className="ps-btn" onClick={() => { setFindOpen((v) => !v); setMenuOpen(false); }}>🔍</button>
                        </div>
                        <div className="ps-menu-row">
                            <span>نسخ كود الملف</span>
                            <CopyText text={file.content} label="نسخ" />
                        </div>
                        <div className="ps-menu-row">
                            <span>سطر {cursor.line}، عمود {cursor.col}</span>
                        </div>
                    </div>
                </>
            )}

            {toast && <div className={'ps-toast' + (toast.err ? ' ps-err' : '')}>{toast.text}</div>}
        </div>
    );
}
