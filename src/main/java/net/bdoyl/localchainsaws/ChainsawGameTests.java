package net.bdoyl.localchainsaws;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.GameTestHolder;

/** {@link ChainsawScenarios} as GameTests (./gradlew runGameTestServer). Forge only registers these in a dev workspace. */
@GameTestHolder(LocalChainsaws.MODID)
public class ChainsawGameTests {
    @GameTestGenerator
    public static Collection<TestFunction> scenarios() {
        List<TestFunction> tests = new ArrayList<>();
        ChainsawScenarios.ALL.forEach((name, s) -> tests.add(new TestFunction("localchainsaws", "localchainsaws." + name,
                LocalChainsaws.MODID + ":arena", s.maxTicks(), 0L, true, h -> s.run().accept(arena(h)))));
        return tests;
    }

    private static TestArena arena(GameTestHelper h) {
        return new TestArena() {
            @Override public ServerLevel level() { return h.getLevel(); }
            @Override public BlockPos abs(BlockPos rel) { return h.absolutePos(rel); }
            @Override public void later(int ticks, Runnable r) { h.runAfterDelay(ticks, r); }
            @Override public void check(boolean ok, String message) { h.assertTrue(ok, message); }
            @Override public void pass() { h.succeed(); }
        };
    }
}
