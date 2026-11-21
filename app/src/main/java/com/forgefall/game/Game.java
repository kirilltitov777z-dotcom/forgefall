package com.forgefall.game;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Игровая симуляция: мир, здания, враги, волны, ресурсы.
 * Всё на "земной" палитре — без неона.
 */
public class Game {

    public static final int TILE = 32;
    public static final int SIZE = 48;

    public static final int EMPTY = 0, ORE_CU = 1, ORE_FE = 2, ROCK = 3;

    public static final int[] DX = {1, 0, -1, 0};
    public static final int[] DY = {0, 1, 0, -1};

    public int[][] terrain;
    public Building[][] buildings;
    public List<Enemy> enemies = new ArrayList<>();
    public List<Bullet> bullets = new ArrayList<>();

    public int copper = 120, iron = 0;
    public int wave = 0;
    public boolean gameOver = false;
    public Building core;
    public Random rnd = new Random();

    private float waveTimer = 10f;
    private float spawnTimer = 0f;
    private int toSpawn = 0;

    /* ---------- Сущности ---------- */

    public static class Building {
        public static final int CORE = 0, DRILL = 1, CONVEYOR = 2, TURRET = 3, WALL = 4;

        public int type, tx, ty, rot, hp, maxHp;
        public float progress;      // бур: добыча; конвейер: движение предмета
        public boolean hasItem;
        public int itemType;        // 0 = медь, 1 = железо
        public float cooldown;

        Building(int type, int tx, int ty, int rot) {
            this.type = type;
            this.tx = tx;
            this.ty = ty;
            this.rot = rot;
            switch (type) {
                case CORE: hp = maxHp = 1200; break;
                case DRILL: hp = maxHp = 110; break;
                case CONVEYOR: hp = maxHp = 70; break;
                case TURRET: hp = maxHp = 170; break;
                case WALL: hp = maxHp = 450; break;
            }
        }
    }

    public static class Enemy {
        public float x, y, hp, maxHp, speed, damage, attackCd = 0f;
        public int type;            // 0 = пехота, 1 = бегун, 2 = танк
        public float radius = 9f;
    }

    public static class Bullet {
        public float x, y, vx, vy, life, damage;
    }

    /* ---------- Инициализация ---------- */

    public Game() {
        reset();
    }

    public void reset() {
        terrain = new int[SIZE][SIZE];
        buildings = new Building[SIZE][SIZE];
        enemies.clear();
        bullets.clear();
        copper = 120;
        iron = 0;
        wave = 0;
        gameOver = false;
        waveTimer = 10f;
        toSpawn = 0;
        spawnTimer = 0f;
        generateWorld();
        int c = SIZE / 2;
        core = new Building(Building.CORE, c, c, 0);
        buildings[c][c] = core;
    }

