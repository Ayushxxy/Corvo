package com.carvo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/**
 * Compact dark vector map view matching corvo-map-cutter.html color grading.
 * Supports 1-finger pan, 2-finger pinch zoom & 360-deg rotate (with upright labels),
 * corner expand toggle, and live CroSite GPS trail + turnaround overlay.
 */
public class CroMapView extends View {

    public interface OnExpandListener {
        void onToggleExpand(boolean expanded);
    }

    private static final double TILE_PX = 256.0;
    private static final double MAX_LAT = 85.05112878;

    private CroMapPack pack;
    private double camLat = 27.1751;
    private double camLon = 78.0421;
    private double viewZoom = 15.2;
    private double viewBearingDeg = 0.0; // clockwise canvas rotation in degrees
    private boolean followGps = true;
    private boolean expanded = false;
    private OnExpandListener expandListener;

    private boolean hasGps = false;
    private double gpsLat = 0.0;
    private double gpsLon = 0.0;
    private float gpsAccM = 0f;
    private boolean gpsOutBounds = false;

    private double[] trailPts = null;
    private boolean hasTurn = false;
    private double turnLat = 0.0, turnLon = 0.0;

    // Multi-touch state
    private int ptr0 = -1, ptr1 = -1;
    private float lastX0 = 0f, lastY0 = 0f;
    private float lastMidX = 0f, lastMidY = 0f;
    private float lastSpan = 0f, lastAng = 0f;
    private boolean gestureMoved = false;
    private boolean multiTouchSession = false;

    private final RectF btnExpand = new RectF();
    private final RectF btnSnap = new RectF();
    private final RectF btnPlus = new RectF();
    private final RectF btnMinus = new RectF();

    // Pre-allocated paints & buffers
    private final Paint pBg = new Paint();
    private final Paint pBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCropEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pWaterPoly = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pParkPoly = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBldgPoly = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPathLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRailLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTrailGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTrailCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLblHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLblArea = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLblRoad = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAccFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAccEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDotRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDotCore = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTurn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPillBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPillBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPillTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pScale = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tmpPath = new Path();
    private final Path cropPath = new Path();
    private final RectF tmpRect = new RectF();
    private final ArrayList<LabelDraw> labels = new ArrayList<LabelDraw>(64);
    private final ArrayList<LabelDraw> placed = new ArrayList<LabelDraw>(32);

    private static final class LabelDraw {
        String text;
        int kind;
        float x, y;
    }

    public CroMapView(Context c) {
        super(c);
        init();
    }

    public CroMapView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        pBg.setColor(0xFF05080D);

        pBorder.setStyle(Paint.Style.STROKE);
        pBorder.setStrokeWidth(dp(1.1f));
        pBorder.setColor(0xFF1E2B3E);

        pCropEdge.setStyle(Paint.Style.STROKE);
        pCropEdge.setStrokeWidth(dp(1.2f));
        pCropEdge.setColor(0x4400E5A0);

        pWaterPoly.setStyle(Paint.Style.FILL);
        pWaterPoly.setColor(0xFF0B233B);

        pParkPoly.setStyle(Paint.Style.FILL);
        pParkPoly.setColor(0xFF0C241D);

        pBldgPoly.setStyle(Paint.Style.FILL);
        pBldgPoly.setColor(0xFF131A26);

        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pLine.setStrokeJoin(Paint.Join.ROUND);

        pPathLine.setStyle(Paint.Style.STROKE);
        pPathLine.setStrokeCap(Paint.Cap.ROUND);
        pPathLine.setStrokeJoin(Paint.Join.ROUND);
        pPathLine.setColor(0xFF00E5A0);
        pPathLine.setPathEffect(new DashPathEffect(new float[]{dp(4f), dp(3f)}, 0f));

        pRailLine.setStyle(Paint.Style.STROKE);
        pRailLine.setColor(0xFF3A314A);
        pRailLine.setPathEffect(new DashPathEffect(new float[]{dp(5f), dp(4f)}, 0f));

