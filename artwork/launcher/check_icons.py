"""Check exported asset dimensions, safe zone, and white alpha silhouettes."""
from pathlib import Path
from PIL import Image
import numpy as np

res = Path(__file__).resolve().parents[2] / 'app/src/main/res'
for density, scale in [('mdpi',1),('hdpi',1.5),('xhdpi',2),('xxhdpi',3),('xxxhdpi',4)]:
    folder = res / f'mipmap-{density}'
    size = round(108*scale)
    bg = Image.open(folder/'ic_launcher_background.png').convert('RGB')
    assert bg.size == (size,size) and bg.getbbox() is None
    for name in ['foreground','monochrome']:
        im = Image.open(folder/f'ic_launcher_{name}.png')
        assert im.mode == 'RGBA' and im.size == (size,size)
        a = np.asarray(im)
        visible = a[:,:,3] > 16
        assert (a[:,:,:3][visible] == 255).all()
        ys, xs = np.where(visible)
        radius = np.sqrt((xs-size/2)**2 + (ys-size/2)**2).max()
        assert radius <= size*33/108, (density,radius)
        assert a[0,:,3].max() <= 4
    assert Image.open(folder/'ic_launcher.png').size == (round(48*scale),)*2
    notification = Image.open(res/f'drawable-{density}/ic_stat_rikkahub.png')
    assert notification.size == (round(24*scale),)*2
    a = np.asarray(notification)
    assert (a[:,:,:3][a[:,:,3]>16] == 255).all()
    assert a[0,:,3].max() <= 4
print('PASS: all five densities, pure black backgrounds, white-alpha silhouettes, adaptive safe zone.')
