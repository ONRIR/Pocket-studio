// Platform adapters. The hosted File build uses the viewer's device-local
// state and CopyButton. The standalone offline build swaps this module for
// platform-standalone (localStorage + clipboard) at bundle time, so the app
// code stays identical and neither build has any runtime network dependency.
import React from 'react';
import { CopyButton, useLocalState } from '@instinct/files';

export interface SavedState { ready: boolean; message?: string }

export function usePersistentState(key: string, initial: string): [string, (v: string) => void, SavedState] {
    const [value, setValue, saved] = useLocalState(key, initial);
    return [value as string, setValue as (v: string) => void, saved as SavedState];
}

export function CopyText({ text, label }: { text: string; label: string }) {
    return <CopyButton text={text} label={label} compact />;
}