        pTrailGlow.setStyle(Paint.Style.STROKE);
        pTrailGlow.setStrokeCap(Paint.Cap.ROUND);
        pTrailGlow.setStrokeJoin(Paint.Join.ROUND);
        pTrailGlow.setColor(0x4400E5A0);
        pTrailGlow.setStrokeWidth(dp(7f));

        pTrailCore.setStyle(Paint.Style.STROKE);
        pTrailCore.setStrokeCap(Paint.Cap.ROUND);
        pTrailCore.setStrokeJoin(Paint.Join.ROUND);
        pTrailCore.setColor(0xFF00E5A0);
        pTrailCore.setStrokeWidth(dp(2.6f));

        pLblHalo.setStyle(Paint.Style.STROKE);
        pLblHalo.setStrokeWidth(dp(2.8f));
        pLblHalo.setColor(0xFF05080D);
        pLblHalo.setTextAlign(Paint.Align.CENTER);

        pLblArea.setColor(0xFFD7E5F7);
        pLblArea.setTypeface(Typeface.DEFAULT_BOLD);
        pLblArea.setTextSize(sp(10.5f));
        pLblArea.setTextAlign(Paint.Align.CENTER);

        pLblRoad.setColor(0xFF7E95B3);
        pLblRoad.setTypeface(Typeface.DEFAULT_BOLD);
        pLblRoad.setTextSize(sp(9f));
        pLblRoad.setTextAlign(Paint.Align.CENTER);

        pAccFill.setStyle(Paint.Style.FILL);
        pAccFill.setColor(0x1E00E5A0);

        pAccEdge.setStyle(Paint.Style.STROKE);
        pAccEdge.setStrokeWidth(dp(1f));
        pAccEdge.setColor(0x5500E5A0);

        pDotRing.setStyle(Paint.Style.FILL);
        pDotRing.setColor(0xFF00E5A0);

        pDotCore.setStyle(Paint.Style.FILL);
        pDotCore.setColor(0xFFFFFFFF);

        pTurn.setStyle(Paint.Style.FILL);
        pTurn.setColor(0xFFFFB454);

        pPillBg.setStyle(Paint.Style.FILL);
        pPillBg.setColor(0xDC0B1017);

        pPillBorder.setStyle(Paint.Style.STROKE);
        pPillBorder.setStrokeWidth(dp(0.95f));
        pPillBorder.setColor(0xFF1E2B3E);

        pPillTxt.setTypeface(Typeface.DEFAULT_BOLD);
        pPillTxt.setTextSize(sp(9.5f));

