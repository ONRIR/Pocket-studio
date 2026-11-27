'use strict';

/**
 * The program side of the floating console that Run opens for C and C++ files.
 *
 * Runs in the Node extension host. A program is started on a real pseudo-terminal
 * (the node-pty the terminal panel uses), so cin and cout behave as they do in a
 * terminal: a prompt printed without a newline shows at once, and typed input is
 * echoed by the line discipline. Its bytes travel to the page through the command
 * 'vscodroid.console.emit', which the web-side bridge extension answers by
 * posting them to the overlay. Keys come back through 'vscodroid.console.input'.
 *
 * Commands (all take one object):
 *   vscodroid.console.start  { id, cmd, cwd, cols, rows }  -> { ok } | { ok: false, error }
 *   vscodroid.console.input  { id, data }
 *   vscodroid.console.resize { id, cols, rows }
 *   vscodroid.console.kill   { id }
 */

const vscode = require('vscode');
const path = require('path');

/** @type {Map<string, {term: any, buffer: string, timer: any}>} */
const sessions = new Map();

let ptyModule = null;
function loadPty() {
    if (ptyModule) return ptyModule;
    const tries = ['node-pty'];
    for (const root of (process.env.NODE_PATH || '').split(':').filter(Boolean)) {
        tries.push(path.join(root, 'node-pty'));
    }
    let last = new Error('node-pty is not available');
    for (const name of tries) {
        try {
            ptyModule = require(name);
            return ptyModule;
        } catch (err) {
            last = err;
        }
    }
    throw last;
}

/** The environment a terminal gets: this host's, minus what only the workbench uses, plus the terminal setting. */
function terminalEnv() {
    const env = {};
    for (const [key, value] of Object.entries(process.env)) {
        if (typeof value !== 'string') continue;
        if (key.startsWith('VSCODE_') || key === 'ELECTRON_RUN_AS_NODE') continue;
        env[key] = value;
    }
    env.TERM = 'xterm-256color';
    env.COLORTERM = 'truecolor';
    let extra = {};
    try {
        extra = vscode.workspace.getConfiguration('terminal.integrated.env').get('linux') || {};
    } catch (_) {
        extra = {};
    }
    for (const [key, value] of Object.entries(extra)) {
        if (value === null) delete env[key];
        else if (typeof value === 'string') env[key] = value;
    }
    return env;
}

function send(id, message) {
    return vscode.commands.executeCommand('vscodroid.console.emit', Object.assign({ id }, message))
        .then(undefined, () => {});
}

function flush(id) {
    const s = sessions.get(id);
    if (!s || !s.buffer) return;
    const data = s.buffer;
    s.buffer = '';
    clearTimeout(s.timer);
    s.timer = undefined;
    send(id, { type: 'data', data });
}

function start(args) {
    const { id, cmd, cwd } = args || {};
    if (typeof id !== 'string' || typeof cmd !== 'string') return { ok: false, error: 'bad request' };
    kill({ id });
    let pty;
    try {
        pty = loadPty();
    } catch (err) {
        return { ok: false, error: String(err && err.message || err) };
    }
    const env = terminalEnv();
    const shell = env.SHELL || '/system/bin/sh';
    let term;
    try {
        term = pty.spawn(shell, ['-c', cmd], {
            name: 'xterm-256color',
            cols: Math.max(20, Math.min(200, args.cols | 0 || 60)),
            rows: Math.max(5, Math.min(100, args.rows | 0 || 16)),
            cwd: typeof cwd === 'string' && cwd ? cwd : env.HOME,
            env
        });
    } catch (err) {
        return { ok: false, error: String(err && err.message || err) };
    }
    const session = { term, buffer: '', timer: undefined };
    sessions.set(id, session);
    term.onData((data) => {
        if (sessions.get(id) !== session) return;
        session.buffer += data;
        // Batched: a program printing in a loop must not become one command per line.
        if (session.buffer.length > 8192) flush(id);
        else if (!session.timer) session.timer = setTimeout(() => flush(id), 25);
    });
    term.onExit((e) => {
        if (sessions.get(id) !== session) return;
        flush(id);
        sessions.delete(id);
        send(id, { type: 'exit', code: e && typeof e.exitCode === 'number' ? e.exitCode : 0, signal: e && e.signal || 0 });
    });
    return { ok: true };
}

function input(args) {
    const s = args && sessions.get(args.id);
    if (s && typeof args.data === 'string') {
        try { s.term.write(args.data); } catch (_) { /* the program just ended */ }
    }
}

function resize(args) {
    const s = args && sessions.get(args.id);
    if (!s) return;
    try { s.term.resize(Math.max(20, Math.min(200, args.cols | 0)), Math.max(5, Math.min(100, args.rows | 0))); } catch (_) { /* ended */ }
}

function kill(args) {
    const s = args && sessions.get(args.id);
    if (!s) return;
    sessions.delete(args.id);
    clearTimeout(s.timer);
    try { s.term.kill(); } catch (_) { /* ended */ }
}

/** @param {vscode.ExtensionContext} context */
function register(context) {
    context.subscriptions.push(
        vscode.commands.registerCommand('vscodroid.console.start', start),
        vscode.commands.registerCommand('vscodroid.console.input', input),
        vscode.commands.registerCommand('vscodroid.console.resize', resize),
        vscode.commands.registerCommand('vscodroid.console.kill', kill),
        { dispose: () => { for (const id of Array.from(sessions.keys())) kill({ id }); } }
    );
}

module.exports = { register };
