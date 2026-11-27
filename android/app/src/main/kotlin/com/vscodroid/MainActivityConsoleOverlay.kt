package com.vscodroid

/**
 * The floating console that Run opens for a C or C++ file.
 *
 * It is a small window drawn over the editor, inside the page: draggable by its title, resizable
 * by the corner handle, minimised to a chip, closed with the cross. It shows what the program
 * prints and takes what the user types (cin), and when the program ends it says
 * "Press any key to continue" and closes on the next key or tap.
 *
 * The program does not run here. It runs on a pseudo-terminal in the extension host (the
 * process-monitor extension's console.js), and the bridge extension's runFile command carries
 * bytes between that and this window over the BroadcastChannel 'vscodroid-console':
 *   extension -> page: open {id,title}, data {id,data}, exit {id,code}, close {id}
 *   page -> extension: opened {id,cols,rows}, input {id,data}, resize {id,cols,rows}, kill {id}
 *
 * Nothing in it reaches AndroidBridge, so it needs no token. The channel is same-origin, so only
 * this page's own scripts and extensions can open a console or type into one.
 *
 * Touch: the window ignores the editor's gestures by not being part of an editor. Its title bar
 * and corner handle capture their pointer and set touch-action none, so dragging them never
 * scrolls or selects anything underneath; the output area scrolls on its own. Keyboard: the
 * window keeps itself inside the visible part of the page, so it rises above the soft keyboard.
 */
