package com.vscodroid

/**
 * Pocket Studio touch selection (added in this fork, injected like the other page scripts).
 *
 * - Long press (about 0.45 s, finger still): selects the word under the finger and puts two
 *   round handles on the ends of the selection. Dragging the same finger on, before lifting,
 *   stretches the selection around that word in any direction.
 * - Drag either handle: moves that end of the selection across lines and columns; near the
 *   top or bottom edge the text scrolls.
 * - Triple tap: selects the line.
 * - A second finger put down while the first rests (or a long press just made a selection):
 *   opens the editor's context menu there. The old press-and-hold-then-lift menu and the
 *   system long-press menu are suppressed inside the editor so they stop competing with selection.
 *
 * Everything goes through the editor's own touch events (the same ones the caret drag uses),
 * and the drag uses the editor's stylus path (pointerType "pen"), which extends a selection from
 * the existing anchor. No VS Code source is patched. Not measured on a device when written.
 */
internal fun MainActivity.injectTouchSelection() {
    webView?.evaluateJavascript(
        """
        (function() {
            if (window.__pocketTouchSelect) return;
            window.__pocketTouchSelect = true;
            var HOLD_MS = 450, MOVE_TOL = 8, DOUBLE_MS = 420, TAP_TOL = 28;
            var EDGE = 40, EDGE_SPEED = 14, MIN_EDGE_SIZE = 104;
            var allowMenu = false;
            var lastType = '';
            var press = null;
            var sd = null;
            var taps = [];
            var swallow = {};

            function clamp(v, lo, hi) { return Math.min(Math.max(v, lo), hi); }
            function touchOf(list, id) {
                for (var i = 0; i < list.length; i++) if (list[i].identifier === id) return list[i];
                return null;
            }
            function linesOf(target) {
                if (!target || !target.closest) return null;
                var lines = target.closest('.lines-content');
                return lines && lines.closest('.monaco-editor') ? lines : null;
            }

            function dispatchOn(lines, type, x, y, count) {
                var stack = document.elementsFromPoint(x, y);
                var el = null;
                for (var i = 0; i < stack.length; i++) {
                    if (lines.contains(stack[i]) && !stack[i].closest('.colorpicker-color-decoration')) { el = stack[i]; break; }
                }
                if (!el) return false;
                var ev = document.createEvent('CustomEvent');
                ev.initEvent(type, false, true);
                ev.initialTarget = el;
                ev.tapCount = count;
                ev.pageX = x; ev.pageY = y; ev.clientX = x; ev.clientY = y;
                ev.translationX = 0; ev.translationY = 0;
                lines.dispatchEvent(ev);
                return true;
            }

            // The editor reads the kind of the last pointer from a pointerdown on its lines.
            // 'pen' makes a drag extend the selection; 'touch' makes it scroll.
            function penMode(lines, on) {
                try {
                    var ev = new PointerEvent('pointerdown', { pointerType: on ? 'pen' : 'touch', pointerId: 4242, bubbles: false, cancelable: false });
                    ev.__pocket = true;
                    lines.dispatchEvent(ev);
                } catch (err) {}
            }

            window.addEventListener('pointerdown', function(e) {
                if (e.__pocket) return;
                lastType = e.pointerType;
            }, true);

            // Selection rectangles -> the two ends.
            function ends(editor) {
                var els = editor.querySelectorAll('.selections-layer .selected-text');
                var first = null, last = null;
                for (var i = 0; i < els.length; i++) {
                    if (els[i].closest('.monaco-editor') !== editor) continue;
                    var r = els[i].getBoundingClientRect();
                    if (!r.height) continue;
                    if (!first || r.top < first.top - 1 || (Math.abs(r.top - first.top) <= 1 && r.left < first.left)) first = r;
                    if (!last || r.top > last.top + 1 || (Math.abs(r.top - last.top) <= 1 && r.right > last.right)) last = r;
                }
                if (!first) return null;
                return {
                    sx: first.left, sy: first.bottom, sMid: (first.top + first.bottom) / 2,
                    ex: last.right, ey: last.bottom, eMid: (last.top + last.bottom) / 2
                };
            }

            // Handles.
            function makeHandle(kind) {
                var h = document.createElement('div');
                h.style.cssText = 'position:fixed;width:44px;height:44px;margin:0;padding:0;z-index:2147483000;touch-action:none;display:none;';
                var dot = document.createElement('div');
                var radius = kind === 'start' ? '50% 0 50% 50%' : '0 50% 50% 50%';
                dot.style.cssText = 'position:absolute;left:11px;top:11px;width:22px;height:22px;background:#2f81f7;' +
                    'border-radius:' + radius + ';box-shadow:0 1px 4px rgba(0,0,0,.5);pointer-events:none;';
                h.appendChild(dot);
                h.addEventListener('touchstart', function(e) { startHandle(kind, e); }, { passive: false });
                h.addEventListener('touchmove', function(e) { moveHandle(e); }, { passive: false });
                h.addEventListener('touchend', function(e) { endHandle(e); }, { passive: false });
                h.addEventListener('touchcancel', function(e) { endHandle(e); }, { passive: false });
                document.body.appendChild(h);
                return h;
            }
            var hStart = null, hEnd = null;
            function place(h, kind, x, y) {
                h.style.left = (kind === 'start' ? x - 33 : x - 11) + 'px';
                h.style.top = (y - 11) + 'px';
                h.style.display = 'block';
            }
            function hideHandles() {
                if (hStart) hStart.style.display = 'none';
                if (hEnd) hEnd.style.display = 'none';
            }
            function refresh() {
                if (!document.body) return;
                if (!hStart) { hStart = makeHandle('start'); hEnd = makeHandle('end'); }
                if (sd && sd.mode === 'handle') return;
                if (lastType !== 'touch') { hideHandles(); return; }
                var editor = document.querySelector('.monaco-editor.focused');
                var en = editor && ends(editor);
                if (!en) { hideHandles(); return; }
                place(hStart, 'start', en.sx, en.sy);
                place(hEnd, 'end', en.ex, en.ey);
            }
            setInterval(refresh, 120);

            // One drag at a time, from a handle or from a long press that is still down.
            function begin(mode, id, lines, editor, x, y, offX, offY) {
                sd = { mode: mode, id: id, lines: lines, editor: editor, x: x, y: y, offX: offX, offY: offY,
                       frame: 0, pen: false, scroller: editor.querySelector('.monaco-scrollable-element') };
            }
            function aimAt() {
                var box = sd.scroller ? sd.scroller.getBoundingClientRect() : sd.lines.getBoundingClientRect();
                return {
                    box: box,
                    x: clamp(sd.x - sd.offX, box.left + 1, box.right - 1),
                    y: clamp(sd.y - sd.offY, box.top + 4, box.bottom - 4)
                };
            }
            function extend() {
                if (!sd) return;
                if (!sd.pen) { penMode(sd.lines, true); sd.pen = true; }
                var at = aimAt();
                dispatchOn(sd.lines, '-monaco-gesturechange', at.x, at.y, 1);
            }
            function edgeStep(pos, lo, hi) {
                if (pos < lo + EDGE) return -EDGE_SPEED * Math.min(1, (lo + EDGE - pos) / EDGE);
                if (pos > hi - EDGE) return EDGE_SPEED * Math.min(1, (pos - (hi - EDGE)) / EDGE);
                return 0;
            }
            function frame() {
                if (!sd) return;
                sd.frame = 0;
                var at = aimAt();
                var sx = at.box.width < MIN_EDGE_SIZE ? 0 : edgeStep(sd.x, at.box.left, at.box.right);
                var sy = at.box.height < MIN_EDGE_SIZE ? 0 : edgeStep(sd.y, at.box.top, at.box.bottom);
                if (sx !== 0 || sy !== 0) {
                    sd.editor.dispatchEvent(new WheelEvent('wheel', {
                        deltaX: -sx, deltaY: -sy, deltaMode: 0, bubbles: false, cancelable: true,
                        clientX: at.x, clientY: at.y
                    }));
                    extend();
                    sd.frame = requestAnimationFrame(frame);
                }
            }
            function follow(x, y) {
                sd.x = x; sd.y = y;
                extend();
                if (!sd.frame) sd.frame = requestAnimationFrame(frame);
            }
            function stopDrag() {
                if (!sd) return;
                if (sd.frame) cancelAnimationFrame(sd.frame);
                if (sd.pen) penMode(sd.lines, false);
                sd = null;
            }

            // Handle drags.
            function startHandle(kind, e) {
                e.preventDefault();
                e.stopPropagation();
                if (sd) return;
                var editor = document.querySelector('.monaco-editor.focused');
                var lines = editor && editor.querySelector('.lines-content');
                var en = editor && ends(editor);
                var t = e.changedTouches[0];
                if (!lines || !en || !t) return;
                var fx, fy, px, py;
                if (kind === 'end') { fx = en.sx + 1; fy = en.sMid; px = en.ex; py = en.eMid; }
                else { fx = en.ex - 1; fy = en.eMid; px = en.sx; py = en.sMid; }
                begin('handle', t.identifier, lines, editor, t.clientX, t.clientY, t.clientX - px, t.clientY - py);
                // The fixed end becomes the anchor, so either handle can be dragged either way.
                dispatchOn(lines, '-monaco-gesturetap', fx, fy, 1);
            }
            function moveHandle(e) {
                e.preventDefault();
                e.stopPropagation();
                if (!sd || sd.mode !== 'handle') return;
                var t = touchOf(e.touches, sd.id);
                if (t) {
                    follow(t.clientX, t.clientY);
                    var h = sd.pointer;
                }
            }
            function endHandle(e) {
                e.preventDefault();
                e.stopPropagation();
                if (sd && sd.mode === 'handle') stopDrag();
            }

            // Long press, triple tap, second finger.
            function cancelPress() {
                if (press && press.timer) clearTimeout(press.timer);
                press = null;
            }
            function firePress() {
                if (!press || press.fired) return;
                if (window.__vscodroidCaretDragged) { press = null; return; }
                press.fired = true;
                dispatchOn(press.lines, '-monaco-gesturetap', press.x, press.y, 2);
                try { if (navigator.vibrate) navigator.vibrate(15); } catch (err) {}
                begin('press', press.id, press.lines, press.editor, press.x, press.y, 0, 0);
            }
            function openMenu(p) {
                allowMenu = true;
                try { dispatchOn(p.lines, '-monaco-gesturecontextmenu', p.x, p.y, 1); } finally { allowMenu = false; }
            }

            window.addEventListener('touchstart', function(e) {
                if (e.touches.length === 2 && press) {
                    // A second finger while the first rests: the context menu.
                    var p = press;
                    var still = Math.abs(p.curX - p.x) <= MOVE_TOL && Math.abs(p.curY - p.y) <= MOVE_TOL;
                    if (still) {
                        e.stopImmediatePropagation();
                        e.preventDefault();
                        cancelPress();
                        stopDrag();
                        swallow[p.id] = true;
                        swallow[e.changedTouches[0].identifier] = true;
                        openMenu(p);
                        return;
                    }
                }
                if (e.touches.length !== 1) return;
                if (lastType !== 'touch') return;
                cancelPress();
                var lines = linesOf(e.target);
                if (!lines) return;
                var t = e.touches[0];
                press = {
                    id: t.identifier, x: t.clientX, y: t.clientY, curX: t.clientX, curY: t.clientY,
                    lines: lines, editor: lines.closest('.monaco-editor'), fired: false, timer: 0
                };
                press.timer = setTimeout(firePress, HOLD_MS);
            }, { capture: true, passive: false });

            window.addEventListener('touchmove', function(e) {
                if (sd && sd.mode === 'handle') return;
                if (!press) return;
                var t = touchOf(e.touches, press.id);
                if (!t) return;
                press.curX = t.clientX; press.curY = t.clientY;
                if (!press.fired) {
                    if (Math.abs(t.clientX - press.x) > MOVE_TOL || Math.abs(t.clientY - press.y) > MOVE_TOL) cancelPress();
                    return;
                }
                // Long press made: the finger now stretches the selection.
                if (sd && sd.mode === 'press') {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    follow(t.clientX, t.clientY);
                }
            }, { capture: true, passive: false });

            function lifted(e) {
                var swallowed = false;
                for (var i = 0; i < e.changedTouches.length; i++) {
                    var id = e.changedTouches[i].identifier;
                    if (swallow[id]) { delete swallow[id]; swallowed = true; }
                }
                if (swallowed) {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    if (e.touches.length === 0) { cancelPress(); stopDrag(); }
                    return;
                }
                if (!press) return;
                var t = touchOf(e.changedTouches, press.id);
                if (!t) return;
                var p = press;
                cancelPress();
                if (p.fired) {
                    // The lift of a long press: the editor must not read it as a tap.
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    stopDrag();
                    return;
                }
                if (e.type !== 'touchend') return;
                if (Math.abs(t.clientX - p.x) > MOVE_TOL || Math.abs(t.clientY - p.y) > MOVE_TOL) return;
                var now = Date.now();
                taps = taps.filter(function(k) { return now - k.t < DOUBLE_MS * 2 && Math.abs(k.x - p.x) < TAP_TOL && Math.abs(k.y - p.y) < TAP_TOL; });
                taps.push({ t: now, x: p.x, y: p.y });
                if (taps.length >= 3 && now - taps[taps.length - 3].t < DOUBLE_MS * 2) {
                    taps = [];
                    // After the editor has handled this tap as a word, select the line.
                    setTimeout(function() { dispatchOn(p.lines, '-monaco-gesturetap', p.x, p.y, 3); }, 0);
                }
            }
            window.addEventListener('touchend', lifted, { capture: true, passive: false });
            window.addEventListener('touchcancel', lifted, true);

            // No context menu from press-and-hold inside the editor, only from the second finger.
            window.addEventListener('-monaco-gesturecontextmenu', function(e) {
                if (allowMenu || lastType !== 'touch') return;
                var t = e.target;
                if (t && t.closest && t.closest('.monaco-editor')) {
                    e.stopImmediatePropagation();
                    e.preventDefault();
                }
            }, true);
            window.addEventListener('contextmenu', function(e) {
                if (allowMenu || lastType !== 'touch') return;
                var t = e.target;
                if (t && t.closest && t.closest('.monaco-editor')) {
                    e.stopImmediatePropagation();
                    e.preventDefault();
                }
            }, true);
        })();
        """.trimIndent(),
        null
    )
}
