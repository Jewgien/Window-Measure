"""Все координаты: пиксели исходного изображения, начало слева сверху."""
import cv2
import numpy as np


class MeasurementError(ValueError):
    pass


def quad(points, label='Контур', min_edge=5):
    p = np.asarray(points, dtype=np.float64)
    if p.shape != (4, 2) or not np.isfinite(p).all():
        raise MeasurementError(f'{label}: укажите четыре точки.')
    edges = np.roll(p, -1, axis=0) - p
    if np.min(np.linalg.norm(edges, axis=1)) < min_edge:
        raise MeasurementError(f'{label}: точки слишком близко; увеличьте эталон в кадре.')
    cross = edges[:, 0] * np.roll(edges, -1, axis=0)[:, 1] - edges[:, 1] * np.roll(edges, -1, axis=0)[:, 0]
    if not (np.all(cross > 1e-4) or np.all(cross < -1e-4)):
        raise MeasurementError(f'{label}: контур пересекается или вырожден. Обходите углы по порядку.')
    if abs(cv2.contourArea(p.astype(np.float32))) < min_edge**2:
        raise MeasurementError(f'{label}: площадь слишком мала.')
    return p


def transform(points, matrix):
    p = np.c_[np.asarray(points), np.ones(len(points))] @ matrix.T
    if np.any(abs(p[:, 2]) < 1e-8) or not (np.all(p[:, 2] > 0) or np.all(p[:, 2] < 0)):
        raise MeasurementError('Слишком сильная перспектива: плоскость пересекает горизонт проекции.')
    return p[:, :2] / p[:, 2:3]


def homography(reference, landscape=False):
    w, h = (297., 210.) if landscape else (210., 297.)
    target = np.array([[0, 0], [w, 0], [w, h], [0, h]], np.float32)
    matrix = cv2.getPerspectiveTransform(np.asarray(reference, np.float32), target)
    if not np.isfinite(matrix).all() or abs(np.linalg.det(matrix)) < 1e-12:
        raise MeasurementError('Не удалось вычислить перспективу.')
    return matrix


def dimensions(rect):
    sides = np.linalg.norm(np.roll(rect, -1, axis=0) - rect, axis=1)
    return np.array([(sides[0] + sides[2]) / 2, (sides[1] + sides[3]) / 2]), sides


def measure(opening, reference, image_size, landscape=False, quality=True):
    """А4 и границы проёма должны находиться в одной плоскости.

    Порядок: верхний левый, верхний правый, нижний правый, нижний левый.
    sensitivity_pct — модель чувствительности к 1 px, НЕ реальная точность.
    """
    op = quad(opening, 'Проём')
    ref = quad(reference, 'Лист А4', min_edge=120 if quality else 5)
    for points in (op, ref):
        if np.any(points < 0) or np.any(points[:, 0] >= image_size[0]) or np.any(points[:, 1] >= image_size[1]):
            raise MeasurementError('Точки должны находиться внутри фотографии.')
    matrix = homography(ref, landscape)
    # Проверяем знак знаменателя одновременно на эталоне и проёме.
    rect = transform(np.vstack([ref, op]), matrix)[4:]
    quad(rect, 'Исправленный контур', 1)
    dims, sides = dimensions(rect)
    mismatch = max(abs(sides[0]-sides[2])/dims[0], abs(sides[1]-sides[3])/dims[1])
    edges = np.roll(rect, -1, axis=0) - rect
    cosines = abs(np.sum(edges * np.roll(edges, -1, axis=0), axis=1) / (sides*np.roll(sides, -1)))
    if quality and (mismatch > .20 or max(cosines) > .30):
        raise MeasurementError('После коррекции контур не прямоугольный. Проверьте углы, ориентацию А4 и общую плоскость.')
    if min(dims) < 50 or max(dims) > 15000:
        raise MeasurementError('Размер вне диапазона 50–15000 мм. Проверьте разметку и ориентацию А4.')
    rng = np.random.default_rng(42)
    deviations = []
    for _ in range(40):
        perturbed = ref + rng.normal(0, 1, ref.shape)
        try:
            varied, _ = dimensions(transform(op + rng.normal(0, 1, op.shape), homography(perturbed, landscape)))
            deviations.append(float(np.max(abs(varied-dims)/dims)))
        except MeasurementError:
            deviations.append(1.)
    sensitivity = float(np.percentile(deviations, 95)*100)
    if quality and sensitivity > 20:
        raise MeasurementError('Разметка неустойчива: снимите ближе, разместите А4 рядом с проёмом или уменьшите угол съёмки.')
    warnings = []
    if sensitivity > 5:
        warnings.append('Высокая чувствительность к ошибке разметки; повторите снимок ближе.')
    if min(np.linalg.norm(np.roll(ref, -1, axis=0)-ref, axis=1)) < 120:
        warnings.append('А4 занимает мало пикселей; желательно не менее 120 px по короткой стороне.')
    if mismatch > .05 or max(cosines) > .10:
        warnings.append('Проверьте прямоугольность и совпадение плоскостей А4 и проёма.')
    rw, rh = (297, 210) if landscape else (210, 297)
    refdim, _ = dimensions(ref)
    old, _ = dimensions(op)
    old = old * [rw/refdim[0], rh/refdim[1]]
    return dict(width_mm=round(float(dims[0]), 1), height_mm=round(float(dims[1]), 1),
                baseline_width_mm=round(float(old[0]), 1), baseline_height_mm=round(float(old[1]), 1),
                sensitivity_pct=round(sensitivity, 2), mismatch_pct=round(mismatch*100, 2),
                warnings=warnings, algorithm='a4-homography-v1', landscape=landscape,
                rectified_points=rect.tolist())


