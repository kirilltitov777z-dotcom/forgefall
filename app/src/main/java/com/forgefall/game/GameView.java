package com.forgefall.game;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Отрисовка, камера, ввод и игровой цикл.
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    // Земная палитра: песок, глина, камень, дерево. Никакого неона.
    private static final int SAND = 0xFFB9A074;
    private static final int TILE_EMPTY = 0xFFC9AC7C;
    private static final int ORE_CU = 0xFFB26A33;
    private static final int ORE_FE = 0xFF8C8A82;
    private static final int ROCK = 0xFF6E6656;
    private static final int CORE = 0xFF5C6B6B;
    private static final int CORE_DARK = 0xFF3E4A4A;
    private static final int DRILL = 0xFF8B5A2B;
    private static final int CONVEYOR = 0xFF5A4634;
    private static final int ARROW = 0xFFD8B26A;
    private static final int TURRET = 0xFF737A80;
    private static final int TURRET_D = 0xFF4B5563;
    private static final int WALL = 0xFF8C8170;
    private static final int COPPER = 0xFFCC7030;
    private static final int IRON = 0xFFC2C2B8;
    private static final int BULLET = 0xFFE8C24B;
    private static final int UI_PANEL = 0xDD332E26;
    private static final int UI_BTN = 0xFF4A4437;
    private static final int UI_SEL = 0xFFD8B26A;

    private final Game game = new Game();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF[] buttons = new RectF[6];
    private final int[] buildTypes = {
            Game.Building.DRILL, Game.Building.CONVEYOR,
            Game.Building.TURRET, Game.Building.WALL
    };
    private final String[] labels = {"Бур", "Лента", "Турель", "Стена", "Удал.", "Повор."};

    private Thread thread;
    private volatile boolean running = false;
    private int w = 1, h = 1;
    private float camX, camY, zoom = 1f;

    private int selected = Game.Building.DRILL;
    private int rot = 0;

    // Ввод
    private int pidA = -1, pidB = -1;
    private float downX, downY, lastX, lastY;
    private boolean moved, uiTap;
    private float pinchDist, pinchZoom;

    public GameView(Context context) {
        super(context);
        getHolder().addCallback(this);
        setFocusable(true);
        camX = Game.SIZE * Game.TILE / 2f;
        camY = camX;
    }

    /* ---------- Жизненный цикл поверхности ---------- */

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        running = true;
        thread = new Thread(this);
        thread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        layoutUI();
        clampCam();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        boolean retry = true;
        while (retry) {
            try {
                thread.join();
                retry = false;
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void layoutUI() {
        float bw = w / 6f;
        float by = h - 76;
        for (int i = 0; i < 6; i++) {
            buttons[i] = new RectF(i * bw, by, (i + 1) * bw, h);
        }
    }

    /* ---------- Игровой цикл ---------- */

    @Override
    public void run() {
        long last = System.nanoTime();
        while (running) {
            Canvas c = getHolder().lockCanvas();
            if (c != null) {
                long now = System.nanoTime();
                float dt = (now - last) / 1e9f;
                last = now;
                if (dt > 0.05f) dt = 0.05f;
                if (!game.gameOver) game.update(dt);
                draw(c);
                getHolder().unlockCanvasAndPost(c);
            }
        }
    }

    /* ---------- Отрисовка ---------- */

    private void draw(Canvas c) {
        c.drawColor(SAND);

        c.save();
        c.translate(w / 2f, h / 2f);
        c.scale(zoom, zoom);
        c.translate(-camX, -camY);

        drawTerrain(c);
        drawBuildings(c);
        drawEnemies(c);
        drawBullets(c);

        c.restore();
        drawUI(c);
    }

    private void drawTerrain(Canvas c) {
        float left = camX - (w / 2f) / zoom;
        float top = camY - (h / 2f) / zoom;
        float right = camX + (w / 2f) / zoom;
        float bottom = camY + (h / 2f) / zoom;
        int x0 = Math.max(0, (int) (left / Game.TILE));
        int y0 = Math.max(0, (int) (top / Game.TILE));
        int x1 = Math.min(Game.SIZE - 1, (int) (right / Game.TILE) + 1);
        int y1 = Math.min(Game.SIZE - 1, (int) (bottom / Game.TILE) + 1);

        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                float px = x * Game.TILE, py = y * Game.TILE;
                int t = game.terrain[x][y];
                int col = t == Game.ROCK ? ROCK : (t == Game.ORE_CU ? ORE_CU
                        : (t == Game.ORE_FE ? ORE_FE : TILE_EMPTY));
                paint.setColor(col);
                paint.setStyle(Paint.Style.FILL);
                c.drawRect(px, py, px + Game.TILE, py + Game.TILE, paint);

                // Тонкая сетка, чтобы плитки читались
                paint.setColor(0x22000000);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1f);
                c.drawRect(px, py, px + Game.TILE, py + Game.TILE, paint);

                if (t == Game.ORE_CU || t == Game.ORE_FE) {
                    paint.setColor(t == Game.ORE_CU ? 0xFF7C431C : 0xFF63635E);
                    paint.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 4; i++) {
                        float hx = hash(x, y, i) * 24 + 4;
                        float hy = hash(y, x, i + 7) * 24 + 4;
                        c.drawCircle(px + hx, py + hy, 2.5f, paint);
                    }
                }
            }
        }
    }

    private float hash(int x, int y, int s) {
        long n = x * 374761393L + y * 668265263L + s * 2246822519L;
        n = (n ^ (n >>> 13)) * 1274126177L;
        return ((n ^ (n >>> 16)) & 0x7fffffffL) / (float) 0x7fffffff;
    }

    private void drawBuildings(Canvas c) {
        float spin = System.currentTimeMillis() * 0.004f;
        for (int x = 0; x < Game.SIZE; x++) {
            for (int y = 0; y < Game.SIZE; y++) {
                Game.Building b = game.buildings[x][y];
                if (b != null) drawBuilding(c, b, spin);
            }
        }
    }

    private void drawBuilding(Canvas c, Game.Building b, float spin) {
        float px = b.tx * Game.TILE, py = b.ty * Game.TILE;
        float cx = px + Game.TILE / 2f, cy = py + Game.TILE / 2f;
        paint.setStyle(Paint.Style.FILL);

        switch (b.type) {
            case Game.Building.CORE: {
                paint.setColor(CORE_DARK);
                c.drawRect(px, py, px + Game.TILE, py + Game.TILE, paint);
                paint.setColor(CORE);
                c.drawRect(px + 3, py + 3, px + Game.TILE - 3, py + Game.TILE - 3, paint);
                paint.setColor(BULLET);
                c.drawCircle(cx, cy, 6, paint);
                paint.setColor(CORE_DARK);
                c.drawCircle(cx, cy, 2.5f, paint);
                break;
            }
            case Game.Building.DRILL: {
                paint.setColor(DRILL);
                c.drawRect(px + 2, py + 2, px + Game.TILE - 2, py + Game.TILE - 2, paint);
                paint.setColor(0xFF5C3A1C);
                c.drawCircle(cx, cy, 9, paint);
                paint.setColor(ARROW);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.5f);
                c.drawLine(cx, cy, cx + (float) Math.cos(spin) * 8,
                        cy + (float) Math.sin(spin) * 8, paint);
                break;
            }
            case Game.Building.CONVEYOR: {
                paint.setColor(CONVEYOR);
                c.drawRect(px + 1, py + 1, px + Game.TILE - 1, py + Game.TILE - 1, paint);
                drawArrow(c, cx, cy, b.rot);
                if (b.hasItem) {
                    paint.setColor(b.itemType == 0 ? COPPER : IRON);
                    paint.setStyle(Paint.Style.FILL);
                    float ix = cx + Game.DX[b.rot] * (b.progress - 0.5f) * Game.TILE;
                    float iy = cy + Game.DY[b.rot] * (b.progress - 0.5f) * Game.TILE;
                    c.drawCircle(ix, iy, 4, paint);
                }
                break;
            }
            case Game.Building.TURRET: {
                paint.setColor(TURRET_D);
                c.drawRect(px + 1, py + 1, px + Game.TILE - 1, py + Game.TILE - 1, paint);
                paint.setColor(TURRET);
                c.drawCircle(cx, cy, 10, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(5);
                paint.setColor(TURRET_D);
                c.drawLine(cx, cy, cx + Game.DX[b.rot] * 13, cy + Game.DY[b.rot] * 13, paint);
                break;
            }
            case Game.Building.WALL: {
                paint.setColor(WALL);
                c.drawRect(px + 1, py + 1, px + Game.TILE - 1, py + Game.TILE - 1, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(0x55332E26);
                paint.setStrokeWidth(2);
                c.drawRect(px + 4, py + 4, px + Game.TILE - 4, py + Game.TILE - 4, paint);
                break;
            }
        }

        if (b.hp < b.maxHp && b.type != Game.Building.CORE) {
            drawHpBar(c, px, py - 5, b.hp / b.maxHp);
        }
    }

    private void drawArrow(Canvas c, float cx, float cy, int rot) {
        paint.setColor(ARROW);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f);
        float dx = Game.DX[rot], dy = Game.DY[rot];
        float tipX = cx + dx * 8, tipY = cy + dy * 8;
        c.drawLine(cx - dx * 8, cy - dy * 8, tipX, tipY, paint);
        if (rot % 2 == 0) {
            c.drawLine(tipX, tipY, tipX - dy * 5 - dx * 5, tipY - dx * 5 - dy * 5, paint);
            c.drawLine(tipX, tipY, tipX + dy * 5 - dx * 5, tipY + dx * 5 - dy * 5, paint);
        } else {
            c.drawLine(tipX, tipY, tipX - dx * 5 - dy * 5, tipY - dy * 5 - dx * 5, paint);
            c.drawLine(tipX, tipY, tipX - dx * 5 + dy * 5, tipY - dy * 5 + dx * 5, paint);
        }
    }

    private void drawHpBar(Canvas c, float px, float py, float frac) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99000000);
        c.drawRect(px + 2, py, px + Game.TILE - 2, py + 4, paint);
        paint.setColor(frac > 0.4f ? 0xFF6FA84B : 0xFFB5443A);
        c.drawRect(px + 2, py, px + 2 + (Game.TILE - 4) * frac, py + 4, paint);
    }

    private void drawEnemies(Canvas c) {
        for (Game.Enemy e : game.enemies) {
            int col = e.type == 2 ? 0xFF6A5678 : (e.type == 1 ? 0xFFB2702E : 0xFF9B3F3A);
            paint.setColor(col);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(e.x, e.y, e.radius, paint);
            paint.setColor(0x55332E26);
            c.drawCircle(e.x, e.y, e.radius * 0.45f, paint);
            float frac = e.hp / e.maxHp;
            float bw = e.radius * 2.2f;
            float bx = e.x - bw / 2f, by = e.y - e.radius - 8;
            paint.setColor(0x99000000);
            c.drawRect(bx, by, bx + bw, by + 4, paint);
            paint.setColor(0xFFC85A4A);
            c.drawRect(bx, by, bx + bw * frac, by + 4, paint);
        }
    }

    private void drawBullets(Canvas c) {
        paint.setColor(BULLET);
        paint.setStyle(Paint.Style.FILL);
        for (Game.Bullet b : game.bullets) {
            c.drawCircle(b.x, b.y, 3, paint);
        }
    }

    /* ---------- UI ---------- */

    private void drawUI(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(UI_PANEL);
        c.drawRect(0, 0, w, 54, paint);

        paint.setTextSize(20);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText("Волна " + game.wave, 12, 34, paint);
        c.drawText("Медь " + game.copper, Math.max(120, w * 0.28f), 34, paint);
        c.drawText("Железо " + game.iron, Math.max(240, w * 0.50f), 34, paint);
        c.drawText("Ядро " + Math.max(0, (int) game.core.hp), Math.max(370, w * 0.72f), 34, paint);

        for (int i = 0; i < 6; i++) {
            RectF r = buttons[i];
            boolean sel = (i <= 3 && selected == buildTypes[i]) || (i == 4 && selected == -1);
            paint.setColor(sel ? UI_SEL : UI_BTN);
            c.drawRect(r, paint);
            paint.setColor(0x88332E26);
            c.drawRect(r.left, r.top, r.left + 3, r.bottom, paint);
            paint.setColor(sel ? 0xFF33291A : Color.WHITE);
            paint.setTextSize(16);
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText(labels[i], r.centerX(), r.centerY() + 6, paint);
        }

        if (game.gameOver) {
            paint.setColor(0xBB201810);
            c.drawRect(0, 0, w, h, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(0xFFE8D8B0);
            paint.setTextSize(34);
            c.drawText("Ядро уничтожено", w / 2f, h / 2f - 20, paint);
            paint.setTextSize(22);
            c.drawText("Волна: " + game.wave, w / 2f, h / 2f + 18, paint);
            paint.setTextSize(18);
            c.drawText("Нажмите, чтобы начать заново", w / 2f, h / 2f + 52, paint);
        }
    }

    /* ---------- Ввод ---------- */

    private boolean isUI(float x, float y) {
        return y <= 54 || y >= h - 76;
    }

    private void handleUI(float x, float y) {
        for (int i = 0; i < 6; i++) {
            if (buttons[i] != null && buttons[i].contains(x, y)) {
                if (i <= 3) selected = buildTypes[i];
                else if (i == 4) selected = -1;
                else rot = (rot + 1) % 4;
                return;
            }
        }
    }

    private void placeAt(float sx, float sy) {
        float wx = (sx - w / 2f) / zoom + camX;
        float wy = (sy - h / 2f) / zoom + camY;
        int tx = (int) (wx / Game.TILE), ty = (int) (wy / Game.TILE);
        if (selected == -1) game.remove(tx, ty);
        else game.place(selected, tx, ty, rot);
    }

    private void clampCam() {
        float max = Game.SIZE * Game.TILE;
        camX = Math.max(0, Math.min(max, camX));
        camY = Math.max(0, Math.min(max, camY));
    }

    private static float clamp(float v, float a, float b) {
        return Math.max(a, Math.min(b, v));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (game.gameOver) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) game.reset();
            return true;
        }

        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int pid = e.getPointerId(idx);
        float x = e.getX(idx), y = e.getY(idx);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                pidA = pid;
                downX = lastX = x;
                downY = lastY = y;
                moved = false;
                uiTap = isUI(x, y);
                if (uiTap) handleUI(x, y);
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (pidA == -1) {
                    pidA = pid;
                    downX = lastX = x;
                    downY = lastY = y;
                    moved = false;
                    uiTap = isUI(x, y);
                } else if (pidB == -1) {
                    pidB = pid;
                    int ia = e.findPointerIndex(pidA);
                    pinchDist = (float) Math.hypot(e.getX(ia) - x, e.getY(ia) - y);
                    pinchZoom = zoom;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (pidA != -1 && pidB != -1
                        && e.findPointerIndex(pidA) != -1 && e.findPointerIndex(pidB) != -1) {
                    int ia = e.findPointerIndex(pidA);
                    int ib = e.findPointerIndex(pidB);
                    float d = (float) Math.hypot(e.getX(ia) - e.getX(ib), e.getY(ia) - e.getY(ib));
                    if (pinchDist > 1f) zoom = clamp(pinchZoom * d / pinchDist, 0.5f, 2.6f);
                } else if (pid == pidA) {
                    if (Math.abs(x - downX) + Math.abs(y - downY) > 14) moved = true;
                    if (moved && !uiTap) {
                        camX -= (x - lastX) / zoom;
                        camY -= (y - lastY) / zoom;
                        clampCam();
                    }
                    lastX = x;
                    lastY = y;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (pid == pidA) {
                    if (!moved && !uiTap && pidB == -1) placeAt(x, y);
                    pidA = -1;
                    if (pidB != -1) {
                        pidA = pidB;
                        pidB = -1;
                        int ia = e.findPointerIndex(pidA);
                        if (ia >= 0) {
                            downX = lastX = e.getX(ia);
                            downY = lastY = e.getY(ia);
                        }
                        moved = true;
                    }
                } else if (pid == pidB) {
                    pidB = -1;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                pidA = -1;
                pidB = -1;
                break;
        }
        return true;
    }
}
