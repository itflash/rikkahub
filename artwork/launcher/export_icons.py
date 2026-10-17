"""Export approved concept C. Requires Python 3, Pillow, NumPy; run from any directory."""
from pathlib import Path
from PIL import Image, ImageDraw
import numpy as np

HERE = Path(__file__).resolve().parent
RES = HERE.parents[1] / 'app/src/main/res'
L = Image.Resampling.LANCZOS
source = Image.open(HERE / 'approved-c-crop.png').convert('L')
# Remove board texture and normalize the approved off-white artwork to white.
a = np.asarray(source, dtype=float)
alpha = Image.fromarray(np.uint8(np.clip((a - 22) / 220, 0, 1) * 255))
alpha = alpha.crop(alpha.point(lambda x: 255 if x > 128 else 0).getbbox())
mark = Image.new('RGBA', alpha.size, 'white')
mark.putalpha(alpha)

def layer(size, radius_fraction):
    # Farthest opaque pixel stays within the adaptive central 66dp safe circle.
    pixels = np.asarray(mark.getchannel('A'))
    ys, xs = np.where(pixels > 16)
    cx, cy = mark.width / 2, mark.height / 2
    radius = np.sqrt((xs-cx)**2 + (ys-cy)**2).max()
    scale = size * radius_fraction / radius
    resized = mark.resize((round(mark.width*scale), round(mark.height*scale)), L)
    out = Image.new('RGBA', (size,size))
    out.alpha_composite(resized, ((size-resized.width)//2, (size-resized.height)//2))
    return out

def tile(size, scale=.92):
    fg = layer(1080, 31.8/108 * scale)
    bg = Image.new('RGBA', fg.size, 'black')
    bg.alpha_composite(fg)
    return bg.crop((180,180,900,900)).resize((size,size), L)

for density, factor in [('mdpi',1),('hdpi',1.5),('xhdpi',2),('xxhdpi',3),('xxxhdpi',4)]:
    folder = RES / f'mipmap-{density}'
    size = round(108*factor)
    fg = layer(1080,31.8/108 * .92).resize((size,size),L)
    for name in ['foreground','monochrome']:
        fg.save(folder / f'ic_launcher_{name}.png')
    Image.new('RGB',(size,size),'black').save(folder / 'ic_launcher_background.png')
    tile(round(48*factor)).convert('RGB').save(folder / 'ic_launcher.png')
    # Notification is an alpha silhouette, never a baked black tile.
    layer(960, .40).resize((round(24*factor),round(24*factor)),L).save(
        RES / f'drawable-{density}/ic_stat_rikkahub.png')

brand=tile(512)
mask=Image.new('L',(512,512)); ImageDraw.Draw(mask).rounded_rectangle((0,0,511,511),radius=116,fill=255)
brand.putalpha(mask)
(RES/'drawable-nodpi').mkdir(exist_ok=True)
brand.save(RES/'drawable-nodpi/ic_brand_logo.png')

# Real launcher viewport: first crop central 72dp out of the full 108dp layer.
preview=Image.new('RGB',(1056,560),'#e9e9e9')
draw=ImageDraw.Draw(preview)
for col,scale in enumerate([1,.92,.88]):
    for row,shape in enumerate(['squircle','circle']):
        size=184; icon=tile(size,scale)
        mask=Image.new('L',(size,size)); md=ImageDraw.Draw(mask)
        if shape=='circle': md.ellipse((0,0,size-1,size-1),fill=255)
        else: md.rounded_rectangle((0,0,size-1,size-1),radius=44,fill=255)
        x=64+col*340; y=40+row*255
        preview.paste(icon,(x,y),mask)
        draw.text((x,y+194),f'{shape} / {scale:.2f}',fill='black')
        small=icon.resize((48,48),L); sm=mask.resize((48,48),L)
        preview.paste(small,(x+208,y+70),sm)
preview.save(HERE/'launcher-preview.png')
print('Exported launcher, themed, notification, export logo, and preview assets.')
