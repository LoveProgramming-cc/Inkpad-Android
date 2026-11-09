"""Loads B's inkpad package on a machine without cairo, GTK or poppler (as A:tools/compat.py does).

    from stubs import load; model, geom = load('ref/B')
"""
import os
import sys
import types
from unittest import mock


def load(app_dir, home):
    sys.path.insert(0, app_dir)
    os.environ['INKPAD_HOME'] = home
    sys.modules['cairo'] = mock.MagicMock()
    gi = types.ModuleType('gi')
    repo = types.ModuleType('gi.repository')
    repo.GLib = mock.MagicMock()
    gi.repository = repo
    sys.modules['gi'], sys.modules['gi.repository'] = gi, repo

    import inkpad  # noqa: E402

    ptext = types.ModuleType('inkpad.ptext')
    # The same stand-in the JS side uses until real text measuring exists (PORTING.md M2).
    ptext.measure = lambda cr, text, size, wrap=0.0: (min(wrap or 1e9, len(text) * size * 0.6), size * 1.3)
    ptext.show = lambda *a, **k: None
    sys.modules['inkpad.ptext'] = ptext
    inkpad.ptext = ptext
    pdf = types.ModuleType('inkpad.pdf')

    class PdfDoc:
        def __init__(self, data):
            self.data, self.n_pages = bytes(data), 1

        def page_size(self, index):
            return 595.0, 842.0

    pdf.PdfDoc = PdfDoc
    sys.modules['inkpad.pdf'] = pdf
    inkpad.pdf = pdf
    from inkpad import geom, model  # noqa: E402
    return model, geom
