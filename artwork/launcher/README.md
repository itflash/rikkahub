# Monochrome launcher — approved concept C

`approved-c-crop.png` is the right-hand C emblem extracted from the concept board approved in this chat (image generation ID `ig_0142f460df2c7466016ad2e347ab2487d0ade9a453dfa6b276`). It is the source of truth; no new artwork is generated during export.

Run with Python 3, Pillow and NumPy:

```sh
python3 artwork/launcher/export_icons.py
python3 artwork/launcher/check_icons.py
```

The exporter normalizes the source into a white alpha silhouette, keeps its interlocking channels, and exports all five Android densities. The final mark's maximum radius is 29.256dp on a 108dp adaptive layer (inside the 33dp safe radius). Preview columns compare 1.00 / 0.92 / 0.88; production uses 0.92. Launcher previews crop the central 72dp viewport before applying the mask.

Notifications use a white transparent silhouette. Conversation screenshot export uses a dedicated rounded black tile (`ic_brand_logo`) so the white foreground does not disappear on light backgrounds. Both adaptive launcher XML resources share the same foreground, black background and themed monochrome layer.
