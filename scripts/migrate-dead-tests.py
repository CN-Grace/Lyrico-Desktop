"""Move the never-compiled Android-era tests (JUnit4) into the kotlin test tree (kotlin.test).

These files sat in `src/test/java`, which this module does not compile (the java source set is cleared so
KGP stops deriving kotlin source dirs from it), so nothing in them has ever run on desktop. Their subjects,
however, are all ported, and -- checked before migrating -- none of them imports an Android API: the only
rewrites needed are the test framework ones.

JUnit4 -> kotlin.test mapping used here:
  org.junit.Test            -> kotlin.test.Test
  org.junit.Before/After    -> kotlin.test.BeforeTest/AfterTest   (+ annotation uses)
  org.junit.Ignore          -> kotlin.test.Ignore
  org.junit.Assert.*        -> kotlin.test.*
  assertArrayEquals(a, b)   -> assertContentEquals(a, b)          (kotlin.test has no array overload set)
"""

import io
import os
import re
import shutil
import sys

SRC_ROOT = 'lyrico-app/src/test/java'
DST_ROOT = 'lyrico-app/src/test/kotlin'

REWRITES = [
    ('import org.junit.Test\n', 'import kotlin.test.Test\n'),
    ('import org.junit.Before\n', 'import kotlin.test.BeforeTest\n'),
    ('import org.junit.After\n', 'import kotlin.test.AfterTest\n'),
    ('import org.junit.Ignore\n', 'import kotlin.test.Ignore\n'),
    ('import org.junit.Assert.*\n', 'import kotlin.test.*\n'),
    ('import org.junit.Assert.assertEquals\n', 'import kotlin.test.assertEquals\n'),
    ('import org.junit.Assert.assertTrue\n', 'import kotlin.test.assertTrue\n'),
    ('import org.junit.Assert.assertFalse\n', 'import kotlin.test.assertFalse\n'),
    ('import org.junit.Assert.assertNull\n', 'import kotlin.test.assertNull\n'),
    ('import org.junit.Assert.assertNotNull\n', 'import kotlin.test.assertNotNull\n'),
    ('@Before\n', '@BeforeTest\n'),
    ('@After\n', '@AfterTest\n'),
]

ASSERT_RE = re.compile(r'\bassertArrayEquals\(')


def convert(text):
    for old, new in REWRITES:
        text = text.replace(old, new)
    text = ASSERT_RE.sub('assertContentEquals(', text)
    return text


def migrate(rel_path):
    src = os.path.join(SRC_ROOT, rel_path)
    dst = os.path.join(DST_ROOT, rel_path)
    text = io.open(src, encoding='utf-8').read()
    # `androidx.datastore` / `androidx.compose` are multiplatform and already used by the port; only a real
    # Android platform import (`android.*`, `androidx.activity`, ...) means the test needs judgement.
    android_api = re.search(r'^import android(\.|x\.(activity|app|content|graphics|net|os|provider|widget))', text, re.M)
    assert not android_api, '%s imports an Android API (%s) -- needs judgement, not a mechanical move' % (src, android_api.group(0))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    io.open(dst, 'w', encoding='utf-8', newline='').write(convert(text))
    os.remove(src)
    print('%s -> %s' % (src, dst))


def main(paths):
    for rel in paths:
        migrate(rel)


if __name__ == '__main__':
    main(sys.argv[1:])