internal fun MainActivity.injectConsoleOverlay() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__vscodroidConsole) return;
            window.__vscodroidConsole = true;
            var ch = new BroadcastChannel('vscodroid-console');
            var root = null, bar, titleEl, body, out, foot, form, inp, handle, chip, probe;
            var runId = '', ended = false, minimized = false;
            // Terminal model: rows of text, a cursor, enough of the escape sequences for ordinary output.
            var rows = [''], r = 0, c = 0, esc = '', MAXROWS = 3000;
            var pos = { left: 8, top: 0, width: 0, height: 0 };

            function px(n) { return Math.round(n) + 'px'; }
            function view() {
                var v = window.visualViewport;
                return { w: v ? v.width : window.innerWidth, h: v ? v.height : window.innerHeight,
                         x: v ? v.offsetLeft : 0, y: v ? v.offsetTop : 0 };
            }
            function build() {
                if (root) return;
                var s = document.createElement('style');
                s.textContent =
                    '#vscandro-console{position:fixed;z-index:2147483000;display:flex;flex-direction:column;' +
                    'background:#0c0c0c;color:#e6e6e6;border:1px solid #555;border-radius:8px;overflow:hidden;' +
                    'box-shadow:0 6px 24px rgba(0,0,0,.6);font-family:monospace;box-sizing:border-box}' +
                    '#vscandro-console *{box-sizing:border-box}' +
                    '#vscandro-console .vc-bar{display:flex;align-items:center;height:40px;flex:none;background:#2d2d2d;' +
                    'padding-left:10px;touch-action:none;user-select:none;-webkit-user-select:none}' +
                    '#vscandro-console .vc-title{flex:1;min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;' +
                    'font:13px sans-serif;color:#ddd}' +
                    '#vscandro-console .vc-btn{flex:none;min-width:40px;height:40px;border:0;background:transparent;color:#ddd;' +
                    'font:13px sans-serif;padding:0 8px}' +
                    '#vscandro-console .vc-btn:active{background:#444}' +
                    '#vscandro-console .vc-body{flex:1;min-height:0;overflow:auto;padding:6px 8px;touch-action:pan-x pan-y;' +
                    'user-select:text;-webkit-user-select:text}' +
                    '#vscandro-console pre{margin:0;font:13px/1.35 monospace;white-space:pre-wrap;word-break:break-all}' +
                    '#vscandro-console .vc-foot{flex:none;padding:4px 8px;font:13px monospace;color:#7fd37f;background:#161616}' +
                    '#vscandro-console form{flex:none;display:flex;border-top:1px solid #333}' +
                    '#vscandro-console input{flex:1;min-width:0;height:40px;border:0;outline:0;background:#161616;color:#fff;' +
                    'font:14px monospace;padding:0 8px}' +
                    '#vscandro-console .vc-grip{position:absolute;right:0;bottom:0;width:32px;height:32px;touch-action:none;' +
                    'background:linear-gradient(135deg,transparent 55%,#888 55%,#888 62%,transparent 62%,transparent 74%,#888 74%,#888 81%,transparent 81%)}' +
                    '#vscandro-chip{position:fixed;z-index:2147483000;right:8px;bottom:96px;height:40px;padding:0 14px;border-radius:20px;' +
                    'background:#0c0c0c;color:#e6e6e6;border:1px solid #666;font:13px sans-serif;display:none;align-items:center;' +
                    'box-shadow:0 4px 14px rgba(0,0,0,.5)}';
                document.documentElement.appendChild(s);
                root = document.createElement('div');
                root.id = 'vscandro-console';
                root.style.display = 'none';
                bar = document.createElement('div'); bar.className = 'vc-bar';
                titleEl = document.createElement('div'); titleEl.className = 'vc-title';
                bar.appendChild(titleEl);
                function btn(label, label2, fn) {
                    var b = document.createElement('button');
                    b.type = 'button'; b.className = 'vc-btn'; b.textContent = label; b.setAttribute('aria-label', label2);
                    b.addEventListener('click', function(e) { e.stopPropagation(); fn(); });
                    // A tap on a button must not start a drag of the bar underneath.
                    b.addEventListener('pointerdown', function(e) { e.stopPropagation(); });
                    bar.appendChild(b);
                    return b;
                }
                btn('Ctrl+C', 'Stop the program', function() { send('input', { data: '\u0003' }); });
                btn('Ctrl+D', 'End of input', function() { send('input', { data: '\u0004' }); });
                btn('\u2013', 'Minimise', function() { setMinimized(true); });
                btn('\u2715', 'Close', function() { closeConsole(true); });
                body = document.createElement('div'); body.className = 'vc-body';
                out = document.createElement('pre'); body.appendChild(out);
                foot = document.createElement('div'); foot.className = 'vc-foot'; foot.style.display = 'none';
                form = document.createElement('form');
                inp = document.createElement('input');
                inp.type = 'text'; inp.autocapitalize = 'off'; inp.autocomplete = 'off'; inp.spellcheck = false;
                inp.setAttribute('autocorrect', 'off'); inp.setAttribute('enterkeyhint', 'send');
                inp.placeholder = 'Type input, then Enter';
                form.appendChild(inp);
                handle = document.createElement('div'); handle.className = 'vc-grip';
                root.appendChild(bar); root.appendChild(body); root.appendChild(foot); root.appendChild(form); root.appendChild(handle);
                document.documentElement.appendChild(root);
                chip = document.createElement('div'); chip.id = 'vscandro-chip';
                chip.addEventListener('click', function() { setMinimized(false); });
                document.documentElement.appendChild(chip);
                probe = document.createElement('span');
                probe.style.cssText = 'position:absolute;visibility:hidden;white-space:pre;font:13px monospace';
                probe.textContent = 'MMMMMMMMMMMMMMMMMMMM';
                root.appendChild(probe);

                form.addEventListener('submit', function(e) {
                    e.preventDefault();
                    if (ended) { closeConsole(false); return; }
                    var text = inp.value;
                    inp.value = '';
                    send('input', { data: text + '\r' });
                });
                // "Press any key": once the program has ended, a key or a tap closes the window.
                inp.addEventListener('keydown', function(e) {
                    if (!ended) return;
                    e.preventDefault();
                    closeConsole(false);
                });
                inp.addEventListener('beforeinput', function(e) {
                    if (!ended) return;
                    e.preventDefault();
                    closeConsole(false);
                });
                foot.addEventListener('click', function() { if (ended) closeConsole(false); });

                drag(bar, function(dx, dy) { pos.left += dx; pos.top += dy; place(); });
                drag(handle, function(dx, dy) { pos.width += dx; pos.height += dy; place(); reportSize(); });
                var vv = window.visualViewport;
                if (vv) { vv.addEventListener('resize', place); vv.addEventListener('scroll', place); }
                window.addEventListener('resize', function() { place(); reportSize(); });
                inp.addEventListener('focus', function() { setTimeout(place, 300); });
            }
            function drag(el, move) {
                var id = null, lx = 0, ly = 0;
                el.addEventListener('pointerdown', function(e) {
                    id = e.pointerId; lx = e.clientX; ly = e.clientY;
                    try { el.setPointerCapture(id); } catch (x) {}
                    e.preventDefault();
                });
                el.addEventListener('pointermove', function(e) {
                    if (e.pointerId !== id) return;
                    move(e.clientX - lx, e.clientY - ly);
                    lx = e.clientX; ly = e.clientY;
                    e.preventDefault();
                });
                function up(e) { if (e.pointerId === id) id = null; }
                el.addEventListener('pointerup', up);
                el.addEventListener('pointercancel', up);
            }
            // Keeps the window on screen and above the soft keyboard.
            function place() {
                if (!root) return;
                var v = view();
                var minW = Math.min(220, v.w - 8), minH = 150;
                pos.width = Math.max(minW, Math.min(pos.width, v.w - 8));
                pos.height = Math.max(minH, Math.min(pos.height, v.h - 8));
                pos.left = Math.max(v.x + 4, Math.min(pos.left, v.x + v.w - pos.width - 4));
                pos.top = Math.max(v.y + 4, Math.min(pos.top, v.y + v.h - pos.height - 4));
                root.style.left = px(pos.left); root.style.top = px(pos.top);
                root.style.width = px(pos.width); root.style.height = px(pos.height);
            }
            function metrics() {
                var rect = probe.getBoundingClientRect();
                var cw = rect.width / 20 || 8;
                var lh = 13 * 1.35;
                var cols = Math.max(20, Math.floor((body.clientWidth - 16) / cw));
                var rws = Math.max(5, Math.floor(body.clientHeight / lh));
                return { cols: cols, rows: rws };
            }
            var sizeTimer = 0;
            function reportSize() {
                if (!runId) return;
                clearTimeout(sizeTimer);
                sizeTimer = setTimeout(function() { var m = metrics(); send('resize', { cols: m.cols, rows: m.rows }); }, 150);
            }
            function send(type, extra) {
                if (!runId) return;
                var m = { type: type, id: runId };
                for (var k in extra) m[k] = extra[k];
                ch.postMessage(m);
            }
            function setMinimized(on) {
                minimized = on;
                root.style.display = on ? 'none' : 'flex';
                chip.style.display = on ? 'flex' : 'none';
                chip.textContent = '\u25B6 ' + titleEl.textContent + (ended ? ' (done)' : '');
                if (!on) { place(); render(); }
            }
            function closeConsole(kill) {
                if (kill && runId && !ended) send('kill', {});
                runId = '';
                if (root) root.style.display = 'none';
                if (chip) chip.style.display = 'none';
                minimized = false;
                try { inp.blur(); } catch (x) {}
            }

            // -- A small terminal: lines, a cursor, and the common escape sequences --
            function reset() { rows = ['']; r = 0; c = 0; esc = ''; }
            function put(ch1) {
                var line = rows[r];
                if (c > line.length) line += new Array(c - line.length + 1).join(' ');
                rows[r] = line.slice(0, c) + ch1 + line.slice(c + 1);
                c++;
            }
            function newRow() {
                r++;
                while (rows.length <= r) rows.push('');
                if (rows.length > MAXROWS) { rows.splice(0, rows.length - MAXROWS); r = rows.length - 1; }
            }
            function csi(params, fin) {
                var n = parseInt(params, 10);
                switch (fin) {
                    case 'K':
                        if (params === '2') rows[r] = '';
                        else if (params === '1') rows[r] = new Array(Math.min(c, rows[r].length) + 1).join(' ') + rows[r].slice(c);
                        else rows[r] = rows[r].slice(0, c);
                        break;
                    case 'J': if (params === '2' || params === '3') { reset(); } break;
                    case 'C': c += isNaN(n) ? 1 : n; break;
                    case 'D': c = Math.max(0, c - (isNaN(n) ? 1 : n)); break;
                    case 'G': c = Math.max(0, (isNaN(n) ? 1 : n) - 1); break;
                    case 'H': case 'f': if (params === '' || params === ';') { c = 0; } break;
                    default: break;
                }
            }
            function feed(text) {
                for (var i = 0; i < text.length; i++) {
                    var ch1 = text.charAt(i);
                    if (esc) {
                        esc += ch1;
                        if (esc.length === 2) {
                            // ESC [ starts a CSI, ESC ] an OSC; any other second byte is a two-byte sequence.
                            if (ch1 !== '[' && ch1 !== ']') esc = '';
                        } else if (esc.charAt(1) === '[') {
                            if (ch1 >= '@' && ch1 <= '~') { csi(esc.slice(2, -1), ch1); esc = ''; }
                        } else if (ch1 === '\u0007' || (ch1 === '\\' && esc.charAt(esc.length - 2) === '\u001b')) {
                            esc = '';
                        } else if (esc.length > 256) { esc = ''; }
                        continue;
                    }
                    var code = ch1.charCodeAt(0);
                    if (code === 27) { esc = ch1; }
                    else if (ch1 === '\r') { c = 0; }
                    else if (ch1 === '\n') { newRow(); }
                    else if (ch1 === '\b') { c = Math.max(0, c - 1); }
                    else if (ch1 === '\t') { do { put(' '); } while (c % 8 !== 0); }
                    else if (code >= 32) { put(ch1); }
                }
                render();
            }
            var pending = false;
            function render() {
                if (pending || minimized) return;
                pending = true;
                requestAnimationFrame(function() {
                    pending = false;
                    var atEnd = body.scrollTop + body.clientHeight >= body.scrollHeight - 24;
                    out.textContent = rows.join('\n');
                    if (atEnd) body.scrollTop = body.scrollHeight;
                });
            }

            ch.onmessage = function(e) {
                var d = e.data;
                if (!d || typeof d.type !== 'string') return;
                if (d.type === 'open') {
                    build();
                    runId = String(d.id);
                    ended = false;
                    reset();
                    out.textContent = '';
                    foot.style.display = 'none';
                    inp.placeholder = 'Type input, then Enter';
                    titleEl.textContent = String(d.title || 'Console');
                    var v = view();
                    if (!pos.width) {
                        pos.width = Math.min(v.w - 16, 560);
                        pos.height = Math.max(180, Math.round(v.h * 0.45));
                        pos.left = v.x + Math.round((v.w - pos.width) / 2);
                        pos.top = v.y + v.h - pos.height - 12;
                    }
                    minimized = false;
                    chip.style.display = 'none';
                    root.style.display = 'flex';
                    place();
                    var m = metrics();
                    ch.postMessage({ type: 'opened', id: runId, cols: m.cols, rows: m.rows });
                    return;
                }
                if (String(d.id) !== runId) return;
                if (d.type === 'data') {
                    feed(String(d.data || ''));
                } else if (d.type === 'exit') {
                    ended = true;
                    foot.textContent = '[Process exited with code ' + d.code + ']  Press any key to continue . . .';
                    foot.style.display = 'block';
                    inp.placeholder = 'Press any key to continue';
                    inp.value = '';
                    render();
                    body.scrollTop = body.scrollHeight;
                    if (minimized) chip.textContent = '\u25B6 ' + titleEl.textContent + ' (done)';
                } else if (d.type === 'close') {
                    closeConsole(false);
                }
            };
        })();
        """.trimIndent(),
        null
    )
}
