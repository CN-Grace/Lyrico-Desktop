"""Swap JUnit's message-first assert arguments to kotlin.test's value-first order.

JUnit4:      assertTrue("why", condition)
kotlin.test: assertTrue(condition, "why")

Applied only when the *first* argument is a string literal, which is the shape the JUnit convention used;
anything else is left alone so a genuinely different call is not silently reordered. The arguments are split
at top-level commas, so a nested call or a lambda body does not confuse it.
"""

import io
import re
import sys

ASSERTS = ('assertTrue', 'assertFalse', 'assertNull', 'assertNotNull', 'assertEquals', 'assertContentEquals')


def split_arguments(inner):
    """Split a call's argument list at top-level commas."""
    parts = []
    depth = 0
    in_str = False
    current = ''
    idx = 0
    while idx < len(inner):
        ch = inner[idx]
        if in_str:
            current += ch
            if ch == '\\':
                current += inner[idx + 1]
                idx += 2
                continue
            if ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
            current += ch
        elif ch in '([{':
            depth += 1
            current += ch
        elif ch in ')]}':
            depth -= 1
            current += ch
        elif ch == ',' and depth == 0:
            parts.append(current)
            current = ''
        else:
            current += ch
        idx += 1
    parts.append(current)
    return parts


def matching_close(text, open_index):
    depth = 0
    in_str = False
    idx = open_index
    while idx < len(text):
        ch = text[idx]
        if in_str:
            if ch == '\\':
                idx += 2
                continue
            if ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch in '([{':
            depth += 1
        elif ch in ')]}':
            depth -= 1
            if depth == 0:
                return idx
        idx += 1
    raise AssertionError('unbalanced call')


def swap(text):
    out = []
    i = 0
    swapped = 0
    while True:
        best = None
        for name in ASSERTS:
            j = text.find(name + '(', i)
            if j != -1 and (best is None or j < best[0]):
                best = (j, name)
        if best is None:
            out.append(text[i:])
            break
        j, name = best
        open_index = j + len(name)
        close = matching_close(text, open_index)
        inner = text[open_index + 1:close]
        parts = split_arguments(inner)
        first = parts[0].strip()
        out.append(text[i:j])
        if len(parts) >= 2 and first.startswith('"') and first.endswith('"'):
            # message first -> move it last
            new_inner = ', '.join(p.strip() for p in parts[1:]) + ', ' + first
            out.append(name + '(' + new_inner + ')')
            swapped += 1
        else:
            out.append(text[j:close + 1])
        i = close + 1
    return ''.join(out), swapped


def main(paths):
    for path in paths:
        src = io.open(path, encoding='utf-8').read()
        converted, swapped = swap(src)
        if swapped:
            io.open(path, 'w', encoding='utf-8', newline='').write(converted)
        print('%s: %d assert call(s) reordered' % (path, swapped))


if __name__ == '__main__':
    main(sys.argv[1:])
