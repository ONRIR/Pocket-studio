import React, { useCallback, useState } from 'react';

export interface SavedState { ready: boolean; message?: string }

export function usePersistentState(key: string, initial: string): [string, (v: string) => void, SavedState] {
    const [value, setValue] = useState<string>(() => {
        try {
            const raw = window.localStorage.getItem(key);
            return raw === null ? initial : raw;
        } catch { return initial; }
    });
    const set = useCallback((v: string) => {
        setValue(v);
        try { window.localStorage.setItem(key, v); } catch { /* full or blocked */ }
    }, [key]);
    return [value, set, { ready: true }];
}

export function CopyText({ text, label }: { text: string; label: string }) {
    const [done, setDone] = useState(false);
    return <button type="button" className="ps-btn" onClick={() => {
        const mark = () => { setDone(true); window.setTimeout(() => setDone(false), 1500); };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(mark, mark);
        } else {
            const ta = document.createElement('textarea');
            ta.value = text; document.body.appendChild(ta); ta.select();
            try { document.execCommand('copy'); } catch { /* ignore */ }
            document.body.removeChild(ta); mark();
        }
    }}>{done ? 'اتنسخ ✓' : label}</button>;
}