def detect_a4(image_rgb):
    """Эвристика: яркий выпуклый четырёхугольник. Результат требует подтверждения."""
    gray = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2GRAY)
    scale = min(1., 1200/max(gray.shape))
    small = cv2.resize(gray, None, fx=scale, fy=scale)
    candidates = []
    for threshold in (170, 200, 225):
        _, binary = cv2.threshold(small, threshold, 255, cv2.THRESH_BINARY)
        contours, _ = cv2.findContours(binary, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        for contour in contours:
            area = cv2.contourArea(contour)
            if not 1000 < area < small.size*.40:
                continue
            poly = cv2.approxPolyDP(contour, .02*cv2.arcLength(contour, True), True)
            if len(poly) != 4 or not cv2.isContourConvex(poly):
                continue
            interior = np.zeros(small.shape, np.uint8)
            cv2.fillConvexPoly(interior, poly[:, 0], 255)
            values = small[interior > 0]
            if not len(values) or np.mean(values >= threshold) < .70:
                continue
            p = poly[:, 0, :].astype(float)
            center = p.mean(0)
            p = p[np.argsort(np.arctan2(p[:, 1]-center[1], p[:, 0]-center[0]))]
            p = np.roll(p, -int(np.argmin(p.sum(1))), axis=0)
            dims, sides = dimensions(p)
            ratio = max(dims)/min(dims)
            if not 1.1 < ratio < 2.2 or min(sides)/scale < 60:
                continue
            # Подгонка к локальному углу, только если он остаётся рядом с контуром.
            full = (p/scale).astype(np.float32).reshape(-1, 1, 2)
            refined = cv2.cornerSubPix(gray, full.copy(), (5, 5), (-1, -1),
                                       (cv2.TERM_CRITERIA_EPS+cv2.TERM_CRITERIA_MAX_ITER, 25, .01))
            if np.max(np.linalg.norm(refined-full, axis=2)) < 8:
                full = refined
            score = area / (1 + 4*abs(ratio-297/210))
            candidates.append((score, full[:, 0, :].tolist()))
    return max(candidates, key=lambda c: c[0])[1] if candidates else None


def detect_openings(image_rgb, reference=None):
    """Ранжированные кандидаты прямоугольного проёма, не семантическая модель."""
    gray = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2GRAY)
    scale = min(1., 1200/max(gray.shape))
    small = cv2.resize(gray, None, fx=scale, fy=scale)
    blur = cv2.GaussianBlur(small, (5, 5), 0)
    masks = [cv2.Canny(blur, 35, 100), cv2.Canny(blur, 70, 180)]
    for threshold in (60, 100, 140, 180):
        masks.append(cv2.threshold(blur, threshold, 255, cv2.THRESH_BINARY)[1])
    candidates = []
    ref = np.asarray(reference, np.float32) if reference is not None else None
    ref_area = abs(cv2.contourArea(ref)) if ref is not None else 0
    for mask in masks:
        contours, _ = cv2.findContours(mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        for c in contours:
            area = abs(cv2.contourArea(c))/scale**2
            if area < max(gray.size*.035, ref_area*3) or area > gray.size*.90:
                continue
            poly = cv2.approxPolyDP(c, .018*cv2.arcLength(c, True), True)
            if len(poly) != 4 or not cv2.isContourConvex(poly):
                continue
            pts = poly[:, 0].astype(float)/scale
            center = pts.mean(0)
            pts = pts[np.argsort(np.arctan2(pts[:, 1]-center[1], pts[:, 0]-center[0]))]
            pts = np.roll(pts, -int(np.argmin(pts.sum(1))), axis=0)
            dims, sides = dimensions(pts)
            if min(sides) < 80 or max(dims)/min(dims) > 4.5:
                continue
            if (pts[:, 0].min() < 3 or pts[:, 1].min() < 3 or
                    pts[:, 0].max() > gray.shape[1]-4 or pts[:, 1].max() > gray.shape[0]-4):
                continue
            if ref is not None:
                overlap, _ = cv2.intersectConvexConvex(pts.astype(np.float32), ref)
                if overlap > ref_area*.05:
                    continue
            # Объединяем почти одинаковые границы, но сохраняем варианты рама/проём.
            if any(np.mean(np.linalg.norm(pts-np.asarray(p), axis=1)) < 8/scale for _, p in candidates):
                continue
            candidates.append((area, pts.tolist()))
    return [p for _, p in sorted(candidates, key=lambda x: x[0], reverse=True)[:8]]


def analyze_photo(image_rgb):
    """Автоматическая разметка; при неудаче возвращает частичный результат."""
    reference = detect_a4(image_rgb)
    openings = detect_openings(image_rgb, reference)
    landscape = False
    if reference:
        dims, _ = dimensions(np.asarray(reference))
        landscape = bool(dims[0] > dims[1])
    result = None
    error = ''
    if reference and openings:
        try:
            result = measure(openings[0], reference, (image_rgb.shape[1], image_rgb.shape[0]), landscape)
        except MeasurementError as exc:
            error = str(exc)
    elif not reference:
        error = 'Лист А4 не найден. Укажите его углы или выберите другое фото.'
    else:
        error = 'Проём не найден. Укажите четыре угла вручную.'
    return dict(reference=reference, openings=openings, landscape=landscape, result=result, error=error)