    private void generateWorld() {
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                terrain[x][y] = EMPTY;
            }
        }
        // Скалы — редкие огромные камни
        for (int i = 0; i < 40; i++) {
            int cx = rnd.nextInt(SIZE), cy = rnd.nextInt(SIZE);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int x = cx + dx, y = cy + dy;
                    if (inBounds(x, y) && rnd.nextFloat() < 0.55f) terrain[x][y] = ROCK;
                }
            }
        }
        // Рудные жилы
        for (int i = 0; i < 26; i++) oreCluster(rnd.nextInt(SIZE), rnd.nextInt(SIZE), ORE_CU);
        for (int i = 0; i < 14; i++) oreCluster(rnd.nextInt(SIZE), rnd.nextInt(SIZE), ORE_FE);
        // Чистая площадка вокруг ядра
        int c = SIZE / 2;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -5; dy <= 5; dy++) {
                int x = c + dx, y = c + dy;
                if (inBounds(x, y) && rnd.nextFloat() < 0.7f) {
                    if (terrain[x][y] == ROCK) terrain[x][y] = EMPTY;
                }
            }
        }
    }

    private void oreCluster(int cx, int cy, int ore) {
        for (int i = 0; i < 7; i++) {
            int x = cx + rnd.nextInt(5) - 2;
            int y = cy + rnd.nextInt(5) - 2;
            if (inBounds(x, y) && terrain[x][y] != ROCK) terrain[x][y] = ore;
        }
    }

    public static boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < SIZE && y < SIZE;
    }

    /* ---------- Строительство ---------- */

    public static int cost(int type) {
        switch (type) {
            case Building.DRILL: return 15;
            case Building.CONVEYOR: return 2;
            case Building.TURRET: return 35;
            case Building.WALL: return 8;
            default: return 0;
        }
    }

    public boolean place(int type, int tx, int ty, int rot) {
        if (gameOver || !inBounds(tx, ty)) return false;
        if (terrain[tx][ty] == ROCK || buildings[tx][ty] != null) return false;
        if (type == Building.DRILL && terrain[tx][ty] != ORE_CU && terrain[tx][ty] != ORE_FE) return false;
        int price = cost(type);
        if (copper < price) return false;
        copper -= price;
        buildings[tx][ty] = new Building(type, tx, ty, rot);
        return true;
    }

    public boolean remove(int tx, int ty) {
        if (gameOver || !inBounds(tx, ty)) return false;
        Building b = buildings[tx][ty];
        if (b == null || b.type == Building.CORE) return false;
        copper += cost(b.type) / 2;
        buildings[tx][ty] = null;
        return true;
    }

    /* ---------- Обновление ---------- */

    public void update(float dt) {
        if (gameOver) return;

        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                Building b = buildings[x][y];
                if (b == null) continue;
                if (b.type == Building.DRILL) updateDrill(b, dt);
                else if (b.type == Building.CONVEYOR) updateConveyor(b, dt);
                else if (b.type == Building.TURRET) updateTurret(b, dt);
            }
        }

        updateBullets(dt);
        updateEnemies(dt);
        updateWaves(dt);

        if (core.hp <= 0) {
            core.hp = 0;
            gameOver = true;
        }
    }

    private void updateDrill(Building b, float dt) {
        int ore = terrain[b.tx][b.ty];
        if (ore != ORE_CU && ore != ORE_FE) return;
        b.progress += dt / 1.4f;
        if (b.progress < 1f) return;
        int nx = b.tx + DX[b.rot], ny = b.ty + DY[b.rot];
        if (!inBounds(nx, ny)) return;
        Building target = buildings[nx][ny];
        int item = ore == ORE_CU ? 0 : 1;
        if (target == core) {
            addResource(item);
            b.progress = 0f;
        } else if (target != null && target.type == Building.CONVEYOR && !target.hasItem) {
            target.hasItem = true;
            target.itemType = item;
            target.progress = 0f;
            b.progress = 0f;
        }
    }

    private void updateConveyor(Building b, float dt) {
        if (!b.hasItem) return;
        b.progress += dt * 1.6f;
        if (b.progress < 1f) return;
        int nx = b.tx + DX[b.rot], ny = b.ty + DY[b.rot];
        if (!inBounds(nx, ny)) { b.progress = 1f; return; }
        Building target = buildings[nx][ny];
        if (target == core) {
            addResource(b.itemType);
            b.hasItem = false;
            b.progress = 0f;
        } else if (target != null && target.type == Building.CONVEYOR && !target.hasItem && target.progress < 0.5f) {
            target.hasItem = true;
            target.itemType = b.itemType;
            target.progress = 0f;
            b.hasItem = false;
            b.progress = 0f;
        } else {
            b.progress = 1f;
        }
    }

    private void addResource(int item) {
        if (item == 0) copper++; else iron++;
    }

    private void updateTurret(Building b, float dt) {
        b.cooldown -= dt;
        if (b.cooldown > 0) return;
        float ox = b.tx * TILE + TILE / 2f;
        float oy = b.ty * TILE + TILE / 2f;
        Enemy best = null;
        float bestD = 5.5f * TILE;
        bestD *= bestD;
        for (Enemy e : enemies) {
            float dx = e.x - ox, dy = e.y - oy;
            float d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = e; }
        }
        if (best == null || copper < 1) return;
        copper--;
        b.cooldown = 0.55f;
        float dx = best.x - ox, dy = best.y - oy;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        Bullet bul = new Bullet();
        bul.x = ox; bul.y = oy;
        bul.vx = dx / d * 320f;
        bul.vy = dy / d * 320f;
        bul.life = 1.2f;
        bul.damage = 26f;
        bullets.add(bul);
    }

    private void updateBullets(float dt) {
        Iterator<Bullet> it = bullets.iterator();
        while (it.hasNext()) {
            Bullet b = it.next();
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.life -= dt;
            boolean hit = false;
            for (Enemy e : enemies) {
                float dx = e.x - b.x, dy = e.y - b.y;
                if (dx * dx + dy * dy < e.radius * e.radius) {
                    e.hp -= b.damage;
                    hit = true;
                    break;
                }
            }
            if (hit || b.life <= 0) it.remove();
        }
    }

    private void updateEnemies(float dt) {
        float cx = core.tx * TILE + TILE / 2f;
        float cy = core.ty * TILE + TILE / 2f;
        Iterator<Enemy> it = enemies.iterator();
        while (it.hasNext()) {
            Enemy e = it.next();
            if (e.hp <= 0) {
                copper += 2;
                it.remove();
                continue;
            }
            e.attackCd -= dt;
            float dx = cx - e.x, dy = cy - e.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 0.001f) continue;
            float nx = e.x + dx / d * e.speed * dt;
            float ny = e.y + dy / d * e.speed * dt;
            int tx = (int) (nx / TILE), ty = (int) (ny / TILE);
            Building target = inBounds(tx, ty) ? buildings[tx][ty] : null;
            if (target != null) {
                if (e.attackCd <= 0) {
                    target.hp -= e.damage;
                    e.attackCd = 0.65f;
                    if (target.hp <= 0 && target != core) buildings[tx][ty] = null;
                }
            } else {
                e.x = nx;
                e.y = ny;
            }
        }
    }

    private void updateWaves(float dt) {
        if (toSpawn > 0) {
            spawnTimer -= dt;
            if (spawnTimer <= 0) {
                spawnEnemy();
                toSpawn--;
                spawnTimer = 0.7f;
            }
            return;
        }
        if (!enemies.isEmpty()) return;
        waveTimer -= dt;
        if (waveTimer <= 0) {
            wave++;
            toSpawn = 4 + wave * 3;
            waveTimer = 12f;
            spawnTimer = 0f;
        }
    }

    private void spawnEnemy() {
        Enemy e = new Enemy();
        int side = rnd.nextInt(4);
        if (side == 0) { e.x = rnd.nextInt(SIZE) * TILE; e.y = 0; }
        else if (side == 1) { e.x = rnd.nextInt(SIZE) * TILE; e.y = (SIZE - 1) * TILE; }
        else if (side == 2) { e.x = 0; e.y = rnd.nextInt(SIZE) * TILE; }
        else { e.x = (SIZE - 1) * TILE; e.y = rnd.nextInt(SIZE) * TILE; }

        int roll = rnd.nextInt(100);
        if (wave >= 3 && roll < 15) {
            e.type = 2; e.maxHp = 260; e.speed = 13; e.damage = 22; e.radius = 12;
        } else if (wave >= 2 && roll < 40) {
            e.type = 1; e.maxHp = 38; e.speed = 46; e.damage = 6; e.radius = 7;
        } else {
            e.type = 0; e.maxHp = 62; e.speed = 24; e.damage = 9;
        }
        e.hp = e.maxHp;
        enemies.add(e);
    }
}
