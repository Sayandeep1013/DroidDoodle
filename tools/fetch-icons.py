"""Vendor Material Symbols as Android VectorDrawables.

Fetched at build-authoring time and committed, not downloaded at runtime:
constraint C3 says the app performs no network I/O after the model download,
and an icon fetched at first launch would break that for the sake of 2KB.

Material Symbols ship with viewBox "0 -960 960 960" -- a negative Y origin,
which VectorDrawable has no way to express. Wrapping the path in a group
translated by +960 maps it back into 0..960.
"""
import re
import subprocess
import sys

ICONS = {
    # node types, drawn inside the cells on the canvas
    'ic_type_place': 'castle',
    'ic_type_character': 'person',
    'ic_type_object': 'inventory_2',
    'ic_type_note': 'sticky_note_2',
    'ic_type_group': 'workspaces',
    # navigation glyphs material-icons-core does not carry
    'ic_resources': 'memory',
    'ic_suite': 'science',
    'ic_trace': 'receipt_long',
    'ic_download': 'download',
    # Not reliably present in material-icons-core, so vendored rather than
    # gambled on: a missing symbol costs a CI round trip to discover.
    'ic_undo': 'undo',
    'ic_redo': 'redo',
    'ic_zoom_out': 'remove',
    'ic_zoom_in': 'add',
}

TEMPLATE = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  Material Symbols Outlined "{name}", Apache License 2.0.
  https://github.com/google/material-design-icons
  See docs/THIRD-PARTY.md. Do not hand-edit; regenerate instead.

  No android:tint: ?attr/colorControlNormal is an AppCompat attribute and this
  is a pure-Compose app with no AppCompat theme. Compose's Icon and the canvas
  both apply their own tint, so the drawable ships white and untinted.

  The group translation compensates for the source viewBox "0 -960 960 960",
  whose negative Y origin VectorDrawable cannot express.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
    <group android:translateY="960">
        <path
            android:fillColor="@android:color/white"
            android:pathData="{path}" />
    </group>
</vector>
'''

OUT = 'app/src/main/res/drawable'
BASE = ('https://raw.githubusercontent.com/google/material-design-icons/master/'
        'symbols/web/{n}/materialsymbolsoutlined/{n}_24px.svg')

failures = []
for target, name in ICONS.items():
    url = BASE.format(n=name)
    svg = subprocess.run(['curl', '-sS', '--max-time', '25', url],
                         capture_output=True, text=True).stdout
    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', svg)
    if not paths or '<svg' not in svg:
        failures.append(name)
        continue
    if len(paths) > 1:
        failures.append('%s (%d paths, expected 1)' % (name, len(paths)))
        continue
    with open('%s/%s.xml' % (OUT, target), 'w', encoding='utf-8', newline='\n') as f:
        f.write(TEMPLATE.format(name=name, path=paths[0]))
    print('%-22s <- %s  (%d chars)' % (target, name, len(paths[0])))

if failures:
    print('FAILED:', failures, file=sys.stderr)
    sys.exit(1)
