"""Builds site/index.html from index.template.html, inlining the fox SVGs.
Each {{fox:Name}} becomes <g class="fox-art">...</g> with the Illustrator classes
turned into fill attributes (so the eight SVGs' .stN classes cannot collide).
The drawings themselves are untouched."""
import re, sys, pathlib
repo = pathlib.Path(sys.argv[1]); here = pathlib.Path(__file__).parent
def fox(name):
    s = (repo / 'art' / f'Libreon_Artwork_{name}.svg').read_text(encoding='utf-8')
    fills = dict(re.findall(r'\.(st\d+)\s*\{\s*fill:\s*(#[0-9a-fA-F]{3,6});\s*\}', s))
    body = re.search(r'</defs>(.*)</svg>', s, re.S).group(1)
    body = re.sub(r'<!--.*?-->', '', body, flags=re.S)
    body = re.sub(r'class="(st\d+)"', lambda m: f'fill="{fills[m.group(1)]}"', body)
    if name == 'Sitting':  # the two eyes are their own groups (eye + highlight circle)
        body = re.sub(r'<g>(\s*<path[^>]*/>\s*<circle)', r'<g class="eye">\1', body)
    body = re.sub(r'>\s+<', '><', body).strip()
    assert 'class="st' not in body, name
    return f'<g class="fox-art">{body}</g>'
t = (here / 'index.template.html').read_text(encoding='utf-8')
t = re.sub(r'\{\{fox:(\w+)\}\}', lambda m: fox(m.group(1)), t)
(repo / 'site' / 'index.html').write_text(t, encoding='utf-8', newline='\n')
print('ok', len(t))
