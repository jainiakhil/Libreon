# Libreon website

The website is a static one-page site in `site/`: `index.html`, `styles.css`, `main.js` and `assets/`. It loads nothing from other servers. It's live at <https://libreon.pages.dev/>: Cloudflare Pages publishes the `site/` folder as is on every push to `main`.

This folder (`site-src/`) holds what builds the page and isn't published. The old address, <https://jainiakhil.github.io/Libreon/>, forwards to the new one; `.github/workflows/pages.yml` publishes that forwarding page.

## Editing

`site/index.html` is generated. Edit `site-src/index.template.html`, then rebuild:

```
python site-src/build.py "path/to/repo"
```

The build inlines the fox artwork from `art/Libreon_Artwork_*.svg` wherever the template says `{{fox:Name}}`.

## Posters and video

The "Posters to share" section shows the posters in a sideways strip, with the promo video as the last tile after a divider. Tapping any tile opens it in a pop-up.

- Posters live in `site/assets/posters/`: `posterN.webp` (600 × 900, shown in the strip) and `posterN-full.webp` (1200 × 1800, opened on tap). They are made from `art/Libreon_PosterN_v2.png`. The download poster (poster 1, with the QR code) comes last.
- The video is `site/assets/video/libreon-promo.mp4` (720 × 1280, portrait), with `libreon-promo.webp` as its still frame before it plays.
- To add, remove or reorder posters, edit the `<figure class="poster">` blocks in the template, keeping the `alt` text up to date, then rebuild.
