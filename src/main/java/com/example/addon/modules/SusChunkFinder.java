package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Sucht geladene Chunks mit vielen künstlichen Blöcken (Bretter, Türen, Betten,
 * Truhen, Fackeln, Glas ...) und schreibt die Treffer in den Chat.
 * Gedacht für den eigenen Test-Server.
 */
public class SusChunkFinder extends Module {

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Integer> radius = sg.add(new IntSetting.Builder()
        .name("radius")
        .description("Suchradius in Chunks.")
        .defaultValue(12).min(1).sliderMax(32)
        .build());

    private final Setting<Integer> threshold = sg.add(new IntSetting.Builder()
        .name("threshold")
        .description("Ab so vielen künstlichen Blöcken gilt ein Chunk als verdächtig.")
        .defaultValue(20).min(1).sliderMax(500)
        .build());

    private final Setting<Integer> chunksPerTick = sg.add(new IntSetting.Builder()
        .name("chunks-per-tick")
        .description("Wie viele Chunks pro Tick geprüft werden.")
        .defaultValue(3).min(1).sliderMax(20)
        .build());

    private final Setting<Boolean> rescan = sg.add(new BoolSetting.Builder()
        .name("rescan")
        .description("Nach jedem fertigen Durchlauf automatisch neu scannen.")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> sideColor = sg.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Farbe der grünen Fläche.")
        .defaultValue(new SettingColor(0, 255, 0, 60))
        .build());

    private final Setting<SettingColor> lineColor = sg.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Farbe der Umrandung.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .build());

    private final Setting<ShapeMode> shapeMode = sg.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Fläche, Linien oder beides.")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Integer> beamHeight = sg.add(new IntSetting.Builder()
        .name("beam-height")
        .description("Höhe einer dünnen Säule in der Chunk-Mitte (0 = aus), damit man den Chunk von Weitem sieht.")
        .defaultValue(30).min(0).sliderMax(200)
        .build());

    private record Hit(int cx, int cz, int score) {}

    private final Deque<int[]> queue = new ArrayDeque<>();
    private final List<Hit> hits = new ArrayList<>();
    private final List<Hit> shown = new ArrayList<>(); // wird gezeichnet

    public SusChunkFinder() {
        super(AddonTemplate.CATEGORY, "sus-chunk-finder",
            "Findet Chunks mit vielen vom Spieler gebauten Blöcken (nur geladene Chunks).");
    }

    @Override
    public void onActivate() {
        startScan();
    }

    @Override
    public void onDeactivate() {
        queue.clear();
        hits.clear();
        shown.clear();
    }

    private void startScan() {
        queue.clear();
        hits.clear();
        if (mc.player == null) return;

        int pcx = mc.player.getBlockPos().getX() >> 4;
        int pcz = mc.player.getBlockPos().getZ() >> 4;
        int r = radius.get();

        List<int[]> list = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                list.add(new int[]{pcx + dx, pcz + dz});
        list.sort(Comparator.comparingInt(c -> (c[0] - pcx) * (c[0] - pcx) + (c[1] - pcz) * (c[1] - pcz)));
        queue.addAll(list);

        info("Scan gestartet (Radius %d Chunks).", r);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        ClientWorld world = mc.world;
        if (world == null || mc.player == null) return;

        if (queue.isEmpty()) return;

        for (int i = 0; i < chunksPerTick.get() && !queue.isEmpty(); i++) {
            int[] c = queue.poll();
            WorldChunk chunk = world.getChunkManager().getWorldChunk(c[0], c[1]);
            if (chunk == null) continue;

            int score = 0;
            for (ChunkSection section : chunk.getSectionArray()) {
                if (section == null || section.isEmpty() || !section.hasAny(SusChunkFinder::isArtificial)) continue;
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        for (int x = 0; x < 16; x++)
                            if (isArtificial(section.getBlockState(x, y, z))) score++;
            }
            if (score >= threshold.get()) hits.add(new Hit(c[0], c[1], score));
        }

        if (queue.isEmpty()) finishScan();
    }

    private void finishScan() {
        shown.clear();
        shown.addAll(hits);
        if (hits.isEmpty()) {
            info("Scan fertig: keine verdächtigen Chunks gefunden.");
        } else {
            info("Scan fertig: %d verdächtige Chunks.", hits.size());
            hits.stream()
                .sorted(Comparator.comparingInt(Hit::score).reversed())
                .limit(10)
                .forEach(h -> info("Chunk %d, %d  (Block %d, %d)  Blöcke: %d",
                    h.cx(), h.cz(), h.cx() * 16 + 8, h.cz() * 16 + 8, h.score()));
        }
        if (rescan.get()) startScan();
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || shown.isEmpty()) return;

        for (Hit h : shown) {
            double x1 = h.cx() * 16;
            double z1 = h.cz() * 16;
            int y = mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, h.cx() * 16 + 8, h.cz() * 16 + 8);

            // grünes Feld auf dem Boden (16 x 16 Blöcke = ein Chunk)
            event.renderer.box(x1, y + 0.02, z1, x1 + 16, y + 0.12, z1 + 16,
                sideColor.get(), lineColor.get(), shapeMode.get(), 0);

            // dünne Säule in der Mitte
            if (beamHeight.get() > 0) {
                event.renderer.box(x1 + 7.5, y, z1 + 7.5, x1 + 8.5, y + beamHeight.get(), z1 + 8.5,
                    sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
    }

    private static boolean isArtificial(BlockState st) {
        return st.isIn(BlockTags.PLANKS) || st.isIn(BlockTags.DOORS) || st.isIn(BlockTags.BEDS)
            || st.isIn(BlockTags.WOOL) || st.isOf(Blocks.CHEST) || st.isOf(Blocks.BARREL)
            || st.isOf(Blocks.CRAFTING_TABLE) || st.isOf(Blocks.FURNACE) || st.isOf(Blocks.TORCH)
            || st.isOf(Blocks.WALL_TORCH) || st.isOf(Blocks.GLASS) || st.isOf(Blocks.GLASS_PANE)
            || st.isOf(Blocks.BOOKSHELF) || st.isOf(Blocks.BRICKS) || st.isOf(Blocks.STONE_BRICKS);
    }
}
