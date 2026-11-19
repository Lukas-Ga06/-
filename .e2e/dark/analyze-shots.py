#!/usr/bin/env python
"""暗色截图量化分析（不依赖肉眼判断）。

口径：
- 亮度 Y 用 sRGB 伽马编码后的 0..255 加权亮度：0.2126R + 0.7152G + 0.0722B。
- 地图：取整块地图元素截图的平均/中位亮度。暗色底图应显著低于浅色底图。
- 图表：取 canvas 截图。
  * 背景 = 全图中位亮度；
  * 文字像素 = 亮度高于背景中位数 + 30 的像素；
  * 文字亮度取这些像素的 95 分位（避开少量抗锯齿极值）；
  * 对比度用 WCAG 相对亮度公式（线性化后 (L1+0.05)/(L2+0.05)）。
- 预算图额外只看顶部标题条（标题+副标题所在区域），避免饼图/柱图彩色像素污染采样。
- 记账饼图只看饼图外圈的环形区域（label 在外圈），中心扇区不参与文字采样。
"""
import json
import os
import sys

import numpy as np
from PIL import Image

EVIDENCE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '.evidence', 'dark')
SCREENS = os.path.join(EVIDENCE, 'screens')
OUT = os.path.join(EVIDENCE, 'luminance-analysis.json')


def load(path):
    return np.asarray(Image.open(path).convert('RGB')).astype(np.float64)


def luminance(rgb):
    return 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]


def wcag_linear(channel):
    c = channel / 255.0
    return np.where(c <= 0.03928, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def contrast_ratio(rgb1, rgb2):
    def rel(rgb):
        r, g, b = (wcag_linear(np.array(float(v))) for v in rgb)
        return float(0.2126 * r + 0.7152 * g + 0.0722 * b)

    l1, l2 = sorted([rel(rgb1), rel(rgb2)], reverse=True)
    return (l1 + 0.05) / (l2 + 0.05)


def summarize_map(path):
    y = luminance(load(path))
    return {
        'path': path,
        'pixels': int(y.size),
        'mean': round(float(y.mean()), 2),
        'median': round(float(np.median(y)), 2),
        'p10': round(float(np.percentile(y, 10)), 2),
        'p90': round(float(np.percentile(y, 90)), 2),
        'darkPixelRatio': round(float((y < 80).mean()), 4),
    }


def text_metrics(y, mask):
    values = y[mask]
    bg = float(np.median(y))
    text_pixels = values[values > bg + 30]
    text95 = float(np.percentile(text_pixels, 95)) if text_pixels.size else bg
    return {
        'bgMedian': round(bg, 2),
        'textPixelCount': int(text_pixels.size),
        'textP95': round(text95, 2),
        'contrastRatio': round(float((wcag_linear(np.array(text95)) + 0.05) / (wcag_linear(np.array(bg)) + 0.05)), 2),
    }


def analyze_canvas(path, mode):
    rgb = load(path)
    y = luminance(rgb)
    h, w = y.shape
    mask = np.ones_like(y, dtype=bool)
    if mode == 'budget-title':
        # 预算图标题在顶部（canvas 顶部 48px）
        strip = max(1, int(h * 0.16))
        mask = np.zeros_like(y, dtype=bool)
        mask[:strip, :] = True
        metric = text_metrics(y, mask)
    elif mode == 'expense-ring':
        # 饼图外圈 label：中心距 > 0.72 * min(w,h)/2 的环形区域
        yy, xx = np.mgrid[0:h, 0:w]
        center = (w / 2.0, h / 2.0)
        outer = 0.72 * min(w, h) / 2.0
        dist = np.sqrt((xx - center[0]) ** 2 + (yy - center[1]) ** 2)
        mask = dist > outer + 4
        metric = text_metrics(y, mask)
    else:
        metric = text_metrics(y, mask)
    result = {
        'path': path,
        'size': [int(w), int(h)],
        'mode': mode,
        'bgMedian': metric['bgMedian'],
        'textPixelCount': metric['textPixelCount'],
        'textP95': metric['textP95'],
        'contrastRatio': metric['contrastRatio'],
        'bgRgbMedian': [int(v) for v in np.median(rgb.reshape(-1, 3), axis=0)],
    }
    return result


def main():
    report = {'generatedAt': __import__('datetime').datetime.now().isoformat(timespec='seconds')}
    map_light = summarize_map(os.path.join(SCREENS, 'map-light.png'))
    map_dark = summarize_map(os.path.join(SCREENS, 'map-dark.png'))
    report['map'] = {
        'light': map_light,
        'dark': map_dark,
        'deltaMean': round(map_light['mean'] - map_dark['mean'], 2),
        'darkOverLight': round(map_dark['mean'] / max(map_light['mean'], 1e-6), 4),
        # 阈值在看数据前固定：暗色底图平均亮度 < 100，浅色 > 150，差值 > 50
        'checks': {
            'darkMeanBelow100': map_dark['mean'] < 100,
            'lightMeanAbove150': map_light['mean'] > 150,
            'deltaAbove50': (map_light['mean'] - map_dark['mean']) > 50,
        },
    }
    report['charts'] = {
        'budget': analyze_canvas(os.path.join(SCREENS, 'budget-dark-canvas.png'), 'budget-title'),
        'expense': analyze_canvas(os.path.join(SCREENS, 'expense-dark-canvas.png'), 'expense-ring'),
    }
    report['charts']['checks'] = {
        # 正文 WCAG AA 4.5:1；饼图标签按正文口径要求 4.5:1
        'budgetTitleContrastAbove4_5': report['charts']['budget']['contrastRatio'] > 4.5,
        'expenseLabelContrastAbove4_5': report['charts']['expense']['contrastRatio'] > 4.5,
    }
    os.makedirs(EVIDENCE, exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()