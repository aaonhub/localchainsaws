package net.bdoyl.localchainsaws;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs every {@link ChainsawScenarios} scenario on a real server (with whatever other mods are installed), logs
 * "[localchainsaws-selftest] PASS/FAIL ..." and a SUMMARY line, then stops the server. Only registered with
 * -Dlocalchainsaws.selftest=true: for throwaway test copies of a modpack server, NEVER the live one (it builds trees
 * in the sky around x=30000, z=30000 and shuts the server down when done).
 */
final class SelfTest {
    private static final Logger LOG = LoggerFactory.getLogger("localchainsaws-selftest");
    private static final int WARMUP_TICKS = 60; // let the forced chunks reach entity-ticking before anything spawns

    private record Task(long due, String scenario, Runnable run) {}

    private static final List<Task> TASKS = new ArrayList<>();
    private static final Map<String, String> RESULTS = new LinkedHashMap<>();
    private static final List<Arena> ARENAS = new ArrayList<>();
    private static long tick;
    private static long deadline = Long.MAX_VALUE;
    private static boolean done;

    private SelfTest() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        ServerLevel level = event.getServer().overworld();
        int i = 0;
        for (String name : ChainsawScenarios.ALL.keySet()) {
            Arena a = new Arena(name, level, new BlockPos(30000 + 48 * i++, 250, 30000));
            a.prepare();
            ARENAS.add(a);
        }
        LOG.info("[localchainsaws-selftest] {} scenarios prepared, starting in {} ticks", ARENAS.size(), WARMUP_TICKS);
        int longest = 0;
        for (Arena a : ARENAS) {
            ChainsawScenarios.Scenario s = ChainsawScenarios.ALL.get(a.name);
            longest = Math.max(longest, s.maxTicks());
            schedule(WARMUP_TICKS, a.name, () -> s.run().accept(a));
        }
        deadline = WARMUP_TICKS + longest + 20;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (done || deadline == Long.MAX_VALUE) return;
        tick++;
        List<Task> due = new ArrayList<>();
        for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (t.due <= tick) { due.add(t); it.remove(); }
        }
        for (Task t : due) {
            if (RESULTS.containsKey(t.scenario)) continue;
            try {
                t.run.run();
            } catch (Throwable e) {
                RESULTS.put(t.scenario, "FAIL " + e.getMessage());
            }
        }
        if (RESULTS.size() == ARENAS.size() || tick > deadline) finish(event.getServer());
    }

    private static void schedule(int ticks, String scenario, Runnable r) {
        TASKS.add(new Task(tick + ticks, scenario, r));
    }

    private static void finish(MinecraftServer server) {
        done = true;
        int passed = 0;
        for (Arena a : ARENAS) {
            String r = RESULTS.getOrDefault(a.name, "FAIL timed out");
            if (r.equals("PASS")) passed++;
            LOG.info("[localchainsaws-selftest] {} {}", r.startsWith("PASS") ? "PASS" : "FAIL", a.name + (r.equals("PASS") ? "" : ": " + r.substring(5)));
        }
        LOG.info("[localchainsaws-selftest] SUMMARY {}/{} passed", passed, ARENAS.size());
        server.halt(false);
    }

    private static final class Arena implements TestArena {
        final String name;
        final ServerLevel level;
        final BlockPos origin;

        Arena(String name, ServerLevel level, BlockPos origin) {
            this.name = name;
            this.level = level;
            this.origin = origin;
        }

        void prepare() {
            for (int cx = (origin.getX() - 8) >> 4; cx <= (origin.getX() + SIZE_X + 8) >> 4; cx++) {
                for (int cz = (origin.getZ() - 8) >> 4; cz <= (origin.getZ() + SIZE_Z + 8) >> 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                }
            }
            for (int x = -2; x < SIZE_X + 2; x++) for (int y = -1; y < SIZE_Y; y++) for (int z = -2; z < SIZE_Z + 2; z++)
                level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }

        @Override public ServerLevel level() { return level; }
        @Override public BlockPos abs(BlockPos rel) { return origin.offset(rel); }
        @Override public void later(int ticks, Runnable r) { schedule(ticks, name, r); }
        @Override public void check(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
        @Override public void pass() { RESULTS.putIfAbsent(name, "PASS"); }
    }
}