        pScale.setStyle(Paint.Style.STROKE);
        pScale.setStrokeWidth(dp(1.4f));
        pScale.setColor(0xFF7F91AB);
    }

    public void setOnExpandListener(OnExpandListener l) {
        this.expandListener = l;
    }

    public void setExpanded(boolean exp) {
        this.expanded = exp;
        invalidate();
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setPack(CroMapPack p) {
        this.pack = p;
        if (p != null) {
            if (hasGps && followGps) {
                camLat = gpsLat;
                camLon = gpsLon;
            } else if (!hasGps) {
                camLat = p.centerLat();
                camLon = p.centerLon();
            }
            viewZoom = Math.max(13.2, Math.min(16.0, p.zoom + 1.0));
        }
        invalidate();
    }

    public void updateState(boolean hasFix, double lat, double lon, float accM,
                            double[] trail, boolean turnFound, double tLat, double tLon) {
        this.hasGps = hasFix;
        this.gpsLat = lat;
        this.gpsLon = lon;
        this.gpsAccM = accM;
        this.trailPts = trail;
        this.hasTurn = turnFound;
        this.turnLat = tLat;
        this.turnLon = tLon;
        if (pack != null && hasFix) {
            this.gpsOutBounds = !pack.contains(lat, lon);
            if (followGps) {
                camLat = lat;
                camLon = lon;
            }
        }
        invalidate();
    }

    private void panByScreenDelta(float dx, float dy) {
        double rad = Math.toRadians(-viewBearingDeg);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        double wdx = dx * cos - dy * sin;
        double wdy = dx * sin + dy * cos;
        double wx = lonToWorldX(camLon, viewZoom) - wdx;
        double wy = latToWorldY(camLat, viewZoom) - wdy;
        camLon = Math.max(-180.0, Math.min(180.0, worldXToLon(wx, viewZoom)));
        camLat = clampLat(worldYToLat(wy, viewZoom));
    }

    private static double normDeg(double d) {
        double r = ((d % 360.0) + 360.0) % 360.0;
        return r > 180.0 ? r - 360.0 : r;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (pack == null) return false;
        int act = ev.getActionMasked();
        switch (act) {
            case MotionEvent.ACTION_DOWN: {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                ptr0 = ev.getPointerId(0);
                ptr1 = -1;
                lastX0 = ev.getX(0);
                lastY0 = ev.getY(0);
                gestureMoved = false;
                multiTouchSession = false;
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (ev.getPointerCount() >= 2) {
                    int idx = ev.getActionIndex();
                    int pid = ev.getPointerId(idx);
                    if (ptr0 == -1) ptr0 = pid;
                    else if (ptr1 == -1) ptr1 = pid;
                    int i0 = ev.findPointerIndex(ptr0);
                    int i1 = ev.findPointerIndex(ptr1);
                    if (i0 >= 0 && i1 >= 0) {
                        float x0 = ev.getX(i0), y0 = ev.getY(i0);
                        float x1 = ev.getX(i1), y1 = ev.getY(i1);
                        lastMidX = (x0 + x1) * 0.5f;
                        lastMidY = (y0 + y1) * 0.5f;
                        lastSpan = (float) Math.hypot(x1 - x0, y1 - y0);
                        lastAng = (float) Math.toDegrees(Math.atan2(y1 - y0, x1 - x0));
                        gestureMoved = true;
                        multiTouchSession = true;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int i0 = ptr0 != -1 ? ev.findPointerIndex(ptr0) : -1;
                int i1 = ptr1 != -1 ? ev.findPointerIndex(ptr1) : -1;
                if (i0 >= 0 && i1 >= 0) {
                    float x0 = ev.getX(i0), y0 = ev.getY(i0);
                    float x1 = ev.getX(i1), y1 = ev.getY(i1);
                    float midX = (x0 + x1) * 0.5f;
                    float midY = (y0 + y1) * 0.5f;
                    float span = (float) Math.hypot(x1 - x0, y1 - y0);
                    float ang = (float) Math.toDegrees(Math.atan2(y1 - y0, x1 - x0));

                    if (lastSpan > 12f && span > 12f) {
                        double dz = Math.log(span / lastSpan) / Math.log(2.0);
                        viewZoom = Math.max(12.0, Math.min(17.4, viewZoom + dz));
                    }
                    double dAng = normDeg(ang - lastAng);
                    if (Math.abs(dAng) < 45.0) {
                        viewBearingDeg = normDeg(viewBearingDeg + dAng);
                    }
                    float mdx = midX - lastMidX, mdy = midY - lastMidY;
                    if (!followGps && Math.hypot(mdx, mdy) > 0.5f) {
                        panByScreenDelta(mdx, mdy);
                    }
                    lastMidX = midX;
                    lastMidY = midY;
                    lastSpan = span;
                    lastAng = ang;
                    gestureMoved = true;
                    multiTouchSession = true;
                    invalidate();
                } else if (i0 >= 0 && !multiTouchSession) {
                    float x = ev.getX(i0), y = ev.getY(i0);
                    float dx = x - lastX0, dy = y - lastY0;
                    if (!gestureMoved && (dx * dx + dy * dy) > dp(6f) * dp(6f)) gestureMoved = true;
                    if (gestureMoved) {
                        followGps = false;
                        panByScreenDelta(dx, dy);
                        lastX0 = x;
                        lastY0 = y;
                        invalidate();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int upId = ev.getPointerId(ev.getActionIndex());
                if (upId == ptr0) {
                    ptr0 = ptr1;
                    ptr1 = -1;
                } else if (upId == ptr1) {
                    ptr1 = -1;
                }
                if (ptr0 != -1) {
                    int i0 = ev.findPointerIndex(ptr0);
                    if (i0 >= 0) {
                        lastX0 = ev.getX(i0);
                        lastY0 = ev.getY(i0);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (!gestureMoved && act == MotionEvent.ACTION_UP) {
                    float x = ev.getX(), y = ev.getY();
                    if (btnExpand.contains(x, y)) {
                        expanded = !expanded;
                        if (expandListener != null) expandListener.onToggleExpand(expanded);
                        invalidate();
                    } else if (btnPlus.contains(x, y)) {
                        viewZoom = Math.min(17.4, viewZoom + 0.65);
                        invalidate();
                    } else if (btnMinus.contains(x, y)) {
                        viewZoom = Math.max(12.0, viewZoom - 0.65);
                        invalidate();
                    } else if (btnSnap.contains(x, y)) {
                        followGps = true;
                        viewBearingDeg = 0.0;
                        if (hasGps) {
                            camLat = gpsLat;
                            camLon = gpsLon;
                        } else {
                            camLat = pack.centerLat();
                            camLon = pack.centerLon();
                        }
                        invalidate();
                    }
                }
                ptr0 = -1;
                ptr1 = -1;
                gestureMoved = false;
                multiTouchSession = false;
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float rad = dp(10f);
        tmpPath.reset();
        tmpRect.set(0f, 0f, w, h);
        tmpPath.addRoundRect(tmpRect, rad, rad, Path.Direction.CW);
        int outerSave = c.save();
        c.clipPath(tmpPath);
        c.drawRect(0f, 0f, w, h, pBg);

        if (pack != null) {
            labels.clear();
            int rotSave = c.save();
            if (Math.abs(viewBearingDeg) > 0.05) {
                c.rotate((float) viewBearingDeg, w * 0.5f, h * 0.5f);
            }
            drawMapTiles(c, w, h);
            drawRunnerOverlay(c, w, h);
            c.restoreToCount(rotSave);

            // Draw labels AFTER un-rotating canvas so street/area names stay 100% upright!
            drawUprightLabels(c, w, h);
            drawHud(c, w, h);
        }
        c.restoreToCount(outerSave);

        tmpRect.set(dp(0.6f), dp(0.6f), w - dp(0.6f), h - dp(0.6f));
        c.drawRoundRect(tmpRect, rad, rad, pBorder);
    }

    private void drawMapTiles(Canvas c, int w, int h) {
        double centerWX = lonToWorldX(camLon, viewZoom);
        double centerWY = latToWorldY(camLat, viewZoom);
        double topLeftWX = centerWX - w * 0.5;
        double topLeftWY = centerWY - h * 0.5;

        // Corner-safe bounding box for arbitrary 360-degree rotation
        double diagHalf = Math.hypot(w, h) * 0.5;
        int dispZ = pack.zoom;
        double tilePx = TILE_PX * Math.pow(2.0, viewZoom - dispZ);
        double scaleFactor = Math.pow(2.0, dispZ - viewZoom);
        int maxN = (1 << dispZ) - 1;
        int minTX = Math.max(0, (int) Math.floor(((centerWX - diagHalf) * scaleFactor) / TILE_PX));
        int maxTX = Math.min(maxN, (int) Math.floor(((centerWX + diagHalf) * scaleFactor) / TILE_PX));
        int minTY = Math.max(0, (int) Math.floor(((centerWY - diagHalf) * scaleFactor) / TILE_PX));
        int maxTY = Math.min(maxN, (int) Math.floor(((centerWY + diagHalf) * scaleFactor) / TILE_PX));

        // Build exact crop mask path (polygon or cropped bounds)
        cropPath.reset();
        if (pack.cropPoly != null && pack.cropPoly.length >= 6) {
            cropPath.moveTo(
                    (float) (lonToWorldX(pack.cropPoly[1], viewZoom) - topLeftWX),
                    (float) (latToWorldY(pack.cropPoly[0], viewZoom) - topLeftWY));
            for (int i = 2; i < pack.cropPoly.length; i += 2) {
                cropPath.lineTo(
                        (float) (lonToWorldX(pack.cropPoly[i + 1], viewZoom) - topLeftWX),
                        (float) (latToWorldY(pack.cropPoly[i], viewZoom) - topLeftWY));
            }
            cropPath.close();
        } else {
            float bx1 = (float) (lonToWorldX(pack.minLon, viewZoom) - topLeftWX);
            float by1 = (float) (latToWorldY(pack.maxLat, viewZoom) - topLeftWY);
            float bx2 = (float) (lonToWorldX(pack.maxLon, viewZoom) - topLeftWX);
            float by2 = (float) (latToWorldY(pack.minLat, viewZoom) - topLeftWY);
            cropPath.addRect(bx1, by1, bx2, by2, Path.Direction.CW);
        }

        int clipSave = c.save();
        c.clipPath(cropPath);

        float kBase = (float) (tilePx / 4096.0);
        for (int ty = minTY; ty <= maxTY; ty++) {
            for (int tx = minTX; tx <= maxTX; tx++) {
                CroMapPack.Tile tile = pack.getTile(tx, ty);
                if (tile == null) continue;
                float sx = (float) (tx * tilePx - topLeftWX);
                float sy = (float) (ty * tilePx - topLeftWY);
                for (CroMapPack.Feature f : tile.features) {
                    if (f.gtype == CroMapPack.GTYPE_POLY) {
                        tmpPath.reset();
                        for (short[] r : f.rings) {
                            if (r.length < 6) continue;
                            tmpPath.moveTo(sx + r[0] * kBase, sy + r[1] * kBase);
                            for (int i = 2; i < r.length; i += 2) {
                                tmpPath.lineTo(sx + r[i] * kBase, sy + r[i + 1] * kBase);
                            }
                            tmpPath.close();
                        }
                        if (f.kind == CroMapPack.KIND_WATER_POLY) {
                            c.drawPath(tmpPath, pWaterPoly);
                        } else if (f.kind == CroMapPack.KIND_PARK_POLY) {
                            c.drawPath(tmpPath, pParkPoly);
                            if (f.label.length() > 0 && f.rings.length > 0 && f.rings[0].length >= 6) {
                                short[] r0 = f.rings[0];
                                long ax = 0, ay = 0;
                                int pts = r0.length / 2;
                                for (int i = 0; i < r0.length; i += 2) {
                                    ax += r0[i];
                                    ay += r0[i + 1];
                                }
                                addLblRotated(f.label, CroMapPack.KIND_PLACE_LBL,
                                        sx + (ax / (float) pts) * kBase,
                                        sy + (ay / (float) pts) * kBase, w, h);
                            }
                        } else if (f.kind == CroMapPack.KIND_BUILDING) {
                            c.drawPath(tmpPath, pBldgPoly);
                        }
                    } else if (f.gtype == CroMapPack.GTYPE_LINE) {
                        tmpPath.reset();
                        for (short[] r : f.rings) {
                            if (r.length < 4) continue;
                            tmpPath.moveTo(sx + r[0] * kBase, sy + r[1] * kBase);
                            for (int i = 2; i < r.length; i += 2) {
                                tmpPath.lineTo(sx + r[i] * kBase, sy + r[i + 1] * kBase);
                            }
                        }
                        if (f.kind == CroMapPack.KIND_PATH) {
                            pPathLine.setStrokeWidth(Math.max(dp(1.2f), (float) (tilePx * 0.0046)));
                            c.drawPath(tmpPath, pPathLine);
                        } else if (f.kind == CroMapPack.KIND_RAIL) {
                            pRailLine.setStrokeWidth(Math.max(dp(1.0f), (float) (tilePx * 0.0036)));
                            c.drawPath(tmpPath, pRailLine);
                        } else if (f.kind == CroMapPack.KIND_WATERWAY) {
                            pLine.setColor(0xFF2A8EC2);
                            pLine.setStrokeWidth(Math.max(dp(1.1f), (float) (tilePx * 0.0046)));
                            c.drawPath(tmpPath, pLine);
                            if (f.label.length() > 0 && f.rings.length > 0 && f.rings[0].length >= 4) {
                                short[] r0 = f.rings[0];
                                int mid = ((r0.length / 2) / 2) * 2;
                                addLblRotated(f.label, CroMapPack.KIND_ROAD_LBL,
                                        sx + r0[mid] * kBase, sy + r0[mid + 1] * kBase, w, h);
                            }
                        } else if (f.kind == CroMapPack.KIND_HIGHWAY) {
                            pLine.setColor(0xFF587CA3);
                            pLine.setStrokeWidth(Math.max(dp(2.0f), (float) (tilePx * 0.011)));
                            c.drawPath(tmpPath, pLine);
                        } else if (f.kind == CroMapPack.KIND_MAJOR_ROAD) {
                            pLine.setColor(0xFF385270);
                            pLine.setStrokeWidth(Math.max(dp(1.5f), (float) (tilePx * 0.0075)));
                            c.drawPath(tmpPath, pLine);
                        } else {
                            pLine.setColor(0xFF203044);
                            pLine.setStrokeWidth(Math.max(dp(0.9f), (float) (tilePx * 0.004)));
                            c.drawPath(tmpPath, pLine);
                        }
                    } else if (f.gtype == CroMapPack.GTYPE_POINT && f.label.length() > 0
                            && f.rings.length > 0 && f.rings[0].length >= 2) {
                        addLblRotated(f.label, f.kind,
                                sx + f.rings[0][0] * kBase, sy + f.rings[0][1] * kBase, w, h);
                    }
                }
            }
        }
        c.restoreToCount(clipSave);
        c.drawPath(cropPath, pCropEdge);
    }

    private void addLblRotated(String text, int kind, float rx, float ry, int w, int h) {
        float cx = w * 0.5f, cy = h * 0.5f;
        float sx = rx, sy = ry;
        if (Math.abs(viewBearingDeg) > 0.05) {
            double rad = Math.toRadians(viewBearingDeg);
            double cos = Math.cos(rad), sin = Math.sin(rad);
            float dx = rx - cx, dy = ry - cy;
            sx = cx + (float) (dx * cos - dy * sin);
            sy = cy + (float) (dx * sin + dy * cos);
        }
        LabelDraw d = new LabelDraw();
        d.text = text;
        d.kind = kind;
        d.x = sx;
        d.y = sy;
        labels.add(d);
    }

    private void drawUprightLabels(Canvas c, int w, int h) {
        if (labels.isEmpty()) return;
        Collections.sort(labels, new Comparator<LabelDraw>() {
            @Override
            public int compare(LabelDraw a, LabelDraw b) {
                return a.kind - b.kind;
            }
        });
        placed.clear();
        float minDx = dp(68f), minDy = dp(16f);
        for (int i = 0; i < labels.size(); i++) {
            LabelDraw lb = labels.get(i);
            if (lb.x < dp(24f) || lb.x > w - dp(24f) || lb.y < dp(26f) || lb.y > h - dp(16f)) continue;
            boolean clash = false;
            for (int j = 0; j < placed.size(); j++) {
                LabelDraw o = placed.get(j);
                if (Math.abs(o.x - lb.x) < minDx && Math.abs(o.y - lb.y) < minDy) {
                    clash = true;
                    break;
                }
            }
            if (clash) continue;
            placed.add(lb);
            Paint fg = lb.kind == CroMapPack.KIND_PLACE_LBL ? pLblArea : pLblRoad;
            pLblHalo.setTextSize(fg.getTextSize());
            pLblHalo.setTypeface(fg.getTypeface());
            c.drawText(lb.text, lb.x, lb.y, pLblHalo);
            c.drawText(lb.text, lb.x, lb.y, fg);
            if (placed.size() >= 18) break;
        }
    }

    private void drawRunnerOverlay(Canvas c, int w, int h) {
        double centerWX = lonToWorldX(camLon, viewZoom);
        double centerWY = latToWorldY(camLat, viewZoom);
        double topLeftWX = centerWX - w * 0.5;
        double topLeftWY = centerWY - h * 0.5;

        if (trailPts != null && trailPts.length >= 4) {
            tmpPath.reset();
            for (int i = 0; i < trailPts.length; i += 2) {
                float sx = (float) (lonToWorldX(trailPts[i + 1], viewZoom) - topLeftWX);
                float sy = (float) (latToWorldY(trailPts[i], viewZoom) - topLeftWY);
                if (i == 0) tmpPath.moveTo(sx, sy);
                else tmpPath.lineTo(sx, sy);
            }
            c.drawPath(tmpPath, pTrailGlow);
            c.drawPath(tmpPath, pTrailCore);

            float ox = (float) (lonToWorldX(trailPts[1], viewZoom) - topLeftWX);
            float oy = (float) (latToWorldY(trailPts[0], viewZoom) - topLeftWY);
            pDotRing.setColor(0xFF00E5A0);
            c.drawCircle(ox, oy, dp(3.5f), pDotRing);
        }

        if (hasTurn) {
            float tx = (float) (lonToWorldX(turnLon, viewZoom) - topLeftWX);
            float ty = (float) (latToWorldY(turnLat, viewZoom) - topLeftWY);
            c.drawCircle(tx, ty, dp(4.5f), pTurn);
            c.drawCircle(tx, ty, dp(2.0f), pBg);
        }

        if (hasGps) {
            float rx = (float) (lonToWorldX(gpsLon, viewZoom) - topLeftWX);
            float ry = (float) (latToWorldY(gpsLat, viewZoom) - topLeftWY);
            double metersPerPx = 40075016.686 * Math.cos(Math.toRadians(camLat))
                    / (TILE_PX * Math.pow(2.0, viewZoom));
            if (gpsAccM > 0f && gpsAccM <= 50f && metersPerPx > 0.05) {
                float rPx = (float) Math.max(dp(7f), Math.min(dp(34f), gpsAccM / metersPerPx));
                c.drawCircle(rx, ry, rPx, pAccFill);
                c.drawCircle(rx, ry, rPx, pAccEdge);
            }
            pDotRing.setColor(0xFF00E5A0);
            c.drawCircle(rx, ry, dp(6.2f), pDotRing);
            c.drawCircle(rx, ry, dp(3.2f), pDotCore);
        }
    }

    private void drawHud(Canvas c, int w, int h) {
        float pad = dp(8f);
        String tag;
        int dotCol;
        if (hasGps && !gpsOutBounds) {
            tag = pack.areaName.toUpperCase(Locale.US);
            dotCol = 0xFF00E5A0;
        } else if (hasGps && gpsOutBounds) {
            tag = pack.areaName.toUpperCase(Locale.US) + " \u00B7 OUTSIDE CUT";
            dotCol = 0xFFFFB454;
        } else {
            tag = pack.areaName.toUpperCase(Locale.US) + " \u00B7 LOCKING GPS";
            dotCol = 0xFF38BDF8;
        }
        if (tag.length() > 24) tag = tag.substring(0, 24);

        pPillTxt.setTextAlign(Paint.Align.LEFT);
        float tw = pPillTxt.measureText(tag);
        float pillH = dp(20f);
        float pillW = tw + dp(22f);
        tmpRect.set(pad, pad, pad + pillW, pad + pillH);
        pPillBorder.setColor(0xFF1E2B3E);
        c.drawRoundRect(tmpRect, pillH * 0.5f, pillH * 0.5f, pPillBg);
        c.drawRoundRect(tmpRect, pillH * 0.5f, pillH * 0.5f, pPillBorder);
        pDotRing.setColor(dotCol);
        c.drawCircle(pad + dp(9f), pad + pillH * 0.5f, dp(2.8f), pDotRing);
        pPillTxt.setColor(0xFFEAF1FA);
        c.drawText(tag, pad + dp(15f), pad + pillH * 0.5f + sp(3.3f), pPillTxt);

        // Top-right Expand + North/Snap controls
        float bSz = dp(25f);
        btnExpand.set(w - pad - bSz, pad, w - pad, pad + bSz);
        c.drawRoundRect(btnExpand, dp(7f), dp(7f), pPillBg);
        pPillBorder.setColor(expanded ? 0xFF00E5A0 : 0xFF1E2B3E);
        c.drawRoundRect(btnExpand, dp(7f), dp(7f), pPillBorder);
        pPillTxt.setTextAlign(Paint.Align.CENTER);
        pPillTxt.setColor(expanded ? 0xFF00E5A0 : 0xFFEAF1FA);
        c.drawText(expanded ? "\u2921" : "\u2922", btnExpand.centerX(), btnExpand.centerY() + sp(3.6f), pPillTxt);

        boolean rotated = Math.abs(viewBearingDeg) > 2.0;
        boolean needReset = !followGps || rotated;
        btnSnap.set(btnExpand.left - dp(5f) - bSz, pad, btnExpand.left - dp(5f), pad + bSz);
        c.drawRoundRect(btnSnap, dp(7f), dp(7f), pPillBg);
        pPillBorder.setColor(needReset ? 0xFF00E5A0 : 0xFF1E2B3E);
        c.drawRoundRect(btnSnap, dp(7f), dp(7f), pPillBorder);
        pPillTxt.setColor(needReset ? 0xFF00E5A0 : 0xFF7F91AB);
        c.drawText(rotated ? "N" : "\u25CE", btnSnap.centerX(), btnSnap.centerY() + sp(3.6f), pPillTxt);

        // Bottom-right zoom - / +
        btnPlus.set(w - pad - bSz, h - pad - bSz * 2 - dp(4f), w - pad, h - pad - bSz - dp(4f));
        btnMinus.set(w - pad - bSz, h - pad - bSz, w - pad, h - pad);
        pPillBorder.setColor(0xFF1E2B3E);
        c.drawRoundRect(btnPlus, dp(7f), dp(7f), pPillBg);
        c.drawRoundRect(btnPlus, dp(7f), dp(7f), pPillBorder);
        c.drawRoundRect(btnMinus, dp(7f), dp(7f), pPillBg);
        c.drawRoundRect(btnMinus, dp(7f), dp(7f), pPillBorder);
        pPillTxt.setColor(0xFFEAF1FA);
        c.drawText("+", btnPlus.centerX(), btnPlus.centerY() + sp(3.5f), pPillTxt);
        c.drawText("\u2212", btnMinus.centerX(), btnMinus.centerY() + sp(3.5f), pPillTxt);

        // Bottom-left scale bar
        double mPerPx = 40075016.686 * Math.cos(Math.toRadians(camLat)) / (TILE_PX * Math.pow(2.0, viewZoom));
        double targetM = dp(48f) * mPerPx;
        int niceM = targetM >= 500 ? 500 : (targetM >= 250 ? 250 : (targetM >= 100 ? 100 : 50));
        float barPx = (float) (niceM / Math.max(0.05, mPerPx));
        float sy = h - dp(10f);
        c.drawLine(pad, sy - dp(3f), pad, sy, pScale);
        c.drawLine(pad, sy, pad + barPx, sy, pScale);
        c.drawLine(pad + barPx, sy - dp(3f), pad + barPx, sy, pScale);
        pPillTxt.setTextAlign(Paint.Align.LEFT);
        pPillTxt.setColor(0xFF7F91AB);
        c.drawText(niceM + "m", pad, sy - dp(5f), pPillTxt);
    }

    private static double clampLat(double lat) {
        return Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
    }

    private static double lonToWorldX(double lon, double z) {
        return ((lon + 180.0) / 360.0) * Math.pow(2.0, z) * TILE_PX;
    }

    private static double latToWorldY(double lat, double z) {
        double rad = Math.toRadians(clampLat(lat));
        double s = Math.sin(rad);
        return (0.5 - Math.log((1.0 + s) / (1.0 - s)) / (4.0 * Math.PI)) * Math.pow(2.0, z) * TILE_PX;
    }

    private static double worldXToLon(double wx, double z) {
        return (wx / (Math.pow(2.0, z) * TILE_PX)) * 360.0 - 180.0;
    }

    private static double worldYToLat(double wy, double z) {
        double n = Math.PI - (2.0 * Math.PI * wy) / (Math.pow(2.0, z) * TILE_PX);
        return Math.toDegrees(Math.atan(0.5 * (Math.exp(n) - Math.exp(-n))));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
