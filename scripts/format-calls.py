"""Rewrite `stringResource(res, args)` calls to the port's `formattedStringResource`.

The library's own vararg overload does not substitute plain `%d`/`%s` placeholders (see
`formattedStringResource`'s KDoc), so every *format* call in a main source has to go through the port's
helper -- `StringFormattingGuardTest` fails the build otherwise.

This scans for the matching close paren and checks for a top-level comma after the first argument, so a
single-argument call that merely spans several lines is left alone. That distinction is the whole point:
a naive cross-line regex over the same files rewrote those too and broke the file (see PLAN #37-E-1).
"""

import io
import sys

OPEN = 'stringResource('
REPLACEMENT = 'formattedStringResource('


def matching_close(src, start):
    """Index of the ')' that closes the call whose '(' is at `start`."""
    k = start
    depth = 0
    in_str = False
    while k < len(src):
        ch = src[k]
        if in_str:
            if ch == '\\':
                k += 2
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
                return k
        k += 1
    raise AssertionError('unbalanced call at %d' % start)


def has_second_argument(inner):
    """True when there is a top-level comma, i.e. the call passes format arguments."""
    depth = 0
    in_str = False
    idx = 0
    while idx < len(inner):
        ch = inner[idx]
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
        elif ch == ',' and depth == 0:
            return True
        idx += 1
    return False


def convert(src):
    out = []
    i = 0
    converted = 0
    while True:
        j = src.find(OPEN, i)
        if j == -1:
            out.append(src[i:])
            break
        # Skip already-converted calls: `formattedStringResource(` contains `StringResource(` but not the
        # lowercase `stringResource(` we search for, so this only guards the exact spelling.
        close = matching_close(src, j + len(OPEN) - 1)
        inner = src[j + len(OPEN):close]
        out.append(src[i:j])
        if has_second_argument(inner):
            out.append(REPLACEMENT + inner + ')')
            converted += 1
        else:
            out.append(src[j:close + 1])
        i = close + 1
    return ''.join(out), converted


def main(paths):
    for path in paths:
        src = io.open(path, encoding='utf-8').read()
        converted_src, converted = convert(src)
        if converted:
            io.open(path, 'w', encoding='utf-8', newline='').write(converted_src)
        print('%s: %d call(s) converted' % (path, converted))


if __name__ == '__main__':
    main(sys.argv[1:])
