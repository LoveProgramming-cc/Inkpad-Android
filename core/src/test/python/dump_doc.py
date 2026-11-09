#!/usr/bin/env python3
"""Prints what B's own loader makes of a .inkpad file, as JSON, for the JS tests to compare with.

    python3 tests/dump_doc.py <file.inkpad> [path to B]
"""
import hashlib
import json
import os
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, here)
from stubs import load  # noqa: E402


def dump(model, doc):
    pages = []
    for page in doc.pages:
        items = []
        for it in page.items:
            d = {'kind': it.kind, 'id': it.id, 'bbox': list(it.bbox)}
            if it.kind == 's':
                d.update(tool=it.tool, color=list(it.color), width=it.width, sens=it.sens, dash=it.dash,
                         open=bool(it.open), pts=[list(p) for p in it.pts])
            elif it.kind == 'h':
                d.update(shape=it.shape, r=[it.x0, it.y0, it.x1, it.y1], color=list(it.color), width=it.width)
            elif it.kind == 't':
                d.update(x=it.x, y=it.y, text=it.text, size=it.size, color=list(it.color), wrap=it.wrap,
                         bg=list(it.bg) if it.bg else None)
            else:
                d.update(r=[it.x, it.y, it.w, it.h], sha=hashlib.sha256(it.data).hexdigest())
            items.append(d)
        pages.append({'id': page.id, 'w': page.w, 'h': page.h, 'paper': page.paper, 'color': list(page.color),
                      'board': bool(page.board), 'bookmark': bool(page.bookmark), 'outline': page.outline,
                      'rot': page.rot, 'mt': page.mt, 'gone': sorted(page.gone), 'items': items,
                      'pdf': '%d/%d' % model._pdf_key(page.pdf) if page.pdf is not None else None,
                      'pdf_sha': hashlib.sha256(page.pdf.data).hexdigest() if page.pdf is not None else None,
                      'pdf_page': page.pdf_page})
    return {'id': doc.id, 'gone_pages': sorted(doc.gone_pages), 'order_mt': doc.order_mt,
            'cover': [doc.cover_spec[0], list(doc.cover_spec[1])] if doc.cover_spec else None, 'pages': pages}


if __name__ == '__main__':
    model, _ = load(os.path.abspath(sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, '..', '..', '..', '..', 'ref', 'B')), tempfile.mkdtemp())
    print(json.dumps(dump(model, model.Document.load(sys.argv[1])), ensure_ascii=False))
