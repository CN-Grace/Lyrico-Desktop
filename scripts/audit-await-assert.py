"""List `awaitUntil(...)` calls immediately followed by an assertion on a *different* field.

The pattern that flaked five times in this suite: wait for observable A, then assert observable B, where B
is produced by a later emission. Printing the candidates makes the review mechanical; each one still has to
be judged (waiting for A and asserting A is fine).
"""
import io, os, re, sys

root = 'lyrico-app/src/test/kotlin'
for dirpath, _, names in os.walk(root):
    for name in sorted(names):
        if not name.endswith('.kt'):
            continue
        path = os.path.join(dirpath, name)
        text = io.open(path, encoding='utf-8').read()
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if 'awaitUntil(' not in line:
                continue
            # Collect the awaited expression across the (possibly multi-line) call.
            block = '\n'.join(lines[i:i + 4])
            waited = re.findall(r'describe = \{ ([^}]+) \}', block)
            after = '\n'.join(lines[i + 1:i + 5])
            asserts = re.findall(r'(assert\w+)\(([^,]+),', after)
            for kind, expr in asserts[:2]:
                if not waited:
                    continue
                if any(w.strip() in expr for w in waited):
                    continue
                print('%s:%d\n  waited: %s\n  assert: %s(%s)' % (path, i + 1, waited[0].strip(), kind, expr.strip()))
